package nz.co.timbertakeoff.drawing

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.pdf.PrintedPdfDocument
import nz.co.timbertakeoff.core.drawing.DrawingElement
import nz.co.timbertakeoff.core.drawing.DrawingSheet
import nz.co.timbertakeoff.core.drawing.SheetSize
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** Prints the same combined D01/D02/D03/M01 vector sheet set used for file export. */
class DrawingPrintAdapter(
    private val context: Context,
    private val sheets: List<DrawingSheet>,
    private val sheetProvider: ((SheetSize) -> List<DrawingSheet>)? = null
) : PrintDocumentAdapter() {
    private val executor = Executors.newSingleThreadExecutor()
    private var selectedAttributes = attributes(sheets)
    private var selectedSheets = sheets
    override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?) {
        if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
        if (sheets.isEmpty()) { callback.onLayoutFailed("No calculated drawings to print."); return }
        selectedAttributes = newAttributes ?: attributes(sheets)
        val media = selectedAttributes.mediaSize
        val requestedSize = if (max(media?.widthMils ?: 16535, media?.heightMils ?: 11692) > 14000) SheetSize.A3 else SheetSize.A4
        selectedSheets = sheetProvider?.invoke(requestedSize) ?: sheets
        callback.onLayoutFinished(PrintDocumentInfo.Builder("Timber takeoff.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(selectedSheets.size).build(), oldAttributes != newAttributes)
    }

    override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal?, callback: WriteResultCallback) {
        val snapshot = selectedSheets
        val printAttributes = selectedAttributes
        executor.execute {
            val document = PrintedPdfDocument(context, printAttributes)
            try {
                val selected = snapshot.indices.filter { index -> pages.any { index in it.start..it.end } }
                if (selected.isEmpty()) { callback.onWriteFinished(emptyArray()); return@execute }
                selected.forEach { index ->
                    if (cancellationSignal?.isCanceled == true) throw PdfExportCancelled()
                    val page = document.startPage(index + 1)
                    val content = page.info.contentRect
                    val original = snapshot[index]
                    val physicalFit = min(content.width() / (original.widthMm * 72.0 / 25.4), content.height() / (original.heightMm * 72.0 / 25.4))
                    val sheet = atPrintedScale(original, physicalFit)
                    page.canvas.save()
                    page.canvas.translate(content.left.toFloat(), content.top.toFloat())
                    DrawingRenderer.draw(page.canvas, sheet, content.width().toFloat(), content.height().toFloat())
                    page.canvas.restore()
                    document.finishPage(page)
                }
                if (cancellationSignal?.isCanceled == true) throw PdfExportCancelled()
                FileOutputStream(destination.fileDescriptor).use { stream ->
                    document.writeTo(stream)
                }
                callback.onWriteFinished(selected.map { PageRange(it, it) }.toTypedArray())
            } catch (_: PdfExportCancelled) {
                callback.onWriteCancelled()
            } catch (error: Exception) {
                callback.onWriteFailed(error.message ?: "Unable to write drawing PDF.")
            } finally { document.close() }
        }
    }

    override fun onFinish() { executor.shutdown(); super.onFinish() }

    companion object {
        /** Printer margins can reduce the drawing further; reflect that scale and retain 2.2 mm printed text. */
        private fun atPrintedScale(sheet: DrawingSheet, fit: Double): DrawingSheet {
            if (fit <= 0.0) return sheet
            val printedScale = sheet.scaleDenominator?.div(fit)
            val elements = sheet.elements.map { element ->
                if (element !is DrawingElement.Text) element else element.copy(
                    text = when {
                        element.text.startsWith("1:") && printedScale != null -> "1:${String.format(Locale.ROOT, "%.2f", printedScale).trimEnd('0').trimEnd('.')}"
                        element.text == "Print at 100%" && kotlin.math.abs(fit - 1.0) > 0.005 -> "Fit to printable area"
                        else -> element.text
                    },
                    sizeMm = max(element.sizeMm, 2.2 / fit)
                )
            }
            return sheet.copy(scaleDenominator = printedScale, elements = elements)
        }
        fun attributes(sheets: List<DrawingSheet>): PrintAttributes {
            val media = if ((sheets.firstOrNull()?.widthMm ?: 420.0) > 350.0) PrintAttributes.MediaSize.ISO_A3 else PrintAttributes.MediaSize.ISO_A4
            return PrintAttributes.Builder().setMediaSize(media.asLandscape()).setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS).build()
        }
    }
}
