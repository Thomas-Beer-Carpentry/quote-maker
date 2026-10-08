package nz.co.timbertakeoff.ui

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nz.co.timbertakeoff.EstimatorApplication
import nz.co.timbertakeoff.MainActivity
import nz.co.timbertakeoff.data.DeckDraft
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutosaveLifecycleTest {
    @Test fun closingActivityFinishesAcceptedDraftWritesInOrder() = runBlocking {
        val app: EstimatorApplication = ApplicationProvider.getApplicationContext()
        val client = app.repository.createClient("Autosave lifecycle client")
        val job = app.repository.createJob(client, "Autosave lifecycle job")
        val taskId = app.repository.createDeckTask(job, "Autosave lifecycle deck")
        val task = checkNotNull(app.repository.getTask(taskId))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[EstimatorViewModel::class.java]
                repeat(40) { index ->
                    model.editTask(task) { it.copy(widthMm = "${4000 + index}", lengthMm = "${5000 + index}") }
                }
            }
        } finally { scenario.close() }
        val saved = withTimeout(60_000) {
            app.repository.tasks.first { records ->
                records.firstOrNull { it.id == taskId }?.let { DeckDraft.fromJson(it.inputJson).widthMm == "4039" } == true
            }.single { it.id == taskId }
        }
        val draft = DeckDraft.fromJson(saved.inputJson)
        assertEquals("4039", draft.widthMm)
        assertEquals("5039", draft.lengthMm)
    }
}
