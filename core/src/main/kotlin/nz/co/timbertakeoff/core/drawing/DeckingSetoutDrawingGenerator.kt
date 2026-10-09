package nz.co.timbertakeoff.core.drawing

import nz.co.timbertakeoff.core.DeckResult
import nz.co.timbertakeoff.core.DeckingSetout
import nz.co.timbertakeoff.core.DeckingSetoutCalculator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A printable, complete tape-measure schedule, derived from the installed board leading edges. */
internal object DeckingSetoutDrawingGenerator {
    private const val ROW_HEIGHT = 5.6
    private const val TABLE_TOP = 92.0

    fun generate(result: DeckResult, title: DrawingTitle, size: SheetSize): List<DrawingSheet> {
        val setout = DeckingSetoutCalculator.calculate(result)
        if (setout.marks.isEmpty()) return emptyList()
        val panels = if (size == SheetSize.A3) 4 else 3
        val bottom = size.heightMm - 43.0
        val rowsPerPanel = floor((bottom - TABLE_TOP) / ROW_HEIGHT).toInt().coerceAtLeast(1)
        val marksPerPage = panels * rowsPerPanel
        val pages = ceil(setout.marks.size.toDouble() / marksPerPage).toInt()
        val panelGap = 6.0
        val panelWidth = (size.widthMm - 30.0 - panelGap * (panels - 1)) / panels
        return (0 until pages).map { page ->
            val b = SheetBuilder(size)
            val code = if (page == 0) "D05" else "D05.${page + 1}"
            DrawingGenerator.frame(b, code, "Decking set-out", title, null)
            header(b, result, setout)
            val firstIndex = page * marksPerPage
            val lastIndex = min(firstIndex + marksPerPage, setout.marks.size)
            repeat(panels) { panel ->
                val start = firstIndex + panel * rowsPerPanel
                if (start >= lastIndex) return@repeat
                val x = 15.0 + panel * (panelWidth + panelGap)
                val runningX = x + panelWidth * 0.65
                val widthX = x + panelWidth - 2.0
                b.text(x + 2.0, TABLE_TOP - 3.0, "BOARD", 2.2, bold = true)
                b.text(runningX, TABLE_TOP - 3.0, "RUNNING (mm)", 2.2, TextAlign.RIGHT, bold = true)
                b.text(widthX, TABLE_TOP - 3.0, "WIDTH", 2.2, TextAlign.RIGHT, bold = true)
                b.line(x, TABLE_TOP - 1.0, x + panelWidth, TABLE_TOP - 1.0, 0.3)
                val end = min(start + rowsPerPanel, lastIndex)
                (start until end).forEachIndexed { row, index ->
                    val mark = setout.marks[index]
                    val y = TABLE_TOP + row * ROW_HEIGHT
                    b.text(x + 2.0, y + 3.8, mark.boardNumber.toString(), 2.5)
                    b.text(runningX, y + 3.8, DrawingGenerator.mm(mark.runningMm), 2.5, TextAlign.RIGHT, bold = true)
                    b.text(widthX, y + 3.8, DrawingGenerator.mm(mark.widthMm), 2.5, TextAlign.RIGHT)
                    b.line(x, y + ROW_HEIGHT, x + panelWidth, y + ROW_HEIGHT, 0.1)
                }
            }
            b.text(15.0, bottom + 4.0, "Read down each column, then across. Board widths in mm. Infill marks only; frame boards excluded.", 2.2)
            b.text(size.widthMm - 15.0, bottom + 8.0,
                "Boards ${firstIndex + 1}–$lastIndex of ${setout.marks.size} · Set-out page ${page + 1}/$pages", 2.2, TextAlign.RIGHT)
            b.sheet(code, "Decking set-out", null)
        }
    }

    private fun header(b: SheetBuilder, result: DeckResult, setout: DeckingSetout) {
        val textWidth = b.size.widthMm - 141.0
        val frameLabel = if (result.input.pictureFrame) "first infill" else "first board"
        val direction = if (setout.markAlongX) "RIGHT (+X)" else "BOTTOM (+Y)"
        val offset = setout.datumOffsetMm
        val offsetLabel = when {
            offset < -0.000001 -> "${DrawingGenerator.mm(-offset)} mm outside framing"
            offset > 0.000001 -> "${DrawingGenerator.mm(offset)} mm inside framing"
            else -> "at outside framing edge"
        }
        b.note(15.0, 40.0, textWidth, "CUMULATIVE BOARD LEADING-EDGE MARKS", 3.0, true)
        b.note(15.0, 47.0, textWidth, "DATUM 0 = $frameLabel leading edge shown on D02. Datum is $offsetLabel.", 2.2, true)
        b.note(15.0, 57.0, textWidth, "Measure toward plan $direction. Lay each board AFTER its mark, toward the next mark; leave the gap before that next line.", 2.2)
        val firstWidth = setout.marks.first().widthMm
        val firstLabel = if (abs(firstWidth - result.input.actualDeckingWidthMm) > 0.001) "FIRST WIDTH / RIP" else "FIRST WIDTH / FULL"
        b.note(15.0, 70.0, textWidth,
            "GAP ${DrawingGenerator.mm(setout.gapMm)} mm · $firstLabel ${DrawingGenerator.mm(firstWidth)} mm · ${setout.marks.size} marks", 2.2, true)
        b.note(15.0, 78.0, textWidth, "Measure every mark from datum. Display rounds to 0.001 mm; never add rounded steps.", 2.2)
        sideDiagram(b, result, setout)
    }

    /** NTS schematic isolates the line/board relationship even for a narrow first rip. */
    private fun sideDiagram(b: SheetBuilder, result: DeckResult, setout: DeckingSetout) {
        val x = b.size.widthMm - 112.0
        val firstWidth = max(9.0, 26.0 * setout.marks.first().widthMm / result.input.actualDeckingWidthMm)
        val nextX = x + 8.0 + firstWidth + 4.0
        b.text(x, 40.0, "LEADING EDGE → BOARD → GAP", 2.2, bold = true)
        b.rect(x + 8.0, 47.0, firstWidth, 17.0, 0.25)
        if (setout.marks.size > 1) b.rect(nextX, 47.0, 26.0, 17.0, 0.25)
        b.line(x + 8.0, 44.0, x + 8.0, 66.0, 0.45)
        b.text(x + 8.0, 44.0, "0", 2.2, TextAlign.CENTER, bold = true)
        if (setout.marks.size > 1) {
            b.line(nextX, 44.0, nextX, 66.0, 0.45)
            b.text(nextX, 44.0, DrawingGenerator.mm(setout.marks[1].runningMm), 2.2, TextAlign.CENTER, bold = true)
        }
        b.text(x + 12.0, 69.0, "BOARD THIS SIDE OF LINE", 2.2, bold = true)
        arrow(b, x + 8.0, 73.0, x + 76.0)
        b.text(x + 42.0, 78.0, "SET-OUT DIRECTION", 2.2, TextAlign.CENTER)
        b.text(x, 83.0, "Schematic NTS. Use D02 for plan orientation.", 2.2)
    }

    private fun arrow(b: SheetBuilder, x1: Double, y: Double, x2: Double) {
        b.line(x1, y, x2, y, 0.3)
        b.line(x2 - 2.0, y - 1.2, x2, y, 0.3)
        b.line(x2 - 2.0, y + 1.2, x2, y, 0.3)
    }
}
