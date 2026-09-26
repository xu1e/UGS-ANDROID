@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.ugs.sicherheit

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Suche wie am Mac: Name, Personalnummer, Bewacher-ID, E-Mail, Objekt, Ort. */
fun workerMatches(w: Entry, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return listOf(w.title, w["personnelNumber"], w["bewacherId"], w["email"], w["object"], w["city"], w["phone"], w["department"])
        .any { it.contains(q, true) }
}

@Composable
fun FilterMenu(label: String, value: String, options: List<String>, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = value != "Alle",
            onClick = { open = true },
            label = { Text(if (value == "Alle") label else "$label: $value", maxLines = 1) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
        )
        DropdownMenu(open, { open = false }, Modifier.heightIn(max = 360.dp)) {
            (listOf("Alle") + options).forEach { o ->
                DropdownMenuItem(
                    text = { Text(o) },
                    leadingIcon = { if (o == value) Icon(Icons.Default.Check, null) },
                    onClick = {
                        change(o)
                        open = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CopyNumber(value: String) {
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }
    Text(
        if (copied) "Kopiert ✓" else value.ifBlank { "—" },
        Modifier.combinedClickable(
            onClick = {},
            onDoubleClick = {
                if (value.isNotBlank()) {
                    clipboard.setText(AnnotatedString(value))
                    copied = true
                }
            },
            onLongClick = {
                if (value.isNotBlank()) {
                    clipboard.setText(AnnotatedString(value))
                    copied = true
                }
            },
        ),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
fun WorkersScreen(vm: UGSViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var department by rememberSaveable { mutableStateOf("Alle") }
    var location by rememberSaveable { mutableStateOf("Alle") }
    var nationality by rememberSaveable { mutableStateOf("Alle") }
    var status by rememberSaveable { mutableStateOf("Alle") }
    var profile by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<Entry?>(null) }
    var export by remember { mutableStateOf(false) }
    val all = vm.rows(Kind.WORKER)
    val filtered =
        all.filter { w ->
            workerMatches(w, query) &&
                (department == "Alle" || w["department"] == department) &&
                (location == "Alle" || w["location"] == location) &&
                (nationality == "Alle" || w["nationality"] == nationality) &&
                (status == "Alle" || w["status"] == status || vm.status(w) == status)
        }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                query,
                { query = it },
                placeholder = { Text("Name, Personalnummer, Bewacher ID") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            if (vm.canExport("workers"))
                IconButton(onClick = { export = true }) { Icon(Icons.Default.PictureAsPdf, "Mitarbeiterliste als PDF") }
            if (vm.canCreate(Kind.WORKER))
                IconButton(onClick = { editor = Entry(kind = Kind.WORKER, fields = emptyMap()) }) {
                    Icon(Icons.Default.Add, "Mitarbeiter anlegen")
                }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterMenu("Abteilung", department, all.map { it["department"] }.filter { it.isNotBlank() }.distinct().sorted()) { department = it }
            FilterMenu("Standort", location, all.map { it["location"] }.filter { it.isNotBlank() }.distinct().sorted()) { location = it }
            FilterMenu("Nationalität", nationality, all.map { it["nationality"] }.filter { it.isNotBlank() }.distinct().sorted()) { nationality = it }
            FilterMenu("Status", status, all.flatMap { listOf(it["status"], vm.status(it)) }.filter { it.isNotBlank() }.distinct().sorted()) { status = it }
        }
        Text(
            "${filtered.size} von ${all.size} Mitarbeitern",
            Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (filtered.isEmpty()) item { EmptyState("Keine Mitarbeiter", "Suche oder Filter ändern oder mit + anlegen.") }
            items(filtered, key = { it.id }) { w ->
                Card(onClick = { profile = w.id }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        WorkerAvatar(vm, w, 44.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(w.title, fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Personalnr. ", style = MaterialTheme.typography.bodySmall)
                                CopyNumber(w["personnelNumber"])
                                Text(" · Bewacher ID ", style = MaterialTheme.typography.bodySmall)
                                CopyNumber(w["bewacherId"])
                            }
                            val sub = listOf(w["nationality"], w["object"], DateText.german(w["startDate"])).filter { it.isNotBlank() }
                            if (sub.isNotEmpty())
                                Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusBadge(vm.status(w))
                    }
                }
            }
        }
    }
    profile?.let { id ->
        val w = vm.worker(id)
        if (w == null) profile = null else WorkerProfile(vm, w, { profile = null }) { editor = it }
    }
    editor?.let { e -> WorkerEditor(vm, e) { editor = null } }
    if (export)
        WorkerListExport(vm, filtered, listOf(
            "Suche „${query.trim()}“".takeIf { query.isNotBlank() },
            "Abteilung: $department".takeIf { department != "Alle" },
            "Standort: $location".takeIf { location != "Alle" },
            "Nationalität: $nationality".takeIf { nationality != "Alle" },
            "Status: $status".takeIf { status != "Alle" },
        ).filterNotNull()) { export = false }
}

@Composable
fun FullScreen(title: String, close: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close) { Icon(Icons.Default.Close, "Schließen") }
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    actions()
                }
                content()
            }
        }
    }
}

@Composable
fun ProofBadge(level: ProofLevel) {
    val c = proofColor(level)
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(c.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
        Text(
            when (level) {
                ProofLevel.OK -> "✓"
                ProofLevel.SOON -> "!"
                ProofLevel.BAD -> "✕"
                ProofLevel.NEUTRAL -> "–"
            },
            color = c,
            fontWeight = FontWeight.Bold,
        )
    }
}

fun proofColor(level: ProofLevel) =
    when (level) {
        ProofLevel.OK -> Color(0xFF34C759)
        ProofLevel.SOON -> Color(0xFFFF9500)
        ProofLevel.BAD -> Color(0xFFFF3B30)
        ProofLevel.NEUTRAL -> Color(0xFF8E8E93)
    }

@Composable
fun ProofSummary(rows: List<ProofRow>) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        for ((level, label) in listOf(ProofLevel.OK to "gültig", ProofLevel.SOON to "bald fällig", ProofLevel.BAD to "fehlt / abgelaufen")) {
            val n = rows.count { it.level == level }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.then(if (n == 0) Modifier.background(Color.Transparent) else Modifier)) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(proofColor(level).copy(alpha = if (n == 0) .4f else 1f)), contentAlignment = Alignment.Center) {
                    Text("$n", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.Black.copy(alpha = .85f))
                }
                Text(" $label", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ProofRowItem(row: ProofRow, click: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().then(if (click != null) Modifier.clickable(onClick = click) else Modifier).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProofBadge(row.level)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(row.title, fontWeight = FontWeight.SemiBold)
            if (row.subtitle.isNotBlank())
                Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(row.verdict, style = MaterialTheme.typography.labelMedium, color = proofColor(row.level))
            Text(row.until, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun WorkerPhotoActions(vm: UGSViewModel, worker: Entry) {
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                vm.run {
                    val png = withContext(Dispatchers.IO) { Photos.thumbnail(vm.app, uri) }
                    withContext(Dispatchers.IO) { vm.repo.setPhoto(worker.id, png) }
                    Photos.invalidate()
                }
        }
    if (vm.canEdit(Kind.WORKER))
        Row {
            TextButton(onClick = { pick.launch(arrayOf("image/*")) }) { Text("Foto wählen") }
            TextButton(
                onClick = {
                    vm.run {
                        withContext(Dispatchers.IO) { vm.repo.setPhoto(worker.id, null) }
                        Photos.invalidate()
                    }
                }
            ) {
                Text("Foto entfernen")
            }
        }
}

@Composable
fun WorkerProfile(vm: UGSViewModel, worker: Entry, close: () -> Unit, edit: (Entry) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var delete by remember { mutableStateOf(false) }
    val documents = vm.rows(Kind.DOCUMENT).filter { it["workerId"] == worker.id }
    val proofs = if (vm.permitted("documents")) Proofs.build(documents) else emptyList()
    val tabs = listOf("Nachweise & Fristen", "Stammdaten", "Fragebogen", "Dokumente", "Verlauf")
    BackHandler(onBack = close)
    FullScreen(
        worker.title,
        close,
        actions = {
            if (vm.canExport("exports"))
                IconButton(
                    onClick = {
                        vm.run {
                            vm.showPdf(QuestionnairePdf.fileName(worker)) { QuestionnairePdf.render(vm.app, worker, vm.company) }
                        }
                    }
                ) {
                    Icon(Icons.Default.Assignment, "Fragebogen PDF")
                }
            if (vm.canEdit(Kind.WORKER)) IconButton(onClick = { edit(worker) }) { Icon(Icons.Default.Edit, "Bearbeiten") }
            if (vm.canDelete(Kind.WORKER)) IconButton(onClick = { delete = true }) { Icon(Icons.Default.Delete, "Löschen") }
        },
    ) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WorkerAvatar(vm, worker, 72.dp)
                            Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(worker.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(listOf(worker["position"], worker["object"]).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                StatusBadge(vm.status(worker))
                            }
                        }
                        WorkerPhotoActions(vm, worker)
                        val facts =
                            listOf(
                                "Bewacher ID" to worker["bewacherId"],
                                "Personalnummer" to worker["personnelNumber"],
                                "Nationalität" to worker["nationality"],
                                "Geburtsdatum" to DateText.german(worker["birthDate"]),
                                "Abteilung" to worker["department"],
                                "Eintritt" to DateText.german(worker["startDate"]),
                            )
                        facts.chunked(2).forEach { pair ->
                            Row {
                                pair.forEach { (k, v) ->
                                    Column(Modifier.weight(1f)) {
                                        Text(k.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(v.ifBlank { "—" })
                                    }
                                }
                            }
                        }
                        if (proofs.isNotEmpty()) ProofSummary(proofs)
                    }
                }
            }
            item {
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                    tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
                }
            }
            when (tab) {
                0 ->
                    if (!vm.permitted("documents")) item { Text("Keine Berechtigung für Nachweise.") }
                    else items(proofs, key = { it.id }) { r -> ProofRowItem(r, r.document?.let { d -> { openDocument(vm, d) } }) }
                1 ->
                    items(schemas.getValue(Kind.WORKER).filter { it.key !in questionnaireFields.map { q -> q.key } }) { f ->
                        val v = worker[f.key]
                        if (v.isNotBlank())
                            Column {
                                Text(f.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                when {
                                    f.key == "email" -> Text(v, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { vm.pdf.dial("mailto:$v") })
                                    f.key == "phone" -> Text(v, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { vm.pdf.dial("tel:${v.replace(" ", "")}") })
                                    f.input == Input.DATE -> Text(DateText.german(v))
                                    else -> Text(optionLabel(f, v))
                                }
                            }
                    }
                2 -> {
                    val filled = questionnaireFields.filter { worker[it.key].isNotBlank() }
                    if (filled.isEmpty()) item { Text("Noch keine Fragebogen-Daten – über „Bearbeiten“ ergänzen.", style = MaterialTheme.typography.bodySmall) }
                    items(filled) { f ->
                        Column {
                            Text(f.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(worker[f.key])
                        }
                    }
                }
                3 ->
                    if (!vm.permitted("documents")) item { Text("Keine Berechtigung für Dokumente.") }
                    else {
                        if (documents.isEmpty()) item { Text("Noch keine Dokumente abgelegt.", style = MaterialTheme.typography.bodySmall) }
                        items(documents.sortedByDescending { it["date"] }, key = { it.id }) { d ->
                            ListItem(
                                headlineContent = { Text(Proofs.title(d)) },
                                supportingContent = {
                                    Text(listOf(d["originalName"].ifBlank { d.title }, if (d["archived"] == "true") "Archiviert" else "").filter { it.isNotBlank() }.joinToString(" · "))
                                },
                                leadingContent = { Icon(Icons.Default.Description, null) },
                                modifier = Modifier.clickable { openDocument(vm, d) },
                            )
                        }
                    }
                else -> {
                    val events = Timeline.build(worker, vm.entries) { vm.permitted(it) }
                    if (events.isEmpty()) item { Text("Noch kein Verlauf.", style = MaterialTheme.typography.bodySmall) }
                    items(events, key = { it.id }) { e ->
                        ListItem(
                            headlineContent = { Text(e.title) },
                            supportingContent = { Text(listOf(DateText.german(e.date), e.detail).filter { it.isNotBlank() }.joinToString(" · ")) },
                            leadingContent = {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(when (e.severity) { 2 -> Color(0xFFFF3B30); 1 -> Color(0xFFFF9500); else -> MaterialTheme.colorScheme.primary }))
                            },
                        )
                    }
                }
            }
        }
    }
    if (delete)
        AlertDialog(
            onDismissRequest = { delete = false },
            title = { Text("Mitarbeiter in den Papierkorb verschieben?") },
            text = { Text(worker.title) },
            confirmButton = { TextButton(onClick = { vm.delete(worker) { delete = false; close() } }) { Text("In Papierkorb") } },
            dismissButton = { TextButton(onClick = { delete = false }) { Text("Abbrechen") } },
        )
}

/** Mitarbeiter anlegen/bearbeiten; der Fragebogen lässt sich schon vor dem Speichern exportieren. */
@Composable
fun WorkerEditor(vm: UGSViewModel, worker: Entry, close: () -> Unit) {
    var askExport by remember { mutableStateOf<Entry?>(null) }
    FormDialog(
        if (worker.revision == 0) "Mitarbeiter anlegen" else "Mitarbeiter bearbeiten",
        schemas.getValue(Kind.WORKER),
        schemas.getValue(Kind.WORKER).associate { it.key to if (it.input == Input.DATE && it.required) today else it.initial } + worker.fields,
        vm,
        close,
        extra = { values ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionHeader("Fragebogen-PDF")
                OutlinedButton(
                    onClick = {
                        vm.run {
                            val draft = worker.copy(fields = worker.fields + values)
                            vm.showPdf(QuestionnairePdf.fileName(draft)) { QuestionnairePdf.render(vm.app, draft, vm.company) }
                        }
                    },
                    enabled = vm.canExport("exports"),
                ) {
                    Text("Fragebogen exportieren")
                }
                Text(
                    "Verwendet die aktuellen Formulardaten, auch vor dem Speichern. Ohne SV-Nummer sind Geburtsort, Geburtsland und Geburtsname Pflicht.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    ) { values ->
        val isNew = worker.revision == 0
        val fill = questionnaireFields.any { values[it.key].orEmpty().isNotBlank() && it.initial != values[it.key] }
        vm.run {
            val saved = withContext(Dispatchers.IO) { vm.repo.save(worker.copy(fields = worker.fields + values)) }
            vm.refresh()
            if (isNew && fill && vm.canExport("exports")) askExport = saved else close()
        }
    }
    askExport?.let { saved ->
        AlertDialog(
            onDismissRequest = { askExport = null; close() },
            title = { Text("Mitarbeiter gespeichert") },
            text = { Text("Fragebogen jetzt als PDF exportieren?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        askExport = null
                        close()
                        vm.run { vm.showPdf(QuestionnairePdf.fileName(saved)) { QuestionnairePdf.render(vm.app, saved, vm.company) } }
                    }
                ) {
                    Text("Fragebogen exportieren")
                }
            },
            dismissButton = { TextButton(onClick = { askExport = null; close() }) { Text("Nicht jetzt") } },
        )
    }
}

@Composable
fun WorkerListExport(vm: UGSViewModel, workers: List<Entry>, filters: List<String>, close: () -> Unit) {
    val key = vm.repo.personalKey("workerPdfColumns")
    val stored = vm.company[key].orEmpty().split(",").filter { k -> WorkerListColumns.all.any { it.id == k } }
    val selected = remember { (stored.ifEmpty { WorkerListColumns.defaultKeys }).toMutableStateList() }
    var masked by remember { mutableStateOf(false) }
    fun persist() = vm.launch { withContext(Dispatchers.IO) { vm.repo.personal("workerPdfColumns", selected.joinToString(",")) } }
    FullScreen("Mitarbeiterliste als PDF", close) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text("Mitarbeiter im PDF: ${workers.size}", fontWeight = FontWeight.SemiBold)
                if (filters.isNotEmpty()) Text("Filter: ${filters.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WorkerListColumns.presets.forEach { (title, keys) ->
                        AssistChip(onClick = { selected.clear(); selected.addAll(keys); persist() }, label = { Text(title) })
                    }
                }
            }
            item { SectionHeader("Spalten (${selected.size})") }
            items(WorkerListColumns.all) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (c.id in selected) selected.remove(c.id) else selected.add(c.id)
                        persist()
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(c.id in selected, null)
                    Text(c.label, Modifier.weight(1f))
                    if (c.sensitive) Text("sensibel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().clickable { masked = !masked }, verticalAlignment = Alignment.CenterVertically) {
                    Switch(masked, { masked = it })
                    Text("Sensible Daten maskieren (••••••)", Modifier.padding(start = 12.dp))
                }
                Text(
                    "Gestaltung wie die Liste „Mitarbeiterdaten“: dunkelblauer Titel, Kopfzeile auf jeder Seite, feine Linien. Hoch- oder Querformat automatisch.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Button(
            onClick = {
                vm.run {
                    val columns = WorkerListColumns.all.filter { it.id in selected }
                    val docs = vm.rows(Kind.DOCUMENT).groupingBy { it["workerId"] }.eachCount()
                    val rows = workers.map { w -> columns.map { WorkerListColumns.text(w, it.id, masked, vm.status(w), docs[w.id] ?: 0) } }
                    val footer =
                        listOf(
                                vm.company["name"].orEmpty(),
                                "${workers.size} Mitarbeiter",
                                if (filters.isEmpty()) "alle Mitarbeiter" else "Filter: ${filters.joinToString(", ")}",
                            )
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                    vm.showPdf("Mitarbeiterliste-$today") { WorkerListPdf.build(columns.map { it.label }, rows, footer) }
                    withContext(Dispatchers.IO) {
                        vm.repo.logged("Mitarbeiterliste als PDF: ${workers.size} Mitarbeiter · Spalten: ${columns.joinToString(", ") { it.label }}${if (masked) " · maskiert" else ""}")
                    }
                }
            },
            enabled = workers.isNotEmpty() && selected.isNotEmpty() && vm.canExport("workers") && !vm.busy,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text("PDF erstellen")
        }
    }
}
