package nz.co.timbertakeoff.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EstimatorDao {
    @Query("SELECT * FROM clients ORDER BY updatedAt DESC, id DESC")
    fun observeClients(): Flow<List<ClientEntity>>

    @Query("SELECT * FROM jobs ORDER BY updatedAt DESC, id DESC")
    fun observeJobs(): Flow<List<JobEntity>>

    @Query("SELECT * FROM tasks ORDER BY id ASC")
    fun observeTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM clients WHERE id = :id")
    suspend fun client(id: Long): ClientEntity?

    @Query("SELECT * FROM jobs WHERE id = :id")
    suspend fun job(id: Long): JobEntity?

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun task(id: Long): TaskEntity?

    @Insert
    suspend fun insertClient(client: ClientEntity): Long

    @Insert
    suspend fun insertJob(job: JobEntity): Long

    @Insert
    suspend fun insertTask(task: TaskEntity): Long

    @Update
    suspend fun updateClient(client: ClientEntity): Int

    @Update
    suspend fun updateJob(job: JobEntity): Int

    @Update
    suspend fun updateTask(task: TaskEntity): Int

    @Query("UPDATE tasks SET inputJson = :inputJson, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTaskDraft(id: Long, inputJson: String, updatedAt: Long): Int

    @Query("UPDATE tasks SET name = :name, inputJson = :inputJson, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTaskDraftAndName(id: Long, inputJson: String, name: String, updatedAt: Long): Int
}
