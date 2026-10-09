package nz.co.timbertakeoff.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import nz.co.timbertakeoff.EstimatorApplication
import nz.co.timbertakeoff.MainActivity
import nz.co.timbertakeoff.core.CalculationOutcome
import nz.co.timbertakeoff.core.FramingOrientation
import nz.co.timbertakeoff.core.PileConnection
import nz.co.timbertakeoff.core.drawing.DrawingGenerator
import nz.co.timbertakeoff.core.drawing.DrawingTitle
import nz.co.timbertakeoff.core.drawing.SheetSize
import nz.co.timbertakeoff.data.DeckDraft
import nz.co.timbertakeoff.drawing.PdfExporter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Running marks pass through saved inputs, the real phone workspace and native vector PDF. */
@RunWith(AndroidJUnit4::class)
class LargeDeckSetoutWorkflowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val app: EstimatorApplication = ApplicationProvider.getApplicationContext()
    private lateinit var jobName: String
    private lateinit var deckName: String
    private var taskId = 0L

    @Before fun createSavedDeckAndOpenApp() {
        val suffix = UUID.randomUUID().toString().take(8)
        jobName = "Board marking job $suffix"
        deckName = "Running marks $suffix"
        val draft = DeckDraft(
            widthMm = "535", lengthMm = "4800",
            deckingProfileId = "140 × 19 mm", actualDeckingWidthMm = "140",
            orientation = FramingOrientation.LENGTHWAYS,
            pileConnection = PileConnection.EXISTING_CONCRETE_BRACKETS,
        )
        runBlocking {
            val clientId = app.repository.createClient("Board marking client $suffix")
            val jobId = app.repository.createJob(clientId, jobName)
            taskId = app.repository.createDeckTask(jobId, deckName, draft)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun closeApp() { scenario.close() }

    @Test fun markFullWidthBoardsFromOneDatumAndPrintTheSchedule() {
        openAndCalculate()
        click("Drawings")
        waitForText("Drawing sheet")
        compose.onNodeWithText("Drawings").assertIsSelected()
        compose.onNodeWithText("Running decking measurements").assertDoesNotExist()
        click("Show decking set-out measurements", scroll = true)
        waitForText("Running decking measurements")
        // 535 + two 20 mm overhangs = 575 = four 140 mm boards + three 5 mm gaps.
        waitForText("20 mm outside the left framing edge", substring = true)
        waitForText("right side (+X) of its mark", substring = true)
        waitForText("Equal board gap: 5 mm", substring = true)
        for ((board, mark) in listOf(1 to 0, 2 to 145, 3 to 290, 4 to 435)) {
            compose.onNodeWithTag("decking-setout-board-$board")
                .assert(hasText("$mark mm"))
                .assert(hasText("140 mm"))
        }
        // Scroll to the last of the four marks so the screen capture shows the actual
        // working schedule, rather than stopping with only its heading at the bottom.
        compose.onNodeWithTag("decking-setout-board-4").performScrollTo()
        for (board in 1..4) compose.onNodeWithTag("decking-setout-board-$board").assertIsDisplayed()
        screenshot("decking-setout-running-marks.png")
        click("Hide decking set-out measurements", scroll = true)
        compose.onNodeWithTag("decking-setout-board-1").assertDoesNotExist()
        click("D01 — Framing plan", scroll = true)
        click("D05 —", substring = true)
        screenshot("decking-setout-D05-preview.png")

        val saved = runBlocking { checkNotNull(app.repository.getTask(taskId)) }
        val outcome = DeckDraft.fromJson(saved.inputJson).calculate(saved.typeId)
        assertTrue("Saved board marking fixture must calculate", outcome is CalculationOutcome.Success)
        val result = (outcome as CalculationOutcome.Success).result
        assertEquals(4, result.geometry.boards.size)
        assertEquals(5.0, result.geometry.deckingGapMm, 1e-8)
        for (size in SheetSize.entries) {
            val sheets = DrawingGenerator.generate(result, DrawingTitle(project = jobName, task = deckName), size)
            val setoutPage = sheets.indexOfFirst { it.code == "D05" }
            assertTrue("${size.name} export must include the set-out schedule", setoutPage >= 0)
            val file = deviceArtifact(app, "decking-setout-${size.name}.pdf")
            file.outputStream().use { PdfExporter.write(it, sheets) }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { pdf ->
                    assertEquals(sheets.size, pdf.pageCount)
                    pdf.openPage(setoutPage).use { page ->
                        assertEquals(if (size == SheetSize.A3) 1191 else 842, page.width)
                        assertEquals(if (size == SheetSize.A3) 842 else 595, page.height)
                        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val pixels = IntArray(bitmap.width * bitmap.height)
                            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                            assertTrue("${size.name} set-out page must print linework and measurements",
                                pixels.count { Color.red(it) < 64 && Color.green(it) < 64 && Color.blue(it) < 64 } > 100)
                            deviceArtifact(app, "decking-setout-${size.name}-D05.png").outputStream().use {
                                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }
                        } finally { bitmap.recycle() }
                    }
                }
            }
        }
    }

    @Test fun runningMarksRemainAbsoluteWhenPagingThroughManyBoards() {
        val saved = runBlocking { checkNotNull(app.repository.getTask(taskId)) }
        runBlocking {
            app.repository.saveTaskDraft(taskId, DeckDraft.fromJson(saved.inputJson).copy(
                widthMm = "3725", pileConnection = PileConnection.CONCRETE_FOOTINGS,
            ))
        }
        openAndCalculate()
        click("Drawings")
        waitForText("Drawing sheet")
        click("Show decking set-out measurements", scroll = true)
        waitForText("Board marks 1–25 of 26")
        compose.onNodeWithTag("decking-setout-board-1").assert(hasText("0 mm"))
        compose.onNodeWithTag("decking-setout-board-25").assert(hasText("3480 mm"))
        click("Next marks", scroll = true)
        waitForText("Board marks 26–26 of 26")
        // Board 26 keeps its distance from the original datum, not the start of this page.
        compose.onNodeWithTag("decking-setout-board-26").assert(hasText("3625 mm"))
        click("Previous marks", scroll = true)
        waitForText("Board marks 1–25 of 26")
        compose.onNodeWithTag("decking-setout-board-1").assert(hasText("0 mm"))
    }

    private fun openAndCalculate() {
        waitForText("Set out. Count materials. Get back to work.")
        click("Jobs")
        click(jobName, scroll = true)
        click(deckName, scroll = true)
        waitForText("Deck specifications")
        compose.onNodeWithText("Inputs").assertIsSelected()
        click("Calculate")
        waitForText("Overall materials")
        compose.onNodeWithText("Materials").assertIsSelected()
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(60_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun click(text: String, scroll: Boolean = false, substring: Boolean = false) {
        waitForText(text, substring)
        val node = compose.onNodeWithText(text, substring = substring)
        if (scroll) node.performScrollTo()
        node.performClick()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val image = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("Emulator should supply a screen capture", image)
        checkNotNull(image).let { bitmap ->
            try {
                deviceArtifact(app, name).outputStream().use {
                    assertTrue("Write screenshot $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
    }
}
