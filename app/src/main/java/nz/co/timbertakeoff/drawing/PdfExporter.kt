package nz.co.timbertakeoff.drawing

import android.graphics.pdf.PdfDocument
import nz.co.timbertakeoff.core.drawing.DrawingSheet
import java.io.OutputStream
import kotlin.math.roundToInt

/** Android's PDF canvas records native paths and text; plans remain vector graphics at print scale. */
object PdfExporter {
    fun write(output: OutputStream, sheets: List<DrawingSheet>) = writeSelected(output, sheets, sheets.indices.toList())

    internal fun writeSelected(output: OutputStream, sheets: List<DrawingSheet>, indices: List<Int>, cancelled: () -> Boolean = { false }) {
        require(sheets.isNotEmpty()) { "At least one drawing sheet is required." }
        val document = PdfDocument()
        try {
            indices.forEachIndexed { pageIndex, sheetIndex ->
                if (cancelled()) throw PdfExportCancelled()
                val sheet = sheets[sheetIndex]
                val width = (sheet.widthMm * 72.0 / 25.4).roundToInt()
                val height = (sheet.heightMm * 72.0 / 25.4).roundToInt()
                val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, pageIndex + 1).create())
                DrawingRenderer.draw(page.canvas, sheet, width.toFloat(), height.toFloat())
                document.finishPage(page)
            }
            if (cancelled()) throw PdfExportCancelled()
            document.writeTo(output)
        } finally { document.close() }
    }
}

internal class PdfExportCancelled : RuntimeException()
