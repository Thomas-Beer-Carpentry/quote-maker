package nz.co.timbertakeoff.core.drawing

import nz.co.timbertakeoff.core.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class DrawingGeneratorTest {
    @Test fun `equal cuts group despite numerical noise while retaining their total`() {
        val cuts = listOf(379.09090909090907, 379.0909090909091, 379.090909090909, 379.09090909090946)
        val entries = CutSchedule.entries(cuts)
        assertEquals(1, entries.size)
        assertEquals(4, entries.single().pieces)
        assertEquals(cuts.sum(), entries.sumOf { it.lengthMm * it.pieces }, 0.000000001)
        assertEquals(2, CutSchedule.entries(listOf(1000.0, 1000.01)).size)
    }

    private fun result(input: DeckInput = DeckInput()): DeckResult {
        val outcome = DeckCalculator.calculate(input)
        assertTrue("Expected valid deck: $outcome", outcome is CalculationOutcome.Success)
        return (outcome as CalculationOutcome.Success).result
    }

    @Test fun `sheets are generated for both print sizes with legible finite vector coordinates`() {
        val inputs = listOf(
            DeckInput(),
            DeckInput(widthMm = 400.0, lengthMm = 400.0),
            DeckInput(widthMm = 20000.0, lengthMm = 4800.0, orientation = FramingOrientation.LENGTHWAYS),
            DeckInput(widthMm = 4800.0, lengthMm = 20000.0, orientation = FramingOrientation.WIDTHWAYS),
            DeckInput(heightMm = 10000.0),
            DeckInput(orientation = FramingOrientation.WIDTHWAYS)
        )
        inputs.forEach { input -> SheetSize.entries.forEach { size ->
            val sheets = DrawingGenerator.generate(result(input), DrawingTitle("Test job", "Client", "Deck"), size)
            assertEquals(listOf("D01", "D02", "D03"), sheets.take(3).map { it.code })
            assertEquals("M01", sheets[3].code)
            sheets.forEach { sheet ->
                assertEquals(size.widthMm, sheet.widthMm, 0.0)
                assertEquals(size.heightMm, sheet.heightMm, 0.0)
                assertTrue(sheet.elements.isNotEmpty())
                sheet.elements.forEach { element ->
                    when (element) {
                        is DrawingElement.Line -> { bound(sheet, element.x1, element.y1); bound(sheet, element.x2, element.y2) }
                        is DrawingElement.Rect -> {
                            assertTrue(element.width >= 0.0 && element.height >= 0.0)
                            bound(sheet, element.x, element.y); bound(sheet, element.x + element.width, element.y + element.height)
                        }
                        is DrawingElement.Circle -> { bound(sheet, element.x - element.radiusMm, element.y - element.radiusMm); bound(sheet, element.x + element.radiusMm, element.y + element.radiusMm) }
                        is DrawingElement.Text -> { bound(sheet, element.x, element.y); assertTrue("Text must remain readable at printed scale", element.sizeMm >= 2.2) }
                    }
                }
            }
        } }
    }

    @Test fun `geometry edits alter drawings and dimension strings`() {
        val initial = DrawingGenerator.generate(result(DeckInput()), sheetSize = SheetSize.A3)
        val changed = DrawingGenerator.generate(result(DeckInput(widthMm = 3900.0, lengthMm = 5200.0)), sheetSize = SheetSize.A3)
        assertNotEquals(initial[0].elements, changed[0].elements)
        assertTrue(texts(changed[0]).contains("3900"))
        assertTrue(texts(changed[0]).contains("5200"))
        assertTrue(texts(changed[1]).contains("3940"))
        assertTrue(texts(changed[1]).contains("5240"))
        assertTrue(texts(initial[0]).joinToString(" ").contains("180 mm side boundaries"))
        assertTrue(texts(initial[0]).joinToString(" ").contains("90 mm internal"))
    }

    @Test fun `decking scene contains every individual board in the calculated orientation`() {
        listOf(FramingOrientation.LENGTHWAYS, FramingOrientation.WIDTHWAYS).forEach { orientation ->
            val result = result(DeckInput(orientation = orientation))
            val sheet = DrawingGenerator.generate(result)[1]
            val rects = sheet.elements.filterIsInstance<DrawingElement.Rect>()
            val drawnBoards = rects.filter { rect -> result.geometry.boards.any { board ->
                val width = (if (board.runsAlongX) board.lengthMm else board.widthMm) / sheet.scaleDenominator!!
                val height = (if (board.runsAlongX) board.widthMm else board.lengthMm) / sheet.scaleDenominator!!
                abs(rect.width - width) < 0.00001 && abs(rect.height - height) < 0.00001
            } }
            assertEquals("No individual board may be dropped from the scene", result.geometry.boards.size, drawnBoards.size)
            assertEquals("Boards must occupy independent positions", result.geometry.boards.size, drawnBoards.map { it.x to it.y }.distinct().size)
            result.geometry.boards.forEach { board ->
                val width = (if (board.runsAlongX) board.lengthMm else board.widthMm) / sheet.scaleDenominator!!
                val height = (if (board.runsAlongX) board.widthMm else board.lengthMm) / sheet.scaleDenominator!!
                assertTrue("Each calculated board must be drawn at scale", rects.any { abs(it.width - width) < 0.00001 && abs(it.height - height) < 0.00001 })
            }
            assertTrue(texts(sheet).any { it.contains("Equal gap") })
        }
    }

    @Test fun `schedule paginates full exact cuts without dropping quantities`() {
        val base = result()
        val cuts = (1..100).map { 1000.0 + it / 10.0 }
        val custom = base.copy(materials = listOf(MaterialLine(MaterialCategory.JOISTS,
            MaterialKey("Timber", "140 × 45 mm · radiata", "lm"), cuts.sum() / 1000.0, cuts)))
        val schedules = DrawingGenerator.generate(custom, sheetSize = SheetSize.A4).drop(3)
        assertTrue("A long cut schedule needs continuation pages", schedules.size > 1)
        val texts = schedules.flatMap(::texts)
        cuts.forEach { cut -> assertTrue("Missing cut $cut", texts.contains("1 × ${DrawingGenerator.mm(cut)} mm")) }
        assertEquals(100, CutSchedule.entries(cuts).sumOf { it.pieces })
        assertTrue(texts.any { it == "CONSOLIDATED MATERIAL SUMMARY" })
        assertTrue(texts.any { it == "${DrawingGenerator.amount(cuts.sum() / 1000.0)} lm" })
    }

    @Test fun `pile section is derived from input profile depths and finished height`() {
        val input = DeckInput(heightMm = 1200.0, bearer = Profiles.framing[2], joist = Profiles.framing[1])
        val result = result(input)
        val section = DrawingGenerator.generate(result)[2]
        val expected = 1200.0 - 19.0 - 190.0 - 240.0 + 500.0
        assertTrue(texts(section).contains("${DrawingGenerator.mm(expected)} PILE CUT"))
        assertTrue(texts(section).contains("1200 FINISHED HEIGHT"))
        assertTrue(texts(section).contains("500 EMBEDMENT"))
        assertTrue(texts(section).contains("600 HOLE"))
        assertTrue(texts(section).contains("400 HOLE"))
    }

    private fun texts(sheet: DrawingSheet) = sheet.elements.filterIsInstance<DrawingElement.Text>().map { it.text }
    private fun bound(sheet: DrawingSheet, x: Double, y: Double) {
        assertTrue("${sheet.code} coordinate ($x,$y) must be finite", x.isFinite() && y.isFinite())
        assertTrue("${sheet.code} x $x outside ${sheet.widthMm}", x in 0.0..sheet.widthMm)
        assertTrue("${sheet.code} y $y outside ${sheet.heightMm}", y in 0.0..sheet.heightMm)
    }
}
