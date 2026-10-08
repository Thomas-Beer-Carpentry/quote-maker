package nz.co.timbertakeoff.core.drawing

import nz.co.timbertakeoff.core.*

data class CutScheduleEntry(val lengthMm: Double, val pieces: Int)
/** Full exact lengths are retained; rounding here changes display only, never takeoff quantities. */
object CutSchedule {
    fun entries(lengthsMm: List<Double>): List<CutScheduleEntry> = lengthsMm.groupingBy { it }.eachCount()
        .map { (length, pieces) -> CutScheduleEntry(length, pieces) }.sortedBy { it.lengthMm }
}

internal object MaterialDrawingGenerator {
    private enum class RowKind { SECTION, MATERIAL, CUT, NOTE }
    private data class Row(val kind: RowKind, val text: String, val quantity: String = "")
    fun generate(result: DeckResult, title: DrawingTitle, size: SheetSize): List<DrawingSheet> {
        val rows = mutableListOf<Row>()
        val descriptionWidth = size.widthMm - 107.0
        fun addWrapped(kind: RowKind, value: String, quantity: String = "") {
            val maxChars = (descriptionWidth / 1.4).toInt()
            val lines = wrap(value, maxChars)
            lines.forEachIndexed { index, text -> rows += Row(if (index == 0) kind else RowKind.NOTE, text, if (index == 0) quantity else "") }
        }
        MaterialCategory.entries.forEach { category ->
            val lines = result.materials.filter { it.category == category }
            if (lines.isEmpty()) return@forEach
            rows += Row(RowKind.SECTION, category.title)
            lines.forEach { line ->
                addWrapped(RowKind.MATERIAL, "${line.key.type} · ${line.key.specification}", "${DrawingGenerator.amount(line.quantity)} ${line.key.unit}")
                if (line.cutLengthsMm.isNotEmpty()) {
                    addWrapped(RowKind.NOTE, "${line.pieceCount} pieces · exact cut schedule (mm)")
                    CutSchedule.entries(line.cutLengthsMm).forEach { cut ->
                        rows += Row(RowKind.CUT, "${cut.pieces} × ${DrawingGenerator.mm(cut.lengthMm)} mm", "${DrawingGenerator.amount(cut.pieces * cut.lengthMm / 1000.0)} m")
                    }
                }
                if (category == MaterialCategory.DECKING) addWrapped(RowKind.NOTE, "${result.geometry.boards.size} boards · actual finished width ${DrawingGenerator.mm(result.input.actualDeckingWidthMm)} mm · starting width ${DrawingGenerator.mm(result.geometry.startingBoardWidthMm)} mm")
            }
        }
        rows += Row(RowKind.SECTION, "Consolidated material summary")
        MaterialConsolidator.consolidate(result.materials).forEach { line ->
            addWrapped(RowKind.MATERIAL, "${line.key.type} · ${line.key.specification}", "${DrawingGenerator.amount(line.quantity)} ${line.key.unit}")
        }
        rows += Row(RowKind.NOTE, "Exact quantities. No waste allowance or stock-length optimisation.")
        rows += Row(RowKind.NOTE, "Concrete bag counts are rounded up; other calculated quantities retain exact totals.")
        val sheets = mutableListOf<DrawingSheet>()
        var index = 0
        var page = 1
        // Keep grouped headings with at least the first following row when a page breaks.
        while (index < rows.size) {
            val b = SheetBuilder(size)
            val code = if (page == 1) "M01" else "M01.$page"
            DrawingGenerator.frame(b, code, "Material takeoff", title, null)
            b.text(15.0, 40.0, "MATERIAL / SPECIFICATION / EXACT CUT LENGTH", 2.5, bold = true)
            b.text(size.widthMm - 16.0, 40.0, "EXACT QUANTITY", 2.5, TextAlign.RIGHT, bold = true)
            b.line(14.0, 43.0, size.widthMm - 14.0, 43.0, 0.3)
            val bottom = size.heightMm - 43.0
            var y = 44.0
            while (index < rows.size) {
                val row = rows[index]
                val height = if (row.kind == RowKind.SECTION) 9.0 else 6.0
                val keep = if (row.kind == RowKind.SECTION && index + 1 < rows.size) 6.0 else 0.0
                if (y + height + keep > bottom) break
                if (row.kind == RowKind.SECTION) {
                    b.rect(14.0, y + 1.0, size.widthMm - 28.0, height - 1.0, 0.2)
                    b.text(17.0, y + 6.5, row.text.uppercase(), 2.8, bold = true)
                } else {
                    val indent = if (row.kind == RowKind.CUT || row.kind == RowKind.NOTE) 4.0 else 0.0
                    b.text(17.0 + indent, y + 4.5, row.text, if (row.kind == RowKind.NOTE) 2.2 else 2.5, bold = row.kind == RowKind.MATERIAL)
                    b.text(size.widthMm - 17.0, y + 4.5, row.quantity, 2.5, TextAlign.RIGHT)
                    if (row.kind == RowKind.MATERIAL) b.line(15.0, y + height, size.widthMm - 15.0, y + height, 0.1)
                }
                y += height
                index++
            }
            b.text(size.widthMm - 15.0, bottom + 4.0, "Material schedule page $page", 2.2, TextAlign.RIGHT)
            sheets += b.sheet(code, "Material takeoff", null)
            page++
        }
        return sheets
    }

    private fun wrap(text: String, maxChars: Int): List<String> {
        val rows = mutableListOf<String>()
        var line = ""
        text.split(Regex("\\s+")).forEach { word ->
            if (line.isNotEmpty() && line.length + 1 + word.length > maxChars) { rows += line; line = "" }
            if (word.length > maxChars) { if (line.isNotEmpty()) { rows += line; line = "" }; rows += word.chunked(maxChars) }
            else line = if (line.isBlank()) word else "$line $word"
        }
        if (line.isNotEmpty()) rows += line
        return rows
    }
}
