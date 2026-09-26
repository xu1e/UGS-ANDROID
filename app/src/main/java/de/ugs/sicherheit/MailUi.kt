@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.ugs.sicherheit

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Anhang aus dem Posteingang für „Vertrag stempeln“. */
data class StampHandoff(
    val name: String,
    val data: ByteArray,
    val mime: String,
    val recipient: String,
    val senderName: String,
    val workerId: String?,
)

val inboxColors =
    linkedMapOf(
        "none" to ("Keine Farbe" to Color.Transparent),
        "red" to ("Rot" to Color(0xFFFF3B30)),
        "orange" to ("Orange" to Color(0xFFFF9500)),
        "yellow" to ("Gelb" to Color(0xFFFFCC00)),
        "green" to ("Grün" to Color(0xFF34C759)),
        "blue" to ("Blau" to Color(0xFF007AFF)),
        "purple" to ("Violett" to Color(0xFFAF52DE)),
    )

private fun dateTime(ms: Long) = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(ms))

/** Automatischer Abruf, solange die App entsperrt ist; optionale Mitteilung bei neuen E-Mails. */
@Composable
fun InboxPoller(vm: UGSViewModel) {
    val s = MailService.inbox(vm)
    LaunchedEffect(vm.user?.id, s) {
        while (true) {
            if (vm.user != null && s.configured && s.automatic && vm.permitted("inbox") && !vm.inboxBusy) {
                runCatching {
                        vm.inboxBusy = true
                        val r = withContext(Dispatchers.IO) { MailService.synchronize(vm, s, "INBOX") }
                        vm.inboxRevision++
                        vm.inboxError = ""
                        if (r.newUnread > 0 && s.notify) notifyNewMail(vm.app, r.newUnread)
                    }
                    .onFailure { vm.inboxError = it.message.orEmpty() }
                vm.inboxBusy = false
            }
            delay(s.interval * 60_000L)
        }
    }
}

fun notifyNewMail(c: Context, count: Int) {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val manager = c.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("inbox", "Posteingang", NotificationManager.IMPORTANCE_DEFAULT))
    // Keine Absender oder Betreffzeilen auf dem Sperrbildschirm.
    val n =
        NotificationCompat.Builder(c, "inbox")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("UGS Posteingang")
            .setContentText(if (count == 1) "1 neue E-Mail" else "$count neue E-Mails")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()
    runCatching { NotificationManagerCompat.from(c).notify(7301, n) }
}

@Composable
fun InboxScreen(vm: UGSViewModel) {
    val s = MailService.inbox(vm)
    var folder by rememberSaveable { mutableStateOf(InboxFolder.INBOX) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var color by rememberSaveable { mutableStateOf("all") }
    var search by rememberSaveable { mutableStateOf("") }
    var items by remember { mutableStateOf(emptyList<InboxItem>()) }
    var status by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<InboxItem?>(null) }
    var compose by remember { mutableStateOf<ComposeDraft?>(null) }
    val serverFolder = s.folder(folder)
    fun reload() {
        vm.launch {
            items = withContext(Dispatchers.IO) { if (s.configured) vm.repo.inbox(s.accountId, serverFolder, 2000).map { InboxItem.from(it) } else emptyList() }
        }
    }
    LaunchedEffect(serverFolder, vm.inboxRevision, s.accountId) { reload() }
    open?.let { item ->
        BackHandler { open = null }
        MessageReader(vm, s, item, { open = null; reload() }) { compose = it }
        compose?.let { d -> ComposeDialog(vm, d, "inbox") { compose = null } }
        return
    }
    val blocked = MailService.blocked(vm, s.accountId)
    val colors = MailService.colors(vm, s.accountId)
    val visible =
        items.filter { m ->
            (search.isBlank() || listOf(m.sender, m.subject, m.body.take(2000), m.senderAddress).any { it.contains(search, true) }) &&
                when (filter) {
                    "unread" -> m.unread
                    "important" -> m.important
                    "attachments" -> m.attachments.isNotEmpty()
                    "blocked" -> m.senderAddress in blocked
                    else -> true
                } &&
                (color == "all" || (colors[m.fingerprint] ?: "none") == color)
        }
    Column(Modifier.fillMaxSize()) {
        if (!s.configured) {
            EmptyState("Posteingang nicht eingerichtet", "Einstellungen → E-Mail-Versand und Posteingang: IMAP-Server, Benutzer und App-Passwort eintragen.")
            Button(onClick = { vm.navigation = "settings" }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Einstellungen öffnen") }
            return@Column
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (vm.canExport("inbox")) AssistChip(onClick = { compose = ComposeDraft.new(vm) }, label = { Text("Neue E-Mail") }, leadingIcon = { Icon(Icons.Default.Edit, null) })
            InboxFolder.entries.forEach { f ->
                FilterChip(folder == f, { folder = f }, label = { Text(f.title) }, leadingIcon = {
                    Icon(when (f) { InboxFolder.INBOX -> Icons.Default.Email; InboxFolder.JUNK -> UgsIcons.Junk; InboxFolder.SPAM -> Icons.Default.Warning; InboxFolder.SAVED -> UgsIcons.Archive }, null)
                })
            }
            FilterMenu("Filter", mapOf("all" to "Alle", "unread" to "Ungelesen", "important" to "Wichtig", "attachments" to "Mit Anhang", "blocked" to "Blockiert")[filter] ?: "Alle", listOf("Ungelesen", "Wichtig", "Mit Anhang", "Blockiert")) {
                filter = mapOf("Alle" to "all", "Ungelesen" to "unread", "Wichtig" to "important", "Mit Anhang" to "attachments", "Blockiert" to "blocked")[it] ?: "all"
            }
            FilterMenu("Farbe", if (color == "all") "Alle" else inboxColors[color]?.first ?: "Alle", inboxColors.values.map { it.first }) { t ->
                color = if (t == "Alle") "all" else inboxColors.entries.firstOrNull { it.value.first == t }?.key ?: "all"
            }
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(search, { search = it }, placeholder = { Text("E-Mails durchsuchen") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    vm.launch {
                        if (vm.inboxBusy) return@launch
                        vm.inboxBusy = true
                        try {
                            status = withContext(Dispatchers.IO) { MailService.synchronize(vm, s, serverFolder) }.status
                            vm.inboxRevision++
                        } finally {
                            vm.inboxBusy = false
                        }
                    }
                },
                enabled = !vm.inboxBusy,
            ) {
                if (vm.inboxBusy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, "Abrufen")
            }
        }
        val info = listOf(status, vm.inboxError).filter { it.isNotBlank() }.joinToString("\n")
        if (info.isNotBlank()) Text(info, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (visible.isEmpty()) item { EmptyState("Keine E-Mails", if (search.isBlank() && filter == "all") "Mit Abrufen den Ordner aktualisieren." else "Suche oder Filter anpassen.") }
            items(visible, key = { it.id }) { m ->
                val c = inboxColors[colors[m.fingerprint] ?: "none"]?.second ?: Color.Transparent
                ListItem(
                    headlineContent = { Text(m.sender.ifBlank { m.senderAddress }, fontWeight = if (m.unread) FontWeight.Bold else FontWeight.Normal, maxLines = 1) },
                    supportingContent = {
                        Column {
                            Text(m.subject, maxLines = 1, fontWeight = if (m.unread) FontWeight.SemiBold else FontWeight.Normal)
                            Text(m.body.replace("\n", " ").take(140), maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingContent = {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF34C759).copy(alpha = .25f)), contentAlignment = Alignment.Center) {
                            Text(m.sender.ifBlank { m.senderAddress }.trim().take(1).uppercase(), fontWeight = FontWeight.Bold)
                        }
                    },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(dateTime(m.date), style = MaterialTheme.typography.labelSmall)
                            Row {
                                if (m.important) Icon(Icons.Default.Star, "Wichtig", Modifier.size(14.dp), tint = Color(0xFFFF9500))
                                if (m.attachments.isNotEmpty()) Icon(UgsIcons.Attachment, "Anhang", Modifier.size(14.dp))
                                if (c != Color.Transparent) Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                            }
                        }
                    },
                    modifier = Modifier.clickable { open = m },
                )
                HorizontalDivider()
            }
        }
    }
    compose?.let { d -> ComposeDialog(vm, d, "inbox") { compose = null } }
}

@Composable
fun MessageReader(vm: UGSViewModel, s: InboxSettings, item: InboxItem, close: () -> Unit, reply: (ComposeDraft) -> Unit) {
    val context = LocalContext.current
    var opened by remember { mutableStateOf<MailService.Opened?>(null) }
    var current by remember { mutableStateOf(item) }
    var error by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var blockAsk by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val canEdit = vm.can(AccessAction.EDIT, "inbox")
    LaunchedEffect(item.id) {
        runCatching { withContext(Dispatchers.IO) { MailService.open(vm, s, item) } }
            .onSuccess {
                opened = it
                current = it.item
                it.warning?.let { w -> error = w }
                vm.inboxRevision++
            }
            .onFailure { error = it.message.orEmpty() }
    }
    fun perform(work: () -> Unit) {
        if (busy) return
        busy = true
        vm.launch {
            try {
                withContext(Dispatchers.IO) { work() }
                vm.refresh()
                vm.inboxRevision++
            } finally {
                busy = false
            }
        }
    }
    val mail = opened?.mail
    val isSaved = current.folder == s.resolvedSaved
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
            Text(current.subject, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2)
            if (vm.canExport("inbox")) {
                IconButton(onClick = { reply(ComposeDraft.reply(vm, current, mail, forward = false)) }) { Icon(UgsIcons.Reply, "Antworten") }
                IconButton(onClick = { reply(ComposeDraft.reply(vm, current, mail, forward = true)) }, enabled = mail != null) { Icon(UgsIcons.Forward, "Weiterleiten") }
                IconButton(
                    onClick = {
                        val m = mail ?: return@IconButton
                        vm.run {
                            val pdf = withContext(Dispatchers.IO) {
                                MailService.printPdf(vm.app, m.subject, listOf("Von" to m.sender, "An" to m.recipient, "Datum" to dateTime(m.date.time)), m.body, m.attachments.map { it.name })
                            }
                            withContext(Dispatchers.IO) { vm.repo.exported("E-Mail gedruckt: ${m.subject}") }
                            MailService.print(context, "E-Mail", pdf)
                        }
                    },
                    enabled = mail != null,
                ) {
                    Icon(UgsIcons.Print, "E-Mail drucken")
                }
            }
            if (canEdit)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Weitere Aktionen") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(if (current.unread) "Als gelesen markieren" else "Als ungelesen markieren") }, onClick = { menu = false; perform { MailService.setUnread(vm, s, current, !current.unread) }; current = current.copy(unread = !current.unread) })
                        DropdownMenuItem(text = { Text(if (current.important) "Markierung entfernen" else "Als wichtig markieren") }, onClick = { menu = false; perform { MailService.setImportant(vm, s, current, !current.important) }; current = current.copy(important = !current.important) })
                        inboxColors.forEach { (k, v) -> DropdownMenuItem(text = { Text("Farbe: ${v.first}") }, onClick = { menu = false; perform { MailService.color(vm, current, k) } }) }
                        DropdownMenuItem(text = { Text(if (isSaved) "Zurück in Posteingang" else "In Gespeichert ablegen") }, onClick = { menu = false; perform { MailService.move(vm, s, current, if (isSaved) "INBOX" else s.resolvedSaved) }; close() })
                        for (f in InboxFolder.entries.filter { s.folder(it) != current.folder })
                            DropdownMenuItem(text = { Text("Verschieben: ${f.title}") }, onClick = { menu = false; perform { MailService.move(vm, s, current, s.folder(f)) }; close() })
                        val isBlocked = current.senderAddress in MailService.blocked(vm, s.accountId)
                        DropdownMenuItem(
                            text = { Text(if (isBlocked) "Absender freigeben" else "Absender blockieren") },
                            onClick = {
                                menu = false
                                if (isBlocked) perform { MailService.unblock(vm, s.accountId, current.senderAddress) } else blockAsk = true
                            },
                        )
                    }
                }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(current.sender.ifBlank { current.senderAddress }, fontWeight = FontWeight.SemiBold)
                Text("${current.senderAddress} · ${dateTime(current.date)}", style = MaterialTheme.typography.bodySmall)
                if (current.recipient.isNotBlank()) Text("An: ${current.recipient}", style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, color = Color(0xFFFF9500), style = MaterialTheme.typography.bodySmall)
            }
            if (mail == null && error.isBlank()) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            mail?.attachments?.takeIf { it.isNotEmpty() }?.let { parts ->
                item { SectionHeader("Anhänge (${parts.size})") }
                items(parts.indices.toList()) { i ->
                    val p = parts[i]
                    val stampable = p.contentType == "application/pdf" || p.contentType.startsWith("image/") || p.name.lowercase().endsWith(".pdf")
                    ListItem(
                        headlineContent = { Text(p.name) },
                        supportingContent = { Text("${p.contentType} · ${android.text.format.Formatter.formatShortFileSize(vm.app, p.data.size.toLong())}") },
                        leadingContent = { Icon(UgsIcons.Attachment, null) },
                        trailingContent = {
                            Row {
                                if (stampable && vm.can(AccessAction.VIEW, "contract"))
                                    IconButton(
                                        onClick = {
                                            val w = StampMail.recognize(vm, current, p.name, mail)
                                            vm.stampHandoff = StampHandoff(p.name, p.data, p.contentType, mail.replyTo.ifBlank { current.senderAddress }, current.sender, w?.id)
                                            vm.navigation = "stamp"
                                        }
                                    ) {
                                        Icon(UgsIcons.Signature, "In „Vertrag stempeln“ öffnen")
                                    }
                                if (vm.canExport("inbox"))
                                    IconButton(
                                        onClick = {
                                            vm.run {
                                                val f = vm.pdf.exportFile(p.name.ifBlank { "Anhang" })
                                                withContext(Dispatchers.IO) {
                                                    f.writeBytes(p.data)
                                                    vm.repo.exported("E-Mail-Anhang: ${p.name}")
                                                }
                                                if (p.contentType == "application/pdf") vm.preview = f else vm.pdf.open(f, p.contentType)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Info, "Öffnen")
                                    }
                            }
                        },
                    )
                }
            }
            item { SelectionContainer { Text(mail?.body ?: current.body) } }
        }
    }
    if (blockAsk)
        AlertDialog(
            onDismissRequest = { blockAsk = false },
            title = { Text("Absender blockieren?") },
            text = { Text("E-Mails von ${current.senderAddress} werden beim Abrufen nach „${s.junkFolder}“ verschoben.") },
            confirmButton = {
                TextButton(onClick = {
                    blockAsk = false
                    perform { MailService.move(vm, s, current, s.junkFolder, block = true) }
                    close()
                }) { Text("Blockieren") }
            },
            dismissButton = { TextButton(onClick = { blockAsk = false }) { Text("Abbrechen") } },
        )
}

data class ComposeDraft(
    val to: String = "",
    val subject: String = "",
    val body: String = "",
    val attachments: List<Mail.Attachment> = emptyList(),
    val inReplyTo: String = "",
    val references: List<String> = emptyList(),
) {
    companion object {
        fun signature(vm: UGSViewModel) = Mail.signature(vm.company)

        fun new(vm: UGSViewModel) = ComposeDraft(body = "\n\n" + signature(vm))

        fun reply(vm: UGSViewModel, item: InboxItem, mail: ParsedMail?, forward: Boolean): ComposeDraft {
            val subject = item.subject
            val quoted = (mail?.body ?: item.body).lines().joinToString("\n") { "> $it" }
            val header = "Am ${dateTime(item.date)} schrieb ${item.sender.ifBlank { item.senderAddress }}:"
            return if (forward)
                ComposeDraft(
                    subject = if (subject.startsWith("WG:", true) || subject.startsWith("Fwd:", true)) subject else "WG: $subject",
                    body = "\n\n${signature(vm)}\n\n---------- Weitergeleitete Nachricht ----------\nVon: ${item.sender} <${item.senderAddress}>\nDatum: ${dateTime(item.date)}\nBetreff: $subject\n\n${mail?.body ?: item.body}",
                    attachments = mail?.attachments?.map { Mail.Attachment(it.name, it.data, it.contentType) }.orEmpty(),
                )
            else
                ComposeDraft(
                    to = item.replyTo.ifBlank { item.senderAddress },
                    subject = if (subject.startsWith("AW:", true) || subject.startsWith("Re:", true)) subject else "AW: $subject",
                    body = "\n\n${signature(vm)}\n\n$header\n$quoted",
                    inReplyTo = item.messageId,
                    references = item.references + listOfNotNull(item.messageId.takeIf { it.isNotBlank() }),
                )
        }
    }
}

@Composable
fun ComposeDialog(vm: UGSViewModel, initial: ComposeDraft, source: String, close: () -> Unit) {
    var d by remember { mutableStateOf(initial) }
    var sent by remember { mutableStateOf(false) }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            vm.run {
                val added =
                    withContext(Dispatchers.IO) {
                        uris.map { u -> Mail.Attachment(ImportService.name(vm.app, u), ImportService.read(vm.app, u, FileLimits.MAIL_BYTES), vm.app.contentResolver.getType(u) ?: "application/octet-stream") }
                    }
                require((d.attachments + added).sumOf { it.data.size.toLong() } <= FileLimits.MAIL_BYTES) { "Die Anhänge dürfen zusammen höchstens ${FileLimits.MAIL_LABEL} groß sein." }
                d = d.copy(attachments = d.attachments + added)
            }
        }
    FullScreen(if (sent) "Gesendet" else "Neue E-Mail", close) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { TextRow("An", d.to) { d = d.copy(to = it) } }
            item { TextRow("Betreff", d.subject) { d = d.copy(subject = it) } }
            item { TextRow("Nachricht", d.body, 10) { d = d.copy(body = it) } }
            items(d.attachments.indices.toList()) { i ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(UgsIcons.Attachment, null)
                    Text(d.attachments[i].name, Modifier.weight(1f).padding(start = 8.dp))
                    IconButton(onClick = { d = d.copy(attachments = d.attachments.filterIndexed { n, _ -> n != i }) }) { Icon(Icons.Default.Delete, "Anhang entfernen") }
                }
            }
            item { OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text("Anhang hinzufügen (zusammen max. ${FileLimits.MAIL_LABEL})") } }
            if (MailService.smtp(vm).host.isBlank())
                item { Text("Der E-Mail-Versand ist noch nicht eingerichtet: Einstellungen → E-Mail-Versand.", color = MaterialTheme.colorScheme.error) }
        }
        Button(
            onClick = {
                vm.run {
                    require(vm.canExport(source)) { "Keine Berechtigung zum Senden." }
                    withContext(Dispatchers.IO) { MailService.send(vm, d.to, d.subject, d.body, d.attachments, source, inReplyTo = d.inReplyTo, references = d.references) }
                    vm.refresh()
                    sent = true
                    vm.notice = "Der SMTP-Server hat die Nachricht angenommen. Dies ist keine Zustell- oder Lesebestätigung."
                    close()
                }
            },
            enabled = !sent && !vm.busy,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, null)
            Spacer(Modifier.width(8.dp))
            Text("Senden")
        }
    }
}

val sentStatus =
    mapOf(
        "sent" to ("Gesendet (SMTP)" to "Der SMTP-Server hat die Nachricht angenommen. Dies ist keine Zustell- oder Lesebestätigung."),
        "failed" to ("Versandfehler" to "Beim Versand ist ein Fehler aufgetreten. Vor einem erneuten Versand bitte prüfen, ob die Nachricht dennoch angekommen ist."),
        "unconfirmed" to ("Nicht bestätigt" to "Der Versand läuft noch oder sein Ergebnis wurde nicht gespeichert. Vor einem erneuten Versand bitte den tatsächlichen Versand prüfen."),
        "handedOff" to ("An Mail-Programm übergeben" to "Ob die Nachricht dort gesendet oder verworfen wurde, kann die App nicht feststellen."),
    )

@Composable
fun SentMailScreen(vm: UGSViewModel) {
    val context = LocalContext.current
    var search by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("Alle") }
    var open by remember { mutableStateOf<Entry?>(null) }
    var compose by remember { mutableStateOf<ComposeDraft?>(null) }
    val all = vm.rows(Kind.SENT_MAIL).sortedByDescending { it["completedAt"].ifBlank { it["createdAt"] } }
    val visible =
        all.filter {
            (search.isBlank() || listOf(it["recipient"], it["subject"], it["body"], it["attachmentNames"]).any { v -> v.contains(search, true) }) &&
                (status == "Alle" || sentStatus[it["status"]]?.first == status)
        }
    open?.let { e ->
        BackHandler { open = null }
        val attachments = e["attachmentNames"].split("\n").filter { it.isNotBlank() }
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { open = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                Text(e["subject"], Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (vm.canExport("sent_mail")) {
                    IconButton(onClick = {
                        vm.run {
                            val parsed = if (e["blob"].isNotBlank()) withContext(Dispatchers.IO) { Mime.parse(vm.repo.blobBytes(e["blob"])) } else null
                            compose = ComposeDraft(subject = "WG: ${e["subject"]}", body = "\n\n${Mail.signature(vm.company)}\n\n---------- Weitergeleitete Nachricht ----------\nAn: ${e["recipient"]}\nBetreff: ${e["subject"]}\n\n${e["body"]}", attachments = parsed?.attachments?.map { Mail.Attachment(it.name, it.data, it.contentType) }.orEmpty())
                        }
                    }) { Icon(UgsIcons.Forward, "Weiterleiten") }
                    IconButton(onClick = { compose = ComposeDraft(to = e["recipient"], subject = "AW: ${e["subject"]}", body = "\n\n${Mail.signature(vm.company)}") }) { Icon(UgsIcons.Reply, "Erneut schreiben") }
                    IconButton(onClick = {
                        vm.run {
                            val pdf = withContext(Dispatchers.IO) { MailService.printPdf(vm.app, e["subject"], listOf("Von" to e["sender"], "An" to e["recipient"], "Datum" to DateText.german(e["createdAt"].take(10)), "Status" to (sentStatus[e["status"]]?.first ?: e["status"])), e["body"], attachments) }
                            MailService.print(context, "E-Mail", pdf)
                        }
                    }) { Icon(UgsIcons.Print, "E-Mail drucken") }
                }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("An: ${e["recipient"]}")
                Text("Von: ${e["sender"]}", style = MaterialTheme.typography.bodySmall)
                Text("Erstellt: ${e["createdAt"].replace("T", " ").take(16)}${if (e["completedAt"].isNotBlank()) " · Abgeschlossen: ${e["completedAt"].replace("T", " ").take(16)}" else ""}", style = MaterialTheme.typography.bodySmall)
                val st = sentStatus[e["status"]]
                Text(st?.first ?: e["status"], fontWeight = FontWeight.SemiBold, color = if (e["status"] == "sent") Color(0xFF34C759) else Color(0xFFFF9500))
                Text(st?.second.orEmpty(), style = MaterialTheme.typography.bodySmall)
                if (attachments.isNotEmpty()) {
                    SectionHeader("Anhänge")
                    attachments.forEach { name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(UgsIcons.Attachment, null)
                            Text(name, Modifier.weight(1f).padding(start = 8.dp))
                            if (e["blob"].isNotBlank() && vm.canExport("sent_mail"))
                                TextButton(onClick = {
                                    vm.run {
                                        val part = withContext(Dispatchers.IO) { Mime.parse(vm.repo.blobBytes(e["blob"])).attachments.firstOrNull { Mail.asciiFilename(it.name) == Mail.asciiFilename(name) || it.name == name } } ?: error("Anhang nicht im Archiv.")
                                        val f = vm.pdf.exportFile(name)
                                        withContext(Dispatchers.IO) { f.writeBytes(part.data) }
                                        if (name.lowercase().endsWith(".pdf")) vm.preview = f else vm.pdf.open(f, part.contentType)
                                    }
                                }) { Text("Öffnen") }
                        }
                    }
                }
                SectionHeader("Nachricht")
                SelectionContainer { Text(e["body"]) }
            }
        }
        compose?.let { d -> ComposeDialog(vm, d, "sent_mail") { compose = null } }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(search, { search = it }, placeholder = { Text("Empfänger, Betreff, Anhang") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, modifier = Modifier.weight(1f))
            if (vm.canExport("sent_mail")) IconButton(onClick = { compose = ComposeDraft.new(vm) }) { Icon(Icons.Default.Edit, "Neue E-Mail") }
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { FilterMenu("Status", status, sentStatus.values.map { it.first }) { status = it } }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp)) {
            if (visible.isEmpty()) item { EmptyState("Keine gesendeten E-Mails", "Versendete Verträge, Sofortmeldungen und Antworten erscheinen hier.") }
            items(visible, key = { it.id }) { e ->
                ListItem(
                    headlineContent = { Text(e["recipient"], maxLines = 1) },
                    supportingContent = { Text(e["subject"], maxLines = 1) },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(e["completedAt"].ifBlank { e["createdAt"] }.let { DateText.german(it.take(10)) }, style = MaterialTheme.typography.labelSmall)
                            Text(sentStatus[e["status"]]?.first ?: e["status"], style = MaterialTheme.typography.labelSmall, color = if (e["status"] == "sent") Color(0xFF34C759) else Color(0xFFFF9500))
                        }
                    },
                    leadingContent = { if (e["attachmentNames"].isNotBlank()) Icon(UgsIcons.Attachment, null) else Icon(Icons.Default.Email, null) },
                    modifier = Modifier.clickable { open = e },
                )
                HorizontalDivider()
            }
        }
    }
    compose?.let { d -> ComposeDialog(vm, d, "sent_mail") { compose = null } }
}

@Composable
fun MailSettingsScreen(vm: UGSViewModel, back: () -> Unit) {
    val c = vm.company
    val v = remember(c) {
        mutableStateMapOf<String, String>().apply {
            for (k in listOf("mail.senderName", "mail.senderAddress", "mail.host", "mail.port", "mail.username", "mail.security", "mail.signatureStyle", "mail.signature", "mail.html", "inbox.host", "inbox.port", "inbox.username", "inbox.junk", "inbox.spam", "inbox.saved", "inbox.automatic", "inbox.interval", "inbox.notify"))
                put(k, c[k].orEmpty())
            if (this["mail.security"].isNullOrBlank()) put("mail.security", "implicit")
            if (this["mail.signatureStyle"].isNullOrBlank()) put("mail.signatureStyle", Mail.SignatureStyle.FIRMA_KOMPLETT.name)
            if (this["inbox.port"].isNullOrBlank()) put("inbox.port", "993")
            if (this["inbox.junk"].isNullOrBlank()) put("inbox.junk", "Junk")
            if (this["inbox.interval"].isNullOrBlank()) put("inbox.interval", "5")
        }
    }
    var smtpPassword by remember { mutableStateOf("") }
    var imapPassword by remember { mutableStateOf("") }
    val editable = vm.can(AccessAction.EDIT, "settings")
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (!granted) vm.notice = "Mitteilungen sind in den Android-Einstellungen ausgeschaltet." }
    fun save(after: (() -> Unit)? = null) {
        vm.run {
            withContext(Dispatchers.IO) {
                vm.repo.settings(v.toMap())
                if (smtpPassword.isNotEmpty()) vm.repo.secret("smtp", smtpPassword)
                val inbox = InboxSettings.of(v.toMap())
                if (inbox.configured) inbox.validate()
                if (imapPassword.isNotEmpty() && inbox.configured) vm.repo.secret("imap-${inbox.accountId}", imapPassword)
            }
            smtpPassword = ""
            imapPassword = ""
            vm.refresh()
            vm.notice = "E-Mail-Einstellungen gespeichert."
            after?.invoke()
        }
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackRow("E-Mail-Versand und Posteingang", back) }
        item { SectionHeader("Absender (SMTP)") }
        item { TextRow("Absendername", v["mail.senderName"].orEmpty()) { v["mail.senderName"] = it } }
        item { TextRow("Absenderadresse", v["mail.senderAddress"].orEmpty()) { v["mail.senderAddress"] = it } }
        item { TextRow("SMTP-Server", v["mail.host"].orEmpty()) { v["mail.host"] = it } }
        item {
            ChoiceInput("Verschlüsselung", v["mail.security"].orEmpty(), listOf("implicit" to "SSL/TLS (Port 465)", "starttls" to "STARTTLS (Port 587)")) {
                v["mail.security"] = it
                v["mail.port"] = if (it == "implicit") "465" else "587"
            }
        }
        item { TextRow("Port", v["mail.port"].orEmpty().ifBlank { if (v["mail.security"] == "starttls") "587" else "465" }) { v["mail.port"] = it } }
        item { TextRow("Benutzername", v["mail.username"].orEmpty()) { v["mail.username"] = it } }
        item { PasswordInput(smtpPassword, { smtpPassword = it }, "SMTP-Passwort / App-Passwort (leer = unverändert)") }
        item { Text("Bei Gmail, GMX und web.de wird ein App-Passwort benötigt. Passwörter liegen nur in der verschlüsselten Datenbank.", style = MaterialTheme.typography.bodySmall) }
        item { SectionHeader("Signatur und Vorlage") }
        item { ChoiceInput("Signatur", v["mail.signatureStyle"].orEmpty(), Mail.SignatureStyle.entries.map { it.name to it.title }) { v["mail.signatureStyle"] = it } }
        if (v["mail.signatureStyle"] == Mail.SignatureStyle.EIGENER.name) item { TextRow("Eigene Signatur", v["mail.signature"].orEmpty(), 4) { v["mail.signature"] = it } }
        item { Text(Mail.signature(c + v), style = MaterialTheme.typography.bodySmall) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(v["mail.html"] != "false", { v["mail.html"] = it.toString() })
                Text("HTML-Vorlage mit Logo („A · Klassisch“)", Modifier.padding(start = 10.dp))
            }
        }
        if (editable)
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { save() }) { Text("Speichern") }
                    OutlinedButton(onClick = {
                        save {
                            vm.run {
                                withContext(Dispatchers.IO) { MailService.testMail(vm) }
                                vm.notice = "Testmail an ${MailService.smtp(vm).senderAddress} wurde vom Server angenommen."
                            }
                        }
                    }) { Text("Testmail senden") }
                }
            }
        item { SectionHeader("Posteingang (IMAP, SSL/TLS)") }
        item { TextRow("IMAP-Server", v["inbox.host"].orEmpty()) { v["inbox.host"] = it } }
        item { TextRow("Port (normalerweise 993)", v["inbox.port"].orEmpty()) { v["inbox.port"] = it } }
        item { TextRow("Benutzername", v["inbox.username"].orEmpty()) { v["inbox.username"] = it } }
        item { PasswordInput(imapPassword, { imapPassword = it }, "IMAP-App-Passwort (leer = unverändert)") }
        item { TextRow("Junk-Ordner", v["inbox.junk"].orEmpty()) { v["inbox.junk"] = it } }
        item { TextRow("Spam-Ordner (leer = wie Junk)", v["inbox.spam"].orEmpty()) { v["inbox.spam"] = it } }
        item { TextRow("Gespeichert-Ordner (leer = Archive)", v["inbox.saved"].orEmpty()) { v["inbox.saved"] = it } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(v["inbox.automatic"] != "false", { v["inbox.automatic"] = it.toString() })
                Text("Automatisch abrufen", Modifier.padding(start = 10.dp))
            }
        }
        item { ChoiceInput("Intervall", v["inbox.interval"].orEmpty(), listOf(1, 5, 10, 15, 30).map { "$it" to "$it Minuten" }) { v["inbox.interval"] = it } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(v["inbox.notify"] == "true", {
                    v["inbox.notify"] = it.toString()
                    if (it && Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                })
                Text("Mitteilungen bei neuen E-Mails (ohne Absender und Betreff)", Modifier.padding(start = 10.dp))
            }
        }
        if (editable)
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { save() }) { Text("Speichern") }
                    OutlinedButton(onClick = {
                        vm.run {
                            val s = InboxSettings.of(v.toMap())
                            val pw = imapPassword.ifEmpty { withContext(Dispatchers.IO) { vm.repo.secret("imap-${s.accountId}") } }
                            require(pw.isNotEmpty()) { "Bitte das IMAP-App-Passwort eintragen." }
                            withContext(Dispatchers.IO) { MailService.test(s, pw) }
                            vm.notice = "Verbindung zum Posteingang und zum Junk-Ordner erfolgreich."
                        }
                    }) { Text("Verbindung prüfen") }
                }
            }
        if (!editable) item { Text("Nur Benutzer mit Einstellungsrechten können die E-Mail-Einstellungen ändern.", color = MaterialTheme.colorScheme.error) }
    }
}

/** Mitarbeiter zu einem eingegangenen Vertrag erkennen: Adresse, Nummer oder Name. */
object StampMail {
    fun recognize(vm: UGSViewModel, item: InboxItem, fileName: String, mail: ParsedMail?): Entry? {
        val workers = vm.rows(Kind.WORKER)
        val address = (mail?.replyTo?.ifBlank { null } ?: item.senderAddress).lowercase()
        workers.firstOrNull { it["email"].isNotBlank() && it["email"].trim().lowercase() == address }?.let { return it }
        workers.firstOrNull { it["email"].isNotBlank() && it["email"].trim().lowercase() == item.senderAddress.lowercase() }?.let { return it }
        val tokens = Regex("[\\p{L}\\p{N}]+").findAll(fileName.substringBeforeLast('.')).map { it.value.lowercase() }.toSet()
        workers.firstOrNull { it["bewacherId"].isNotBlank() && it["bewacherId"].lowercase() in tokens }?.let { return it }
        workers.firstOrNull { it["personnelNumber"].isNotBlank() && it["personnelNumber"] in tokens }?.let { return it }
        workers.firstOrNull { w -> w["lastName"].isNotBlank() && w["lastName"].lowercase() in tokens && (w["firstName"].isBlank() || w["firstName"].lowercase() in tokens) }?.let { return it }
        val sender = item.sender.lowercase()
        return workers.firstOrNull { w -> w["lastName"].isNotBlank() && sender.contains(w["lastName"].lowercase()) && sender.contains(w["firstName"].lowercase()) }
    }
}
