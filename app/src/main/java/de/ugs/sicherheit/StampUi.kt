package de.ugs.sicherheit

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * „Vertrag stempeln“ wie iOS Build 36: mehrere Stempel, automatische Suche des
 * Unterschriftsfelds, Kopieren auf Seiten, Mitarbeiter per Bewacher ID oder
 * Personalnummer, Ablage in der Personalakte und Versand per E-Mail.
 */
@Composable
fun StampScreen(vm: UGSViewModel) {
    var source by remember { mutableStateOf<ByteArray?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var ratios by remember { mutableStateOf(emptyList<Float>()) }
    var page by remember { mutableIntStateOf(0) }
    var stamps by remember { mutableStateOf(emptyList<StampPlacement>()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var printDate by remember { mutableStateOf(true) }
    var date by remember { mutableStateOf(today) }
    var signature by remember { mutableStateOf(true) }
    var authorized by remember { mutableStateOf(false) }
    var copyPages by remember { mutableStateOf("alle") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    // Ergebnis und Fassung: jede Änderung verwirft das PDF, die Fassung verhindert doppelte Ablage.
    var output by remember { mutableStateOf<ByteArray?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var filedKey by remember { mutableStateOf<String?>(null) }
    var numberInput by remember { mutableStateOf("") }
    var workerId by remember { mutableStateOf("") }
    var mailRecipient by remember { mutableStateOf("") }
    var mailSubject by remember { mutableStateOf(ContractStamp.subject(vm.company)) }
    var mailBody by remember { mutableStateOf("") }
    var mailEdited by remember { mutableStateOf(false) }
    var mailBusy by remember { mutableStateOf(false) }
    var mailStatus by remember { mutableStateOf("") }
    var alsoFile by remember { mutableStateOf(true) }
    var fromMail by remember { mutableStateOf<StampHandoff?>(null) }
    val worker = vm.entries.firstOrNull { it.id == workerId && it.kind == Kind.WORKER }
    val selected = stamps.firstOrNull { it.id == selectedId } ?: stamps.firstOrNull { it.page == page }
    val canExport = vm.canExport("contract")
    val canFile = vm.can(AccessAction.CREATE, "documents")
    val filesToo = alsoFile && worker != null && canFile

    fun changed() {
        output = null
    }

    fun regenerateMail() {
        mailSubject = ContractStamp.subject(vm.company)
        mailBody = ContractStamp.body(worker, vm.company, fromMail != null)
        mailEdited = false
    }

    fun selectWorker(w: Entry) {
        workerId = w.id
        val reply = fromMail?.recipient.orEmpty()
        mailRecipient = if (Mail.isAddress(reply)) reply else w["email"].trim()
        mailStatus = ""
        mailSubject = ContractStamp.subject(vm.company)
        mailBody = ContractStamp.body(w, vm.company, fromMail != null)
        mailEdited = false
        changed()
    }

    suspend fun load(data: ByteArray, name: String, mime: String) {
        val pdf = withContext(Dispatchers.IO) { if (mime.startsWith("image/") || !name.lowercase().endsWith(".pdf") && mime != "application/pdf") ContractStamp.imageToPdf(data) else data }
        val info = withContext(Dispatchers.IO) { ContractStamp.pageRatios(pdf) to ContractStamp.locate(pdf) }
        require(info.first.isNotEmpty()) { "Das PDF enthält keine Seiten." }
        source = pdf
        file = withContext(Dispatchers.IO) { vm.pdf.export(pdf, "Stempel_Original.pdf") }
        ratios = info.first
        stamps = listOf(info.second)
        selectedId = info.second.id
        page = info.second.page
        message = info.second.source.label
        changed()
    }

    // Übergabe aus dem Posteingang: Datei laden, Mitarbeiter erkennen, Antwortadresse als Empfänger.
    LaunchedEffect(vm.stampHandoff) {
        val h = vm.stampHandoff ?: return@LaunchedEffect
        vm.stampHandoff = null
        fromMail = h
        workerId = ""
        numberInput = ""
        mailStatus = ""
        busy = true
        try {
            load(h.data, h.name, h.mime)
            val w = vm.entries.firstOrNull { it.id == h.workerId && it.kind == Kind.WORKER }
            if (w != null) {
                selectWorker(w)
                numberInput = w["bewacherId"].ifBlank { w["personnelNumber"] }
                message = "Mitarbeiter erkannt: ${w.title}."
            } else {
                regenerateMail()
                message = "Mitarbeiter nicht automatisch erkannt – bitte Bewacher ID oder Personalnummer eingeben."
            }
            if (Mail.isAddress(h.recipient)) mailRecipient = h.recipient
        } catch (e: Exception) {
            message = e.message.orEmpty()
        } finally {
            busy = false
        }
    }
    LaunchedEffect(Unit) { if (mailBody.isEmpty()) regenerateMail() }

    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            fromMail = null
            busy = true
            vm.launch {
                try {
                    val (data, name, mime) =
                        withContext(Dispatchers.IO) { Triple(ImportService.read(vm.app, uri), ImportService.name(vm.app, uri), vm.app.contentResolver.getType(uri).orEmpty()) }
                    load(data, name, mime)
                } finally {
                    busy = false
                }
            }
        }

    suspend fun render(): Pair<ByteArray, Int> {
        output?.let { return it to revision }
        require(canExport) { "Keine Berechtigung zum Exportieren." }
        require(!signature || authorized) { "Verwendung der Unterschrift bestätigen." }
        val data = source ?: error("Bitte zuerst den unterschriebenen Vertrag laden.")
        val placements = stamps
        val bytes = withContext(Dispatchers.IO) { ContractStamp.render(vm.app, data, vm.company, placements, if (printDate) date else "", signature) }
        output = bytes
        revision++
        return bytes to revision
    }

    suspend fun fileDocument(bytes: ByteArray, rev: Int, w: Entry): Boolean {
        val key = "${w.id}-$rev"
        if (filedKey == key) {
            message = "Diese Fassung liegt bereits in der Personalakte."
            return true
        }
        withContext(Dispatchers.IO) {
            vm.repo.archive(w.id, "Arbeitsvertrag", ContractStamp.fileName(w).removeSuffix(".pdf"), bytes, notes = "Vom Mitarbeiter unterschrieben, Firmenstempel und Unterschrift Arbeitgeber gesetzt.")
        }
        filedKey = key
        vm.refresh()
        message = "In der Personalakte gespeichert (${w.title})."
        return true
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Vertrag stempeln", style = MaterialTheme.typography.headlineSmall) }
        item {
            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Unterschriebenen Vertrag laden", style = MaterialTheme.typography.titleMedium)
                    fromMail?.let { Text("Aus dem Posteingang: ${it.senderName} · ${it.name}", style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick = { pick.launch(arrayOf("application/pdf", "image/*")) }, enabled = !busy) { Text(if (source == null) "PDF oder Foto auswählen" else "Anderes Dokument auswählen") }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (source != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { page = (page - 1).coerceAtLeast(0) }, enabled = page > 0) { Text("‹") }
                            Text("Seite ${page + 1} / ${ratios.size}")
                            TextButton(onClick = { page = (page + 1).coerceAtMost(ratios.size - 1) }, enabled = page < ratios.size - 1) { Text("›") }
                        }
                        Text("${stamps.size} Stempel im Dokument · ${stamps.count { it.page == page }} auf dieser Seite", style = MaterialTheme.typography.bodySmall)
                        Text("Auf eine freie Stelle tippen setzt einen Stempel, Stempel mit dem Finger verschieben.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        val f = file
        if (f != null && page < ratios.size)
            item {
                StampPreview(vm, f, page, ratios[page], stamps.filter { it.page == page }, selected?.id, if (printDate) Rules.german(date) else "", signature,
                    tap = { x, y ->
                        val s = StampPlacement(page, x.coerceIn(0f, .95f), y.coerceIn(0f, .95f))
                        stamps = stamps + s
                        selectedId = s.id
                        changed()
                    },
                    select = { selectedId = it },
                    move = { id, dx, dy ->
                        stamps = stamps.map { if (it.id == id) it.copy(x = (it.x + dx).coerceIn(0f, 1f - it.width), y = (it.y + dy).coerceIn(0f, .98f), source = StampPlacement.Source.MANUAL) else it }
                        selectedId = id
                        changed()
                    },
                )
            }
        if (source != null) {
            item {
                ElevatedCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Ausgewählter Stempel", style = MaterialTheme.typography.titleMedium)
                        val s = selected
                        if (s == null) Text("Auf die Vorschau tippen, um einen Stempel zu setzen.", style = MaterialTheme.typography.bodySmall)
                        else {
                            Text("Seite ${s.page + 1} · ${s.source.label}", style = MaterialTheme.typography.bodySmall)
                            Text("Stempelgröße: ${(s.width * 100).toInt()} % der Seitenbreite")
                            Slider(s.width, { v -> stamps = stamps.map { if (it.id == s.id) it.copy(width = v) else it }; changed() }, valueRange = StampPlacement.widthRange)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(s.showDate, { v -> stamps = stamps.map { if (it.id == s.id) it.copy(showDate = v) else it }; changed() })
                                Text("Datum auf diesem Stempel drucken")
                            }
                            Text("Datumsgröße: ${(s.dateScale * 100).toInt()} %")
                            Slider(s.dateScale, { v -> stamps = stamps.map { if (it.id == s.id) it.copy(dateScale = v) else it }; changed() }, valueRange = StampPlacement.dateRange)
                            TextButton(onClick = { stamps = stamps.filterNot { it.id == s.id }; selectedId = null; changed() }) { Text("Stempel entfernen", color = MaterialTheme.colorScheme.error) }
                        }
                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(printDate, { printDate = it; changed() })
                            Text("Datum drucken")
                        }
                        if (printDate) CalendarInput("Datum", date, true) { date = it; changed() }
                        OutlinedButton(
                            onClick = {
                                val data = source ?: return@OutlinedButton
                                vm.run {
                                    val found = withContext(Dispatchers.IO) { ContractStamp.locate(data) }
                                    stamps = stamps.filterNot { it.page == found.page } + found
                                    selectedId = found.id
                                    page = found.page
                                    message = found.source.label
                                    changed()
                                }
                            },
                            enabled = !busy,
                        ) { Text("Position automatisch suchen") }
                    }
                }
            }
            item {
                ElevatedCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Mehrere Seiten", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(copyPages, { copyPages = it }, label = { Text("alle oder 1-3, 5") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = {
                            val s = selected ?: return@OutlinedButton
                            val targets = ContractStamp.pageIndices(copyPages, ratios.size)
                            if (targets.isEmpty()) message = "Seitenangabe nicht verstanden. Beispiele: alle · 1-3, 5"
                            else {
                                stamps = stamps + targets.filter { it != s.page }.map { s.copy(toPage = it) }
                                message = "Stempel auf ${targets.size} Seite(n) gesetzt."
                                changed()
                            }
                        }, enabled = selected != null) { Text("Ausgewählten Stempel auf diese Seiten kopieren") }
                        TextButton(onClick = { stamps = stamps.filterNot { it.page == page }; changed() }, enabled = stamps.any { it.page == page }) { Text("Stempel dieser Seite entfernen") }
                    }
                }
            }
            item {
                ElevatedCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Unterschrift und Export", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(signature, { signature = it; authorized = false; changed() })
                            Text("Hinterlegte Arbeitgeberunterschrift ergänzen")
                        }
                        if (signature)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(authorized, { authorized = it })
                                Text("Ich bin zur Verwendung dieser Unterschrift berechtigt und habe die Formanforderungen geprüft.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            }
                        Button(
                            onClick = {
                                vm.run {
                                    val (bytes, _) = render()
                                    val out = withContext(Dispatchers.IO) {
                                        vm.repo.exported("Vertrag gestempelt (${stamps.size} Stempel)")
                                        vm.pdf.export(bytes, ContractStamp.fileName(worker))
                                    }
                                    vm.preview = out
                                }
                            },
                            enabled = !busy && stamps.isNotEmpty() && canExport,
                        ) { Text("Gestempeltes PDF prüfen") }
                    }
                }
            }
            item {
                ElevatedCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Mitarbeiter und Personalakte", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(numberInput, { numberInput = it }, label = { Text("Bewacher ID oder Personalnummer") }, singleLine = true, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                val w = ContractStamp.findWorker(vm.rows(Kind.WORKER), numberInput)
                                if (w == null) message = "Kein Mitarbeiter mit dieser Bewacher ID oder Personalnummer." else selectWorker(w)
                            }, enabled = numberInput.isNotBlank()) { Text("Laden") }
                        }
                        FieldInput(personField, workerId, vm) { id -> vm.entries.firstOrNull { it.id == id }?.let { selectWorker(it) } }
                        worker?.let { Text(ContractStamp.workerLine(it), style = MaterialTheme.typography.bodySmall) }
                        if (canFile)
                            OutlinedButton(onClick = {
                                val w = worker ?: return@OutlinedButton
                                vm.run {
                                    val (bytes, rev) = render()
                                    fileDocument(bytes, rev, w)
                                }
                            }, enabled = worker != null && !busy && !mailBusy && stamps.isNotEmpty()) { Text("In Personalakte ablegen") }
                    }
                }
            }
            if (canExport)
                item {
                    ElevatedCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Per E-Mail an den Mitarbeiter", style = MaterialTheme.typography.titleMedium)
                            TextRow("E-Mail des Mitarbeiters", mailRecipient) { mailRecipient = it }
                            TextRow("Betreff", mailSubject) { mailSubject = it; mailEdited = true }
                            TextRow("Nachricht", mailBody, 8) { mailBody = it; mailEdited = true }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { regenerateMail() }) { Text("Text neu erzeugen") }
                                if (mailEdited) Text("manuell bearbeitet", style = MaterialTheme.typography.labelSmall)
                            }
                            if (canFile)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(alsoFile, { alsoFile = it }, enabled = worker != null)
                                    Text("Beim Senden auch in der Personalakte ablegen")
                                }
                            Button(
                                onClick = {
                                    if (mailBusy) return@Button
                                    mailBusy = true
                                    mailStatus = ""
                                    val recipient = mailRecipient.trim()
                                    val target = worker
                                    val file = filesToo
                                    vm.launch {
                                        try {
                                            require(Mail.isAddress(recipient)) { "Bitte eine gültige E-Mail-Adresse eingeben." }
                                            val (bytes, rev) = render()
                                            withContext(Dispatchers.IO) {
                                                MailService.send(vm, recipient, mailSubject.trim(), mailBody, listOf(Mail.Attachment(ContractStamp.fileName(target), bytes)), "contract", ContractStamp.layout)
                                                vm.repo.exported("Gestempelter Arbeitsvertrag (${stamps.size} Stempel) per E-Mail an $recipient: ${target?.title ?: "ohne Mitarbeiter"}")
                                            }
                                            vm.refresh()
                                            mailStatus = "An $recipient gesendet."
                                            if (file && target != null && fileDocument(bytes, rev, target)) mailStatus = "An $recipient gesendet und in der Personalakte von ${target.title} abgelegt."
                                        } finally {
                                            mailBusy = false
                                        }
                                    }
                                },
                                enabled = !busy && !mailBusy && stamps.isNotEmpty() && Mail.isAddress(mailRecipient.trim()) && mailSubject.isNotBlank() && mailBody.isNotBlank(),
                            ) { Text(if (mailBusy) "Wird gesendet …" else if (filesToo) "Senden und ablegen" else "Per E-Mail senden") }
                            if (mailStatus.isNotBlank()) Text(mailStatus, style = MaterialTheme.typography.bodySmall)
                            val sender = MailService.smtp(vm).senderAddress
                            Text(
                                listOfNotNull(
                                        if (worker != null && worker["email"].isBlank() && fromMail == null) "Für ${worker.title} ist keine E-Mail-Adresse gespeichert – bitte oben eintragen." else null,
                                        if (sender.isBlank()) "Absenderadresse fehlt noch – Einstellungen → E-Mail-Versand." else "Versand direkt über $sender; danach unter Gesendete E-Mails.",
                                    )
                                    .joinToString(" "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
        }
        if (message.isNotBlank()) item { Text(message, color = Color(0xFFFF9500)) }
    }
}

@Composable
private fun StampPreview(
    vm: UGSViewModel,
    file: File,
    page: Int,
    ratio: Float,
    stamps: List<StampPlacement>,
    selected: String?,
    date: String,
    signature: Boolean,
    tap: (Float, Float) -> Unit,
    select: (String) -> Unit,
    move: (String, Float, Float) -> Unit,
) {
    var bitmap by remember(file, page) { mutableStateOf<Bitmap?>(null) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(file, page) {
        bitmap =
            runCatching {
                    withContext(Dispatchers.IO) {
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                            PdfRenderer(fd).use { r ->
                                r.openPage(page).use { p ->
                                    val w = 1080
                                    Bitmap.createBitmap(w, (w.toFloat() * p.height / p.width).toInt().coerceIn(1, 2400), Bitmap.Config.ARGB_8888).also {
                                        it.eraseColor(android.graphics.Color.WHITE)
                                        p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    }
                                }
                            }
                        }
                    }
                }
                .onFailure { vm.error = "Seite ${page + 1}: ${it.message}" }
                .getOrNull()
    }
    val density = LocalDensity.current
    val stampRatio = if (signature) ContractStamp.RATIO_SIGNED else ContractStamp.RATIO_PLAIN
    Box(
        Modifier.fillMaxWidth()
            .aspectRatio(1f / ratio.coerceIn(.3f, 4f))
            .background(Color.White)
            .onSizeChanged { area = it }
            .pointerInput(page) { detectTapGestures { p -> tap(p.x / area.width, p.y / area.height) } }
    ) {
        bitmap?.let { Image(it.asImageBitmap(), "PDF Seite ${page + 1}", Modifier.fillMaxSize()) } ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
        if (area.width > 0)
            for (s in stamps) {
                val wPx = s.width * area.width
                val hPx = wPx * stampRatio
                with(density) {
                    Box(
                        Modifier.offset(x = (s.x * area.width).toDp(), y = (s.y * area.height).toDp())
                            .size(wPx.toDp(), hPx.toDp())
                            .background(Color(0x22135E9E))
                            .border(if (s.id == selected) 2.dp else 1.dp, if (s.id == selected) Color(0xFF006BD6) else Color.Gray)
                            .pointerInput(s.id, area) {
                                detectDragGestures(onDragStart = { select(s.id) }) { change, drag ->
                                    change.consume()
                                    move(s.id, drag.x / area.width, drag.y / area.height)
                                }
                            }
                    ) {
                        Text("Firmenstempel", Modifier.align(Alignment.Center), style = MaterialTheme.typography.labelSmall, color = Color(0xFF133869))
                    }
                    if (s.showDate && date.isNotBlank())
                        Text(
                            "Datum: $date",
                            Modifier.offset(x = (s.x * area.width).toDp(), y = (s.y * area.height + hPx + 2).toDp()),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF133869),
                        )
                }
            }
    }
}
