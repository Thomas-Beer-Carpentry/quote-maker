package nz.co.timbertakeoff.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.co.timbertakeoff.core.CalculationOutcome
import nz.co.timbertakeoff.core.MaterialConsolidator
import nz.co.timbertakeoff.core.MaterialLine
import nz.co.timbertakeoff.core.TaskLibrary
import nz.co.timbertakeoff.data.ClientEntity
import nz.co.timbertakeoff.data.DeckDraft
import nz.co.timbertakeoff.data.JobEntity
import nz.co.timbertakeoff.data.TaskEntity

private data class JobTakeoff(val lines: List<MaterialLine>, val errors: Map<Long, List<String>>, val taskLines: Map<Long, List<MaterialLine>>)

@Composable
internal fun JobScreen(job: JobEntity, client: ClientEntity?, tasks: List<TaskEntity>, model: EstimatorViewModel, openTask: (Long) -> Unit) {
    var newTask by remember { mutableStateOf(false) }
    val computationKey = tasks.map { Triple(it.id, it.typeId, it.inputJson) }
    val takeoff = remember(computationKey) { mutableStateOf<JobTakeoff?>(null) }
    LaunchedEffect(computationKey) {
        takeoff.value = withContext(Dispatchers.Default) {
            val errors = mutableMapOf<Long, List<String>>()
            val taskMaterials = mutableMapOf<Long, List<MaterialLine>>()
            tasks.forEach { task ->
                val outcome = runCatching { DeckDraft.fromJson(task.inputJson).calculate(task.typeId) }.getOrElse {
                    CalculationOutcome.Invalid(listOf("Saved inputs could not be read: ${it.message}."))
                }
                when (outcome) {
                    is CalculationOutcome.Success -> taskMaterials[task.id] = outcome.result.materials
                    is CalculationOutcome.Invalid -> errors[task.id] = outcome.errors
                }
            }
            JobTakeoff(MaterialConsolidator.consolidate(taskMaterials.values.flatten()), errors, taskMaterials)
        }
    }
    ScreenColumn {
        Text(client?.name?.ifBlank { "Unnamed client" } ?: "Client", style = MaterialTheme.typography.labelLarge)
        Field("Job name", job.name, { value -> model.editJob(job) { it.copy(name = value) } })
        Field("Job notes", job.notes, { value -> model.editJob(job) { it.copy(notes = value) } }, multiline = true)
        Text("Tasks", style = MaterialTheme.typography.titleLarge)
        Button(onClick = { newTask = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("+ Add deck task") }
        if (tasks.isEmpty()) EmptyState("This job has no tasks", "Add a freestanding timber deck. You can add several independent decks to the same job.")
        tasks.forEach { task ->
            Card(modifier = Modifier.fillMaxWidth().clickable { openTask(task.id) }) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        Text(task.name.ifBlank { "Unnamed task" }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        Text("›", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(TaskLibrary.definitions.firstOrNull { it.id == task.typeId }?.title ?: "Unsupported task type", style = MaterialTheme.typography.bodySmall)
                    val result = takeoff.value
                    when {
                        result == null -> Text("Calculating…", style = MaterialTheme.typography.bodySmall)
                        task.id in result.errors -> Text(result.errors.getValue(task.id).firstOrNull() ?: "Inputs need attention", color = MaterialTheme.colorScheme.error)
                        else -> Text("Plans and exact materials available", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        val result = takeoff.value
        if (result == null) CircularProgressIndicator()
        else {
            if (result.errors.isNotEmpty()) {
                Notice(
                    "Incomplete job takeoff: ${result.errors.size} of ${tasks.size} tasks could not be calculated and are excluded.\n" +
                        tasks.filter { it.id in result.errors }.joinToString("\n") { "${it.name.ifBlank { "Unnamed task" }}: ${result.errors.getValue(it.id).firstOrNull()}" },
                    error = true,
                )
            }
            if (tasks.isNotEmpty()) {
                OverallMaterials(result.lines, heading = "Overall job materials")
                MaterialBreakdown(result.taskLines.values.flatten())
            }
        }
        Notice("Preliminary estimating / set-out only. Exact quantities do not include waste allowances or structural verification.")
    }
    if (newTask) NameDialog("Add freestanding deck", "Task name", { newTask = false }) { name -> model.createDeckTask(job.id, name, openTask) }
}
