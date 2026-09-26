package de.ugs.sicherheit

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** TLS mit normaler Zertifikats- und Hostnamenprüfung; nichts wird abgeschaltet. */
object Tls {
    fun wrap(plain: Socket?, host: String, port: Int, timeoutMs: Int): SSLSocket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val socket =
            if (plain != null) factory.createSocket(plain, host, port, true) as SSLSocket
            else (factory.createSocket() as SSLSocket).also { it.connect(InetSocketAddress(host, port), timeoutMs) }
        socket.soTimeout = timeoutMs
        socket.startHandshake()
        check(HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.session)) {
            "Das Zertifikat passt nicht zum Server $host."
        }
        return socket
    }
}

data class SmtpSettings(
    val senderName: String,
    val senderAddress: String,
    val host: String,
    val port: Int,
    val username: String,
    /** true = SSL/TLS ab dem ersten Byte (465), false = STARTTLS (587). */
    val implicit: Boolean,
) {
    fun validate() {
        if (host.isBlank()) throw Mail.error("Es ist kein SMTP-Server eingetragen. Einstellungen → E-Mail-Versand.")
        if (!Mail.isAddress(senderAddress)) throw Mail.error("Die Absenderadresse fehlt oder ist ungültig. Einstellungen → E-Mail-Versand.")
        if (port !in 1..65535) throw Mail.error("Der SMTP-Port ist ungültig.")
    }

    companion object {
        fun of(s: Map<String, String>): SmtpSettings {
            val implicit = s["mail.security"] != "starttls"
            val port = s["mail.port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: if (implicit) 465 else 587
            return SmtpSettings(s["mail.senderName"].orEmpty(), s["mail.senderAddress"].orEmpty().trim(), s["mail.host"].orEmpty().trim(), port, s["mail.username"].orEmpty().trim(), implicit)
        }
    }
}

/** SMTP-Gespräch: EHLO, optional STARTTLS, AUTH, MAIL FROM, RCPT TO, DATA. */
class SmtpClient private constructor(private var socket: Socket, private val host: String) {
    private var input: InputStream = BufferedInputStream(socket.getInputStream())
    private var output: OutputStream = socket.getOutputStream()

    private fun line(): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) throw Mail.error("Der SMTP-Server hat die Verbindung beendet.")
            if (c == '\n'.code) break
            b.write(c)
            if (b.size() > 64 * 1024) throw Mail.error("SMTP-Antwort zu lang.")
        }
        return String(b.toByteArray(), Charsets.UTF_8).trimEnd('\r')
    }

    private fun reply(expect: Set<Int>, step: String): Pair<Int, String> {
        val lines = mutableListOf<String>()
        while (true) {
            val l = line()
            lines += l
            if (l.length < 4 || l[3] != '-') break
        }
        val joined = lines.joinToString("\n")
        val code = joined.take(3).toIntOrNull() ?: 0
        if (code !in expect) throw Mail.smtpFailure(step, code, joined)
        return code to joined
    }

    private fun command(text: String, expect: Set<Int>, step: String): Pair<Int, String> {
        output.write((text + "\r\n").toByteArray(Charsets.UTF_8))
        output.flush()
        return reply(expect, step)
    }

    private fun startTls() {
        socket = Tls.wrap(socket, host, socket.port, 45000)
        input = BufferedInputStream(socket.getInputStream())
        output = socket.getOutputStream()
    }

    private fun authenticate(capabilities: String, user: String, password: String) {
        val upper = capabilities.uppercase()
        if ("AUTH" in upper && "PLAIN" in upper) {
            val bytes = byteArrayOf(0) + user.toByteArray() + byteArrayOf(0) + password.toByteArray()
            command("AUTH PLAIN " + Base64.getEncoder().encodeToString(bytes), setOf(235), "Anmeldung")
        } else {
            command("AUTH LOGIN", setOf(334), "Anmeldung")
            command(Base64.getEncoder().encodeToString(user.toByteArray()), setOf(334), "Anmeldung")
            command(Base64.getEncoder().encodeToString(password.toByteArray()), setOf(235), "Anmeldung")
        }
    }

    /** RFC-5321-Dot-Stuffing, Abschluss mit CRLF.CRLF. */
    private fun writeBody(message: ByteArray) {
        val out = java.io.BufferedOutputStream(output, 64 * 1024)
        var lineStart = true
        var i = 0
        while (i < message.size) {
            val b = message[i]
            if (lineStart && b == '.'.code.toByte()) out.write('.'.code)
            if (b == '\n'.code.toByte() && (i == 0 || message[i - 1] != '\r'.code.toByte())) {
                out.write('\r'.code)
            }
            out.write(b.toInt())
            lineStart = b == '\n'.code.toByte()
            i++
        }
        if (!lineStart) out.write("\r\n".toByteArray())
        out.write(".\r\n".toByteArray())
        out.flush()
    }

    fun close() = runCatching { socket.close() }

    companion object {
        fun send(settings: SmtpSettings, password: String, recipient: String, message: ByteArray) {
            settings.validate()
            val target = recipient.trim()
            if (!Mail.isAddress(target)) throw Mail.error("Die E-Mail-Adresse „$target“ ist ungültig.")
            val socket =
                try {
                    if (settings.implicit) Tls.wrap(null, settings.host, settings.port, 45000)
                    else Socket().also {
                        it.connect(InetSocketAddress(settings.host, settings.port), 45000)
                        it.soTimeout = 45000
                    }
                } catch (e: java.io.IOException) {
                    throw Mail.error("Keine Verbindung zum SMTP-Server ${settings.host}:${settings.port}. Server, Port und Verschlüsselung prüfen.\n\n${e.message.orEmpty()}")
                }
            val c = SmtpClient(socket, settings.host)
            try {
                c.reply(setOf(220), "Begrüßung")
                val name = "UGS-Personal-Android"
                var caps = c.command("EHLO $name", setOf(250), "EHLO").second
                if (!settings.implicit) {
                    c.command("STARTTLS", setOf(220), "STARTTLS")
                    c.startTls()
                    caps = c.command("EHLO $name", setOf(250), "EHLO nach STARTTLS").second
                }
                if (settings.username.isNotEmpty()) c.authenticate(caps, settings.username, password)
                Mail.maximumSize(caps)?.let { max ->
                    if (message.size > max)
                        throw Mail.error(
                            "Die E-Mail ist ${Mail.megabytes(message.size.toLong())} groß; Ihr Mailserver nimmt höchstens ${Mail.megabytes(max)} an. Anhänge werden beim Senden etwa ein Drittel größer. Bitte weniger oder kleinere Anhänge senden oder die Datei über „Teilen“ weitergeben."
                        )
                }
                c.command("MAIL FROM:<${settings.senderAddress}>", setOf(250), "Absender")
                c.command("RCPT TO:<$target>", setOf(250, 251), "Empfänger")
                c.command("DATA", setOf(354), "Nachrichtenübergabe")
                // Große Nachrichten: mehr Zeit für Upload und Virenprüfung des Servers.
                c.socket.soTimeout = (45000 + minOf(300000L, message.size / 1000L)).toInt()
                c.writeBody(message)
                c.reply(setOf(250), "Nachricht")
                runCatching { c.command("QUIT", setOf(221, 250), "Abschluss") }
            } catch (e: java.net.SocketTimeoutException) {
                throw Mail.error("Der SMTP-Server ${settings.host}:${settings.port} antwortet nicht rechtzeitig.")
            } finally {
                c.close()
            }
        }
    }
}

data class InboxSettings(
    val host: String = "",
    val port: Int = 993,
    val username: String = "",
    val junkFolder: String = "Junk",
    val spamFolder: String = "",
    val savedFolder: String = "",
    val automatic: Boolean = true,
    val interval: Int = 5,
    val notify: Boolean = false,
) {
    val resolvedSpam
        get() = spamFolder.ifBlank { junkFolder }

    val resolvedSaved
        get() = savedFolder.ifBlank { "Archive" }

    val configured
        get() = host.isNotBlank() && username.isNotBlank()

    val accountId
        get() = Mail.sha256("${host.lowercase()}\n$port\n$username".toByteArray()).take(32)

    fun folder(f: InboxFolder) =
        when (f) {
            InboxFolder.INBOX -> "INBOX"
            InboxFolder.JUNK -> junkFolder
            InboxFolder.SPAM -> resolvedSpam
            InboxFolder.SAVED -> resolvedSaved
        }

    fun validate() {
        require(port != 587 && port != 465) {
            "Port $port ist für den SMTP-Versand vorgesehen. Für den Posteingang bitte den IMAP-Server Ihres Anbieters mit SSL/TLS verwenden, normalerweise Port 993."
        }
        require(port != 143) { "Dieser Posteingang unterstützt SSL/TLS, nicht IMAP-STARTTLS auf Port 143. Bitte normalerweise Port 993 verwenden." }
        require(host.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9.\\-]*[A-Za-z0-9])?")) && port in 1..65535 && username.isNotEmpty() && username.none { it == '\n' || it == '\r' || it == '\u0000' } && junkFolder.isNotEmpty() && !junkFolder.equals("INBOX", true)) {
            "Bitte IMAP-Server, Port, Benutzername und einen Junk-/Spam-Ordner ungleich INBOX eintragen."
        }
        for (f in listOf(junkFolder, resolvedSpam, resolvedSaved)) {
            require(f.isNotBlank() && !f.equals("INBOX", true)) { "Bitte für Spam und Gespeichert einen Serverordner ungleich INBOX eintragen." }
            ImapCodec.quoted(ImapCodec.mailbox(f))
        }
        require(resolvedSaved != junkFolder && resolvedSaved != resolvedSpam) { "Gespeichert benötigt einen eigenen Archivordner, getrennt von Junk und Spam." }
        ImapCodec.quoted(username)
    }

    companion object {
        fun of(s: Map<String, String>) =
            InboxSettings(
                s["inbox.host"].orEmpty(),
                s["inbox.port"]?.toIntOrNull() ?: 993,
                s["inbox.username"].orEmpty(),
                s["inbox.junk"].orEmpty().ifBlank { "Junk" },
                s["inbox.spam"].orEmpty(),
                s["inbox.saved"].orEmpty(),
                s["inbox.automatic"] != "false",
                s["inbox.interval"]?.toIntOrNull()?.takeIf { it in listOf(1, 5, 10, 15, 30) } ?: 5,
                s["inbox.notify"] == "true",
            )
    }
}

enum class InboxFolder(val title: String) {
    INBOX("Posteingang"),
    JUNK("Junk"),
    SPAM("Spam"),
    SAVED("Gespeichert"),
}

/** Serielle IMAP-Sitzung mit getaggten Antworten und exakt gerahmten Literalen. */
class ImapClient(settings: InboxSettings, password: String, private val literalLimit: Int = 12 * 1024 * 1024) : AutoCloseable {
    private val socket: SSLSocket
    private val input: InputStream
    private val output: OutputStream
    private var tag = 0

    data class Response(val lines: List<String>, val literals: List<ByteArray>)

    init {
        settings.validate()
        socket =
            try {
                Tls.wrap(null, settings.host, settings.port, 30000)
            } catch (e: java.net.SocketTimeoutException) {
                throw Mail.error("Zeitüberschreitung beim SSL/TLS-Verbindungsaufbau zu ${settings.host}:${settings.port}. Bitte IMAP-Server und SSL/TLS-Port (normalerweise 993) prüfen.")
            } catch (e: java.io.IOException) {
                throw Mail.error("Keine sichere IMAP-Verbindung möglich. Server, Port und Zertifikat prüfen.\n\n${e.message.orEmpty()}")
            }
        input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
        output = socket.getOutputStream()
        try {
            val greeting = line().uppercase()
            require(greeting.startsWith("* OK") || greeting.startsWith("* PREAUTH")) { "Der Server bietet keinen IMAP-Zugang an." }
            if (!greeting.startsWith("* PREAUTH"))
                command(
                    "LOGIN ${ImapCodec.quoted(settings.username)} ${ImapCodec.quoted(password)}",
                    "IMAP-Anmeldung fehlgeschlagen. Benutzername und App-Passwort prüfen. Reine OAuth-Anmeldung wird nicht unterstützt.",
                )
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun line(): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) throw Mail.error("Der IMAP-Server hat die Verbindung beendet.")
            if (c == '\n'.code) break
            b.write(c)
            if (b.size() > 8 * 1024 * 1024) throw Mail.error("IMAP-Antwort zu groß.")
        }
        return String(b.toByteArray(), Charsets.UTF_8).trimEnd('\r')
    }

    private fun literal(count: Int): ByteArray {
        if (count < 0 || count > literalLimit) throw Mail.error("Die Nachricht überschreitet die zulässige Downloadgröße.")
        val out = ByteArray(count)
        var off = 0
        while (off < count) {
            val n = input.read(out, off, count - off)
            if (n < 0) throw Mail.error("Unvollständige E-Mail.")
            off += n
        }
        return out
    }

    fun command(cmd: String, failure: String = "Der IMAP-Server hat die Aktion abgelehnt. Bitte Einstellungen und Ordnernamen prüfen."): Response {
        tag++
        val token = "UGS$tag"
        output.write("$token $cmd\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
        val lines = mutableListOf<String>()
        val literals = mutableListOf<ByteArray>()
        var size = 0L
        while (true) {
            val text = line()
            size += text.length
            if (size > maxOf(24L * 1024 * 1024, literalLimit + 1024L * 1024)) throw Mail.error("IMAP-Antwort zu groß.")
            lines += text
            if (text.startsWith("$token ")) {
                if (!text.uppercase().startsWith("$token OK")) throw Mail.error(failure)
                return Response(lines, literals)
            }
            if (text.uppercase().startsWith("* BYE")) throw Mail.error("Der IMAP-Server hat die Sitzung beendet.")
            Regex("\\{(\\d+)\\+?}$").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                val bytes = literal(it)
                size += it
                literals += bytes
            }
        }
    }

    fun select(folder: String, readOnly: Boolean = false): Long {
        val r = command("${if (readOnly) "EXAMINE" else "SELECT"} ${ImapCodec.quoted(ImapCodec.mailbox(folder))}")
        return Mime.capture("\\[UIDVALIDITY (\\d+)]", r.lines.joinToString("\n"))?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw Mail.error("UIDVALIDITY fehlt. Der Posteingang wurde nicht verändert.")
    }

    fun search() = ImapCodec.uids(command("UID SEARCH ALL").lines)

    fun flags(uids: List<Long>) = if (uids.isEmpty()) emptyMap() else ImapCodec.flags(command("UID FETCH ${uids.joinToString(",")} (UID FLAGS RFC822.SIZE)").lines)

    fun message(uid: Long, headersOnly: Boolean = false): ByteArray =
        command("UID FETCH $uid (BODY.PEEK[${if (headersOnly) "HEADER" else ""}])").literals.firstOrNull()?.takeIf { it.isNotEmpty() }
            ?: throw Mail.error("Die Nachricht ist nicht mehr auf dem Server. Bitte erneut abrufen.")

    fun verify(folder: String, validity: Long, uid: Long, readOnly: Boolean = false) {
        val v = select(folder, readOnly)
        if (v != validity || flags(listOf(uid))[uid] == null) throw Mail.error("Der Ordner oder die Nachricht hat sich geändert. Bitte zuerst erneut abrufen.")
    }

    fun setUnread(folder: String, validity: Long, uid: Long, value: Boolean): ImapCodec.Flags {
        verify(folder, validity, uid)
        command("UID STORE $uid ${if (value) "-" else "+"}FLAGS.SILENT (\\Seen)")
        return flags(listOf(uid))[uid]?.takeIf { it.unread == value } ?: throw Mail.error("Der Gelesen-Status konnte nicht bestätigt werden. Bitte erneut abrufen.")
    }

    fun setImportant(folder: String, validity: Long, uid: Long, value: Boolean): ImapCodec.Flags {
        verify(folder, validity, uid)
        command("UID STORE $uid ${if (value) "+" else "-"}FLAGS.SILENT (\\Flagged)")
        return flags(listOf(uid))[uid]?.takeIf { it.important == value } ?: throw Mail.error("Die Wichtig-Markierung konnte nicht bestätigt werden. Bitte erneut abrufen.")
    }

    fun move(folder: String, validity: Long, uid: Long, target: String) {
        if (folder == target) return
        verify(folder, validity, uid)
        val caps = command("CAPABILITY").lines.joinToString(" ").uppercase().split(Regex("\\s+"))
        if ("MOVE" !in caps && "IMAP4REV2" !in caps)
            throw Mail.error("Dieser Server unterstützt kein sicheres IMAP MOVE. Die Nachricht bleibt im bisherigen Ordner; bitte im Mail-Programm verschieben.")
        command("UID MOVE $uid ${ImapCodec.quoted(ImapCodec.mailbox(target))}", "Verschieben fehlgeschlagen. Den genauen Junk-/Spam-Ordner und die Schreibrechte prüfen; vor Wiederholung erneut abrufen.")
    }
}
