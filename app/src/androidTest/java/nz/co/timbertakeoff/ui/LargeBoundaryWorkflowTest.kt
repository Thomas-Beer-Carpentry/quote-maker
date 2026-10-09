package nz.co.timbertakeoff.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assert
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
import nz.co.timbertakeoff.core.DeckCalculator
import nz.co.timbertakeoff.core.DeckResult
import nz.co.timbertakeoff.core.DeckingSetoutCalculator
import nz.co.timbertakeoff.core.FramingOrientation
import nz.co.timbertakeoff.core.MaterialCategory
import nz.co.timbertakeoff.core.MaterialConsolidator
import nz.co.timbertakeoff.core.MemberKind
import nz.co.timbertakeoff.core.Point
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
import kotlin.math.abs

/** A deck exceeding 6 m in both directions passes through Room, the phone and native PDF. */
@RunWith(AndroidJUnit4::class)
class LargeBoundaryWorkflowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val app: EstimatorApplication = ApplicationProvider.getApplicationContext()
    private lateinit var jobName: String
    private lateinit var deckName: String
    private var taskId = 0L

    @Before fun createSavedLargeDeckAndOpenApp() {
        val suffix = UUID.randomUUID().toString().take(8)
        jobName = "Large boundary job $suffix"
        deckName = "Eight by nine metre deck $suffix"
        val draft = DeckDraft(
            widthMm = "8000", lengthMm = "9000",
            deckingProfileId = "140 × 19 mm", actualDeckingWidthMm = "140",
            orientation = FramingOrientation.LENGTHWAYS,
        )
        runBlocking {
            val clientId = app.repository.createClient("Large boundary client $suffix")
            val jobId = app.repository.createJob(clientId, jobName)
            taskId = app.repository.createDeckTask(jobId, deckName, draft)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun closeApp() { scenario.close() }

    @Test fun calculateStaggeredBoundariesInBothOrientationsReopenAndPrint() {
        openSavedDeck()
        for (orientation in listOf(FramingOrientation.LENGTHWAYS, FramingOrientation.WIDTHWAYS)) {
            if (orientation == FramingOrientation.WIDTHWAYS) {
                click("Lengthways · bearers parallel to deck length", scroll = true)
                click("Widthways · bearers parallel to deck width")
            }
            compose.onNodeWithText("Inputs").assertIsSelected()
            compose.onNodeWithText("1. Deck width (mm)").assert(hasText("8000"))
            compose.onNodeWithText("2. Deck length (mm)").assert(hasText("9000"))
            click("Calculate")
            waitForText("Overall materials")
            compose.onNodeWithText("Materials").assertIsSelected()
            compose.onNodeWithText("Layout cannot be calculated", substring = true).assertDoesNotExist()

            compose.waitUntil(60_000) {
                runBlocking {
                    app.repository.getTask(taskId)?.let {
                        val draft = DeckDraft.fromJson(it.inputJson)
                        draft.widthMm == "8000" && draft.lengthMm == "9000" && draft.orientation == orientation
                    } == true
                }
            }
            val saved = runBlocking { checkNotNull(app.repository.getTask(taskId)) }
            val outcome = DeckDraft.fromJson(saved.inputJson).calculate(saved.typeId)
            assertTrue("An 8000 × 9000 mm saved deck must calculate in $orientation", outcome is CalculationOutcome.Success)
            val result = (outcome as CalculationOutcome.Success).result
            assertLargeDeckGeometryAndTakeoff(result, orientation)

            val overallJoist = MaterialConsolidator.consolidate(result.materials).single {
                it.key.specification == result.input.joist.label && it.key.unit == "lm"
            }
            waitForText("${number(overallJoist.quantity)} lm")
            click("Show material breakdown", scroll = true)
            click("Joists and boundary joists", scroll = true)
            val joistLine = result.materials.single { it.category == MaterialCategory.JOISTS }
            waitForText("${joistLine.pieceCount} pieces")
            compose.onNodeWithText("${joistLine.pieceCount} pieces").performScrollTo()
            screenshot("large-boundary-${orientation.name.lowercase()}-materials.png")

            click("Drawings")
            waitForText("Drawing sheet")
            compose.onNodeWithText("Drawings").assertIsSelected()
            waitForText("8000 × 9000 mm framing", substring = true)
            screenshot("large-boundary-${orientation.name.lowercase()}-D01-preview.png")

            // The first mark remains zero, and the board-side instruction follows the
            // actual orientation rather than always pointing to the same screen edge.
            click("Show decking set-out measurements", scroll = true)
            waitForText("Running decking measurements")
            val setout = DeckingSetoutCalculator.calculate(result)
            val side = if (orientation == FramingOrientation.LENGTHWAYS) "right side (+X)" else "lower side (+Y)"
            waitForText(side, substring = true)
            compose.onNodeWithTag("decking-setout-board-1").assert(hasText("0 mm"))
            compose.onNodeWithTag("decking-setout-board-2")
                .assert(hasText("${number(setout.marks[1].runningMm)} mm"))
            click("Hide decking set-out measurements", scroll = true)

            exportAndRenderPlans(result, orientation)

            // Relaunch the actual Activity, reopening the persisted task on Inputs.
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            openSavedDeck()
            compose.onNodeWithText("Inputs").assertIsSelected()
            compose.onNodeWithText("1. Deck width (mm)").assert(hasText("8000"))
            compose.onNodeWithText("2. Deck length (mm)").assert(hasText("9000"))
            waitForText(if (orientation == FramingOrientation.LENGTHWAYS)
                "Lengthways · bearers parallel to deck length" else "Widthways · bearers parallel to deck width")
        }
    }

    private fun assertLargeDeckGeometryAndTakeoff(result: DeckResult, orientation: FramingOrientation) {
        val geometry = result.geometry
        assertEquals(orientation, geometry.orientation)
        assertTrue("All calculated member and material invariants must hold", DeckCalculator.validate(result).isEmpty())
        assertTrue("Every physical framing cut is positive and at most 6000 mm",
            geometry.members.all { it.lengthMm > 0.0 && it.lengthMm <= 6000.0 + 1e-6 })
        val boundaries = geometry.members.filter { it.kind == MemberKind.BOUNDARY }
        assertTrue("Long double boundaries must be represented by physical cuts", boundaries.size > 8)
        // Four complete side members plus four end members shortened by two double
        // boundaries: 4 × 8000 + 4 × (9000 - 4 × 45), with axes interchangeable.
        assertEquals(67280.0, boundaries.sumOf { it.lengthMm }, 1e-6)

        fun u(point: Point) = if (orientation == FramingOrientation.LENGTHWAYS) point.y else point.x
        val perpendicularJoists = geometry.members.filter { it.kind == MemberKind.JOIST }
        for ((first, second) in listOf("BE1" to "BE2", "BE3" to "BE4")) {
            val firstJoins = geometry.joins.filter { it.runId == first }
            val secondJoins = geometry.joins.filter { it.runId == second }
            assertTrue("$first and $second both need splices on this long deck", firstJoins.isNotEmpty() && secondJoins.isNotEmpty())
            assertTrue("Doubled end-boundary splices must be staggered",
                firstJoins.none { a -> secondJoins.any { b -> abs(u(a.position) - u(b.position)) < 1e-6 } })
            for (join in firstJoins + secondJoins) {
                assertTrue("${join.runId} splice must meet an actual perpendicular joist",
                    perpendicularJoists.any { abs(u(it.start) - u(join.position)) < 1e-6 })
            }
        }

        // Six bearer lines are required in either orientation. The independent pile
        // intervals are ceil((9000 - 400)/1300)=7 or ceil((8000 - 400)/1300)=6.
        // The staggered end-boundary detail adds no extra bearer or pile supports.
        assertEquals(6, geometry.bearerPositionsMm.size)
        val expectedPiles = if (orientation == FramingOrientation.LENGTHWAYS) 48 else 42
        val expectedBearerMetres = if (orientation == FramingOrientation.LENGTHWAYS) 108.0 else 96.0
        assertEquals(expectedPiles, geometry.piles.size)
        assertEquals(expectedBearerMetres,
            result.materials.single { it.category == MaterialCategory.BEARERS }.quantity, 1e-6)
        val joistCuts = geometry.members.filter { it.kind == MemberKind.JOIST || it.kind == MemberKind.BOUNDARY }
            .map { it.lengthMm }.sorted()
        val joistLine = result.materials.single { it.category == MaterialCategory.JOISTS }
        assertEquals("The takeoff retains each actual joist/boundary cut", joistCuts, joistLine.cutLengthsMm.sorted())
        assertEquals(joistCuts.sum() / 1000.0, joistLine.quantity, 1e-6)
    }

    private fun exportAndRenderPlans(result: DeckResult, orientation: FramingOrientation) {
        for (size in SheetSize.entries) {
            val sheets = DrawingGenerator.generate(result,
                DrawingTitle(project = jobName, task = deckName, preparedDate = "2026-10-09"), size)
            assertEquals(listOf("D01", "D02", "D03", "M01"), sheets.take(4).map { it.code })
            val basename = "large-boundary-${orientation.name.lowercase()}-${size.name}"
            val file = deviceArtifact(app, "$basename.pdf")
            file.outputStream().use { PdfExporter.write(it, sheets) }
            assertTrue("${size.name} export must contain vector drawing content", file.length() > 1000)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { pdf ->
                    assertEquals(sheets.size, pdf.pageCount)
                    for (index in 0 until pdf.pageCount) {
                        pdf.openPage(index).use { page ->
                            assertEquals(if (size == SheetSize.A3) 1191 else 842, page.width)
                            assertEquals(if (size == SheetSize.A3) 842 else 595, page.height)
                            // Save the large framing/decking plans and any splice detail
                            // at twice the native page resolution for independent review.
                            if (sheets[index].code in setOf("D01", "D02", "D06")) {
                                val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    val pixels = IntArray(bitmap.width * bitmap.height)
                                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                    assertTrue("${size.name} ${sheets[index].code} must print linework and dimensions",
                                        pixels.count { Color.red(it) < 64 && Color.green(it) < 64 && Color.blue(it) < 64 } > 100)
                                    deviceArtifact(app, "$basename-${sheets[index].code}.png").outputStream().use {
                                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                                    }
                                } finally { bitmap.recycle() }
                            }
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
