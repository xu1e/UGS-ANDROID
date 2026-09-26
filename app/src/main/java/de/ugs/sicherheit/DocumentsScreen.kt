@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.ugs.sicherheit

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Entschlüsselt ein Dokument in den privaten Cache und zeigt es an bzw. öffnet es. */
fun openDocument(vm: UGSViewModel, e: Entry) {
    vm.run {
        val mime = e["mime"].ifBlank { "application/pdf" }
        val name = e["originalName"].ifBlank { e.title + if (mime == "application/pdf") ".pdf" else "" }
        val file = vm.pdf.exportFile(name)
        withContext(Dispatchers.IO) {
            vm.repo.documentTo(e, file)
            vm.repo.exported("Dokument geöffnet: ${e.title}")
        }
        if (mime == "application/pdf") vm.preview = file else vm.pdf.open(file, mime)
    }
}

object DocumentExport {
    fun component(v: String): String {
        val clean = v.map { if (it.isLetterOrDigit() || it in " ._-") it else '_' }.joinToString("").trim(' ', '.')
        var limited = clean
        while (limited.toByteArray().size > 180) limited = limited.dropLast(1)
        return limited.ifEmpty { "Dokument" }
    }

    /** ZIP mit Ordnern je Mitarbeiter und Kategorie; Dateien werden gestreamt entschlüsselt. */
    fun zip(vm: UGSViewModel, documents: List<Entry>): File {
        require(documents.isNotEmpty()) { "Keine Dokumente zum Exportieren." }
        val out = vm.pdf.exportFile("UGS-Dokumente-$today.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (d in documents) {
                val w = vm.worker(d["workerId"])
                val folder =
                    if (w == null) "Ohne Mitarbeiter"
                    else component("${w["personnelNumber"]}-${w.title}")
                val name = "$folder/${component(DocumentCategories.canonical(d["category"]))}/${component(d.id.take(8) + "-" + d["originalName"].ifBlank { d.title })}"
                zip.putNextEntry(ZipEntry(name))
                val tmp = File.createTempFile("doc", ".bin", vm.app.cacheDir)
                try {
                    vm.repo.documentTo(d, tmp)
                    tmp.inputStream().use { it.copyTo(zip) }
                } finally {
                    tmp.delete()
                }
                zip.closeEntry()
            }
        }
        vm.repo.exported("Dokumente als ZIP: ${documents.size}")
        return out
    }
}

@Composable
fun DocumentsScreen(vm: UGSViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    var grouped by rememberSaveable { mutableStateOf(true) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf<Map<String, String>?>(null) }
    var detail by remember { mutableStateOf<Entry?>(null) }
    val docs = vm.rows(Kind.DOCUMENT)
    val visible =
        docs.filter { d ->
            (d["archived"] == "true") == archived &&
                (query.isBlank() ||
                    (listOf(d.title, d["originalName"], d["category"], d["documentNumber"]) +
                            listOfNotNull(vm.worker(d["workerId"])?.let { it.title + " " + it["personnelNumber"] + " " + it["bewacherId"] }))
                        .any { it.contains(query, true) })
        }
    folder?.let { id ->
        BackHandler { folder = null }
        PersonnelFile(vm, id, { folder = null }, { adding = it }) { detail = it }
        adding?.let { initial -> DocumentImport(vm, initial) { adding = null } }
        detail?.let { d -> DocumentDetail(vm, d) { detail = null } }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                query,
                { query = it },
                placeholder = { Text("Mitarbeiter, Datei, Kategorie") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            if (vm.can(AccessAction.CREATE, "documents"))
                IconButton(onClick = { adding = mapOf("category" to "Sonstiges", "date" to today) }) { Icon(Icons.Default.Add, "Dokument importieren") }
            if (vm.canExport("documents"))
                IconButton(
                    onClick = {
                        vm.run {
                            val file = withContext(Dispatchers.IO) { DocumentExport.zip(vm, visible) }
                            vm.pdf.share(file, "application/zip")
                        }
                    },
                    enabled = visible.isNotEmpty(),
                ) {
                    Icon(Icons.Default.Share, "Sichtbare Dokumente als ZIP")
                }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(grouped, { grouped = true }, label = { Text("Nach Mitarbeiter") })
            FilterChip(!grouped, { grouped = false }, label = { Text("Alle Dateien") })
            FilterChip(archived, { archived = !archived }, label = { Text("Archiv") })
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (visible.isEmpty()) item { EmptyState("Keine Dokumente", "Mit + ein Dokument (PDF, Bild, Office – bis ${FileLimits.FILE_LABEL}) ablegen.") }
            if (grouped) {
                val folders = visible.groupBy { it["workerId"] }.toList().sortedBy { (id, _) -> vm.worker(id)?.title?.lowercase() ?: "~" }
                items(folders, key = { it.first.ifBlank { "none" } }) { (id, list) ->
                    val w = vm.worker(id)
                    val worst = list.filter { it["archived"] != "true" }.map { ProofLevel.of(it.deadline().state) }.minByOrNull { it.ordinal } ?: ProofLevel.NEUTRAL
                    val categories = list.map { Proofs.title(it) }.distinct()
                    Card(onClick = { if (id.isNotBlank()) folder = id }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProofBadge(worst)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(w?.title ?: "Ohne Mitarbeiter", fontWeight = FontWeight.SemiBold)
                                Text(
                                    listOfNotNull(w?.get("personnelNumber"), if (categories.size > 3) categories.take(3).joinToString(", ") + " +${categories.size - 3}" else categories.joinToString(", ")).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                )
                            }
                            Text(if (list.size == 1) "1 Datei" else "${list.size} Dateien", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            } else
                items(visible, key = { it.id }) { d -> DocumentRow(vm, d) { detail = d } }
        }
    }
    adding?.let { initial -> DocumentImport(vm, initial) { adding = null } }
    detail?.let { d -> DocumentDetail(vm, d) { detail = null } }
}

@Composable
fun DocumentRow(vm: UGSViewModel, d: Entry, click: () -> Unit) {
    val deadline = d.deadline()
    val level = ProofLevel.of(deadline.state)
    Card(onClick = click, modifier = Modifier.fillMaxWidth().alpha(if (d["archived"] == "true") .6f else 1f)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProofBadge(level)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(Proofs.title(d), fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    listOfNotNull(vm.worker(d["workerId"])?.title, d["originalName"].ifBlank { d.title }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(deadline.label, style = MaterialTheme.typography.labelMedium, color = proofColor(level))
                Text(if (deadline.date.isEmpty()) (if (d["archived"] == "true") "Archiv" else "ohne Frist") else DateText.german(deadline.date), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Personalakte: Kopf, Kategorie-Filter, Dokumente mit Vorschau, hinzufügen, alle exportieren. */
@Composable
fun PersonnelFile(vm: UGSViewModel, workerId: String, back: () -> Unit, add: (Map<String, String>) -> Unit, open: (Entry) -> Unit) {
    var category by rememberSaveable { mutableStateOf("Alle") }
    var archived by rememberSaveable { mutableStateOf(false) }
    val worker = vm.worker(workerId)
    val documents =
        vm.rows(Kind.DOCUMENT).filter { it["workerId"] == workerId }
            .sortedWith(compareBy({ if (it["archived"] == "true") 1 else 0 }, { Proofs.title(it) }, { it["originalName"] }))
    val categories = documents.map { DocumentCategories.canonical(it["category"]) }.distinct().sorted()
    val visible = documents.filter { (archived || it["archived"] != "true") && (category == "Alle" || DocumentCategories.canonical(it["category"]) == category) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                Text(worker?.title ?: "Personalakte", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (vm.can(AccessAction.CREATE, "documents"))
                    IconButton(onClick = { add(mapOf("workerId" to workerId, "category" to if (category == "Alle") "Sonstiges" else category, "date" to today)) }) {
                        Icon(Icons.Default.Add, "Dokument hinzufügen")
                    }
                if (vm.canExport("documents"))
                    IconButton(
                        onClick = {
                            vm.run {
                                val file = withContext(Dispatchers.IO) { DocumentExport.zip(vm, visible) }
                                vm.pdf.share(file, "application/zip")
                            }
                        },
                        enabled = visible.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.Share, "Alle exportieren")
                    }
            }
        }
        if (worker != null)
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        WorkerAvatar(vm, worker, 56.dp)
                        Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(listOf(worker["position"], worker["object"]).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            StatusBadge(vm.status(worker))
                            ProofSummary(Proofs.build(documents))
                        }
                    }
                }
            }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(category == "Alle", { category = "Alle" }, label = { Text("Alle (${documents.size})") })
                categories.forEach { c ->
                    FilterChip(category == c, { category = c }, label = { Text("${DocumentCategories.title(c)} (${documents.count { DocumentCategories.canonical(it["category"]) == c }})") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(archived, { archived = it })
                Text("Archivierte anzeigen", Modifier.padding(start = 10.dp))
            }
        }
        item { SectionHeader("Dokumente (${visible.size})") }
        if (visible.isEmpty()) item { Text("Keine Dokumente in dieser Auswahl.", style = MaterialTheme.typography.bodySmall) }
        items(visible, key = { it.id }) { d -> DocumentRow(vm, d) { open(d) } }
    }
}

/** Dokument ablegen: erst die Angaben, dann die Datei (bis 250 MB). */
@Composable
fun DocumentImport(vm: UGSViewModel, initial: Map<String, String>, close: () -> Unit) {
    var pending by remember { mutableStateOf<Map<String, String>?>(null) }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val values = pending
            if (uri != null && values != null)
                vm.run {
                    withContext(Dispatchers.IO) { ImportService.attach(vm, uri, values + ("title" to values["title"].orEmpty().ifBlank { ImportService.name(vm.app, uri) })) }
                    vm.refresh()
                    pending = null
                    close()
                }
        }
    val fields =
        schemas.getValue(Kind.DOCUMENT).map { f ->
            when (f.key) {
                "workerId" -> f.copy(label = "Mitarbeiter", required = true)
                "title" -> f.copy(label = "Bezeichnung (leer = Dateiname)", required = false)
                else -> f
            }
        }.filter { it.key != "archived" }
    FormDialog("Dokument importieren", fields, initial, vm, close, saveLabel = "Datei wählen (max. ${FileLimits.FILE_LABEL})") { values ->
        if (values["workerId"].isNullOrBlank()) vm.error = "Mitarbeiter ist erforderlich."
        else {
            pending = values
            pick.launch(arrayOf("*/*"))
        }
    }
}

@Composable
fun DocumentDetail(vm: UGSViewModel, d: Entry, close: () -> Unit) {
    var delete by remember { mutableStateOf(false) }
    val editable = vm.canEdit(Kind.DOCUMENT)
    FormDialog(
        "Dokument · ${vm.worker(d["workerId"])?.title.orEmpty()}",
        schemas.getValue(Kind.DOCUMENT),
        d.fields,
        vm,
        close,
        extra = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    listOf(d["originalName"], d["size"].toLongOrNull()?.let { android.text.format.Formatter.formatShortFileSize(vm.app, it) }.orEmpty(), d["mime"]).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { openDocument(vm, d) }) { Text("Vorschau / Öffnen") }
                    if (vm.canDelete(Kind.DOCUMENT)) OutlinedButton(onClick = { delete = true }) { Text("In Papierkorb") }
                }
            }
        },
        saveLabel = if (editable) "Details speichern" else "Schließen",
    ) { values ->
        if (!editable) close() else vm.save(d.copy(fields = d.fields + values)) { close() }
    }
    if (delete)
        AlertDialog(
            onDismissRequest = { delete = false },
            title = { Text("Dokument in den Papierkorb verschieben?") },
            text = { Text(d.title) },
            confirmButton = { TextButton(onClick = { vm.delete(d) { delete = false; close() } }) { Text("In Papierkorb") } },
            dismissButton = { TextButton(onClick = { delete = false }) { Text("Abbrechen") } },
        )
}

/** Mitarbeiter über Personalnummer oder Bewacher-ID übernehmen (oder aus der Liste). */
@Composable
fun WorkerLookup(vm: UGSViewModel, selected: Entry?, pick: (Entry) -> Unit) {
    var byBewacher by remember { mutableStateOf(false) }
    var number by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!byBewacher, { byBewacher = false }, label = { Text("Personalnummer") })
            FilterChip(byBewacher, { byBewacher = true }, label = { Text("Bewacher ID") })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(number, { number = it }, label = { Text(if (byBewacher) "Bewacher ID" else "Personalnummer") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    val w = Workers.find(number, vm.entries, byBewacher)
                    if (w != null) {
                        pick(w)
                        result = w.title
                    } else result = "Kein Mitarbeiter mit dieser Nummer gefunden."
                }
            ) {
                Text("Übernehmen")
            }
        }
        ChoiceInput(
            "oder aus der Liste",
            selected?.id.orEmpty(),
            listOf("" to "Bitte wählen") + vm.rows(Kind.WORKER).map { it.id to "${it.title} · ${it["personnelNumber"]}" },
        ) { id -> vm.worker(id)?.let { pick(it); result = it.title } }
        if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.bodySmall)
    }
}
