package de.ugs.sicherheit

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.FileOutputStream
import java.time.Instant
import java.util.Base64
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject

/** Nachricht im Posteingang-Zwischenspeicher. */
data class InboxItem(
    val id: String,
    val account: String,
    val folder: String,
    val validity: Long,
    val uid: Long,
    val fingerprint: String,
    val sender: String,
    val senderAddress: String,
    val recipient: String,
    val subject: String,
    val date: Long,
    val body: String,
    val attachments: List<String>,
    val important: Boolean,
    val unread: Boolean,
    val replyTo: String,
    val messageId: String,
    val references: List<String>,
    val blob: String,
) {
    fun json(): String =
        JSONObject()
            .put("validity", validity).put("uid", uid).put("fingerprint", fingerprint).put("sender", sender).put("senderAddress", senderAddress)
            .put("recipient", recipient).put("subject", subject).put("date", date).put("body", body).put("attachments", JSONArray(attachments))
            .put("important", important).put("unread", unread).put("replyTo", replyTo).put("messageId", messageId).put("references", JSONArray(references))
            .toString()

    fun row() = Repository.InboxRow(id, account, folder, uid, Instant.ofEpochMilli(date).toString(), json(), blob, !unread)

    companion object {
        fun from(r: Repository.InboxRow): InboxItem {
            val o = JSONObject(r.payload)
            fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            return InboxItem(
                r.id, r.account, r.folder, o.optLong("validity"), r.uid, o.optString("fingerprint"), o.optString("sender"), o.optString("senderAddress"),
                o.optString("recipient"), o.optString("subject"), o.optLong("date"), o.optString("body"), list("attachments"), o.optBoolean("important"),
                o.optBoolean("unread"), o.optString("replyTo"), o.optString("messageId"), list("references"), r.blob,
            )
        }
    }
}

object MailService {
    fun smtp(vm: UGSViewModel) = SmtpSettings.of(vm.company)

    fun inbox(vm: UGSViewModel) = InboxSettings.of(vm.company)

    private fun logo(c: Context): Mail.Inline? =
        runCatching { Mail.Inline("ugs-logo", "image/png", "UGS-Sicherheit-Logo.png", c.assets.open("branding/ugs-mail-logo.png").use { it.readBytes() }) }.getOrNull()

    /**
     * Sendet direkt über SMTP. Der Verlauf wird vor dem Versand reserviert, damit ein
     * abgebrochener Versand ehrlich als „nicht bestätigt“ erscheint und nie als Erfolg.
     * Blockierend – nur aus Dispatchers.IO aufrufen.
     */
    fun send(
        vm: UGSViewModel,
        to: String,
        subject: String,
        body: String,
        attachments: List<Mail.Attachment> = emptyList(),
        source: String = "",
        layout: Mail.Layout = Mail.Layout(),
        inReplyTo: String = "",
        references: List<String> = emptyList(),
        html: Boolean = true,
    ): Entry {
        val target = to.trim()
        if (!Mail.isAddress(target)) throw Mail.error("Bitte eine gültige E-Mail-Adresse eingeben.")
        if (subject.isBlank()) throw Mail.error("Bitte einen Betreff eingeben.")
        if (subject.contains('\n') || subject.contains('\r')) throw Mail.error("Der Betreff darf keinen Zeilenumbruch enthalten.")
        val settings = smtp(vm)
        settings.validate()
        val password = vm.repo.secret("smtp")
        if (password.isEmpty()) throw Mail.error("Es ist noch kein SMTP-Passwort hinterlegt. Bitte unter Einstellungen → E-Mail-Versand eintragen und speichern.")
        if (attachments.sumOf { it.data.size.toLong() } > FileLimits.MAIL_BYTES)
            throw Mail.error("Die Anhänge sind zusammen größer als ${FileLimits.MAIL_LABEL} und zu groß für eine E-Mail. Bitte die Datei anders weitergeben (Teilen).")
        val useHtml = html && vm.company["mail.html"] != "false"
        val inline = if (useHtml) listOfNotNull(logo(vm.app)) else emptyList()
        val finalLayout = if (layout.attachments.isEmpty()) layout.copy(attachments = attachments.map { it.name }) else layout
        val raw =
            Mail.build(
                settings.senderName, settings.senderAddress, target, subject, body,
                if (useHtml) Mail.html(body, vm.company, settings.senderAddress, inline.isNotEmpty(), finalLayout) else null,
                attachments, inline, inReplyTo, references, Date(),
            )
        var entry =
            vm.repo.recordMail(
                Entry(
                    kind = Kind.SENT_MAIL,
                    fields =
                        mapOf(
                            "title" to subject, "subject" to subject, "recipient" to target, "sender" to settings.senderAddress, "body" to body,
                            "attachmentNames" to attachments.joinToString("\n") { it.name }, "mode" to "smtp", "status" to "unconfirmed",
                            "createdAt" to Instant.now().toString(), "source" to source,
                        ),
                ),
                raw,
            )
        try {
            SmtpClient.send(settings, password, target, raw)
        } catch (e: Exception) {
            runCatching { vm.repo.recordMail(entry.copy(fields = entry.fields + mapOf("status" to "failed", "completedAt" to Instant.now().toString()))) }
            throw e
        }
        entry =
            try {
                vm.repo.recordMail(entry.copy(fields = entry.fields + mapOf("status" to "sent", "completedAt" to Instant.now().toString())))
            } catch (e: Exception) {
                throw Mail.error("Der Server hat die Nachricht angenommen. Der Verlauf konnte nicht bestätigt werden. Bitte nicht erneut senden; zuerst den Versand prüfen.")
            }
        return entry
    }

    fun testMail(vm: UGSViewModel) {
        val s = smtp(vm)
        send(
            vm,
            s.senderAddress,
            "Testmail – UGS Personalverwaltung",
            "Diese Testmail wurde von der UGS-Personalverwaltung (Android) gesendet.\n\nWenn Sie sie erhalten, ist der E-Mail-Versand richtig eingerichtet.\n\nSo sieht Ihre Vorlage aus: Kopfbereich mit Logo, Nachrichtentext und Fußzeile mit den Firmendaten.\n",
            source = "test",
        )
    }

    // MARK: Posteingang

    fun password(vm: UGSViewModel, s: InboxSettings): String {
        s.validate()
        return vm.repo.secret("imap-${s.accountId}").ifEmpty { throw Mail.error("Bitte unter Einstellungen → E-Mail → Posteingang das IMAP-App-Passwort speichern.") }
    }

    fun blocked(vm: UGSViewModel, account: String): Set<String> =
        runCatching { JSONArray(vm.company["inbox.meta.blocked.$account"].orEmpty().ifBlank { "[]" }).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrDefault(emptySet())

    fun colors(vm: UGSViewModel, account: String): Map<String, String> =
        runCatching { JSONObject(vm.company["inbox.meta.colors.$account"].orEmpty().ifBlank { "{}" }).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } }.getOrDefault(emptyMap())

    fun folderKey(folder: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(folder.toByteArray())

    data class SyncResult(val status: String, val newUnread: Int)

    /** Lädt je Abruf die neuesten 50 noch nicht gespeicherten Nachrichten. Blockierend. */
    fun synchronize(vm: UGSViewModel, s: InboxSettings, folder: String): SyncResult {
        val password = password(vm, s)
        val mayEdit = vm.can(AccessAction.EDIT, "inbox")
        ImapClient(s, password).use { c ->
            val validity = c.select(folder, readOnly = !mayEdit)
            val server = c.search()
            val cached = vm.repo.inbox(s.accountId, folder, 100000).map { InboxItem.from(it) }
            val stale = cached.filter { it.validity != validity }
            if (stale.isNotEmpty()) vm.repo.dropInbox(s.accountId, folder, stale.map { it.uid })
            val known = cached.filter { it.validity == validity }.associateBy { it.uid }
            val pending = server.filter { it !in known }.take(50)
            val active = (server.filter { it in known } + pending).sortedDescending()
            var downloaded = 0
            var newUnread = 0
            for (chunk in active.chunked(50)) {
                val flags = c.flags(chunk)
                for (uid in chunk) {
                    val f = flags[uid] ?: continue
                    val old = known[uid]
                    if (old != null) {
                        if (old.unread != f.unread || old.important != f.important) vm.repo.putInbox(old.copy(unread = f.unread, important = f.important).row())
                        continue
                    }
                    val headersOnly = f.size > 10L * 1024 * 1024
                    val raw = c.message(uid, headersOnly)
                    val p = Mime.parse(raw, headersOnly)
                    val blob = if (headersOnly) "" else vm.repo.storeBlob(raw)
                    val item =
                        InboxItem(
                            "${s.accountId}:${folderKey(folder)}:$validity:$uid", s.accountId, folder, validity, uid, p.fingerprint, p.sender, p.senderAddress,
                            p.recipient, p.subject, p.date.time, p.body.take(200000), p.attachments.map { it.name }, f.important, f.unread, p.replyTo, p.messageId,
                            p.references, blob,
                        )
                    vm.repo.putInbox(item.row())
                    downloaded++
                    if (f.unread) newUnread++
                }
            }
            vm.repo.dropInbox(s.accountId, folder, known.keys - server.toSet())
            var moved = 0
            if (mayEdit && folder.equals("INBOX", true)) {
                val blocked = blocked(vm, s.accountId)
                if (blocked.isNotEmpty())
                    for (item in vm.repo.inbox(s.accountId, folder, 100000).map { InboxItem.from(it) }.filter { it.senderAddress in blocked }) {
                        c.move(folder, item.validity, item.uid, s.junkFolder)
                        vm.repo.dropInbox(s.accountId, folder, listOf(item.uid))
                        moved++
                    }
            }
            val remaining = maxOf(0, server.size - known.keys.intersect(server.toSet()).size - downloaded)
            return SyncResult(
                "$downloaded E-Mails geladen · $moved blockierte Nachrichten nach Junk/Spam verschoben." + if (remaining > 0) " Noch $remaining ältere E-Mails: erneut abrufen." else "",
                newUnread,
            )
        }
    }

    data class Opened(val mail: ParsedMail, val item: InboxItem, val warning: String?)

    /** Öffnet eine Nachricht vollständig (bis 150 MB) und markiert sie als gelesen. Blockierend. */
    fun open(vm: UGSViewModel, s: InboxSettings, item: InboxItem): Opened {
        return try {
            ImapClient(s, password(vm, s), FileLimits.INCOMING_MAIL_BYTES).use { c ->
                c.verify(item.folder, item.validity, item.uid, readOnly = true)
                val flag = c.flags(listOf(item.uid))[item.uid] ?: throw Mail.error("Die Nachricht wurde verschoben oder gelöscht. Bitte erneut abrufen.")
                var confirmed = flag
                var warning: String? = null
                if (vm.can(AccessAction.EDIT, "inbox") && flag.unread)
                    runCatching { confirmed = c.setUnread(item.folder, item.validity, item.uid, false) }.onFailure { warning = it.message }
                if (flag.size > FileLimits.INCOMING_MAIL_BYTES) throw Mail.error("Diese Nachricht ist größer als ${FileLimits.INCOMING_MAIL_LABEL}. Bitte im Mail-Programm öffnen.")
                val raw = if (item.blob.isNotBlank()) runCatching { vm.repo.blobBytes(item.blob) }.getOrNull() ?: c.message(item.uid) else c.message(item.uid)
                val blob = item.blob.ifBlank { vm.repo.storeBlob(raw) }
                val updated = item.copy(unread = confirmed.unread, important = confirmed.important, blob = blob)
                vm.repo.putInbox(updated.row())
                Opened(Mime.parse(raw), updated, warning)
            }
        } catch (e: Exception) {
            if (item.blob.isBlank()) throw e
            Opened(Mime.parse(vm.repo.blobBytes(item.blob)), item, "Gespeicherte Nachricht. Der Server konnte nicht aktualisiert werden.")
        }
    }

    fun setUnread(vm: UGSViewModel, s: InboxSettings, item: InboxItem, value: Boolean) =
        ImapClient(s, password(vm, s)).use { c ->
            val f = c.setUnread(item.folder, item.validity, item.uid, value)
            vm.repo.putInbox(item.copy(unread = f.unread, important = f.important).row())
        }

    fun setImportant(vm: UGSViewModel, s: InboxSettings, item: InboxItem, value: Boolean) =
        ImapClient(s, password(vm, s)).use { c ->
            val f = c.setImportant(item.folder, item.validity, item.uid, value)
            vm.repo.putInbox(item.copy(unread = f.unread, important = f.important).row())
        }

    fun move(vm: UGSViewModel, s: InboxSettings, item: InboxItem, target: String, block: Boolean = false) {
        if (block) {
            val list = blocked(vm, s.accountId) + item.senderAddress
            vm.repo.inboxMeta("blocked.${s.accountId}", JSONArray(list.sorted()).toString())
        }
        try {
            ImapClient(s, password(vm, s)).use { c -> c.move(item.folder, item.validity, item.uid, target) }
            if (item.folder != target) vm.repo.dropInbox(item.account, item.folder, listOf(item.uid))
        } catch (e: Exception) {
            if (block) throw Mail.error("Die Blockierregel wurde gespeichert. Verschieben konnte nicht bestätigt werden; bitte erneut abrufen. ${e.message}")
            throw e
        }
    }

    fun unblock(vm: UGSViewModel, account: String, address: String) =
        vm.repo.inboxMeta("blocked.$account", JSONArray((blocked(vm, account) - address).sorted()).toString())

    fun color(vm: UGSViewModel, item: InboxItem, color: String) {
        val map = colors(vm, item.account).toMutableMap()
        if (color == "none") map.remove(item.fingerprint) else map[item.fingerprint] = color
        vm.repo.inboxMeta("colors.${item.account}", JSONObject(map as Map<*, *>).toString())
    }

    fun test(s: InboxSettings, password: String) {
        ImapClient(s, password).use { c ->
            c.select("INBOX", true)
            c.select(s.junkFolder, true)
        }
    }

    // MARK: Drucken

    /** Druckt Kopfzeilen, vollständigen Text und Anhangnamen; keine unverschlüsselte Exportdatei. */
    fun printPdf(c: Context, title: String, header: List<Pair<String, String>>, body: String, attachments: List<String>): ByteArray {
        val w = PdfWriter()
        var s = w.begin()
        var y = 40f
        PdfKit.logo(c, s, 420f, 24f, 130f)
        y += s.text(title, 44f, y + 20, 360f, 15f, Face.BOLD) + 30
        for ((k, v) in header) {
            s.text("$k:", 44f, y, 90f, 9.5f, Face.BOLD)
            y += maxOf(14f, s.text(v, 140f, y, 410f, 9.5f)) + 2
        }
        if (attachments.isNotEmpty()) {
            s.text("Anhänge:", 44f, y, 90f, 9.5f, Face.BOLD)
            y += s.text(attachments.joinToString("\n"), 140f, y, 410f, 9.5f) + 4
        }
        s.line(44f, y + 4, 551f, y + 4)
        y += 14
        for (para in body.split("\n")) {
            var rest = para.ifEmpty { " " }
            while (rest.isNotEmpty()) {
                val h = s.textHeight(rest, 507f, 10.5f)
                if (y + minOf(h, 20f) > 800) {
                    s = w.begin()
                    y = 44f
                }
                val room = 800 - y
                if (h <= room) {
                    y += s.text(rest, 44f, y, 507f, 10.5f) + 2
                    rest = ""
                } else {
                    // Absatz passt nicht: wortweise so viel wie möglich auf diese Seite.
                    val words = rest.split(" ")
                    var n = words.size
                    while (n > 1 && s.textHeight(words.take(n).joinToString(" "), 507f, 10.5f) > room) n--
                    y += s.text(words.take(n).joinToString(" "), 44f, y, 507f, 10.5f, maxHeight = room)
                    rest = words.drop(n).joinToString(" ")
                    s = w.begin()
                    y = 44f
                }
            }
        }
        return w.bytes()
    }

    fun print(c: Context, name: String, pdf: ByteArray) {
        val manager = c.getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print(
            name,
            object : PrintDocumentAdapter() {
                override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?) {
                    if (cancel?.isCanceled == true) callback.onLayoutCancelled()
                    else callback.onLayoutFinished(PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
                }

                override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancel: CancellationSignal?, callback: WriteResultCallback) {
                    try {
                        FileOutputStream(destination.fileDescriptor).use { it.write(pdf) }
                        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (e: Exception) {
                        callback.onWriteFailed(e.message)
                    }
                }
            },
            null,
        )
    }
}
