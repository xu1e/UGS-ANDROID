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
    var selected by remember { mutableStateOf<String?>(null) }
    var badge by remember { mutableStateOf(false) }
    if (selected == "probation") {
        BackHandler { selected = null }
        Column {
            TextButton(onClick = { selected = null }) { Text("Zurück zum Exportzentrum") }
            ContractScreen(vm, "probation")
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Dokumente & Firmenunterlagen", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            ExportTile(
                "Kündigung innerhalb der Probezeit",
                "Mit Kalender und Prüfung der Zweiwochenfrist",
            ) {
                selected = "probation"
            }
        }
        item {
            ExportTile("Dienstausweis ausfüllen", "Mitarbeiter, Bewacher-ID und Gültigkeit") {
                badge = true
            }
        }
        item {
            ExportTile("Belehrung nach § 2a SchwarzArbG", "Für einen ausgewählten Mitarbeiter") {
                selected = "instruction"
            }
        }
        items(vm.pdf.templates.entries.toList()) { (name, file) ->
            ExportTile(name, "Originalvorlage mit UGS-Branding") {
                vm.run { vm.showPdf(name) { vm.pdf.asset(file) } }
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
                vm.showPdf("Belehrung") {
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
fun StampScreen(vm: UGSViewModel) {
    var source by remember { mutableStateOf<File?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var x by remember { mutableFloatStateOf(.1f) }
    var y by remember { mutableFloatStateOf(.72f) }
    var size by remember { mutableFloatStateOf(.34f) }
    var includeDate by remember { mutableStateOf(true) }
    var date by remember { mutableStateOf(today) }
    var signature by remember { mutableStateOf(false) }
    var authorized by remember { mutableStateOf(false) }
    var placement by remember { mutableStateOf(false) }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                vm.run {
                    source =
                        withContext(Dispatchers.IO) {
                            vm.pdf.export(ImportService.read(vm.app, uri), "Stempel_Original.pdf")
                        }
                    page = 0
                }
        }
    if (placement && source != null) {
        Dialog(
            onDismissRequest = { placement = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.systemBarsPadding()) {
                    Text("Tippe auf die linke obere Ecke des Stempels.", Modifier.padding(16.dp))
                    TextButton(onClick = { placement = false }) { Text("Schließen") }
                    PdfPages(source!!, vm) { p, px, py ->
                        page = p
                        x = px
                        y = py
                        placement = false
                    }
                }
            }
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("Firmenstempel ohne Logo", style = MaterialTheme.typography.headlineSmall) }
        item {
            Text("Firmenangaben stammen aus den Einstellungen. Das Ausgangs-PDF bleibt erhalten.")
        }
        item {
            OutlinedButton(onClick = { pick.launch(arrayOf("application/pdf")) }) {
                Text(if (source == null) "PDF auswählen" else "Anderes PDF auswählen")
            }
        }
        if (source != null) {
            item {
                Text("PDF geladen · Stempel auf Seite ${page+1}")
                OutlinedButton(onClick = { placement = true }) { Text("Position im PDF wählen") }
            }
            item {
                Text("Stempelbreite: ${(size*100).toInt()} % der Seite")
                Slider(size, { size = it }, valueRange = .15f..0.65f)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(includeDate, { includeDate = it })
                    Text("Datum im Stempel")
                }
            }
            if (includeDate) item { CalendarInput("Datum", date, true) { date = it } }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        signature,
                        {
                            signature = it
                            authorized = false
                        },
                    )
                    Text("Hinterlegte Arbeitgeberunterschrift ergänzen")
                }
            }
            if (signature)
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(authorized, { authorized = it })
                        Text(
                            "Ich bin zur Verwendung dieser Unterschrift berechtigt und habe die Formanforderungen geprüft.",
                            Modifier.weight(1f),
                        )
                    }
                }
            item {
                Button(
                    onClick = {
                        vm.run {
                            require(!signature || authorized) {
                                "Verwendung der Unterschrift bestätigen."
                            }
                            vm.showPdf("Vertrag_gestempelt") {
                                vm.pdf.stamp(
                                    source!!.readBytes(),
                                    vm.company,
                                    page,
                                    x,
                                    y,
                                    size,
                                    if (includeDate) date else "",
                                    signature,
                                )
                            }
                        }
                    }
                ) {
                    Text("Gestempeltes PDF prüfen")
                }
            }
        }
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
                            val bytes = ImportService.read(vm.app, uri)
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
                enabled = vm.canEdit,
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
        Button(onClick = { pick.launch(arrayOf("image/*")) }, enabled = vm.canEdit) {
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
