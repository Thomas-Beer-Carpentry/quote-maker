package nz.co.timbertakeoff.ui

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nz.co.timbertakeoff.MainActivity
import nz.co.timbertakeoff.core.CalculationOutcome
import nz.co.timbertakeoff.core.DeckCalculator
import nz.co.timbertakeoff.core.DeckInput
import nz.co.timbertakeoff.core.drawing.DrawingGenerator
import nz.co.timbertakeoff.core.drawing.DrawingTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the same pending snapshot and result handler as the system document picker. */
@RunWith(AndroidJUnit4::class)
class PdfExportLifecycleTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun pendingPdfSnapshotSurvivesRotationBeforeDocumentResultArrives() {
        val result = (DeckCalculator.calculate(DeckInput()) as CalculationOutcome.Success).result
        val sheets = DrawingGenerator.generate(result, DrawingTitle(project = "Rotation export", task = "Deck"))
        lateinit var beforeRotation: EstimatorViewModel
        activity.scenario.onActivity {
            beforeRotation = ViewModelProvider(it)[EstimatorViewModel::class.java]
            beforeRotation.preparePdfExport(sheets)
        }
        activity.scenario.recreate()
        val output = File(checkNotNull(context.getExternalFilesDir(null)), "smoke-rotation.pdf")
        lateinit var afterRotation: EstimatorViewModel
        activity.scenario.onActivity {
            afterRotation = ViewModelProvider(it)[EstimatorViewModel::class.java]
            assertSame("Pending export must remain with the Activity ViewModel", beforeRotation, afterRotation)
            afterRotation.writePendingPdf(Uri.fromFile(output))
        }
        runBlocking {
            withTimeout(60_000) { afterRotation.pdfExportBusy.first { !it } }
        }
        assertTrue("The returned document URI receives the complete PDF", output.length() > 1000)
        assertTrue("Successful export remains visible after rotation", afterRotation.pdfExportMessage.value?.contains("vector PDF exported") == true)
        ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { pdf -> assertEquals(sheets.size, pdf.pageCount) }
        }
    }
}
