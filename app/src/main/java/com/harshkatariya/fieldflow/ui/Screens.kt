package com.harshkatariya.fieldflow.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import coil.compose.AsyncImage
import com.harshkatariya.fieldflow.core.*
import com.harshkatariya.fieldflow.data.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun date(time: Long): String = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
@Composable fun Page(content: @Composable ColumnScope.() -> Unit) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content) }
@Composable fun Heading(title: String, subtitle: String? = null) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) } } }
@Composable fun Panel(title: String, subtitle: String, onClick: (() -> Unit)? = null, trailing: String? = null) {
    val modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    Card(modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            trailing?.let { BadgeText(it) }
        }
    }
}
@Composable fun BadgeText(text: String) { Surface(color = if (text.contains("FAIL") || text.contains("CONFLICT") || text.contains("CRITICAL")) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) { Text(text.replace('_', ' '), Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium) } }
@Composable fun Primary(label: String, enabled: Boolean = true, onClick: () -> Unit) { Button(onClick, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled, shape = RoundedCornerShape(14.dp)) { Text(label) } }
@Composable fun Empty(message: String) { Panel("Nothing here yet", message) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun FieldFlow(vm: FieldViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    if (!state.ready) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("FieldFlow", style = MaterialTheme.typography.headlineLarge); CircularProgressIndicator() } }; return }
    val session = state.session
    if (session == null) { Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding -> Box(Modifier.padding(padding)) { Login(vm, busy) } }; return }
    key(session.technician.id, session.demo) {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route ?: "home"
        val main = route in listOf("home", "work", "assets", "sync")
        Scaffold(
            topBar = { TopAppBar(title = { Text("FIELD / FLOW", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { if (!main) IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton({ nav.navigate("alerts") }) { Icon(Icons.Default.NotificationsNone, "Notifications") }; IconButton({ nav.navigate("settings") }) { Icon(Icons.Default.Settings, "Settings") } }) },
            bottomBar = { if (main) NavigationBar {
                listOf(Triple("home", "Today", Icons.Default.GridView), Triple("work", "Work", Icons.AutoMirrored.Filled.ListAlt), Triple("assets", "Assets", Icons.Default.Inventory2), Triple("sync", "Sync", Icons.Default.Sync)).forEach { (path, title, icon) ->
                    NavigationBarItem(route == path, { nav.navigate(path) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true } }, { Icon(icon, title) }, label = { Text(title) })
                }
            } }, snackbarHost = { SnackbarHost(snackbar) }
        ) { padding -> Column(Modifier.padding(padding).fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(connectivityMessage(session.demo, state.online), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            NavHost(nav, startDestination = "home", modifier = Modifier.weight(1f)) {
                composable("home") { Dashboard(state, nav) }
                composable("work") { WorkDirectory(state.local, nav) }
                composable("assets") { AssetsDirectory(state.local, nav) }
                composable("work/{id}") { back -> WorkDetails(back.arguments?.getString("id")!!, state.local, vm, nav) }
                composable("asset/{id}") { back -> AssetDetails(back.arguments?.getString("id")!!, state.local, nav) }
                composable("inspection/{id}") { back -> Checklist(back.arguments?.getString("id")!!, state.local, vm, nav) }
                composable("defect/{id}") { back -> DefectForm(back.arguments?.getString("id")!!, vm, nav) }
                composable("review/{id}") { back -> Review(back.arguments?.getString("id")!!, state.local, vm, nav) }
                composable("sync") { SyncCenter(state, vm, nav) }
                composable("history") { History(state.local, nav) }
                composable("alerts") { Alerts(state.local, nav) }
                composable("settings") { Settings(session, vm) }
                composable("scan") { ScannerScreen(state.local.assets, { nav.navigate("asset/$it") { popUpTo("scan") { inclusive = true } } }, vm::report) }
                composable("camera/{id}/{question}/{defect}") { back ->
                    val id = back.arguments?.getString("id")!!
                    CameraScreen { path -> vm.action {
                        try { vm.repository.photo(id, Photo(File(path).nameWithoutExtension, back.arguments?.getString("question")?.takeUnless { it == "none" }, back.arguments?.getString("defect")?.takeUnless { it == "none" }), path); nav.popBackStack() }
                        catch (e: Exception) { File(path).delete(); throw e }
                    } }
                }
            }
        } }
    }
}

@Composable private fun Login(vm: FieldViewModel, busy: Boolean) {
    var email by rememberSaveable { mutableStateOf("alex@fieldflow.example") }
    var password by rememberSaveable { mutableStateOf("") }
    Page {
        Spacer(Modifier.height(24.dp)); BadgeText("FIELD SERVICE, WITHOUT LIMITS")
        Heading("FieldFlow", "Offline-first inspections.\nKeep your work moving, wherever the job takes you.")
        Panel("Ready for the field", "Work orders, equipment history and inspections — available even without a signal.")
        Text("Connected account", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        Primary("Sign in", !busy && email.isNotBlank() && password.isNotBlank()) { vm.login(email, password); password = "" }
        HorizontalDivider()
        Text("Explore the local demo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Fictional records. No password or server needed. Demo submissions remain on this device.", style = MaterialTheme.typography.bodyMedium)
        Seed.technicians.forEach { tech -> OutlinedButton({ vm.demo(tech) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy) { Text("Continue as ${tech.name}") } }
    }
}
@Composable private fun Dashboard(state: UiState, nav: NavHostController) {
    val local = state.local
    Page {
        Heading("Ready, ${state.session!!.technician.name.substringBefore(' ')}.", "Your field operations at a glance")
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF14283E)), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("TODAY'S WORKSPACE", color = Color(0xFF7DE6D4), style = MaterialTheme.typography.labelLarge)
                Text("${local.work.size} assigned jobs", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("${local.work.count { it.dueAt < now() && it.status != "COMPLETED" }} overdue  ·  ${local.inspections.count { it.status == InspectionStatus.DRAFT }} drafts", color = Color(0xFFD4DFEA))
                Button({ nav.navigate("work") }) { Text("Open work orders") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { Panel("${local.assets.size}", "Equipment", { nav.navigate("assets") }) }
            Box(Modifier.weight(1f)) { Panel("${local.operations.count { it.status != "SYNCED" }}", "Queued / needs attention", { nav.navigate("sync") }) }
        }
        Primary("Scan equipment QR") { nav.navigate("scan") }
        Text("Next up", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (local.work.isEmpty()) Empty("Use Sync now to download assigned work.")
        local.work.take(3).forEach { WorkCard(it, local, nav) }
        OutlinedButton({ nav.navigate("history") }, Modifier.fillMaxWidth()) { Text("Inspection history & audit") }
    }
}
@Composable private fun WorkCard(work: WorkOrder, local: LocalState, nav: NavHostController) {
    val asset = local.assets.find { it.id == work.assetId }
    Panel(work.title, "${asset?.code ?: work.assetId} · ${asset?.location.orEmpty()}\nDue ${date(work.dueAt)}", { nav.navigate("work/${work.id}") }, "${work.priority} · ${if (work.dueAt < now()) "OVERDUE" else "UPCOMING"}")
}
@Composable private fun WorkDirectory(local: LocalState, nav: NavHostController) {
    var query by rememberSaveable { mutableStateOf("") }; var overdue by rememberSaveable { mutableStateOf(false) }
    Page {
        Heading("Work orders", "Assigned to you. Available offline.")
        OutlinedTextField(query, { query = it }, label = { Text("Search work or equipment") }, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, null) })
        FilterChip(overdue, { overdue = !overdue }, { Text("Overdue only") })
        val filtered = local.work.filter { (!overdue || it.dueAt < now()) && (it.title.contains(query, true) || local.assets.find { a -> a.id == it.assetId }?.let { a -> Rules.matches(a, query) } == true) }
        if (filtered.isEmpty()) Empty("No work orders match your search.")
        filtered.forEach { WorkCard(it, local, nav) }
    }
}
@Composable private fun AssetsDirectory(local: LocalState, nav: NavHostController) {
    var query by rememberSaveable { mutableStateOf("") }
    Page {
        Heading("Equipment", "Know the asset before you start.")
        OutlinedTextField(query, { query = it }, label = { Text("Search code, name or location") }, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, null) })
        OutlinedButton({ nav.navigate("scan") }, Modifier.fillMaxWidth()) { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Scan or enter equipment code") }
        val assets = local.assets.filter { Rules.matches(it, query) }
        if (assets.isEmpty()) Empty("No matching equipment.")
        assets.forEach { Panel(it.name, "${it.code} · ${it.category}\n${it.location}", { nav.navigate("asset/${it.id}") }, it.status) }
    }
}
@Composable private fun WorkDetails(id: String, local: LocalState, vm: FieldViewModel, nav: NavHostController) {
    val work = local.work.find { it.id == id }
    Page {
        if (work == null) { Empty("Work order is not available for this account."); return@Page }
        Heading(work.title, work.id.uppercase()); BadgeText("${work.priority} PRIORITY")
        Panel("Schedule", "Due ${date(work.dueAt)}\n${work.status}")
        local.assets.find { it.id == work.assetId }?.let { Panel(it.name, "${it.code}\n${it.location}", { nav.navigate("asset/${it.id}") }) }
        Panel("Inspection checklist", local.templates.find { it.id == work.templateId }?.name ?: "Template unavailable")
        Primary(if (local.inspections.any { it.workOrderId == id && it.status == InspectionStatus.DRAFT }) "Resume inspection" else "Start inspection") { vm.start(id) { nav.navigate("inspection/$it") } }
        local.inspections.filter { it.workOrderId == id }.forEach { InspectionCard(it, local, nav) }
    }
}
@Composable private fun AssetDetails(id: String, local: LocalState, nav: NavHostController) {
    Page {
        val asset = local.assets.find { it.id == id }
        if (asset == null) { Empty("Equipment not found in local records."); return@Page }
        BadgeText(asset.code); Heading(asset.name, asset.category); BadgeText(asset.status)
        Panel("Equipment profile", "${asset.location}\n${asset.model}\nLast inspected ${date(asset.lastInspected)}\nNext inspection ${date(asset.nextInspection)}")
        Text("Assigned work", style = MaterialTheme.typography.titleLarge)
        val work = local.work.filter { it.assetId == id }
        if (work.isEmpty()) Empty("No work assigned to you for this asset.")
        work.forEach { WorkCard(it, local, nav) }
        Text("Inspection history", style = MaterialTheme.typography.titleLarge)
        local.inspections.filter { it.assetId == id }.forEach { InspectionCard(it, local, nav) }
    }
}

@Composable private fun InspectionCard(i: Inspection, local: LocalState, nav: NavHostController) { Panel(local.assets.find { it.id == i.assetId }?.name ?: i.assetId, "${date(i.startedAt)} · ${i.answers.size} responses · ${i.defects.size} defects", { nav.navigate(if (i.status == InspectionStatus.DRAFT) "inspection/${i.id}" else "review/${i.id}") }, if (i.status == InspectionStatus.DRAFT) "DRAFT" else local.sync[i.id] ?: "PENDING") }

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun Checklist(id: String, local: LocalState, vm: FieldViewModel, nav: NavHostController) {
    val i = local.inspections.find { it.id == id }
    val template = local.templates.find { it.id == i?.templateId }
    Page {
        if (i == null || template == null) { Empty("Loading saved inspection…"); return@Page }
        if (i.status != InspectionStatus.DRAFT) { Heading("Inspection submitted"); Primary("View saved inspection") { nav.navigate("review/$id") }; return@Page }
        Heading(template.name, "Draft saves automatically on this device.")
        val answered = template.questions.count { q -> i.answers.any { it.questionId == q.id && it.value.isNotBlank() } }
        LinearProgressIndicator(progress = { answered.toFloat() / template.questions.size }, modifier = Modifier.fillMaxWidth())
        Text("$answered of ${template.questions.size} answered", style = MaterialTheme.typography.labelLarge)
        template.questions.forEachIndexed { index, q ->
            val answer = i.answers.find { it.questionId == q.id } ?: Answer(q.id, "")
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${index + 1}. ${q.label}${if (q.required) " *" else ""}", style = MaterialTheme.typography.titleMedium)
                    when (q.type) {
                        QuestionType.PASS_FAIL_NA, QuestionType.YES_NO -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (if (q.type == QuestionType.YES_NO) listOf("YES", "NO") else listOf("PASS", "FAIL", "N/A")).forEach { choice ->
                                FilterChip(answer.value == choice, { vm.answer(id, answer.copy(value = choice)) }, { Text(choice) })
                            }
                        }
                        else -> {
                            var value by rememberSaveable(id, q.id) { mutableStateOf(answer.value) }
                            OutlinedTextField(value, { value = it; vm.answer(id, answer.copy(value = it)) }, modifier = Modifier.fillMaxWidth(),
                                label = { Text(if (q.type == QuestionType.NUMBER) "Reading (${q.unit})" else "Response") },
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (q.type == QuestionType.NUMBER) androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Text))
                            if (q.type == QuestionType.NUMBER) Text("Range ${q.min} to ${q.max} ${q.unit}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    var comment by rememberSaveable(id, q.id) { mutableStateOf(answer.comment) }
                    OutlinedTextField(comment, { comment = it; vm.action { vm.repository.comment(id, q.id, it) } }, label = { Text("Optional comment") }, modifier = Modifier.fillMaxWidth())
                    if (q.photoRequired || answer.value == "FAIL") OutlinedButton({ nav.navigate("camera/$id/${q.id}/none") }) { Text("${if (q.photoRequired) "Required" else "Add"} photo · ${i.photos.count { it.questionId == q.id }} saved") }
                }
            }
        }
        OutlinedButton({ nav.navigate("defect/$id") }, Modifier.fillMaxWidth()) { Text("Report a defect (${i.defects.size})") }
        LocationButton { lat, lon -> vm.action { vm.repository.location(id, lat, lon) } }
        i.latitude?.let { Text("Location attached · ${"%.4f".format(it)}, ${"%.4f".format(i.longitude)}") }
        Primary("Review inspection") { vm.action { nav.navigate("review/$id") } }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun DefectForm(id: String, vm: FieldViewModel, nav: NavHostController) {
    var description by rememberSaveable { mutableStateOf("") }; var severity by rememberSaveable { mutableStateOf(Severity.MEDIUM) }
    Page {
        Heading("Report a defect", "Describe what you found. Evidence can be added after saving.")
        OutlinedTextField(description, { description = it }, label = { Text("Description (at least 5 characters)") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        Text("Severity", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Severity.entries.forEach { FilterChip(severity == it, { severity = it }, { Text(it.name) }) } }
        Primary("Save defect", description.trim().length in 5..4000) { vm.action { vm.repository.defect(id, description, severity); nav.popBackStack() } }
    }
}
@Composable private fun Review(id: String, local: LocalState, vm: FieldViewModel, nav: NavHostController) {
    var confirm by remember { mutableStateOf(false) }
    val i = local.inspections.find { it.id == id }
    Page {
        if (i == null) { Empty("Inspection unavailable."); return@Page }
        Heading(if (i.status == InspectionStatus.DRAFT) "Review & submit" else "Inspection record", local.assets.find { it.id == i.assetId }?.name)
        BadgeText(if (i.status == InspectionStatus.DRAFT) "DRAFT" else local.sync[id] ?: "PENDING")
        val template = local.templates.find { it.id == i.templateId }
        template?.questions?.forEach { q -> val a = i.answers.find { it.questionId == q.id }; Panel(q.label, "${a?.value?.ifBlank { "Not answered" } ?: "Not answered"}${a?.comment?.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()}") }
        i.defects.forEach { defect ->
            Panel("${defect.severity} defect", defect.description)
            if (i.status == InspectionStatus.DRAFT) OutlinedButton({ nav.navigate("camera/$id/none/${defect.id}") }) { Text("Attach defect photo") }
        }
        local.photos.filter { it.inspectionId == id }.forEach { AsyncImage(File(it.path), "Inspection photo evidence", Modifier.fillMaxWidth().height(200.dp)) }
        i.latitude?.let { Panel("Location", "$it, ${i.longitude}") }
        Text("Started ${date(i.startedAt)}", style = MaterialTheme.typography.bodySmall)
        if (i.status == InspectionStatus.DRAFT && template != null) {
            val errors = Rules.validate(i, template)
            if (errors.isNotEmpty()) Panel("Before you submit", errors.joinToString("\n"))
            OutlinedButton({ nav.navigate("inspection/$id") }, Modifier.fillMaxWidth()) { Text("Continue editing") }
            Primary("Submit inspection", errors.isEmpty()) { confirm = true }
            Text("Submission locks this record and adds it to the durable sync queue.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Submit inspection?") }, text = { Text("Your answers will be saved locally and queued. You can view them offline after submission.") },
        confirmButton = { TextButton({ confirm = false; vm.submit(id) { nav.navigate("sync") { popUpTo("home") } } }) { Text("Confirm submission") } }, dismissButton = { TextButton({ confirm = false }) { Text("Keep editing") } })
}
@Composable private fun SyncCenter(state: UiState, vm: FieldViewModel, nav: NavHostController) {
    Page {
        Heading("Sync center", "A clear record of what is saved and what needs attention.")
        if (state.session!!.demo) Panel("Local demo mode", "Pending records stay on this device. Connected mode uses a separate database and authenticated backend.")
        Primary("Sync now / Retry failed", !state.session.demo) { vm.sync() }
        if (state.local.operations.isEmpty()) Empty("No queued submissions. Connected sync also refreshes your assigned work.")
        state.local.operations.forEach { op ->
            Panel("Inspection ${op.inspectionId.take(8)}", "${date(op.createdAt)} · ${op.attempts} attempts\n${op.error}", { nav.navigate("review/${op.inspectionId}") }, op.status)
            if (op.status == "CONFLICT") {
                op.serverPayload?.let { payload -> val server = Wire.decodeFromString<Inspection>(payload); Panel("Server record · version ${server.version}", server.answers.joinToString("\n") { "${it.questionId}: ${it.value}" }) }
                Text("Keep the original record and conflict evidence. Create a separate draft with your answers, then review and add fresh photos before submission.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton({ vm.action { val id = vm.repository.resolveAsNew(op.id); nav.navigate("inspection/$id") } }) { Text("Copy local work to a new draft") }
            }
        }
    }
}
@Composable private fun History(local: LocalState, nav: NavHostController) {
    Page {
        Heading("History & audit", "Your persistent field activity")
        if (local.inspections.isEmpty()) Empty("Start an inspection to create history.")
        local.inspections.forEach { InspectionCard(it, local, nav) }
        Text("Audit timeline", style = MaterialTheme.typography.titleLarge)
        local.audits.forEach { Panel(it.message, date(it.createdAt)) }
    }
}
@Composable private fun Alerts(local: LocalState, nav: NavHostController) {
    Page {
        Heading("Notifications", "Derived from your work and synchronization activity")
        local.work.filter { it.dueAt < now() }.forEach { Panel("Overdue · ${it.title}", "Due ${date(it.dueAt)}", { nav.navigate("work/${it.id}") }, it.priority.name) }
        local.work.filter { it.dueAt >= now() }.forEach { Panel("Assigned · ${it.title}", "Due ${date(it.dueAt)}", { nav.navigate("work/${it.id}") }) }
        local.operations.filter { it.status != "PENDING" }.forEach { Panel("Sync ${it.status.lowercase()}", it.error.ifBlank { "Inspection ${it.inspectionId.take(8)} saved to server" }, { nav.navigate("sync") }) }
        if (local.work.isEmpty() && local.operations.isEmpty()) Empty("No alerts for this account.")
    }
}
@Composable private fun Settings(session: Session, vm: FieldViewModel) {
    val theme by vm.theme.collectAsStateWithLifecycle()
    Page {
        Heading("Profile & settings", session.technician.name)
        Panel(if (session.demo) "Local demonstration" else "Connected technician", "${session.technician.email}\nOffline access expires ${date(session.expiresAt)}")
        Text("Appearance", style = MaterialTheme.typography.titleLarge)
        listOf("system", "light", "dark").forEach { value -> Row(Modifier.fillMaxWidth().clickable { vm.action { vm.sessions.setTheme(value) } }, verticalAlignment = Alignment.CenterVertically) { RadioButton(theme == value, { vm.action { vm.sessions.setTheme(value) } }); Text(value.replaceFirstChar { it.uppercase() }) } }
        Panel("Device storage", "Drafts, submitted records and photos stay on this device after logout. Only the same technician can access their work in the app. Logout clears the encrypted session and cancels background sync.")
        Panel("Privacy", "Location is captured only when requested. No background tracking. This demonstration uses fictional equipment and people.")
        Primary("Log out") { vm.logout() }
    }
}
