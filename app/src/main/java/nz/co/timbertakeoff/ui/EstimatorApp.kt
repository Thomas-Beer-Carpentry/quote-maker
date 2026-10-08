package nz.co.timbertakeoff.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.co.timbertakeoff.core.TaskLibrary
import nz.co.timbertakeoff.core.drawing.DrawingSheet
import nz.co.timbertakeoff.data.ClientEntity
import nz.co.timbertakeoff.data.JobEntity
import nz.co.timbertakeoff.data.TaskEntity

private val EstimatorColours = lightColorScheme(
    primary = Color(0xFF24483B),
    secondary = Color(0xFF596746),
    background = Color(0xFFF6F7F2),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8EDE4),
    onSurface = Color(0xFF1D2520),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EstimatorApp(model: EstimatorViewModel, onExportPdf: (List<DrawingSheet>, String) -> Unit) {
    val clients by model.clients.collectAsStateWithLifecycle()
    val jobs by model.jobs.collectAsStateWithLifecycle()
    val tasks by model.tasks.collectAsStateWithLifecycle()
    val pending by model.pendingSaves.collectAsStateWithLifecycle()
    val saveError by model.saveError.collectAsStateWithLifecycle()
    val pdfMessage by model.pdfExportMessage.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("home") }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(page) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    val pageId = page.substringAfter(':', "").toLongOrNull()
    val client = clients.firstOrNull { it.id == pageId }
    val job = jobs.firstOrNull { it.id == pageId }
    val task = tasks.firstOrNull { it.id == pageId }
    val title = when (page.substringBefore(':')) {
        "clients" -> "Clients"
        "jobs" -> "Jobs"
        "library" -> "Task Library"
        "client" -> client?.name?.ifBlank { "Client" } ?: "Client"
        "job" -> job?.name?.ifBlank { "Job" } ?: "Job"
        "task" -> task?.name?.ifBlank { "Deck" } ?: "Deck"
        else -> "Timber Takeoff"
    }
    fun back() {
        page = when (page.substringBefore(':')) {
            "client" -> "clients"
            "job" -> "jobs"
            "task" -> task?.let { "job:${it.jobId}" } ?: "jobs"
            else -> "home"
        }
    }
    BackHandler(enabled = page != "home") { back() }
    MaterialTheme(colorScheme = EstimatorColours) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, maxLines = 1)
                            Text(
                                if (saveError != null) "Local save needs attention" else if (pending > 0) "Saving locally…" else "Saved on this device · Offline",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    navigationIcon = { if (page != "home") TextButton(onClick = { back() }) { Text("‹ Back") } },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (saveError != null) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Notice(saveError!!, error = true)
                        TextButton(onClick = model::retrySaves) { Text("Retry saving edits") }
                    }
                }
                if (pdfMessage != null) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Notice(pdfMessage!!, error = pdfMessage!!.contains("failed") || pdfMessage!!.contains("interrupted"))
                        TextButton(onClick = model::dismissPdfMessage) { Text("Dismiss") }
                    }
                }
                when (page.substringBefore(':')) {
                    "clients" -> ClientList(clients, { page = "client:$it" }, model)
                    "jobs" -> JobsList(jobs, clients, { page = "job:$it" }, { page = "client:$it" }, model)
                    "library" -> LibraryScreen()
                    "client" -> if (client != null) ClientScreen(client, jobs.filter { it.clientId == client.id }, model, { page = "job:$it" }) else LoadingScreen()
                    "job" -> if (job != null) JobScreen(job, clients.firstOrNull { it.id == job.clientId }, tasks.filter { it.jobId == job.id }, model, { page = "task:$it" }) else LoadingScreen()
                    "task" -> if (task != null) {
                        val parentJob = jobs.firstOrNull { it.id == task.jobId }
                        DeckTaskScreen(task, parentJob, clients.firstOrNull { it.id == parentJob?.clientId }, model, onExportPdf)
                    } else LoadingScreen()
                    else -> HomeScreen(clients.size, jobs.size) { page = it }
                }
            }
        }
    }
}

@Composable
internal fun ScreenColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

@Composable
private fun LoadingScreen() = ScreenColumn { Text("Opening saved record…") }

@Composable
private fun HomeScreen(clientCount: Int, jobCount: Int, navigate: (String) -> Unit) = ScreenColumn {
    Text("Set out. Count materials. Get back to work.", style = MaterialTheme.typography.headlineSmall)
    Text("Freestanding timber decks, with live plans and exact material quantities.")
    HomeCard("Clients", "$clientCount saved clients", { navigate("clients") })
    HomeCard("Jobs", "$jobCount saved jobs · multiple deck tasks", { navigate("jobs") })
    HomeCard("Task Library", "Deck — Freestanding Timber", { navigate("library") })
    Notice("Preliminary estimating / set-out information. These provisional rules do not verify structural design or compliance.")
}

@Composable
private fun HomeCard(title: String, description: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(modifier = Modifier.padding(20.dp).heightIn(min = 48.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
            Text("›", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun ClientList(clients: List<ClientEntity>, open: (Long) -> Unit, model: EstimatorViewModel) {
    var newClient by remember { mutableStateOf(false) }
    ScreenColumn {
        Button(onClick = { newClient = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("+ New client") }
        if (clients.isEmpty()) EmptyState("No clients yet", "Create a client, then add their first job.")
        clients.forEach { client ->
            HomeCard(client.name.ifBlank { "Unnamed client" }, listOf(client.phone, client.email).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Contact details and jobs" }, { open(client.id) })
        }
    }
    if (newClient) NameDialog("New client", "Client name", { newClient = false }) { name -> model.createClient(name, open) }
}

@Composable
private fun ClientScreen(client: ClientEntity, jobs: List<JobEntity>, model: EstimatorViewModel, openJob: (Long) -> Unit) {
    var newJob by remember { mutableStateOf(false) }
    ScreenColumn {
        Text("Client details", style = MaterialTheme.typography.titleLarge)
        Field("Name", client.name, { value -> model.editClient(client) { it.copy(name = value) } })
        Field("Phone", client.phone, { value -> model.editClient(client) { it.copy(phone = value) } })
        Field("Email", client.email, { value -> model.editClient(client) { it.copy(email = value) } })
        Field("Notes", client.notes, { value -> model.editClient(client) { it.copy(notes = value) } }, multiline = true)
        Spacer(Modifier.height(8.dp))
        Text("Jobs for this client", style = MaterialTheme.typography.titleLarge)
        Button(onClick = { newJob = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("+ New job") }
        if (jobs.isEmpty()) EmptyState("No jobs yet", "Add a job to start a deck estimate.")
        jobs.forEach { job -> HomeCard(job.name.ifBlank { "Unnamed job" }, job.notes.ifBlank { "Open tasks and consolidated materials" }, { openJob(job.id) }) }
    }
    if (newJob) NameDialog("New job", "Job name", { newJob = false }) { name -> model.createJob(client.id, name, openJob) }
}

@Composable
private fun JobsList(jobs: List<JobEntity>, clients: List<ClientEntity>, open: (Long) -> Unit, openClient: (Long) -> Unit, model: EstimatorViewModel) {
    var showCreate by remember { mutableStateOf(false) }
    var chosenClient by remember(clients) { mutableStateOf(clients.firstOrNull()?.id) }
    var jobName by remember { mutableStateOf("") }
    ScreenColumn {
        Button(onClick = { showCreate = !showCreate }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("+ New job") }
        if (showCreate) {
            if (clients.isEmpty()) {
                Notice("Create a client before adding a job.")
            } else {
                Choice("Client", chosenClient ?: clients.first().id, clients.map { it.id }, { id -> clients.first { it.id == id }.name.ifBlank { "Unnamed client" } }, { chosenClient = it })
                Field("Job name", jobName, { jobName = it })
                Button(onClick = {
                    val selected = chosenClient ?: return@Button
                    showCreate = false
                    model.createJob(selected, jobName, open)
                    jobName = ""
                }, enabled = jobName.isNotBlank()) { Text("Create job") }
            }
        }
        if (jobs.isEmpty()) EmptyState("No jobs yet", "Jobs belong to a client and can contain multiple independent deck tasks.")
        jobs.forEach { job ->
            val client = clients.firstOrNull { it.id == job.clientId }
            HomeCard(job.name.ifBlank { "Unnamed job" }, client?.name?.ifBlank { "Unnamed client" } ?: "Client", { open(job.id) })
        }
        if (clients.isNotEmpty() && jobs.isEmpty()) {
            Text("Start with a client", style = MaterialTheme.typography.titleMedium)
            clients.forEach { client -> TextButton(onClick = { openClient(client.id) }) { Text(client.name.ifBlank { "Unnamed client" }) } }
        }
    }
}

@Composable
private fun LibraryScreen() = ScreenColumn {
    Text("Construction task definitions", style = MaterialTheme.typography.titleLarge)
    TaskLibrary.definitions.forEach { definition ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(definition.title, style = MaterialTheme.typography.titleLarge)
                Text(definition.description)
                Text("Add this task from any job. Every instance has its own inputs and drawings.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Notice("Freestanding decks on flat, level ground. Construction rules are provisional estimating assumptions.")
}
