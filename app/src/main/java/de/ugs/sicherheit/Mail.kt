package de.ugs.sicherheit

import java.nio.charset.Charset
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Adresse, Signaturen, Vorlagen und RFC-5322-Nachrichten (plattformunabhängig). */
object Mail {
    private val addressPattern = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")

    fun isAddress(value: String): Boolean {
        val t = value.trim()
        return t.length in 5..254 && addressPattern.matches(t)
    }

    fun error(text: String) = IllegalStateException(text)

    // MARK: Signaturen

    enum class SignatureStyle(val title: String) {
        FIRMA_KOMPLETT("Firma vollständig"),
        PERSONALABTEILUNG("Personalabteilung"),
        KOMPAKT("Kompakt"),
        NUR_GRUSSFORMEL("Nur Grußformel"),
        FREUNDLICHE_GRUESSE("Freundliche Grüße (weniger formell)"),
        EIGENER("Eigener Text");

        fun text(c: Map<String, String>, sender: String): String {
            val city = "${c["postalCode"].orEmpty()} ${c["city"].orEmpty()}".trim()
            val phone = c["phone"].orEmpty().let { if (it.isBlank()) "" else "Tel.: $it" }
            val mail = if (sender.isBlank()) "" else "E-Mail: $sender"
            val name = c["name"].orEmpty()
            val rep = c["representative"].orEmpty()
            val (closing, details) =
                when (this) {
                    FIRMA_KOMPLETT -> "Mit freundlichen Grüßen" to listOf(rep, name, c["street"].orEmpty(), city, phone, mail)
                    PERSONALABTEILUNG -> "Mit freundlichen Grüßen" to listOf(name, "Personalabteilung", c["street"].orEmpty(), city, phone, mail)
                    KOMPAKT -> "Mit freundlichen Grüßen" to listOf(name, phone)
                    NUR_GRUSSFORMEL -> "Mit freundlichen Grüßen" to listOf(rep, name)
                    FREUNDLICHE_GRUESSE -> "Freundliche Grüße" to listOf(rep, name, city, phone)
                    EIGENER -> return ""
                }
            val block = details.map { it.trim() }.filter { it.isNotEmpty() }
            return if (block.isEmpty()) closing else closing + "\n\n" + block.joinToString("\n")
        }
    }

    /** Gewählte Signatur; „Eigener Text“ leer = vollständiger Firmenblock. */
    fun signature(settings: Map<String, String>): String {
        val sender = settings["mail.senderAddress"].orEmpty().trim()
        val style = runCatching { SignatureStyle.valueOf(settings["mail.signatureStyle"].orEmpty()) }.getOrDefault(SignatureStyle.FIRMA_KOMPLETT)
        if (style == SignatureStyle.EIGENER) {
            val own = settings["mail.signature"].orEmpty().trim()
            return own.ifEmpty { SignatureStyle.FIRMA_KOMPLETT.text(settings, sender) }
        }
        return style.text(settings, sender)
    }

    fun greeting(salutation: String, lastName: String): String {
        val last = lastName.trim()
        return when {
            salutation == "Herr" && last.isNotEmpty() -> "Sehr geehrter Herr $last,"
            salutation == "Frau" && last.isNotEmpty() -> "Sehr geehrte Frau $last,"
            else -> "Sehr geehrte Damen und Herren,"
        }
    }

    enum class Template(val title: String, val subject: String, val body: String) {
        ZUR_UNTERSCHRIFT(
            "Vertrag zur Unterschrift",
            "Ihr Arbeitsvertrag",
            "anbei erhalten Sie Ihren Arbeitsvertrag als PDF-Datei.\n\nBitte prüfen Sie das Dokument sorgfältig, drucken Sie es aus und senden Sie uns ein unterschriebenes Exemplar zurück. Sie können es uns auch eingescannt per E-Mail zurücksenden.\n\nFür Rückfragen stehen wir Ihnen gerne zur Verfügung.",
        ),
        ZUR_ANSICHT(
            "Vertrag zu den Unterlagen",
            "Ihr Arbeitsvertrag zu Ihren Unterlagen",
            "anbei erhalten Sie Ihren Arbeitsvertrag als PDF-Datei für Ihre Unterlagen.\n\nEine Unterschrift ist nicht erforderlich, das Dokument dient ausschließlich Ihrer Information.\n\nFür Rückfragen stehen wir Ihnen gerne zur Verfügung.",
        ),
        WILLKOMMEN(
            "Willkommen im Team",
            "Willkommen im Team",
            "wir freuen uns sehr, Sie ab dem {beginn} in unserem Team begrüßen zu dürfen.\n\nAnbei erhalten Sie Ihren Arbeitsvertrag als PDF-Datei. Bitte prüfen Sie das Dokument, unterschreiben Sie es und bringen Sie ein Exemplar zu Ihrem ersten Arbeitstag mit.\n\nAlles Weitere besprechen wir persönlich. Wir freuen uns auf die Zusammenarbeit.",
        ),
        ERINNERUNG(
            "Erinnerung – Vertrag fehlt noch",
            "Erinnerung: Ihr Arbeitsvertrag",
            "wir haben Ihnen Ihren Arbeitsvertrag bereits zugesendet, bisher liegt uns jedoch noch kein unterschriebenes Exemplar vor.\n\nZur Sicherheit erhalten Sie den Vertrag anbei noch einmal. Bitte senden Sie uns das unterschriebene Dokument zeitnah zurück.\n\nSollten Sie den Vertrag bereits abgeschickt haben, betrachten Sie diese E-Mail bitte als gegenstandslos.",
        ),
        KORREKTUR(
            "Korrigierte Fassung",
            "Korrigierte Fassung Ihres Arbeitsvertrages",
            "anbei erhalten Sie die korrigierte Fassung Ihres Arbeitsvertrages als PDF-Datei.\n\nDie zuvor zugesendete Version ist damit ungültig. Bitte verwenden Sie ausschließlich das beigefügte Dokument und senden Sie uns ein unterschriebenes Exemplar zurück.\n\nWir bitten die Unannehmlichkeiten zu entschuldigen.",
        ),
        FREI("Freier Text", "Ihr Arbeitsvertrag", ""),
    }

    /** Anrede + Vorlage + Signatur, alle Platzhalter aufgelöst. */
    fun compose(t: Template, w: Entry, settings: Map<String, String>, start: String = w["startDate"]): Pair<String, String> {
        val salutation = when (w["gender"]) { "männlich" -> "Herr"; "weiblich" -> "Frau"; else -> "" }
        val values =
            mapOf("{name}" to w.title, "{vorname}" to w["firstName"], "{nachname}" to w["lastName"], "{anrede}" to salutation, "{beginn}" to DateText.german(start), "{firma}" to settings["name"].orEmpty(), "{ort}" to settings["city"].orEmpty())
        fun fill(s: String) = values.entries.fold(s) { a, (k, v) -> a.replace(k, v) }
        val firma = settings["name"].orEmpty().trim()
        val subject = fill(t.subject).let { if (firma.isEmpty()) it else "$it – $firma" }
        val blocks = listOf(greeting(salutation, w["lastName"]), fill(t.body).trim(), signature(settings)).filter { it.isNotEmpty() }
        return subject to blocks.joinToString("\n\n") + "\n"
    }

    // MARK: Nachricht

    data class Attachment(val name: String, val data: ByteArray, val contentType: String = "application/pdf")

    data class Inline(val cid: String, val mime: String, val fileName: String, val data: ByteArray)

    fun encodedWord(v: String) = if (v.all { it.code in 32..126 }) v else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(v.toByteArray()) + "?="

    fun addressHeader(name: String, address: String) = name.trim().let { if (it.isEmpty()) "<$address>" else "${encodedWord(it)} <$address>" }

    fun asciiFilename(value: String): String {
        var folded = value
        for ((a, b) in listOf("ä" to "ae", "ö" to "oe", "ü" to "ue", "Ä" to "Ae", "Ö" to "Oe", "Ü" to "Ue", "ß" to "ss")) folded = folded.replace(a, b)
        val out = folded.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("").trim()
        return out.ifEmpty { "Dokument.pdf" }
    }

    private fun base64Lines(data: ByteArray) = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(data)

    fun rfcDate(d: Date = Date()) = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(d)

    fun messageIds(value: String): List<String> =
        Regex("<[^<>\\s\\x00-\\x1F\\x7F]+@[^<>\\s\\x00-\\x1F\\x7F]+>").findAll(value).map { it.value }.toList().takeLast(20)

    private fun safeMime(v: String) = if (Regex("[A-Za-z0-9!#$&^_.+\\-]+/[A-Za-z0-9!#$&^_.+\\-]+").matches(v)) v else "application/octet-stream"

    /** Baut die Rohnachricht; alle Teile base64, daher kein Dot-Stuffing-Risiko im Inhalt. */
    fun build(
        fromName: String,
        fromAddress: String,
        to: String,
        subject: String,
        body: String,
        html: String? = null,
        attachments: List<Attachment> = emptyList(),
        inline: List<Inline> = emptyList(),
        inReplyTo: String = "",
        references: List<String> = emptyList(),
        date: Date = Date(),
    ): ByteArray {
        val domain = fromAddress.substringAfter('@', "ugs.local")
        val lines = mutableListOf<String>()
        lines += "From: " + addressHeader(fromName, fromAddress)
        lines += "To: <$to>"
        lines += "Subject: " + encodedWord(subject)
        lines += "Date: " + rfcDate(date)
        lines += "Message-ID: <${UUID.randomUUID()}@$domain>"
        messageIds(inReplyTo).firstOrNull()?.let { parent ->
            lines += "In-Reply-To: $parent"
            lines += "References: " + messageIds((references + parent).joinToString(" ")).distinct().joinToString("\r\n ")
        }
        lines += "MIME-Version: 1.0"
        fun text(t: String, type: String) = listOf("Content-Type: $type; charset=\"utf-8\"", "Content-Transfer-Encoding: base64", "", base64Lines(t.toByteArray()))
        var core = text(body, "text/plain")
        if (!html.isNullOrEmpty()) {
            val b = "UGS-ALT-${UUID.randomUUID()}"
            core = listOf("Content-Type: multipart/alternative; boundary=\"$b\"", "", "--$b") + text(body, "text/plain") + listOf("", "--$b") + text(html, "text/html") + listOf("", "--$b--")
            if (inline.isNotEmpty()) {
                val r = "UGS-REL-${UUID.randomUUID()}"
                val rel = mutableListOf("Content-Type: multipart/related; type=\"multipart/alternative\"; boundary=\"$r\"", "", "--$r")
                rel += core
                rel += ""
                for (img in inline) {
                    val n = asciiFilename(img.fileName)
                    rel += listOf("--$r", "Content-Type: ${img.mime}; name=\"$n\"", "Content-Transfer-Encoding: base64", "Content-ID: <${img.cid}>", "Content-Disposition: inline; filename=\"$n\"", "", base64Lines(img.data), "")
                }
                rel += "--$r--"
                core = rel
            }
        }
        if (attachments.isNotEmpty()) {
            val m = "UGS-MIX-${UUID.randomUUID()}"
            val mix = mutableListOf("Content-Type: multipart/mixed; boundary=\"$m\"", "", "--$m")
            mix += core
            mix += ""
            for (a in attachments) {
                val n = asciiFilename(a.name)
                val ext = java.net.URLEncoder.encode(a.name, "UTF-8").replace("+", "%20")
                mix += listOf(
                    "--$m",
                    "Content-Type: ${safeMime(a.contentType)}; name=\"$n\"",
                    "Content-Transfer-Encoding: base64",
                    "Content-Disposition: attachment; filename=\"$n\";\r\n filename*=UTF-8''$ext",
                    "",
                    base64Lines(a.data),
                    "",
                )
            }
            mix += "--$m--"
            core = mix
        }
        lines += core
        lines += ""
        return lines.joinToString("\r\n").toByteArray()
    }

    // MARK: HTML-Vorlage „A · Klassisch“

    data class Layout(val eyebrow: String = "", val headline: String = "", val attachments: List<String> = emptyList(), val steps: String = "", val buttonTitle: String = "", val buttonAddress: String = "")

    fun escape(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun paragraphs(text: String): String {
        val out = mutableListOf<String>()
        for (block in text.replace("\r\n", "\n").split("\n\n")) {
            val content = block.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (content.isEmpty()) continue
            out +=
                if (content.all { it.startsWith("- ") })
                    "<ul style=\"margin:0 0 14px 0;padding-left:20px;\">\n" + content.joinToString("\n") { "<li style=\"margin:0 0 4px 0;\">${escape(it.drop(2))}</li>" } + "\n</ul>"
                else "<p style=\"margin:0 0 14px 0;\">${content.joinToString("<br>") { escape(it) }}</p>"
        }
        return out.ifEmpty { listOf("<p style=\"margin:0;\">&nbsp;</p>") }.joinToString("\n")
    }

    /** Tabellenlayout mit Inline-Styles, das in Outlook, Gmail und Apple Mail erhalten bleibt. */
    fun html(text: String, company: Map<String, String>, sender: String, logo: Boolean, layout: Layout = Layout()): String {
        val navy = "#0b1f3a"
        val gold = "#b8912a"
        val blue = "#0a5cc2"
        val font = "-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif"
        val name = company["name"].orEmpty().trim().ifEmpty { "UGS Sicherheit GmbH" }
        val city = company["city"].orEmpty().trim()
        val headerLogo =
            if (logo)
                "<td width=\"56\" valign=\"middle\" style=\"width:56px;padding:0;\"><table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td style=\"background-color:#ffffff;border-radius:10px;padding:6px;\"><img src=\"cid:ugs-logo\" width=\"44\" alt=\"${escape(name)}\" style=\"display:block;border:0;outline:none;width:44px;max-width:44px;height:auto;\"></td></tr></table></td>"
            else ""
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html lang=\"de\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta name=\"color-scheme\" content=\"light\"><title>${escape(layout.headline.ifEmpty { name })}</title></head>")
        sb.append("<body style=\"margin:0;padding:0;background-color:#e9ecf1;\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background-color:#e9ecf1;\"><tr><td align=\"center\" style=\"padding:28px 12px;\">")
        sb.append("<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"width:600px;max-width:100%;background-color:#ffffff;border-radius:12px;overflow:hidden;\">")
        sb.append("<tr><td style=\"background-color:$navy;padding:24px 32px;\"><table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>$headerLogo")
        sb.append("<td valign=\"middle\" style=\"padding:0 0 0 ${if (logo) 14 else 0}px;font-family:$font;\"><div style=\"font-size:18px;line-height:24px;font-weight:bold;color:#ffffff;\">${escape(name)}</div>")
        sb.append("<div style=\"font-size:12px;line-height:18px;color:#c9d3e1;\">Sicherheitsdienst${if (city.isEmpty()) "" else " · " + escape(city)}</div></td></tr></table></td></tr>")
        sb.append("<tr><td style=\"height:4px;line-height:4px;font-size:0;background-color:$gold;\">&nbsp;</td></tr>")
        if (layout.headline.isNotEmpty()) {
            sb.append("<tr><td style=\"padding:30px 32px 0 32px;font-family:$font;\">")
            if (layout.eyebrow.isNotEmpty()) sb.append("<div style=\"font-size:12px;line-height:16px;font-weight:bold;letter-spacing:1px;text-transform:uppercase;color:$blue;margin:0 0 6px 0;\">${escape(layout.eyebrow)}</div>")
            sb.append("<div style=\"font-size:24px;line-height:30px;font-weight:bold;color:$navy;\">${escape(layout.headline)}</div></td></tr>")
        }
        sb.append("<tr><td style=\"padding:22px 32px 8px 32px;font-family:$font;font-size:15px;line-height:1.6;color:#1f2937;\">${paragraphs(text)}</td></tr>")
        if (layout.attachments.isNotEmpty()) {
            sb.append("<tr><td style=\"padding:4px 32px 8px 32px;\">")
            for (file in layout.attachments.take(10)) {
                val pdf = file.lowercase().endsWith(".pdf")
                sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border:1px solid #e5e7eb;border-radius:10px;background-color:#fafbfc;margin:0 0 8px 0;\"><tr>")
                sb.append("<td width=\"36\" valign=\"middle\" style=\"padding:12px 0 12px 14px;\"><div style=\"width:36px;height:44px;line-height:44px;border-radius:5px;background-color:${if (pdf) "#fdecec" else "#eef2f7"};color:${if (pdf) "#b42318" else navy};font-family:$font;font-size:10px;font-weight:bold;text-align:center;\">${if (pdf) "PDF" else "DATEI"}</div></td>")
                sb.append("<td valign=\"middle\" style=\"padding:12px 14px;font-family:$font;\"><div style=\"font-size:14px;line-height:20px;font-weight:bold;color:#111827;\">${escape(file)}</div><div style=\"font-size:12px;line-height:18px;color:#4b5563;\">${if (pdf) "PDF-Dokument" else "Datei"} · im Anhang dieser E-Mail</div></td>")
                sb.append("<td align=\"right\" valign=\"middle\" style=\"padding:12px 14px;font-family:$font;font-size:12px;font-weight:bold;color:$blue;\">Anhang</td></tr></table>")
            }
            sb.append("</td></tr>")
        }
        if (layout.steps.isNotEmpty())
            sb.append("<tr><td style=\"padding:8px 32px 8px 32px;\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background-color:#f3f6fa;border-radius:10px;\"><tr><td style=\"padding:16px 18px;font-family:$font;\"><div style=\"font-size:13px;line-height:18px;font-weight:bold;color:$navy;margin:0 0 6px 0;\">So geht es weiter</div><div style=\"font-size:13px;line-height:20px;color:#374151;\">${escape(layout.steps)}</div></td></tr></table></td></tr>")
        if (layout.buttonTitle.isNotEmpty() && isAddress(layout.buttonAddress))
            sb.append("<tr><td style=\"padding:14px 32px 8px 32px;\"><table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td style=\"background-color:$blue;border-radius:8px;\"><a href=\"mailto:${escape(layout.buttonAddress.trim())}\" style=\"display:inline-block;padding:12px 22px;font-family:$font;font-size:14px;font-weight:bold;color:#ffffff;text-decoration:none;\">${escape(layout.buttonTitle)}</a></td></tr></table></td></tr>")
        val address = listOf(company["street"].orEmpty(), "${company["postalCode"].orEmpty()} ${company["city"].orEmpty()}").map { it.trim() }.filter { it.isNotEmpty() }
        val contact = mutableListOf<String>()
        company["phone"]?.trim()?.takeIf { it.isNotEmpty() }?.let { contact += "Tel. ${escape(it)}" }
        if (sender.isNotEmpty()) contact += "<a href=\"mailto:${escape(sender)}\" style=\"color:$blue;text-decoration:none;\">${escape(sender)}</a>"
        val footer = mutableListOf((listOf(name) + address).filter { it.isNotEmpty() }.joinToString(" · ") { escape(it) })
        if (contact.isNotEmpty()) footer += contact.joinToString(" · ")
        company["representative"]?.trim()?.takeIf { it.isNotEmpty() }?.let { footer += "Geschäftsführer: ${escape(it)}" }
        sb.append("<tr><td style=\"padding:24px 32px 0 32px;\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td style=\"height:1px;line-height:1px;font-size:0;background-color:#e5e7eb;\">&nbsp;</td></tr></table></td></tr>")
        sb.append("<tr><td style=\"padding:18px 32px 26px 32px;font-family:$font;\"><table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:0 0 8px 0;\"><tr>")
        if (logo) sb.append("<td width=\"22\" valign=\"middle\" style=\"padding:0 10px 0 0;\"><img src=\"cid:ugs-logo\" width=\"22\" alt=\"\" style=\"display:block;border:0;width:22px;height:auto;\"></td>")
        sb.append("<td valign=\"middle\" style=\"font-size:13px;font-weight:bold;color:$navy;\">${escape(name)}</td></tr></table>")
        sb.append("<div style=\"font-size:12px;line-height:19px;color:#4b5563;\">${footer.joinToString("<br>")}</div>")
        sb.append("<div style=\"font-size:11px;line-height:17px;color:#6b7280;margin:10px 0 0 0;\">Diese E-Mail und ihre Anhänge können personenbezogene und vertrauliche Informationen enthalten. Falls Sie nicht der richtige Empfänger sind, informieren Sie uns bitte und löschen Sie die Nachricht.</div></td></tr>")
        sb.append("</table><div style=\"padding:14px 0 0 0;font-family:$font;font-size:11px;color:#6b7280;\">Objektschutz · Revier- &amp; Streifendienst · Veranstaltungsschutz · Personenschutz</div></td></tr></table></body></html>")
        return sb.toString()
    }

    fun megabytes(bytes: Long) = String.format(Locale.GERMANY, "%.0f MB", bytes / 1048576.0)

    /** „250-SIZE 52428800“ aus der EHLO-Antwort. */
    fun maximumSize(capabilities: String): Long? {
        for (line in capabilities.lines()) {
            val words = line.uppercase().replace("-", " ").split(" ").filter { it.isNotEmpty() }
            val i = words.indexOf("SIZE")
            if (i >= 0 && i + 1 < words.size) words[i + 1].toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        return null
    }

    fun smtpFailure(step: String, code: Int, text: String): Exception {
        val hint =
            when (code) {
                421 -> "Der SMTP-Server hat die Verbindung beendet ($step)."
                450, 451, 452 -> "Der SMTP-Server hat die Nachricht vorübergehend abgelehnt ($step). Bitte später erneut versuchen."
                500, 501, 502, 503, 504 -> "Der SMTP-Server hat den Befehl abgelehnt ($step)."
                530, 534, 535, 538 -> "Der SMTP-Server hat die Anmeldung abgelehnt. Benutzername und Passwort prüfen. Bei Gmail, GMX und web.de wird ein App-Passwort benötigt, nicht das normale Kennwort."
                552 -> "Der SMTP-Server hat die E-Mail abgelehnt, weil sie zu groß ist ($step). Viele Anbieter erlauben nur 20–50 MB pro E-Mail; Anhänge werden beim Senden etwa ein Drittel größer."
                550, 551, 553, 554 -> "Der SMTP-Server hat die Nachricht abgelehnt ($step). Absender- und Empfängeradresse prüfen."
                else -> "Der E-Mail-Versand ist fehlgeschlagen ($step, SMTP-Code $code)."
            }
        val detail = text.trim()
        return error(if (detail.isEmpty()) hint else "$hint\n\n$detail")
    }

    fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}

/** Geparste eingehende Nachricht: nur Text, keine Skripte, keine entfernten Bilder. */
data class ParsedMail(
    val sender: String,
    val senderAddress: String,
    val recipient: String,
    val subject: String,
    val date: Date,
    val body: String,
    val attachments: List<MailPart>,
    val fingerprint: String,
    val replyTo: String,
    val messageId: String,
    val references: List<String>,
)

data class MailPart(val name: String, val contentType: String, val data: ByteArray)

object Mime {
    private val latin1: Charset = Charsets.ISO_8859_1

    private class Part(var plain: String = "", var html: String = "", val attachments: MutableList<MailPart> = mutableListOf())

    fun capture(pattern: String, text: String, group: Int = 1): String? =
        Regex(pattern, RegexOption.IGNORE_CASE).find(text)?.groups?.get(group)?.value

    fun parse(data: ByteArray, headersOnly: Boolean = false): ParsedMail {
        val source = String(data, latin1)
        val (headers, _) = split(source)
        val from = decodeHeader(headers["from"].orEmpty())
        val address =
            capture("<([^<>\\s]+@[^<>\\s]+)>", from) ?: capture("(?:^|\\s)([A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,})(?:$|\\s)", from) ?: ""
        val part = if (headersOnly) Part() else parsePart(source, 0)
        val body =
            if (headersOnly) "Diese Nachricht ist größer als 10 MB. Beim Öffnen werden Inhalt und Anhänge nachgeladen (bis ${FileLimits.INCOMING_MAIL_LABEL})."
            else part.plain.ifEmpty { plainHtml(part.html) }
        return ParsedMail(
            from,
            address.lowercase(),
            decodeHeader(headers["to"].orEmpty()),
            decodeHeader(headers["subject"] ?: "(Ohne Betreff)"),
            parseDate(headers["date"].orEmpty()) ?: Date(),
            body.ifEmpty { "Keine Textvorschau verfügbar." },
            part.attachments,
            Mail.sha256(data),
            replyAddress(headers["reply-to"].orEmpty(), address),
            Mail.messageIds(headers["message-id"].orEmpty()).firstOrNull().orEmpty(),
            Mail.messageIds(headers["references"].orEmpty()),
        )
    }

    fun replyAddress(value: String, fallback: String): String {
        val decoded = decodeHeader(value).trim()
        val candidate = capture("<([^<>\\s]+@[^<>\\s]+)>", decoded) ?: decoded
        return if (Mail.isAddress(candidate)) candidate else fallback
    }

    private fun split(source: String): Pair<Map<String, String>, String> {
        val a = source.indexOf("\r\n\r\n").takeIf { it >= 0 }
        val b = source.indexOf("\n\n").takeIf { it >= 0 }
        val (at, len) =
            when {
                a != null && (b == null || a <= b) -> a to 4
                b != null -> b to 2
                else -> source.length to 0
            }
        val headerText = source.substring(0, at).replace("\r\n", "\n")
        val body = if (at + len <= source.length) source.substring(at + len) else ""
        val headers = linkedMapOf<String, String>()
        var key = ""
        for (line in headerText.split("\n")) {
            if (line.startsWith(" ") || line.startsWith("\t")) {
                if (key.isNotEmpty()) headers[key] = headers[key].orEmpty() + " " + line.trim()
            } else {
                val colon = line.indexOf(':')
                if (colon > 0) {
                    key = line.substring(0, colon).lowercase()
                    if (!headers.containsKey(key)) headers[key] = line.substring(colon + 1).trim()
                }
            }
        }
        return headers to body
    }

    private fun parameter(name: String, value: String): String? {
        val e = Regex.escape(name)
        return capture("(?:^|;)\\s*$e=\"([^\"]*)\"", value) ?: capture("(?:^|;)\\s*$e=([^;\\s]+)", value)
    }

    private fun parsePart(source: String, depth: Int): Part {
        if (depth >= 16) return Part(plain = "Die Nachricht ist zu stark verschachtelt.")
        val (headers, body) = split(source)
        val type = headers["content-type"] ?: "text/plain; charset=utf-8"
        val media = type.substringBefore(';').trim().lowercase()
        val disposition = headers["content-disposition"].orEmpty()
        val extended =
            parameter("filename*", disposition)?.let { v ->
                val pieces = v.split("'")
                val raw = if (pieces.size >= 3) pieces.drop(2).joinToString("'") else v
                runCatching { java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrNull()
            }
        val filename = extended ?: parameter("filename", disposition) ?: parameter("name", type)
        val embedded = media.startsWith("image/") && filename == null && !disposition.lowercase().startsWith("attachment")
        if (disposition.lowercase().startsWith("attachment") || filename != null || embedded) {
            val name =
                filename?.let { decodeHeader(it) }
                    ?: if (embedded) "Bild-${headers["content-id"].orEmpty().trim('<', '>', ' ').substringBefore('@').filter { it.isLetterOrDigit() || it in "._-" }.take(40).ifEmpty { "1" }}.${media.substringAfter('/').substringBefore('+')}"
                    else "Anhang"
            return Part(attachments = mutableListOf(MailPart(name, media, decoded(body, headers["content-transfer-encoding"].orEmpty()))))
        }
        val boundary = parameter("boundary", type)
        if (media.startsWith("multipart/") && !boundary.isNullOrEmpty()) {
            val marker = Regex("(?m)^--" + Regex.escape(boundary) + "(--)?[ \\t]*(?:\\r\\n|\\n|$)")
            val matches = marker.findAll(body).toList()
            val combined = Part()
            for (i in 0 until (matches.size - 1).coerceAtMost(300)) {
                if (matches[i].groups[1] != null) break
                var chunk = body.substring(matches[i].range.last + 1, matches[i + 1].range.first)
                chunk = chunk.replace(Regex("(?:\\r\\n|\\n)\\z"), "")
                val child = parsePart(chunk, depth + 1)
                combined.attachments += child.attachments
                if (child.plain.isNotEmpty()) combined.plain += (if (combined.plain.isEmpty()) "" else "\n\n") + child.plain
                if (child.html.isNotEmpty()) combined.html += child.html
            }
            return combined
        }
        val text = decodeBytes(decoded(body, headers["content-transfer-encoding"].orEmpty()), parameter("charset", type) ?: "utf-8")
        return when (media) {
            "text/plain" -> Part(plain = text)
            "text/html" -> Part(html = text)
            else -> Part()
        }
    }

    private fun decoded(body: String, encoding: String): ByteArray {
        val bytes = body.toByteArray(latin1)
        return when (encoding.trim().lowercase()) {
            "base64" -> runCatching { Base64.getMimeDecoder().decode(bytes) }.getOrDefault(ByteArray(0))
            "quoted-printable" -> quotedPrintable(bytes)
            else -> bytes
        }
    }

    fun quotedPrintable(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun hex(b: Byte): Int? =
            when (b.toInt().toChar()) {
                in '0'..'9' -> b - 48
                in 'A'..'F' -> b - 55
                in 'a'..'f' -> b - 87
                else -> null
            }
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] == '='.code.toByte()) {
                if (i + 1 < bytes.size && bytes[i + 1] == 10.toByte()) { i += 2; continue }
                if (i + 2 < bytes.size && bytes[i + 1] == 13.toByte() && bytes[i + 2] == 10.toByte()) { i += 3; continue }
                if (i + 2 < bytes.size) {
                    val h = hex(bytes[i + 1])
                    val l = hex(bytes[i + 2])
                    if (h != null && l != null) {
                        out.write(h * 16 + l)
                        i += 3
                        continue
                    }
                }
            }
            out.write(bytes[i].toInt())
            i++
        }
        return out.toByteArray()
    }

    private fun decodeBytes(bytes: ByteArray, charset: String): String {
        val cs = runCatching { Charset.forName(charset.trim().trim('"')) }.getOrDefault(Charsets.UTF_8)
        return String(bytes, cs)
    }

    fun decodeHeader(source: String): String {
        var result = source.replace(Regex("(\\?=)\\s+(=\\?)"), "$1$2")
        result = Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=").replace(result) { m ->
            val payload = m.groupValues[3]
            val bytes =
                if (m.groupValues[2].equals("b", true)) runCatching { Base64.getDecoder().decode(payload) }.getOrNull()
                else quotedPrintable(payload.replace("_", " ").toByteArray(latin1))
            bytes?.let { decodeBytes(it, m.groupValues[1]) } ?: m.value
        }
        // UTF-8-Header, die oben als ISO-8859-1 gelesen wurden.
        if (result.all { it.code < 256 }) {
            val raw = result.toByteArray(latin1)
            val utf8 = Charsets.UTF_8.newDecoder()
            runCatching { return utf8.decode(java.nio.ByteBuffer.wrap(raw)).toString() }
        }
        return result
    }

    fun plainHtml(html: String): String {
        var t = html.replace(Regex("(?is)<(script|style|head)\\b[^>]*>.*?</\\1\\s*>"), "")
        t = t.replace(Regex("(?i)<\\s*(br\\b[^>]*|/p|/div|/li|/tr|/h[1-6])\\s*>"), "\n")
        t = t.replace(Regex("(?s)<[^>]*>"), "")
        t = Regex("&#(x[0-9a-fA-F]+|[0-9]+);").replace(t) { m ->
            val n = m.groupValues[1]
            val v = if (n.startsWith("x")) n.drop(1).toIntOrNull(16) else n.toIntOrNull()
            v?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value
        }
        for ((e, v) in listOf("&nbsp;" to " ", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'", "&amp;" to "&")) t = t.replace(e, v)
        return t.trim()
    }

    private fun parseDate(source: String): Date? {
        val clean = source.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
        for (f in listOf("EEE, d MMM yyyy HH:mm:ss Z", "d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm Z", "d MMM yyyy HH:mm Z"))
            runCatching { return SimpleDateFormat(f, Locale.US).parse(clean) }
        return null
    }
}

/** IMAP-Hilfen: Anführung, modifiziertes UTF-7, Antworten auswerten. */
object ImapCodec {
    fun quoted(text: String): String {
        require(text.none { it.code < 32 || it.code == 127 }) { "Ungültiges Steuerzeichen in einer IMAP-Einstellung." }
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    fun mailbox(text: String): String {
        val out = StringBuilder()
        val non = StringBuilder()
        fun flush() {
            if (non.isEmpty()) return
            val bytes = non.toString().toByteArray(Charsets.UTF_16BE)
            out.append("&").append(Base64.getEncoder().encodeToString(bytes).replace("/", ",").replace("=", "")).append("-")
            non.setLength(0)
        }
        for (ch in text) {
            if (ch.code in 32..126) {
                flush()
                out.append(if (ch == '&') "&-" else ch.toString())
            } else non.append(ch)
        }
        flush()
        return out.toString()
    }

    data class Flags(val uid: Long, val important: Boolean, val unread: Boolean, val size: Long)

    fun uids(lines: List<String>): List<Long> {
        val line = lines.firstOrNull { it.uppercase() == "* SEARCH" || it.uppercase().startsWith("* SEARCH ") } ?: error("Der IMAP-Server lieferte keine gültige Nachrichtenliste.")
        val tokens = line.split(" ").drop(2).filter { it.isNotEmpty() }
        val values = tokens.mapNotNull { it.toLongOrNull() }
        require(values.size == tokens.size && values.all { it in 1..4294967295L }) { "Ungültige IMAP-Nachrichtenkennungen." }
        return values.distinct().sortedDescending()
    }

    fun flags(lines: List<String>): Map<Long, Flags> {
        val out = mutableMapOf<Long, Flags>()
        for (line in lines) {
            if (!line.uppercase().contains(" FETCH ")) continue
            val uid = Mime.capture("\\bUID (\\d+)", line)?.toLongOrNull() ?: continue
            val flags = Mime.capture("\\bFLAGS \\(([^)]*)\\)", line) ?: continue
            val tokens = flags.uppercase().split(" ").toSet()
            out[uid] = Flags(uid, "\\FLAGGED" in tokens, "\\SEEN" !in tokens, Mime.capture("\\bRFC822\\.SIZE (\\d+)", line)?.toLongOrNull() ?: 0)
        }
        return out
    }
}
