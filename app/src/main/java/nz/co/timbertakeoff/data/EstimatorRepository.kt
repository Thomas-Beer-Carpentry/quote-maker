package nz.co.timbertakeoff.data

import kotlinx.coroutines.flow.Flow

/** Database-only persistence. Calculation and drawing geometry are deliberately not stored. */
class EstimatorRepository(private val dao: EstimatorDao) {
    val clients: Flow<List<ClientEntity>> = dao.observeClients()
    val jobs: Flow<List<JobEntity>> = dao.observeJobs()
    val tasks: Flow<List<TaskEntity>> = dao.observeTasks()

    suspend fun getClient(id: Long): ClientEntity? = dao.client(id)
    suspend fun getJob(id: Long): JobEntity? = dao.job(id)
    suspend fun getTask(id: Long): TaskEntity? = dao.task(id)

    suspend fun createClient(name: String, phone: String = "", email: String = "", notes: String = ""): Long =
        dao.insertClient(ClientEntity(name = name, phone = phone, email = email, notes = notes))

    suspend fun saveClient(client: ClientEntity) {
        check(dao.updateClient(client.copy(updatedAt = System.currentTimeMillis())) == 1) {
            "The client no longer exists."
        }
    }

    suspend fun createJob(clientId: Long, name: String, notes: String = ""): Long =
        dao.insertJob(JobEntity(clientId = clientId, name = name, notes = notes))

    suspend fun saveJob(job: JobEntity) {
        check(dao.updateJob(job.copy(updatedAt = System.currentTimeMillis())) == 1) {
            "The job no longer exists."
        }
    }

    suspend fun createDeckTask(jobId: Long, name: String, draft: DeckDraft = DeckDraft()): Long =
        dao.insertTask(TaskEntity(jobId = jobId, name = name, inputJson = draft.toJson()))

    suspend fun saveTask(task: TaskEntity) {
        check(dao.updateTask(task.copy(updatedAt = System.currentTimeMillis())) == 1) {
            "The task no longer exists."
        }
    }

    /** Atomic targeted updates prevent a draft save from overwriting unrelated task properties. */
    suspend fun saveTaskDraft(taskId: Long, draft: DeckDraft, name: String? = null) {
        val count = if (name == null) {
            dao.updateTaskDraft(taskId, draft.toJson(), System.currentTimeMillis())
        } else {
            dao.updateTaskDraftAndName(taskId, draft.toJson(), name, System.currentTimeMillis())
        }
        check(count == 1) { "The task no longer exists." }
    }
}
