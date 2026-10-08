package nz.co.timbertakeoff.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nz.co.timbertakeoff.core.FramingOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun clientJobsAndIndependentInvalidDraftsSurviveDatabaseReopen() = runBlocking {
        val databaseName = "persistence-test.db"
        context.deleteDatabase(databaseName)
        var database = open(databaseName)
        try {
            var repository = EstimatorRepository(database.estimatorDao())
            val clientId = repository.createClient("Site client", "021 555 010", "client@example.nz", "Gate on left")
            val jobId = repository.createJob(clientId, "Back garden", "Flat site")
            val taskA = repository.createDeckTask(jobId, "Main deck")
            val taskB = repository.createDeckTask(jobId, "Landing")
            val unfinished = DeckDraft(widthMm = "", heightMm = "-", maxJoistSpacingMm = "0", orientation = FramingOrientation.WIDTHWAYS)
            repository.saveTaskDraft(taskA, unfinished, "Main deck edited")
            val savedClient = checkNotNull(repository.getClient(clientId))
            repository.saveClient(savedClient.copy(notes = "Use side access"))
            database.close()

            database = open(databaseName)
            repository = EstimatorRepository(database.estimatorDao())
            assertEquals("Use side access", repository.getClient(clientId)?.notes)
            assertEquals(clientId, repository.getJob(jobId)?.clientId)
            assertEquals("Main deck edited", repository.getTask(taskA)?.name)
            assertEquals(unfinished, DeckDraft.fromJson(checkNotNull(repository.getTask(taskA)).inputJson))
            assertTrue(unfinished.toInput() is DraftInputResult.Invalid)
            assertEquals(DeckDraft(), DeckDraft.fromJson(checkNotNull(repository.getTask(taskB)).inputJson))
            assertEquals(2, repository.tasks.first().count { it.jobId == jobId })
            assertEquals(1, repository.clients.first().size)
            assertEquals(1, repository.jobs.first().size)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun rawInputRoundTripPreservesFieldsAndProfileChanges() {
        val draft = DeckDraft(
            widthMm = " 3600.50 ", lengthMm = "0", heightMm = "NaN", overhangMm = "",
            bearerProfileId = "missing profile", deckingSpecies = "Custom species", screwSpecification = "12g × 75 mm"
        )
        assertEquals(draft, DeckDraft.fromJson(draft.toJson()))
        assertTrue(draft.toInput() is DraftInputResult.Invalid)
        val kwila = nz.co.timbertakeoff.core.Profiles.decking.last()
        val changed = DeckDraft().withDeckingProfile(kwila)
        assertEquals("Kwila", changed.deckingSpecies)
        assertEquals("90", changed.actualDeckingWidthMm)
        assertTrue(changed.toInput() is DraftInputResult.Valid)
    }

    @Test
    fun updatingClientDoesNotReplaceOrCascadeExistingJobs() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, EstimatorDatabase::class.java).build()
        try {
            val repository = EstimatorRepository(database.estimatorDao())
            val clientId = repository.createClient("Before")
            val jobId = repository.createJob(clientId, "Job")
            val taskId = repository.createDeckTask(jobId, "Deck")
            repository.saveClient(checkNotNull(repository.getClient(clientId)).copy(name = "After"))
            assertEquals("After", repository.getClient(clientId)?.name)
            assertEquals(jobId, repository.getTask(taskId)?.jobId)
            assertFalse(repository.jobs.first().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun jobsCannotBelongToMissingClients() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, EstimatorDatabase::class.java).build()
        try {
            var rejected = false
            try {
                EstimatorRepository(database.estimatorDao()).createJob(99999, "Orphan")
            } catch (_: SQLiteConstraintException) {
                rejected = true
            }
            assertTrue("Foreign keys must reject orphan jobs", rejected)
        } finally {
            database.close()
        }
    }

    private fun open(name: String): EstimatorDatabase = Room.databaseBuilder(context, EstimatorDatabase::class.java, name).build()
}
