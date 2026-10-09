package nz.co.timbertakeoff.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import nz.co.timbertakeoff.core.MaterialCategory
import nz.co.timbertakeoff.core.MaterialConsolidator
import nz.co.timbertakeoff.core.MaterialLine
import java.util.Locale

internal fun number(value: Double, places: Int = 3): String {
    val text = String.format(Locale.ROOT, "%.${places}f", value)
    return if ('.' in text) text.trimEnd('0').trimEnd('.') else text
}

@Composable
internal fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    numeric: Boolean = false,
    supporting: String? = null,
    multiline: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
        supportingText = supporting?.let { { Text(it) } },
    )
}

@Composable
internal fun <T> Choice(label: String, value: T, options: List<T>, display: (T) -> String, onChange: (T) -> Unit) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
        OutlinedButton(onClick = {
            // A focusable popup must not restore an old text editor when it closes.
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            expanded = true
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(display(value), modifier = Modifier.weight(1f))
            Text("▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(display(option)) }, onClick = { expanded = false; onChange(option) })
            }
        }
    }
}

@Composable
internal fun Notice(text: String, error: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun EmptyState(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 22.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun NameDialog(title: String, label: String, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Field(label, name, { name = it }) },
        confirmButton = {
            val focusManager = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            Button(onClick = {
                focusManager.clearFocus(force = true)
                keyboard?.hide()
                onCreate(name)
                onDismiss()
            }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun OverallMaterials(lines: List<MaterialLine>, heading: String = "Overall materials") {
    Text(heading, style = MaterialTheme.typography.titleLarge)
    Text("Identical materials combined. Exact quantities; no purchasing waste allowance.", style = MaterialTheme.typography.bodySmall)
    val totals = remember(lines) { MaterialConsolidator.consolidate(lines) }
    if (totals.isEmpty()) {
        EmptyState("No material quantities yet", "Complete a valid deck task to calculate the takeoff.")
        return
    }
    totals.forEach { line ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${number(line.quantity)} ${line.key.unit}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(line.key.type, style = MaterialTheme.typography.titleMedium)
                Text(line.key.specification, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun MaterialBreakdown(lines: List<MaterialLine>) {
    if (lines.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(if (expanded) "Hide material breakdown" else "Show material breakdown")
    }
    if (expanded) Materials(lines)
}

@Composable
internal fun Materials(lines: List<MaterialLine>, consolidated: Boolean = false) {
    if (lines.isEmpty()) {
        EmptyState("No material quantities yet", "Complete a valid deck task to calculate the takeoff.")
        return
    }
    Text(if (consolidated) "Consolidated exact materials" else "Exact material takeoff", style = MaterialTheme.typography.titleLarge)
    Text("Calculated quantities only. No purchasing waste allowance.", style = MaterialTheme.typography.bodySmall)
    MaterialCategory.entries.forEach { category ->
        val categoryLines = lines.filter { it.category == category }
        if (categoryLines.isNotEmpty()) {
            var expanded by remember(category, consolidated) { mutableStateOf(consolidated) }
            Card(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(category.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(if (expanded) "−" else "+")
                }
                if (expanded) {
                    categoryLines.forEach { line ->
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(line.key.type, fontWeight = FontWeight.SemiBold)
                            Text(line.key.specification, style = MaterialTheme.typography.bodyMedium)
                            Text("${number(line.quantity)} ${line.key.unit}", style = MaterialTheme.typography.titleMedium)
                            if (line.cutLengthsMm.isNotEmpty()) {
                                Text("${line.pieceCount} ${if (category == MaterialCategory.DECKING) "boards" else "pieces"}", style = MaterialTheme.typography.bodyMedium)
                                val cuts = line.cutLengthsMm.groupBy { number(it, 2) }.entries
                                    .sortedBy { it.key.toDoubleOrNull() ?: 0.0 }
                                    .joinToString("  ·  ") { (length, pieces) -> "${pieces.size} × $length mm" }
                                Text("Cut lengths: $cuts", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
