package nz.co.timbertakeoff.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.co.timbertakeoff.core.drawing.DrawingSheet
import nz.co.timbertakeoff.drawing.PdfExporter
import nz.co.timbertakeoff.data.ClientEntity
import nz.co.timbertakeoff.data.DeckDraft
import nz.co.timbertakeoff.EstimatorApplication
import nz.co.timbertakeoff.data.JobEntity
import nz.co.timbertakeoff.data.TaskEntity

/** One ordered writer survives activity recreation and preserves every raw field edit. */
class EstimatorViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as EstimatorApplication).repository
    private val clientEdits = MutableStateFlow<Map<Long, ClientEntity>>(emptyMap())
    private val jobEdits = MutableStateFlow<Map<Long, JobEntity>>(emptyMap())
    private val taskEdits = MutableStateFlow<Map<Long, TaskEntity>>(emptyMap())
    val clients = combine(repository.clients, clientEdits) { saved, edits ->
        saved.map { edits[it.id] ?: it }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val jobs = combine(repository.jobs, jobEdits) { saved, edits ->
        saved.map { edits[it.id] ?: it }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val tasks = combine(repository.tasks, taskEdits) { saved, edits ->
        saved.map { edits[it.id] ?: it }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val pendingSaves = MutableStateFlow(0)
    val saveError = MutableStateFlow<String?>(null)
    val pdfExportBusy = MutableStateFlow(false)
    val pdfExportMessage = MutableStateFlow<String?>(null)
    private var pendingExportSheets: List<DrawingSheet>? = null
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        (application as EstimatorApplication).persistenceScope.launch {
            for (write in writes) {
                try {
                    write()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    saveError.value = "Changes could not be saved: ${error.message ?: "local storage is unavailable"}."
                } finally {
                    pendingSaves.value = (pendingSaves.value - 1).coerceAtLeast(0)
                }
            }
        }
    }

    override fun onCleared() {
        // Closing the channel drains accepted writes; clearing the Activity must not cancel them.
        writes.close()
        super.onCleared()
    }

    private fun enqueue(write: suspend () -> Unit) {
        pendingSaves.value += 1
        writes.trySend(write)
    }

    fun createClient(name: String, onCreated: (Long) -> Unit) = enqueue {
        onCreated(repository.createClient(name.trim()))
    }

    fun updateClient(client: ClientEntity) {
        clientEdits.value = clientEdits.value + (client.id to client)
        enqueue { repository.saveClient(client) }
    }

    fun editClient(client: ClientEntity, change: (ClientEntity) -> ClientEntity) =
        updateClient(change(clientEdits.value[client.id] ?: client))

    fun createJob(clientId: Long, name: String, onCreated: (Long) -> Unit) = enqueue {
        onCreated(repository.createJob(clientId, name.trim()))
    }

    fun updateJob(job: JobEntity) {
        jobEdits.value = jobEdits.value + (job.id to job)
        enqueue { repository.saveJob(job) }
    }

    fun editJob(job: JobEntity, change: (JobEntity) -> JobEntity) =
        updateJob(change(jobEdits.value[job.id] ?: job))

    fun createDeckTask(jobId: Long, name: String, onCreated: (Long) -> Unit) = enqueue {
        onCreated(repository.createDeckTask(jobId, name.trim()))
    }

    fun updateTask(task: TaskEntity, draft: DeckDraft, name: String = task.name) {
        taskEdits.value = taskEdits.value + (task.id to task.copy(name = name, inputJson = draft.toJson()))
        enqueue { repository.saveTaskDraft(task.id, draft, name) }
    }

    fun editTask(task: TaskEntity, name: String? = null, change: (DeckDraft) -> DeckDraft = { it }) {
        val latest = taskEdits.value[task.id] ?: task
        val draft = runCatching { DeckDraft.fromJson(latest.inputJson) }.getOrElse {
            saveError.value = "This task's saved inputs could not be read: ${it.message}."
            return
        }
        updateTask(latest, change(draft), name ?: latest.name)
    }

    /** Retrying idempotent updates is safe; creation failures remain visible and can be retried in the form. */
    fun retrySaves() {
        saveError.value = null
        clientEdits.value.values.forEach { client -> enqueue { repository.saveClient(client) } }
        jobEdits.value.values.forEach { job -> enqueue { repository.saveJob(job) } }
        taskEdits.value.values.forEach { task ->
            enqueue { repository.saveTaskDraft(task.id, DeckDraft.fromJson(task.inputJson), task.name) }
        }
    }

    /** The snapshot and I/O belong to the ViewModel, so rotation or tab changes cannot discard an export. */
    fun preparePdfExport(sheets: List<DrawingSheet>) {
        pendingExportSheets = sheets.toList()
        pdfExportBusy.value = true
        pdfExportMessage.value = null
    }

    fun writePendingPdf(uri: Uri?) {
        val snapshot = pendingExportSheets
        pendingExportSheets = null
        if (uri == null) {
            pdfExportBusy.value = false
            return
        }
        if (snapshot == null) {
            pdfExportBusy.value = false
            pdfExportMessage.value = "PDF export was interrupted when the app restarted. Reopen the deck and export again."
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = getApplication<Application>().contentResolver.openOutputStream(uri, "w")
                        ?: error("The selected file could not be opened")
                    output.use { PdfExporter.write(it, snapshot) }
                }
                pdfExportMessage.value = "Combined ${snapshot.size}-sheet vector PDF exported."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                pdfExportMessage.value = "PDF export failed: ${error.message}."
            } finally {
                pdfExportBusy.value = false
            }
        }
    }

    fun dismissPdfMessage() { pdfExportMessage.value = null }
}
