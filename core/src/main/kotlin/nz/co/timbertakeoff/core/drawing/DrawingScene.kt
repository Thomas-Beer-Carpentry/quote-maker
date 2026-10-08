package nz.co.timbertakeoff.core.drawing

/** Vector primitives expressed in physical sheet millimetres, including text and line widths. */
enum class SheetSize(val widthMm: Double, val heightMm: Double) { A3(420.0, 297.0), A4(297.0, 210.0) }
enum class TextAlign { LEFT, CENTER, RIGHT }
sealed class DrawingElement {
    data class Line(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val weightMm: Double = 0.18, val dashed: Boolean = false) : DrawingElement()
    data class Rect(val x: Double, val y: Double, val width: Double, val height: Double, val weightMm: Double = 0.18, val fill: Boolean = false, val dashed: Boolean = false) : DrawingElement()
    data class Circle(val x: Double, val y: Double, val radiusMm: Double, val weightMm: Double = 0.18, val fill: Boolean = false) : DrawingElement()
    data class Text(val x: Double, val y: Double, val text: String, val sizeMm: Double = 2.5, val align: TextAlign = TextAlign.LEFT, val rotationDegrees: Double = 0.0, val bold: Boolean = false) : DrawingElement()
}
data class DrawingTitle(
    val project: String = "Untitled job",
    val client: String = "",
    val task: String = "Deck",
    val address: String = "",
    val preparedDate: String = ""
)
data class DrawingSheet(
    val code: String,
    val title: String,
    val widthMm: Double,
    val heightMm: Double,
    val scaleDenominator: Double?,
    val elements: List<DrawingElement>
)

internal class SheetBuilder(val size: SheetSize) {
    val elements = mutableListOf<DrawingElement>()
    fun line(x1: Double, y1: Double, x2: Double, y2: Double, weight: Double = 0.18, dashed: Boolean = false) {
        elements += DrawingElement.Line(x1, y1, x2, y2, weight, dashed)
    }
    fun rect(x: Double, y: Double, width: Double, height: Double, weight: Double = 0.18, fill: Boolean = false, dashed: Boolean = false) {
        elements += DrawingElement.Rect(x, y, width, height, weight, fill, dashed)
    }
    fun text(x: Double, y: Double, value: String, size: Double = 2.5, align: TextAlign = TextAlign.LEFT, rotation: Double = 0.0, bold: Boolean = false) {
        elements += DrawingElement.Text(x, y, value, size.coerceAtLeast(2.2), align, rotation, bold)
    }
    fun circle(x: Double, y: Double, radius: Double, weight: Double = 0.18, fill: Boolean = false) {
        elements += DrawingElement.Circle(x, y, radius, weight, fill)
    }
    /** Draw a wrapped note with a conservative width estimate for ordinary technical text. */
    fun note(x: Double, y: Double, width: Double, value: String, size: Double = 2.5, bold: Boolean = false): Double {
        val actualSize = if (this.size == SheetSize.A4 && size < 3.0) 2.2 else size
        val maxChars = (width / (actualSize * 0.56)).toInt().coerceAtLeast(8)
        val rows = mutableListOf<String>()
        var current = ""
        value.split(Regex("\\s+")).forEach { word ->
            if (current.isNotEmpty() && current.length + word.length + 1 > maxChars) { rows += current; current = "" }
            if (word.length > maxChars) {
                if (current.isNotEmpty()) { rows += current; current = "" }
                word.chunked(maxChars).forEach { rows += it }
            } else current = if (current.isEmpty()) word else "$current $word"
        }
        if (current.isNotEmpty()) rows += current
        rows.forEachIndexed { i, row -> text(x, y + i * actualSize * 1.45, row, actualSize, bold = bold) }
        return y + rows.size * actualSize * 1.45
    }
    fun sheet(code: String, title: String, scale: Double?) = DrawingSheet(code, title, size.widthMm, size.heightMm, scale, elements.toList())
}
