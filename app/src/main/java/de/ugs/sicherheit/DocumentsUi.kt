@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.ugs.sicherheit

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlinx.coroutines.*

@Composable
fun PdfPreview(file: File, vm: UGSViewModel) {
    val save =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/pdf")
        ) { uri ->
            if (uri != null)
                vm.run {
                    withContext(Dispatchers.IO) {
                        vm.app.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                            file.inputStream().use { it.copyTo(out) }
                        } ?: error("Zieldatei kann nicht geschrieben werden.")
                    }
                    vm.notice = "PDF gespeichert."
                }
        }
    Dialog(
        onDismissRequest = { vm.preview = null },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.preview = null }) {
                        Icon(Icons.Default.Close, "Schließen")
                    }
                    Text(file.name, Modifier.weight(1f), maxLines = 1)
                    IconButton(onClick = { save.launch(file.name) }) {
                        Icon(Icons.Default.Download, "PDF speichern")
                    }
                    IconButton(
                        onClick = {
                            vm.run {
                                vm.repo.exported("Teilen ${file.name}")
                                vm.pdf.share(file)
                            }
                        }
                    ) {
                        Icon(Icons.Default.Share, "Teilen")
                    }
                }
                PdfPages(file, vm)
            }
        }
    }
}

@Composable
fun PdfPages(file: File, vm: UGSViewModel, onTap: ((Int, Float, Float) -> Unit)? = null) {
    var count by remember(file) { mutableIntStateOf(0) }
    LaunchedEffect(file) {
        try {
            count =
                withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                        PdfRenderer(fd).use { it.pageCount }
                    }
                }
        } catch (e: Exception) {
            vm.error = "PDF kann nicht angezeigt werden: ${e.message}"
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(count) { index -> PdfPage(file, index, vm, onTap) }
    }
}

@Composable
private fun PdfPage(
    file: File,
    index: Int,
    vm: UGSViewModel,
    onTap: ((Int, Float, Float) -> Unit)?,
) {
    var bitmap by remember(file, index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file, index) {
        try {
            bitmap =
                withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                        PdfRenderer(fd).use { r ->
                            r.openPage(index).use { p ->
                                val width = 1080
                                val height =
                                    (width.toFloat() * p.height / p.width)
                                        .toInt()
                                        .coerceAtMost(2400)
                                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                                    it.eraseColor(android.graphics.Color.WHITE)
                                    p.render(
                                        it,
                                        null,
                                        null,
                                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                    )
                                }
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            vm.error = "Seite ${index+1}: ${e.message}"
        }
    }
    Column {
        Text("Seite ${index+1}", style = MaterialTheme.typography.labelSmall)
        val b = bitmap
        if (b != null)
            Image(
                b.asImageBitmap(),
                "PDF Seite ${index+1}",
                Modifier.fillMaxWidth()
                    .then(
                        if (onTap != null)
                            Modifier.pointerInput(index) {
                                detectTapGestures { p ->
                                    onTap(index, p.x / size.width, p.y / size.height)
                                }
                            }
                        else Modifier
                    ),
            )
        else
            Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
    }
}

@Composable
fun ExportsScreen(vm: UGSViewModel) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var badge by remember { mutableStateOf(false) }
    when (selected) {
        "letter" -> {
            BackHandler { selected = null }
            LetterScreen(vm) { selected = null }
            return
        }
        "certificate" -> {
            BackHandler { selected = null }
            CertificateScreen(vm) { selected = null }
            return
        }
        "timesheet" -> {
            BackHandler { selected = null }
            TimesheetScreen(vm) { selected = null }
            return
        }
    }
    val enabled = vm.canExport("exports")
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Dokumente & Firmenunterlagen", style = MaterialTheme.typography.headlineSmall) }
        if (!enabled) item { Text("Keine Berechtigung zum Exportieren.", color = MaterialTheme.colorScheme.error) }
        item { SectionHeader("Schreiben") }
        item {
            ExportTile("Abmahnung · Kündigung · Probezeit", "Mit Prüfung der Fristen; wird in der Personalakte abgelegt") {
                if (enabled) selected = "letter"
            }
        }
        item { SectionHeader("Formulare") }
        item {
            ExportTile("Erweitertes Führungszeugnis", "Anforderung auf der Originalvorlage") { if (enabled) selected = "certificate" }
        }
        item {
            ExportTile("Stundenzettel", "Zeiten eines Monats laden, prüfen und ausfüllen") { if (enabled) selected = "timesheet" }
        }
        item {
            ExportTile("Dienstausweis ausfüllen", "Mitarbeiter, Bewacher-ID und Gültigkeit") { if (enabled) badge = true }
        }
        item {
            ExportTile("Belehrung nach § 2a SchwarzArbG", "Für einen ausgewählten Mitarbeiter") { if (enabled) selected = "instruction" }
        }
        item { SectionHeader("UGS Sicherheit · PDF-Setup") }
        items(vm.pdf.templates.entries.toList()) { (name, file) ->
            ExportTile(name, "Originalvorlage mit UGS-Branding") {
                if (enabled) vm.run { vm.showPdf(name) { vm.pdf.asset(file) } }
            }
        }
    }
    if (badge)
        FormDialog(
            "Dienstausweis",
            listOf(
                personField,
                date("issued", "Ausgestellt am", true),
                date("valid", "Gültig bis", true),
            ),
            mapOf("issued" to today),
            vm,
            { badge = false },
        ) { v ->
            vm.run {
                val w = vm.entries.find { it.id == v["workerId"] } ?: error("Mitarbeiter wählen.")
                vm.showPdf("Dienstausweis_${w["personnelNumber"]}") {
                    vm.pdf.badge(w, v["issued"].orEmpty(), v["valid"].orEmpty())
                }
                badge = false
            }
        }
    if (selected == "instruction")
        FormDialog(
            "Belehrung",
            listOf(
                personField,
                date("signingDate", "Datum", true),
                field("signingPlace", "Ort", true),
            ),
            mapOf("signingDate" to today, "signingPlace" to vm.company["city"].orEmpty()),
            vm,
            { selected = null },
        ) { v ->
            vm.run {
                val w = vm.entries.find { it.id == v["workerId"] } ?: error("Mitarbeiter wählen.")
                vm.showPdf("Belehrung-SchwarzArbG-${w["lastName"]}-${w["firstName"]}", archiveTo = w.id, category = "Anweisung") {
                    vm.pdf.template(
                        "ugs-belehrung-schwarzarbg",
                        Contracts.base(w, vm.company, v),
                        vm.company,
                    )
                }
                selected = null
            }
        }
}

/** Abmahnung, Kündigung oder Kündigung in der Probezeit – mit automatischer Ablage. */
@Composable
fun LetterScreen(vm: UGSViewModel, back: () -> Unit) {
    var worker by remember { mutableStateOf<Entry?>(null) }
    var data by remember { mutableStateOf(LetterData()) }
    fun change(next: LetterData) {
        data = next.copy(reviewed = if (next.reviewed != data.reviewed) next.reviewed else false)
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { BackRow("Schreiben", back) }
        item { WorkerLookup(vm, worker) { w -> worker = w; change(data.copy(startDate = w["startDate"])) } }
        item {
            ChoiceInput("Art", data.kind.name, LetterData.Kind.entries.map { it.name to it.title }) { change(data.copy(kind = LetterData.Kind.valueOf(it))) }
        }
        item {
            ChoiceInput("Anrede", data.salutation, listOf("neutral" to "Guten Tag (neutral)", "female" to "Sehr geehrte Frau", "male" to "Sehr geehrter Herr")) {
                change(data.copy(salutation = it))
            }
        }
        item { CalendarInput("Briefdatum", data.letterDate, true) { change(data.copy(letterDate = it)) } }
        if (data.kind == LetterData.Kind.PROBATION) {
            item { CalendarInput("Arbeitsbeginn", data.startDate, true) { change(data.copy(startDate = it)) } }
            item { CalendarInput("Vereinbartes Probezeitende", data.probationEndDate, true) { change(data.copy(probationEndDate = it)) } }
            item { CalendarInput("Geplanter / tatsächlicher Zugang", data.receiptDate, true) { change(data.copy(receiptDate = it)) } }
            item { CalendarInput("Beendigungsdatum", data.endDate, true) { change(data.copy(endDate = it)) } }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(data.individualNotice, { change(data.copy(individualNotice = it)) })
                    Text("Abweichende vertragliche / tarifliche Frist", Modifier.padding(start = 10.dp))
                }
            }
            if (data.individualNotice)
                item {
                    OutlinedTextField(data.noticeRule, { change(data.copy(noticeRule = it)) }, label = { Text("Geprüfte Kündigungsregel") }, modifier = Modifier.fillMaxWidth())
                }
            item {
                Text(
                    "Die gesetzliche Frist beträgt grundsätzlich 14 Tage ab Zugang, längstens während der ersten sechs Monate. Abweichende Fristen, Sonderkündigungsschutz und eine erforderliche Betriebsratsanhörung sind im Einzelfall zu prüfen. Auf Papier eigenhändig unterschreiben; die PDF allein ersetzt die Schriftform nicht.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            item {
                CalendarInput(if (data.kind == LetterData.Kind.TERMINATION && data.immediate) "Kenntnis vom Kündigungsgrund" else "Vorfall", data.incidentDate, true) {
                    change(data.copy(incidentDate = it))
                }
            }
            if (data.kind == LetterData.Kind.TERMINATION) {
                item {
                    ChoiceInput("Kündigungsart", data.immediate.toString(), listOf("false" to "Ordentlich", "true" to "Fristlos")) {
                        change(data.copy(immediate = it == "true"))
                    }
                }
                if (!data.immediate) item { CalendarInput("Beendigungsdatum", data.endDate, true) { change(data.copy(endDate = it)) } }
            }
            item {
                OutlinedTextField(data.reason, { change(data.copy(reason = it)) }, label = { Text("Begründung / Sachverhalt") }, minLines = 4, modifier = Modifier.fillMaxWidth())
            }
            if (data.kind == LetterData.Kind.WARNING)
                item {
                    OutlinedTextField(data.expected, { change(data.copy(expected = it)) }, label = { Text("Beanstandete Pflicht und erwartetes Verhalten") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(data.reviewed, { data = data.copy(reviewed = it) })
                Text("Inhalt und Fristen fachlich geprüft")
            }
        }
        item {
            Button(
                onClick = {
                    vm.run {
                        val w = worker ?: error("Mitarbeiter wählen.")
                        require(vm.can(AccessAction.CREATE, "documents")) { "Keine Berechtigung zum Ablegen in der Personalakte." }
                        vm.showPdf("${data.fileLabel}-${w["personnelNumber"]}-${data.letterDate}", archiveTo = w.id, category = data.category) {
                            LetterPdf.render(vm.app, w, vm.company, data)
                        }
                        vm.notice = "Im Mitarbeiterprofil gespeichert."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Schreiben als PDF")
            }
        }
    }
}

@Composable
fun CertificateScreen(vm: UGSViewModel, back: () -> Unit) {
    var worker by remember { mutableStateOf<Entry?>(null) }
    var data by remember { mutableStateOf(CertificateRequest()) }
    var confirmed by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { BackRow("Erweitertes Führungszeugnis", back) }
        item {
            WorkerLookup(vm, worker) { w ->
                worker = w
                data = CertificateRequest.of(w, vm.company)
                confirmed = false
            }
        }
        val text: List<Pair<String, Pair<String, (String) -> CertificateRequest>>> =
            listOf(
                "Firma" to (data.requester to { v: String -> data.copy(requester = v) }),
                "Firmenanschrift" to (data.requesterAddress to { v: String -> data.copy(requesterAddress = v) }),
                "Nachname" to (data.lastName to { v: String -> data.copy(lastName = v) }),
                "Vorname" to (data.firstName to { v: String -> data.copy(firstName = v) }),
                "Mitarbeiteranschrift" to (data.address to { v: String -> data.copy(address = v) }),
                "Tätigkeit" to (data.activity to { v: String -> data.copy(activity = v) }),
                "Auftraggeber / Objekt" to (data.client to { v: String -> data.copy(client = v) }),
            )
        items(text) { (label, pair) ->
            OutlinedTextField(pair.first, { data = pair.second(it) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item { CalendarInput("Geburtsdatum", data.birthDate, true) { data = data.copy(birthDate = it) } }
        item { CalendarInput("Ausstellungsdatum", data.date, true) { data = data.copy(date = it) } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(confirmed, { confirmed = it })
                Text("Voraussetzungen für die Anforderung geprüft")
            }
            Text("Die Bescheinigung verwendet die hinterlegte Originalvorlage. Angaben vor dem Export prüfen.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Button(
                onClick = {
                    vm.run {
                        val w = worker ?: error("Bitte einen Mitarbeiter übernehmen.")
                        val id = w["personnelNumber"].ifBlank { w["bewacherId"] }
                        vm.showPdf("Anforderung-erweitertes-Fuehrungszeugnis-$id-${data.date}") { CertificatePdf.render(vm.app, data) }
                    }
                },
                enabled = worker != null && confirmed,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("PDF erstellen")
            }
        }
    }
}

@Composable
fun TimesheetScreen(vm: UGSViewModel, back: () -> Unit) {
    var worker by remember { mutableStateOf<Entry?>(null) }
    var month by remember { mutableStateOf(today.take(7)) }
    val rows = remember { mutableStateListOf<TimesheetRow>() }
    var loaded by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackRow("Stundenzettel", back) }
        item { WorkerLookup(vm, worker) { worker = it; loaded = "" } }
        item {
            OutlinedTextField(month, { month = it; loaded = "" }, label = { Text("Monat JJJJ-MM") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            OutlinedButton(
                onClick = {
                    vm.run {
                        val w = worker ?: error("Mitarbeiter wählen.")
                        val days = MonthCalendar.days(month)
                        val times = vm.rows(Kind.TIME).filter { it["workerId"] == w.id }
                        rows.clear()
                        rows.addAll(
                            days.mapIndexed { i, d ->
                                val t = times.firstOrNull { it["date"] == d.iso }
                                TimesheetRow(
                                    i + 1,
                                    t?.get("startTime").orEmpty(),
                                    t?.get("endTime").orEmpty(),
                                    t?.get("breakMinutes")?.ifBlank { "0" } ?: "0",
                                    notes = t?.get("notes").orEmpty(),
                                    weekday = d.weekday,
                                    workFree = d.workFree,
                                )
                            }
                        )
                        loaded = "${w.id}|$month"
                    }
                },
                enabled = worker != null,
            ) {
                Text("Zeiten für Stundenzettel laden")
            }
        }
        items(rows.indices.toList()) { i ->
            val r = rows[i]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Tag ${r.day} · ${r.weekday}${if (r.workFree) " · arbeitsfrei" else ""}", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(r.start, { rows[i] = r.copy(start = it) }, label = { Text("Beginn") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(r.end, { rows[i] = r.copy(end = it) }, label = { Text("Ende") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(r.breakMinutes, { rows[i] = r.copy(breakMinutes = it) }, label = { Text("Pause") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.weight(1f)) { ChoiceInput("Kürzel", r.code, TimesheetRow.codes.map { it to it.ifBlank { "—" } }) { rows[i] = r.copy(code = it) } }
                        OutlinedTextField(r.notes, { rows[i] = r.copy(notes = it) }, label = { Text("Notizen") }, singleLine = true, modifier = Modifier.weight(2f))
                    }
                }
            }
        }
        if (rows.isNotEmpty())
            item {
                Button(
                    onClick = {
                        vm.run {
                            val w = worker ?: error("Mitarbeiter wählen.")
                            require(loaded == "${w.id}|$month") { "Bitte die Zeiten für diesen Monat neu laden." }
                            vm.showPdf("Stundenzettel-${w["personnelNumber"]}-$month", archiveTo = w.id, category = "Stundenzettel") {
                                TimesheetPdf.render(vm.app, w, vm.company, month, rows.toList())
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Stundenzettel als PDF")
                }
            }
    }
}

@Composable
fun ExportTile(title: String, subtitle: String, click: () -> Unit) {
    Card(onClick = click, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            leadingContent = { Icon(Icons.Default.PictureAsPdf, null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
        )
    }
}


@Composable
fun ImportScreen(vm: UGSViewModel) {
    var rows by remember { mutableStateOf(emptyList<List<String>>()) }
    val mapping = remember { mutableStateMapOf<String, Int>() }
    var update by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf(emptyList<Entry>()) }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                vm.run {
                    rows =
                        withContext(Dispatchers.IO) {
                            val bytes = ImportService.read(vm.app, uri, FileLimits.IMPORT_BYTES)
                            if (
                                bytes.size >= 2 &&
                                    bytes[0] == 80.toByte() &&
                                    bytes[1] == 75.toByte()
                            )
                                ImportService.xlsx(bytes)
                            else ImportService.csv(String(bytes, Charsets.UTF_8))
                        }
                    require(rows.size > 1) {
                        "Datei benötigt eine Kopfzeile und mindestens einen Datensatz."
                    }
                    mapping.clear()
                    mapping.putAll(ImportService.suggest(rows.first()))
                }
        }
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("CSV / Excel importieren", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Die erste Zeile enthält Spaltenüberschriften. Weise die Felder zu und prüfe die Vorschau vor dem Import."
            )
        }
        item {
            Button(
                onClick = {
                    pick.launch(
                        arrayOf(
                            "text/*",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/octet-stream",
                        )
                    )
                },
                enabled = vm.can(AccessAction.CREATE, "import"),
            ) {
                Text("Datei auswählen")
            }
        }
        if (rows.isNotEmpty()) {
            item { Text("${rows.size-1} Datensätze · ${rows.first().size} Spalten") }
            items(schemas.getValue(Kind.WORKER)) { f ->
                ChoiceInput(
                    f.label + (if (f.required) " *" else ""),
                    (mapping[f.key] ?: -1).toString(),
                    listOf("-1" to "Nicht zugeordnet") +
                        rows.first().mapIndexed { i, s -> i.toString() to "${i+1}: $s" },
                ) {
                    mapping[f.key] = it.toInt()
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(update, { update = it })
                    Text("Bestehende Personalnummern aktualisieren")
                }
            }
            item {
                Button(
                    onClick = {
                        vm.run {
                            batch =
                                rows
                                    .drop(1)
                                    .filter { it.any(String::isNotBlank) }
                                    .map { row ->
                                        val values =
                                            schemas.getValue(Kind.WORKER).associate { f ->
                                                val index = mapping[f.key] ?: -1
                                                var raw =
                                                    if (index >= 0)
                                                        row.getOrNull(index).orEmpty().trim()
                                                    else f.initial
                                                if (
                                                    f.input == Input.DATE &&
                                                        raw.matches(
                                                            Regex("\\d{1,2}\\.\\d{1,2}\\.\\d{4}")
                                                        )
                                                ) {
                                                    val parts = raw.split('.')
                                                    raw =
                                                        "%04d-%02d-%02d"
                                                            .format(
                                                                parts[2].toInt(),
                                                                parts[1].toInt(),
                                                                parts[0].toInt(),
                                                            )
                                                }
                                                f.key to raw
                                            }
                                        val existing =
                                            vm.rows(Kind.WORKER).find {
                                                it["personnelNumber"] == values["personnelNumber"]
                                            }
                                        require(update || existing == null) {
                                            "Personalnummer ${values["personnelNumber"]} existiert bereits."
                                        }
                                        if (existing != null)
                                            existing.copy(
                                                fields =
                                                    existing.fields +
                                                        values.filter { (k, _) ->
                                                            (mapping[k] ?: -1) >= 0
                                                        }
                                            )
                                        else Entry(kind = Kind.WORKER, fields = values)
                                    }
                            val all = vm.entries.toMutableList()
                            for (e in batch) {
                                Rules.validate(e, all)
                                all.removeAll { it.id == e.id }
                                all += e
                            }
                            confirm = true
                        }
                    }
                ) {
                    Text("Vorschau prüfen")
                }
            }
        }
    }
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("${batch.size} Mitarbeiter importieren?") },
            text = {
                LazyColumn {
                    items(batch) { e ->
                        Text(
                            "${e["personnelNumber"]} · ${e.title}\n${e["street"]}, ${e["city"]} · ${e["startDate"]}",
                            Modifier.padding(vertical = 6.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.run {
                            withContext(Dispatchers.IO) { vm.repo.saveBatch(batch) }
                            vm.refresh()
                            confirm = false
                            rows = emptyList()
                            vm.notice = "${batch.size} Mitarbeiter importiert."
                        }
                    }
                ) {
                    Text("Importieren")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Abbrechen") } },
        )
}

@Composable
fun OcrScreen(vm: UGSViewModel) {
    var text by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf(false) }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.run { text = ImportService.ocr(vm.app, uri) }
        }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Ausweis / Nachweis einlesen", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Die Texterkennung erfolgt auf dem Gerät. Übernimm nur geprüfte Angaben in die Personalakte."
        )
        Button(onClick = { pick.launch(arrayOf("image/*")) }, enabled = vm.canCreate(Kind.WORKER)) {
            Icon(Icons.Default.DocumentScanner, null)
            Text(" Foto auswählen")
        }
        if (text.isNotBlank()) {
            OutlinedTextField(
                text,
                { text = it },
                label = { Text("Erkannter Text – prüfen und korrigieren") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 10,
            )
            Button(onClick = { edit = true }) { Text("Mitarbeiter aus geprüften Angaben anlegen") }
        }
    }
    if (edit)
        FormDialog(
            "Mitarbeiter anlegen",
            schemas.getValue(Kind.WORKER),
            emptyMap(),
            vm,
            { edit = false },
        ) { v ->
            vm.save(Entry(kind = Kind.WORKER, fields = v)) {
                edit = false
                vm.notice = "Mitarbeiter gespeichert."
            }
        }
}
