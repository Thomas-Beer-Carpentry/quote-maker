package nz.co.timbertakeoff.ui

import android.content.Context
import android.print.PrintManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.co.timbertakeoff.core.CalculationOutcome
import nz.co.timbertakeoff.core.DeckResult
import nz.co.timbertakeoff.core.FramingOrientation
import nz.co.timbertakeoff.core.OrientationAlternative
import nz.co.timbertakeoff.core.Profiles
import nz.co.timbertakeoff.core.drawing.DrawingGenerator
import nz.co.timbertakeoff.core.drawing.DrawingSheet
import nz.co.timbertakeoff.core.drawing.DrawingTitle
import nz.co.timbertakeoff.core.drawing.SheetSize
import nz.co.timbertakeoff.data.ClientEntity
import nz.co.timbertakeoff.data.DeckDraft
import nz.co.timbertakeoff.data.JobEntity
import nz.co.timbertakeoff.data.TaskEntity
import nz.co.timbertakeoff.drawing.DrawingPrintAdapter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate

internal fun orientationLabel(orientation: FramingOrientation): String = when (orientation) {
    FramingOrientation.AUTOMATIC -> "Automatic"
    FramingOrientation.LENGTHWAYS -> "Lengthways · bearers parallel to deck length"
    FramingOrientation.WIDTHWAYS -> "Widthways · bearers parallel to deck width"
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun DeckTaskScreen(task: TaskEntity, job: JobEntity?, client: ClientEntity?, model: EstimatorViewModel, onExportPdf: (List<DrawingSheet>, String) -> Unit) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var tab by rememberSaveable(task.id) { mutableStateOf(0) }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(tab, imeVisible) {
        if (tab != 0) {
            // Popup dismissal can complete a pending IME show after the tab click's hide.
            // React to actual insets after the parameter editors have left composition.
            withFrameNanos { }
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    val parsed = remember(task.id, task.inputJson) { runCatching { DeckDraft.fromJson(task.inputJson) } }
    val draft = parsed.getOrNull()
    var requestedInputJson by rememberSaveable(task.id) { mutableStateOf<String?>(null) }
    var requestedTypeId by rememberSaveable(task.id) { mutableStateOf<String?>(null) }
    var calculationRequest by rememberSaveable(task.id) { mutableStateOf(0) }
    var showMaterialsAfterCalculation by rememberSaveable(task.id) { mutableStateOf(false) }
    // Editing a draft invalidates its result immediately, while autosave remains independent.
    val outcome = remember(task.typeId, task.inputJson) { mutableStateOf<CalculationOutcome?>(null) }
    var calculating by remember(task.typeId, task.inputJson) { mutableStateOf(false) }
    LaunchedEffect(task.typeId, task.inputJson, requestedInputJson, requestedTypeId, calculationRequest) {
        if (requestedInputJson != task.inputJson || requestedTypeId != task.typeId) {
            requestedInputJson = null
            requestedTypeId = null
            showMaterialsAfterCalculation = false
            return@LaunchedEffect
        }
        calculating = true
        outcome.value = if (draft == null) CalculationOutcome.Invalid(listOf("Saved inputs could not be read: ${parsed.exceptionOrNull()?.message}."))
        else withContext(Dispatchers.Default) {
            try {
                draft.calculate(task.typeId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CalculationOutcome.Invalid(listOf("Calculation could not complete: ${error.message}."))
            }
        }
        calculating = false
        if (outcome.value is CalculationOutcome.Success && showMaterialsAfterCalculation) tab = 1
        showMaterialsAfterCalculation = false
    }
    val result = (outcome.value as? CalculationOutcome.Success)?.result
    val exporting by model.pdfExportBusy.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "PRELIMINARY ESTIMATING / SET-OUT",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
            listOf("Inputs", "Materials", "Drawings").forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = {
                    focusManager.clearFocus(force = true)
                    keyboard?.hide()
                    showMaterialsAfterCalculation = false
                    tab = index
                }, text = { Text(title) })
            }
        }
        when (tab) {
            0 -> Column(modifier = Modifier.weight(1f)) {
                Box(modifier = Modifier.weight(1f)) {
                    ScreenColumn {
                        if (draft == null) Notice("The saved task inputs are unreadable: ${parsed.exceptionOrNull()?.message}. They have been preserved on this device.", error = true)
                        else DeckParameters(task, draft, model)
                        if (calculating || outcome.value is CalculationOutcome.Invalid) OutcomeNotice(outcome.value, calculating)
                        if (result != null) OrientationComparison(result)
                    }
                }
                (outcome.value as? CalculationOutcome.Invalid)?.errors?.firstOrNull()?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                }
                Button(onClick = {
                    focusManager.clearFocus(force = true)
                    keyboard?.hide()
                    requestedInputJson = task.inputJson
                    requestedTypeId = task.typeId
                    showMaterialsAfterCalculation = true
                    calculationRequest += 1
                }, enabled = draft != null && !calculating,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 56.dp)) {
                    Text(if (calculating) "Calculating…" else "Calculate")
                }
            }
            1 -> ScreenColumn {
                if (result == null) {
                    OutcomeNotice(outcome.value, calculating)
                    Button(onClick = { tab = 0 }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Review inputs") }
                }
                if (result != null) {
                    OverallMaterials(result.materials)
                    MaterialBreakdown(result.materials)
                    var showAssumptions by remember(task.id) { mutableStateOf(false) }
                    TextButton(onClick = { showAssumptions = !showAssumptions }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(if (showAssumptions) "Hide calculation assumptions" else "Show calculation assumptions")
                    }
                    if (showAssumptions) result.notes.forEach { Notice(it) }
                }
            }
            else -> {
                if (result != null) DrawingWorkspace(result, job, client, task, exporting, onExportPdf)
                else ScreenColumn {
                    OutcomeNotice(outcome.value, calculating)
                    Button(onClick = { tab = 0 }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Review inputs") }
                }
            }
        }
    }
}

@Composable
private fun OutcomeNotice(outcome: CalculationOutcome?, calculating: Boolean = false) {
    when (outcome) {
        null -> if (calculating) { CircularProgressIndicator(); Text("Calculating the current layout…") }
            else Notice("Enter or review the inputs, then tap Calculate to update materials and drawings.")
        is CalculationOutcome.Invalid -> {
            Notice("Layout cannot be calculated\n" + outcome.errors.joinToString("\n") { "• $it" }, error = true)
            if (outcome.alternatives.isNotEmpty()) Alternatives(outcome.alternatives, null)
        }
        is CalculationOutcome.Success -> {
            outcome.result.notes.forEach { Notice(it) }
        }
    }
}

@Composable
private fun DeckParameters(task: TaskEntity, draft: DeckDraft, model: EstimatorViewModel) {
    fun edit(change: (DeckDraft) -> DeckDraft) = model.editTask(task, change = change)
    Text("Deck specifications", style = MaterialTheme.typography.titleLarge)
    Text("All dimensions are millimetres. Width and length are outside framing dimensions; height is ground to the finished decking surface.", style = MaterialTheme.typography.bodySmall)
    Field("1. Deck width (mm)", draft.widthMm, { value -> edit { it.copy(widthMm = value) } }, numeric = true)
    Field("2. Deck length (mm)", draft.lengthMm, { value -> edit { it.copy(lengthMm = value) } }, numeric = true)
    Field("3. Deck height (mm)", draft.heightMm, { value -> edit { it.copy(heightMm = value) } }, numeric = true)
    Choice("4. Bearer profile", draft.bearerProfileId, Profiles.framing.map { it.name }, { it }, { value -> edit { it.copy(bearerProfileId = value) } })
    Choice("5. Joist profile", draft.joistProfileId, Profiles.framing.map { it.name }, { it }, { value -> edit { it.copy(joistProfileId = value) } })
    Field("6. Maximum joist spacing (mm)", draft.maxJoistSpacingMm, { value -> edit { it.copy(maxJoistSpacingMm = value) } }, numeric = true)
    Choice("7. Decking profile", draft.deckingProfileId, Profiles.decking.map { it.nominal }, { it }, { value ->
        edit { it.withDeckingProfile(Profiles.decking.first { profile -> profile.nominal == value }) }
    })
    Field("8. Actual finished decking width (mm)", draft.actualDeckingWidthMm, { value -> edit { it.copy(actualDeckingWidthMm = value) } }, numeric = true)
    Field("9. Decking species", draft.deckingSpecies, { value -> edit { it.copy(deckingSpecies = value) } })
    Text("Layout and fixing options", style = MaterialTheme.typography.titleLarge)
    Choice("Framing orientation", draft.orientation, FramingOrientation.entries, ::orientationLabel, { value -> edit { it.copy(orientation = value) } })
    Field("Decking overhang on all four sides (mm)", draft.overhangMm, { value -> edit { it.copy(overhangMm = value) } }, numeric = true)
    Field("Decking screw specification", draft.screwSpecification, { value -> edit { it.copy(screwSpecification = value) } })
    Field("Concrete yield per 20 kg bag (m³)", draft.concreteYieldM3PerBag, { value -> edit { it.copy(concreteYieldM3PerBag = value) } }, numeric = true, supporting = "Use the concrete supplier's stated yield. Default 0.01 m³ / bag.")
    Text("Task details", style = MaterialTheme.typography.titleLarge)
    Field("Task name", task.name, { value -> model.editTask(task, name = value) })
    Notice("Flat, level ground assumed. Doubled bearers and boundaries, 125 × 125 mm piles, 500 mm embedment and 400 × 400 × 600 mm holes. These are provisional estimating assumptions.")
}

@Composable
private fun OrientationComparison(result: DeckResult) {
    Text("Framing orientation", style = MaterialTheme.typography.titleLarge)
    Text("Recommended: ${orientationLabel(result.recommended)}", style = MaterialTheme.typography.titleMedium)
    Text("Current layout: ${orientationLabel(result.geometry.orientation)}", style = MaterialTheme.typography.bodyMedium)
    Text("Automatic compares exact framing timber length first, then pile count; lengthways wins an exact tie. Decking is perpendicular to joists.", style = MaterialTheme.typography.bodySmall)
    Alternatives(result.alternatives, result.recommended)
}

@Composable
private fun Alternatives(alternatives: List<OrientationAlternative>, recommended: FramingOrientation?) {
    alternatives.forEach { alternative ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(orientationLabel(alternative.orientation), style = MaterialTheme.typography.titleSmall)
                if (alternative.errors.isNotEmpty()) Text(alternative.errors.joinToString("\n"), color = MaterialTheme.colorScheme.error)
                else Text("${alternative.timberLengthMm?.let { number(it / 1000.0) } ?: "—"} m framing timber · ${alternative.pileCount ?: "—"} piles${if (alternative.orientation == recommended) " · Recommended" else ""}")
            }
        }
    }
}

@Composable
private fun DrawingWorkspace(result: DeckResult, job: JobEntity?, client: ClientEntity?, task: TaskEntity, exporting: Boolean, onExportPdf: (List<DrawingSheet>, String) -> Unit) {
    val context = LocalContext.current
    var sheetSizeName by rememberSaveable(task.id) { mutableStateOf(SheetSize.A3.name) }
    val sheetSize = SheetSize.valueOf(sheetSizeName)
    val preparedDate = remember { LocalDate.now().toString() }
    val title = DrawingTitle(project = job?.name.orEmpty(), client = client?.name.orEmpty(), task = task.name, preparedDate = preparedDate)
    val sheetsState = remember(result, title, sheetSize) { mutableStateOf<List<DrawingSheet>?>(null) }
    val drawingError = remember(result, title, sheetSize) { mutableStateOf<String?>(null) }
    LaunchedEffect(result, title, sheetSize) {
        try {
            sheetsState.value = withContext(Dispatchers.Default) { DrawingGenerator.generate(result, title, sheetSize) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            drawingError.value = "Drawings could not be generated: ${error.message}."
        }
    }
    var selectedCode by rememberSaveable(task.id) { mutableStateOf("D01") }
    var viewport by remember { mutableStateOf<PlanPreviewView?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    ScreenColumn {
        val sheets = sheetsState.value
        Text("Construction drawing workspace", style = MaterialTheme.typography.titleLarge)
        if (drawingError.value != null) Notice(drawingError.value!!, error = true)
        else if (sheets == null) { CircularProgressIndicator(); Text("Generating dimensioned sheets…") }
        else {
            val selectedSheet = sheets.firstOrNull { it.code == selectedCode } ?: sheets.first()
            Choice("Drawing sheet", selectedSheet.code, sheets.map { it.code }, { code -> sheets.first { it.code == code }.let { "${it.code} — ${it.title}" } }, { selectedCode = it })
            Card(modifier = Modifier.fillMaxWidth()) {
                AndroidView(
                    factory = { PlanPreviewView(it).also { view -> viewport = view } },
                    update = { it.sheet = selectedSheet },
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Pinch to zoom · drag to pan", modifier = Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { viewport?.resetViewport() }) { Text("Fit sheet") }
            }
            Text("${number(result.input.widthMm, 1)} × ${number(result.input.lengthMm, 1)} mm framing · ${number(result.input.heightMm, 1)} mm finished height", style = MaterialTheme.typography.bodyMedium)
            Text("Joists ${number(result.geometry.actualJoistSpacingMm, 2)} mm centres · board gap ${number(result.geometry.deckingGapMm, 2)} mm", style = MaterialTheme.typography.bodySmall)
            if (result.geometry.startingBoardWidthMm < result.input.actualDeckingWidthMm - 0.001) {
                Text("Ripped starting board: ${number(result.geometry.startingBoardWidthMm, 2)} mm", style = MaterialTheme.typography.bodySmall)
            }
            Choice("Print / PDF sheet size", sheetSize, SheetSize.entries, { if (it == SheetSize.A3) "A3 landscape · preferred" else "A4 landscape" }, { sheetSizeName = it.name })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val fileName = "${job?.name ?: "Job"}_${task.name}_${sheetSize.name}".replace(Regex("[^A-Za-z0-9._-]+"), "_")
                        onExportPdf(sheets, "$fileName.pdf")
                    },
                    enabled = !exporting,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) { Text(if (exporting) "Exporting…" else "Export all PDF") }
                OutlinedButton(onClick = {
                    try {
                        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                        manager.print("${job?.name ?: "Job"} — ${task.name}", DrawingPrintAdapter(context, sheets) { size ->
                            DrawingGenerator.generate(result, title, size)
                        }, DrawingPrintAdapter.attributes(sheets))
                    } catch (error: Exception) {
                        exportMessage = "Printing could not start: ${error.message}."
                    }
                }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Print all") }
            }
            exportMessage?.let { Notice(it, error = it.contains("failed") || it.contains("could not")) }
        }
        OrientationComparison(result)
        result.notes.forEach { Notice(it) }
        Notice("Drawings are preliminary estimating / set-out information, not verified construction documentation. No structural compliance has been checked.")
    }
}
