package nz.co.timbertakeoff.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import nz.co.timbertakeoff.core.CalculationOutcome
import nz.co.timbertakeoff.core.DeckCalculator
import nz.co.timbertakeoff.core.DeckInput
import nz.co.timbertakeoff.core.drawing.DrawingGenerator
import nz.co.timbertakeoff.core.drawing.DrawingTitle
import nz.co.timbertakeoff.core.drawing.SheetSize
import nz.co.timbertakeoff.drawing.PdfExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Tests the real Android PDF canvas/exporter and reopens every page with Android's PDF renderer. */
@RunWith(AndroidJUnit4::class)
class PdfOutputTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun combinedA3AndA4PdfHasCorrectPaperDimensionsAndVisibleEverySheet() {
        val outcome = DeckCalculator.calculate(DeckInput())
        assertTrue("Default deck must have a usable calculation", outcome is CalculationOutcome.Success)
        val result = (outcome as CalculationOutcome.Success).result
        val title = DrawingTitle(project = "PDF smoke test", client = "Test client", task = "Freestanding deck", preparedDate = "2026-10-08")
        for (size in SheetSize.entries) {
            val sheets = DrawingGenerator.generate(result, title, size)
            assertTrue("Combined drawing set includes plans, section and material schedule", sheets.map { it.code }.containsAll(listOf("D01", "D02", "D03", "M01")))
            val file = File(checkNotNull(context.getExternalFilesDir(null)), "smoke-${size.name}.pdf")
            file.outputStream().use { PdfExporter.write(it, sheets) }
            assertTrue("Export creates a nonempty PDF", file.length() > 1000)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { pdf ->
                    assertEquals("Every generated sheet appears in the combined PDF", sheets.size, pdf.pageCount)
                    // ISO landscape paper dimensions in whole PDF points (1/72 inch).
                    val expectedWidth = if (size == SheetSize.A3) 1191 else 842
                    val expectedHeight = if (size == SheetSize.A3) 842 else 595
                    for (index in 0 until pdf.pageCount) {
                        pdf.openPage(index).use { page ->
                            assertEquals("${size.name} page width", expectedWidth, page.width)
                            assertEquals("${size.name} page height", expectedHeight, page.height)
                            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val pixels = IntArray(bitmap.width * bitmap.height)
                                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                val darkPixels = pixels.count { Color.red(it) < 64 && Color.green(it) < 64 && Color.blue(it) < 64 }
                                assertTrue("${size.name} ${sheets[index].code} must render readable linework/text rather than a blank page", darkPixels > 100)
                            } finally { bitmap.recycle() }
                        }
                    }
                }
            }
        }
    }
}
