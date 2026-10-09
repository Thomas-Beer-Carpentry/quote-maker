package nz.co.timbertakeoff.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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

/** New options travel through the real form, autosave, calculation, drawing view and native PDF. */
@RunWith(AndroidJUnit4::class)
class PictureFrameWorkflowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val app: EstimatorApplication = ApplicationProvider.getApplicationContext()
    private lateinit var jobName: String
    private lateinit var deckName: String
    private var taskId = 0L

    @Before fun createSavedJobAndOpenApp() {
        val suffix = UUID.randomUUID().toString().take(8)
        jobName = "Picture frame job $suffix"
        deckName = "Mitred deck $suffix"
        runBlocking {
            val clientId = app.repository.createClient("Picture frame client $suffix")
            val jobId = app.repository.createJob(clientId, jobName)
            taskId = app.repository.createDeckTask(jobId, deckName)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun closeApp() { scenario.close() }

    @Test
    fun configureBracketMountedPictureFrameReopenAndPrintTheCalculatedPlans() {
        openSavedDeck()
        compose.onNodeWithText("Inputs").assertIsSelected()
        replace("1. Deck width (mm)", "3670")
        click("90 × 19 mm", scroll = true)
        click("140 × 19 mm")
        click("Automatic", scroll = true)
        click("Lengthways · bearers parallel to deck length")
        // An unfinished yield remains saved but must not block the existing-concrete option.
        replace("Concrete yield per 20 kg bag (m³)", "unfinished yield")
        click("Post holes with concrete", scroll = true)
        click("Brackets bolted into existing concrete")
        compose.onNodeWithText("Concrete yield per 20 kg bag (m³)").assertDoesNotExist()
        click("Picture-frame decking", scroll = true)
        compose.onNodeWithText("Picture-frame decking").assertIsOn()
        waitForText("Picture-frame support nog centres: 120 mm", substring = true)
        screenshot("picture-frame-inputs.png")

        click("Calculate")
        waitForText("Overall materials")
        compose.onNodeWithText("Materials").assertIsSelected()
        // 3 bearer lines × 5 posts, each 1000 - 19 - 140 - 190 = 651 mm.
        waitForText("9.765 lm")
        waitForText("Post brackets")
        waitForText("15 each")
        compose.onNodeWithText("Concrete").assertDoesNotExist()
        compose.onNodeWithText("Concrete bags").assertDoesNotExist()
        click("Show material breakdown", scroll = true)
        waitForText("Exact material takeoff")
        compose.onNodeWithText("Concrete").assertDoesNotExist()
        click("Piles", scroll = true)
        waitForText("Cut lengths: 15 × 651 mm")
        click("Nogs / blocking", scroll = true)
        waitForText("Ripped packers: 2 × 45 mm source cuts", substring = true)
        waitForText("finished 140 × 7.5 mm", substring = true)
        screenshot("picture-frame-materials.png")

        compose.waitUntil(60_000) {
            runBlocking {
                app.repository.getTask(taskId)?.let {
                    val draft = DeckDraft.fromJson(it.inputJson)
                    draft.pictureFrame && draft.pileConnection == PileConnection.EXISTING_CONCRETE_BRACKETS &&
                        draft.widthMm == "3670" && draft.actualDeckingWidthMm == "140" &&
                        draft.overhangMm == "20" && draft.orientation == FramingOrientation.LENGTHWAYS &&
                        draft.concreteYieldM3PerBag == "unfinished yield"
                } == true
            }
        }

        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        openSavedDeck()
        compose.onNodeWithText("Inputs").assertIsSelected()
        compose.onNodeWithText("Picture-frame decking").assertIsOn()
        waitForText("Brackets bolted into existing concrete")
        compose.onNodeWithText("Concrete yield per 20 kg bag (m³)").assertDoesNotExist()
        click("Calculate")
        waitForText("Overall materials")
        click("Drawings")
        waitForText("Drawing sheet")
        waitForText("Export all PDF")
        screenshot("picture-frame-framing.png")
        click("D01 — Framing plan", scroll = true)
        click("D02 — Decking plan")
        waitForText("Ripped first infill board: 85 mm")
        screenshot("picture-frame-decking.png")

        // Export the saved form's latest result through the same native vector PDF engine.
        val saved = runBlocking { checkNotNull(app.repository.getTask(taskId)) }
        val outcome = DeckDraft.fromJson(saved.inputJson).calculate(saved.typeId)
        assertTrue("Reopened bracket/picture-frame settings must calculate successfully", outcome is CalculationOutcome.Success)
        val result = (outcome as CalculationOutcome.Success).result
        val title = DrawingTitle(project = jobName, task = deckName, preparedDate = "2026-10-09")
        for (size in SheetSize.entries) {
            val sheets = DrawingGenerator.generate(result, title, size)
            assertEquals(listOf("D01", "D02", "D03", "M01"), sheets.take(4).map { it.code })
            val file = deviceArtifact(app, "picture-frame-${size.name}.pdf")
            file.outputStream().use { PdfExporter.write(it, sheets) }
            assertTrue("${size.name} picture-frame PDF must contain vector drawing content", file.length() > 1000)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { pdf ->
                    assertEquals(sheets.size, pdf.pageCount)
                    for (index in 0 until pdf.pageCount) {
                        pdf.openPage(index).use { page ->
                            assertEquals(if (size == SheetSize.A3) 1191 else 842, page.width)
                            assertEquals(if (size == SheetSize.A3) 842 else 595, page.height)
                            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val pixels = IntArray(bitmap.width * bitmap.height)
                                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                assertTrue("${size.name} ${sheets[index].code} must display linework and annotations",
                                    pixels.count { Color.red(it) < 64 && Color.green(it) < 64 && Color.blue(it) < 64 } > 100)
                            } finally { bitmap.recycle() }
                        }
                    }
                }
            }
        }
    }

    private fun openSavedDeck() {
        waitForText("Set out. Count materials. Get back to work.")
        click("Jobs")
        click(jobName, scroll = true)
        click(deckName, scroll = true)
        waitForText("Deck specifications")
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(60_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun click(text: String, scroll: Boolean = false) {
        waitForText(text)
        val node = compose.onNodeWithText(text)
        if (scroll) node.performScrollTo()
        node.performClick()
    }

    private fun replace(label: String, value: String) {
        waitForText(label)
        compose.onNodeWithText(label).performScrollTo().performTextReplacement(value)
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
