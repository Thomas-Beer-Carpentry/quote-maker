package nz.co.timbertakeoff

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import nz.co.timbertakeoff.ui.EstimatorApp
import nz.co.timbertakeoff.ui.EstimatorViewModel

class MainActivity : ComponentActivity() {
    private val model: EstimatorViewModel by viewModels()
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        model.writePendingPdf(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EstimatorApp(model) { sheets, fileName ->
                model.preparePdfExport(sheets)
                exportDocument.launch(fileName)
            }
        }
    }
}
