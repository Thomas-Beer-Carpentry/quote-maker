package nz.co.timbertakeoff.ui

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nz.co.timbertakeoff.EstimatorApplication
import nz.co.timbertakeoff.MainActivity
import nz.co.timbertakeoff.core.FramingOrientation
import nz.co.timbertakeoff.data.DeckDraft
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Drives the real Activity, Compose forms, Room repository and calculated drawing workspace. */
@RunWith(AndroidJUnit4::class)
class CarpenterWorkflowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val app: EstimatorApplication = ApplicationProvider.getApplicationContext()

    @Before fun openApp() { scenario = ActivityScenario.launch(MainActivity::class.java) }
    @After fun closeApp() { scenario.close() }

    @Test
    fun createEstimateEditIndependentDecksAndReopenSavedWork() {
        val suffix = UUID.randomUUID().toString().take(8)
        val clientName = "Workflow client $suffix"
        val jobName = "Garden deck $suffix"
        val deckName = "Main deck $suffix"
        val landingName = "Landing $suffix"

        click("Clients")
        click("+ New client")
        replace("Client name", clientName, scroll = false)
        click("Create")
        waitForText("Client details")
        replace("Phone", "021 555 010")
        click("+ New job", scroll = true)
        replace("Job name", jobName, scroll = false)
        click("Create")
        waitForText("+ Add deck task")
        click("+ Add deck task", scroll = true)
        replace("Task name", deckName, scroll = false)
        click("Create")
        waitForText("Deck specifications")
        waitForKeyboardHidden()
        compose.onNodeWithText("Inputs").assertIsSelected()
        val tabPositions = listOf("Inputs", "Materials", "Drawings").map {
            compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.left
        }
        assertTrue("Inputs come first, materials second and drawings last", tabPositions.zipWithNext().all { (a, b) -> a < b })
        compose.onNodeWithText("Calculate").assertExists()
        click("Materials")
        compose.onNodeWithText("Overall materials").assertDoesNotExist()
        compose.onNodeWithText("Export all PDF").assertDoesNotExist()
        click("Inputs")
        click("Calculate")
        waitForText("Overall materials")
        compose.onNodeWithText("Materials").assertIsSelected()
        waitForKeyboardHidden()
        // Independent expected default: 67.08 m joists/boundaries + 4.17 m nogs.
        compose.onNodeWithText("71.25 lm").assertExists()
        assertEquals("Identical joist/nog timber has one overall row", 1,
            compose.onAllNodesWithText("140 × 45 mm · H3.2 treated radiata pine").fetchSemanticsNodes().size)
        compose.onNodeWithText("Exact material takeoff").assertDoesNotExist()
        click("Show material breakdown", scroll = true)
        waitForText("Exact material takeoff")
        click("Nogs / blocking", scroll = true)
        waitForText("4.17 lm")
        click("Hide material breakdown", scroll = true)

        click("Inputs")
        replace("1. Deck width (mm)", "")
        click("Drawings")
        waitForText("then tap Calculate", substring = true)
        compose.onNodeWithText("Construction drawing workspace").assertDoesNotExist()
        compose.onNodeWithText("Export all PDF").assertDoesNotExist()
        click("Inputs")
        click("Calculate")
        waitForText("Layout cannot be calculated", substring = true)
        compose.onNodeWithText("Inputs").assertIsSelected()
        compose.onNodeWithText("Deck width (mm) must be a finite number.").assertIsDisplayed()

        replace("1. Deck width (mm)", "4200")
        replace("2. Deck length (mm)", "5200")
        click("90 × 19 mm", scroll = true)
        click("140 × 19 mm")
        replace("Decking overhang on all four sides (mm)", "25")
        click("Automatic", scroll = true)
        compose.onNode(hasText("Widthways · bearers parallel to deck width") and hasClickAction()).performClick()
        click("Calculate")
        waitForText("Overall materials")
        waitForKeyboardHidden()
        waitForText("153 lm")
        screenshot("workflow-02-materials.png")
        click("Drawings")
        waitForText("Construction drawing workspace")
        waitForKeyboardHidden()
        click("Drawing sheet", scroll = true, clickNode = false)
        waitForText("Export all PDF")
        screenshot("workflow-01-framing.png")

        click("Materials")
        waitForText("Overall materials")
        click("Show material breakdown", scroll = true)
        waitForText("Exact material takeoff")
        waitForKeyboardHidden()
        compose.onNode(hasText("Decking") and hasClickAction()).performScrollTo().performClick()
        waitForText("36 boards")
        waitForText("Cut lengths: 36 × 4250 mm")
        screenshot("workflow-04-breakdown.png")

        // Wait for actual database content, rather than trusting the optimistic screen alone.
        compose.waitUntil(60_000) {
            runBlocking {
                app.repository.tasks.first().firstOrNull { it.name == deckName }?.let {
                    val saved = DeckDraft.fromJson(it.inputJson)
                    saved.widthMm == "4200" && saved.lengthMm == "5200" && saved.overhangMm == "25" &&
                        saved.deckingProfileId == "140 × 19 mm" && saved.orientation == FramingOrientation.WIDTHWAYS
                } == true
            }
        }

        click("‹ Back")
        waitForText("Overall job materials")
        click("+ Add deck task", scroll = true)
        replace("Task name", landingName, scroll = false)
        click("Create")
        waitForText("Deck specifications")
        compose.onNodeWithText("Inputs").assertIsSelected()
        assertField("1. Deck width (mm)", "3600")

        val savedDecks = runBlocking { app.repository.tasks.first() }
        val main = savedDecks.first { it.name == deckName }
        val landing = savedDecks.first { it.name == landingName }
        assertEquals("Both decks belong to the same job", main.jobId, landing.jobId)
        assertEquals("Edits stay independent", "4200", DeckDraft.fromJson(main.inputJson).widthMm)
        assertEquals("New task retains independent defaults", "3600", DeckDraft.fromJson(landing.inputJson).widthMm)
        val savedJob = runBlocking { checkNotNull(app.repository.getJob(main.jobId)) }
        val savedClient = runBlocking { checkNotNull(app.repository.getClient(savedJob.clientId)) }
        assertEquals(clientName, savedClient.name)
        assertEquals("021 555 010", savedClient.phone)
        assertEquals(jobName, savedJob.name)

        // Reopening begins with saved inputs; Calculate produces the latest materials/plans.
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("Set out. Count materials. Get back to work.")
        click("Jobs")
        waitForText(jobName)
        click(jobName, scroll = true)
        waitForText(deckName)
        click(deckName, scroll = true)
        waitForText("Deck specifications")
        compose.onNodeWithText("Inputs").assertIsSelected()
        assertField("1. Deck width (mm)", "4200")
        assertField("2. Deck length (mm)", "5200")
        assertField("Decking overhang on all four sides (mm)", "25")
        scenario.recreate()
        waitForText("1. Deck width (mm)")
        assertField("1. Deck width (mm)", "4200")
        assertField("2. Deck length (mm)", "5200")
        compose.onNodeWithText("Inputs").assertIsSelected()
        screenshot("workflow-00-inputs.png")
        click("Calculate")
        waitForText("Overall materials")
        click("Drawings")
        waitForText("Drawing sheet")
        waitForText("Export all PDF")
        scenario.recreate()
        waitForText("Drawing sheet")
        waitForText("Export all PDF")
        compose.onNodeWithText("Drawings").assertIsSelected()
        waitForKeyboardHidden()
        screenshot("workflow-03-reopened.png")
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(60_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun click(text: String, scroll: Boolean = false, clickNode: Boolean = true) {
        waitForText(text)
        val node = compose.onNodeWithText(text)
        if (scroll) node.performScrollTo()
        if (clickNode) node.performClick()
    }

    private fun replace(label: String, value: String, scroll: Boolean = true) {
        waitForText(label)
        val node = compose.onNodeWithText(label)
        if (scroll) node.performScrollTo()
        node.performTextReplacement(value)
    }

    private fun assertField(label: String, expected: String) {
        waitForText(label)
        val text = compose.onNodeWithText(label).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals(label, expected, text)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("Emulator should supply a screen capture", bitmap)
        checkNotNull(bitmap).let { image ->
            try {
                val target = deviceArtifact(app, name)
                target.outputStream().use { assertTrue("Write screenshot $name", image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { image.recycle() }
        }
    }

    private fun waitForKeyboardHidden() {
        try {
            compose.waitUntil(60_000) {
                var visible = false
                scenario.onActivity { activity ->
                    visible = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                !visible
            }
        } catch (error: Exception) {
            screenshot("workflow-keyboard-failure.png")
            throw error
        }
    }
}
