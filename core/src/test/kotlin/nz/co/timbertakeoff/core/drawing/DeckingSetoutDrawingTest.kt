package nz.co.timbertakeoff.core.drawing

import nz.co.timbertakeoff.core.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class DeckingSetoutDrawingTest {
    private data class PrintedMark(val board: Int, val running: String, val width: String)

    @Test fun `full width boards print cumulative 145 290 and 435 marks in either orientation`() {
        listOf(FramingOrientation.LENGTHWAYS, FramingOrientation.WIDTHWAYS).forEach { orientation ->
            val input = DeckInput(
                widthMm = if (orientation == FramingOrientation.LENGTHWAYS) 2855.0 else 4800.0,
                lengthMm = if (orientation == FramingOrientation.LENGTHWAYS) 4800.0 else 2855.0,
                decking = Profiles.decking[1], orientation = orientation
            )
            val result = result(input)
            assertEquals(5.0, result.geometry.deckingGapMm, 0.000001)
            SheetSize.entries.forEach { size ->
                val sheets = DrawingGenerator.generate(result, sheetSize = size)
                assertEquals(listOf("D01", "D02", "D03", "M01"), sheets.take(4).map { it.code })
                val printed = printedMarks(sheets)
                assertEquals(listOf("0", "145", "290", "435"), printed.take(4).map { it.running })
                assertTrue(printed.all { it.width == "140" })
                val d02 = texts(sheets[1]).joinToString(" ")
                assertTrue(d02.contains("DATUM 0"))
                assertTrue(d02.contains("SET-OUT +"))
                assertTrue(d02.contains("Boards go AFTER each line"))
                assertTrue(d02.contains("Running board marks: D05"))
                val d05 = texts(sheets.first { it.code == "D05" }).joinToString(" ")
                assertTrue(d05.contains("Datum is 20 mm outside framing"))
                assertTrue(d05.contains(if (orientation == FramingOrientation.LENGTHWAYS) "RIGHT (+X)" else "BOTTOM (+Y)"))
                assertTrue(d05.contains("BOARD THIS SIDE OF LINE"))
            }
        }
    }

    @Test fun `ripped first infill and picture frame offset print actual geometry`() {
        val calculated = result(DeckInput(widthMm = 3670.0, decking = Profiles.decking[1], pictureFrame = true,
            orientation = FramingOrientation.LENGTHWAYS))
        val infill = calculated.geometry.boards.filter { it.role == DeckBoardRole.INFILL }.sortedBy { it.origin.x }
        assertTrue(infill.first().widthMm < calculated.input.actualDeckingWidthMm)
        SheetSize.entries.forEach { size ->
            val sheets = DrawingGenerator.generate(calculated, sheetSize = size)
            val printed = printedMarks(sheets)
            assertEquals(infill.size, printed.size)
            infill.forEachIndexed { index, board ->
                assertEquals(index + 1, printed[index].board)
                assertEquals(DrawingGenerator.mm(board.origin.x - infill.first().origin.x), printed[index].running)
                assertEquals(DrawingGenerator.mm(board.widthMm), printed[index].width)
            }
            val text = texts(sheets.first { it.code == "D05" }).joinToString(" ")
            assertTrue(text.contains("Datum is 125 mm inside framing"))
            assertTrue(text.contains("FIRST WIDTH / RIP ${DrawingGenerator.mm(infill.first().widthMm)} mm"))
            assertTrue("Packers keep their existing detail before set-out pages", sheets.indexOfFirst { it.code == "D04" } <
                sheets.indexOfFirst { it.code == "D05" })
        }
    }

    @Test fun `all large deck board marks print exactly once with contiguous continuation pages`() {
        val calculated = result(DeckInput(widthMm = 20000.0, lengthMm = 4800.0,
            orientation = FramingOrientation.LENGTHWAYS))
        val infill = calculated.geometry.boards.filter { it.role == DeckBoardRole.INFILL }.sortedBy { it.origin.x }
        assertTrue(infill.size > 200)
        SheetSize.entries.forEach { size ->
            val sheets = DrawingGenerator.generate(calculated, sheetSize = size)
            val schedule = sheets.filter { it.code == "D05" || it.code.startsWith("D05.") }
            assertTrue(schedule.size > 1)
            assertEquals((1..schedule.size).map { if (it == 1) "D05" else "D05.$it" }, schedule.map { it.code })
            val printed = printedMarks(sheets)
            assertEquals(infill.size, printed.size)
            assertEquals((1..infill.size).toList(), printed.map { it.board })
            infill.forEachIndexed { index, board ->
                assertEquals(DrawingGenerator.mm(board.origin.x - infill.first().origin.x), printed[index].running)
                assertEquals(DrawingGenerator.mm(board.widthMm), printed[index].width)
            }
            schedule.forEach { sheet ->
                val label = texts(sheet).joinToString(" ")
                assertTrue(label.contains("DATUM 0"))
                assertTrue(label.contains("BOARD THIS SIDE OF LINE"))
                assertTrue(label.contains("Measure every mark from datum"))
                sheet.elements.filterIsInstance<DrawingElement.Text>().forEach { text ->
                    assertTrue("Printed text remains legible", text.sizeMm >= 2.2)
                    assertTrue(text.x in 0.0..sheet.widthMm && text.y in 0.0..sheet.heightMm)
                    if (text.y >= 92.0 && text.text.toDoubleOrNull() != null)
                        assertTrue("Numeric rows remain above the title block", text.y < sheet.heightMm - 38.0)
                }
            }
        }
    }

    @Test fun `fractional board gaps use each calculated origin without rounded pitch drift`() {
        val calculated = result(DeckInput(widthMm = 3717.0, lengthMm = 4800.0,
            decking = Profiles.decking[1], orientation = FramingOrientation.LENGTHWAYS))
        assertTrue(abs(calculated.geometry.deckingGapMm - kotlin.math.round(calculated.geometry.deckingGapMm)) > 0.001)
        val boards = calculated.geometry.boards.filter { it.role == DeckBoardRole.INFILL }.sortedBy { it.origin.x }
        val printed = printedMarks(DrawingGenerator.generate(calculated, sheetSize = SheetSize.A4))
        val expectedLast = boards.last().origin.x - boards.first().origin.x
        assertEquals(DrawingGenerator.mm(expectedLast), printed.last().running)
        assertEquals(boards.size, printed.size)
    }

    @Test fun `datum setout and board direction labels clear narrow board linework and other callouts`() {
        listOf(FramingOrientation.LENGTHWAYS, FramingOrientation.WIDTHWAYS).forEach { orientation ->
            val narrow = DeckInput(
                widthMm = if (orientation == FramingOrientation.LENGTHWAYS) 535.0 else 4800.0,
                lengthMm = if (orientation == FramingOrientation.LENGTHWAYS) 4800.0 else 535.0,
                decking = Profiles.decking[1], orientation = orientation
            )
            val frame = DeckInput(widthMm = 3670.0, decking = Profiles.decking[1], pictureFrame = true,
                orientation = orientation)
            listOf(narrow, frame).forEach { input -> SheetSize.entries.forEach { size ->
                val sheet = DrawingGenerator.generate(result(input), sheetSize = size)[1]
                val perimeter = sheet.elements.filterIsInstance<DrawingElement.Rect>().single { it.weightMm == 0.4 }
                val text = sheet.elements.filterIsInstance<DrawingElement.Text>()
                val annotations = listOf("DATUM 0", "SET-OUT +", "BOARD DIRECTION").map { label ->
                    text.single { it.text == label }
                }
                annotations.forEach { label ->
                    val box = textBounds(label)
                    assertTrue("${label.text} must sit outside decking linework at ${size.name}",
                        box.right < perimeter.x || box.left > perimeter.x + perimeter.width ||
                            box.bottom < perimeter.y || box.top > perimeter.y + perimeter.height)
                }
                for (i in annotations.indices) for (j in i + 1 until annotations.size)
                    assertSeparate(annotations[i], annotations[j])
                val callouts = text.filter { it.text.endsWith(" OVERHANG") || it.text.startsWith("INFILL RIP ") ||
                    it.text.startsWith("START ") || it.text == "PF" }
                annotations.forEach { label -> callouts.forEach { assertSeparate(label, it) } }
                assertEquals("Direction label is legible without rotating through board lines", 0.0,
                    annotations.single { it.text == "BOARD DIRECTION" }.rotationDegrees, 0.0)
            } }
        }
    }

    private data class TextBox(val left: Double, val top: Double, val right: Double, val bottom: Double)
    private fun textBounds(text: DrawingElement.Text): TextBox {
        val width = text.text.length * text.sizeMm * 0.65
        val left = text.x - when (text.align) {
            TextAlign.LEFT -> 0.0
            TextAlign.CENTER -> width / 2.0
            TextAlign.RIGHT -> width
        }
        return TextBox(left, text.y - text.sizeMm, left + width, text.y + text.sizeMm * 0.2)
    }
    private fun assertSeparate(first: DrawingElement.Text, second: DrawingElement.Text) {
        val a = textBounds(first)
        val b = textBounds(second)
        assertTrue("Labels '${first.text}' and '${second.text}' must remain separate",
            a.right < b.left || b.right < a.left || a.bottom < b.top || b.bottom < a.top)
    }

    private fun result(input: DeckInput): DeckResult {
        val outcome = DeckCalculator.calculate(input)
        assertTrue("Expected valid fixture: $outcome", outcome is CalculationOutcome.Success)
        return (outcome as CalculationOutcome.Success).result
    }

    private fun texts(sheet: DrawingSheet) = sheet.elements.filterIsInstance<DrawingElement.Text>().map { it.text }

    /** Recover table cells by column and baseline, independent of title/header numeric labels. */
    private fun printedMarks(sheets: List<DrawingSheet>): List<PrintedMark> = sheets
        .filter { it.code == "D05" || it.code.startsWith("D05.") }.flatMap { sheet ->
            val text = sheet.elements.filterIsInstance<DrawingElement.Text>()
            text.filter { it.align == TextAlign.LEFT && it.y >= 92.0 && it.y < sheet.heightMm - 43.0 &&
                it.text.toIntOrNull() != null }.sortedWith(compareBy({ it.x }, { it.y })).map { number ->
                val cells = text.filter { abs(it.y - number.y) < 0.000001 && it.x > number.x &&
                    it.x < number.x + 100.0 && it.align == TextAlign.RIGHT }
                PrintedMark(number.text.toInt(), cells.single { it.bold }.text, cells.single { !it.bold }.text)
            }
        }
}
