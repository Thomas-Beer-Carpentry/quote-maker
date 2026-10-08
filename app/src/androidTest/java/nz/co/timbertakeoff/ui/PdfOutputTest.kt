package nz.co.timbertakeoff.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import nz.co.timbertakeoff.core.CalculationOutcome
import nz.co.timbertakeoff.core.DeckCalculator
import nz.co.timbertakeoff.core.DeckInput
import nz.co.timbertakeoff.core.drawing.DrawingGenerator
import nz.co.timbertakeoff.core.drawing.DrawingElement
import nz.co.timbertakeoff.core.drawing.DrawingSheet
import nz.co.timbertakeoff.core.drawing.DrawingTitle
import nz.co.timbertakeoff.core.drawing.SheetSize
import nz.co.timbertakeoff.drawing.PdfExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Tests the real Android PDF canvas/exporter and reopens every page with Android's PDF renderer. */
@RunWith(AndroidJUnit4::class)
class PdfOutputTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun printedSmallTextKeepsCorrectGlyphAdvances() {
        // Small millimetre-sized Paint fonts previously produced overlapping PDF letters.
        // Compare actual PDF ink bounds with the font's vector outline at high resolution.
        val labels = listOf(
            DrawingElement.Text(10.0, 15.0, "illliiiilllliiii", sizeMm = 2.2),
            DrawingElement.Text(10.0, 35.0, "Joist spacing 424.091 mm", sizeMm = 2.5),
            DrawingElement.Text(10.0, 55.0, "B1 DOUBLE BEARER 190 x 45", sizeMm = 2.5, bold = true)
        )
        val sheet = DrawingSheet("TEST", "Printed text spacing", 100.0, 70.0, null, labels)
        val file = deviceArtifact(context, "text-spacing.pdf")
        file.outputStream().use { PdfExporter.write(it, listOf(sheet)) }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { pdf ->
                pdf.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width * 4, page.height * 4, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val scale = min(page.width / 100f, page.height / 70f) * 4f
                        val offsetY = (bitmap.height - 70f * scale) / 2f
                        labels.forEach { label ->
                            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
                                textSize = label.sizeMm.toFloat() * 100f
                                typeface = Typeface.create("sans-serif", if (label.bold) Typeface.BOLD else Typeface.NORMAL)
                            }
                            val path = Path()
                            paint.getTextPath(label.text, 0, label.text.length, 0f, 0f, path)
                            val bounds = RectF()
                            path.computeBounds(bounds, true)
                            val expectedWidth = bounds.width() / 100f * scale
                            val baseline = offsetY + label.y.toFloat() * scale
                            val top = (baseline - 5f * scale).toInt().coerceAtLeast(0)
                            val bottom = (baseline + 2f * scale).toInt().coerceAtMost(bitmap.height - 1)
                            var left = bitmap.width
                            var right = -1
                            for (y in top..bottom) for (x in 0 until bitmap.width) {
                                if (Color.red(bitmap.getPixel(x, y)) < 128) {
                                    left = min(left, x)
                                    right = max(right, x)
                                }
                            }
                            assertTrue("Printed text must be visible: ${label.text}", right >= left)
                            val actualWidth = (right - left + 1).toFloat()
                            assertTrue("PDF glyph spacing for '${label.text}': expected $expectedWidth px, got $actualWidth px",
                                abs(actualWidth - expectedWidth) <= max(3f, expectedWidth * 0.02f))
                        }
                    } finally { bitmap.recycle() }
                }
            }
        }
    }

    @Test
    fun combinedA3AndA4PdfHasCorrectPaperDimensionsAndVisibleEverySheet() {
        val outcome = DeckCalculator.calculate(DeckInput())
        assertTrue("Default deck must have a usable calculation", outcome is CalculationOutcome.Success)
        val result = (outcome as CalculationOutcome.Success).result
        val title = DrawingTitle(project = "PDF smoke test", client = "Test client", task = "Freestanding deck", preparedDate = "2026-10-08")
        for (size in SheetSize.entries) {
            val sheets = DrawingGenerator.generate(result, title, size)
            assertTrue("Combined drawing set includes plans, section and material schedule", sheets.map { it.code }.containsAll(listOf("D01", "D02", "D03", "M01")))
            val file = deviceArtifact(context, "smoke-${size.name}.pdf")
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
