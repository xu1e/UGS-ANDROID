package de.ugs.sicherheit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Anschreiben zur Sofortmeldung wie in iOS 1.9.0 (Betriebsnummer 69562254). */
object SofortmeldungTemplate {
    const val COMPANY_NUMBER = "69562254"
    const val SUBJECT = "Sofortmeldung – Betriebsnummer $COMPANY_NUMBER"
    const val MAX_COUNT = 10

    val message =
        """
        Betriebs Nummer: $COMPANY_NUMBER

        Sehr geehrte Damen und Herren,

        ich habe soeben die Sofortmeldung für den Mitarbeiter vorgenommen. Die entsprechende Bestätigung finden Sie als PDF im Anhang.

        Ich bitte Sie, die Sofortmeldung entsprechend zu berücksichtigen und in Ihren Unterlagen zu vermerken.

        Die reguläre Meldung durch unsere Lohnbuchhaltung erfolgt innerhalb der gesetzlichen Frist von 4–6 Wochen im Rahmen der ersten Lohnabrechnung. Bitte berücksichtigen Sie dies bei der weiteren Bearbeitung.

        Wir bitten Sie, den Versicherungsschutz bzw. die Absicherung des Mitarbeiters bis zur Übermittlung der regulären Meldung entsprechend fortzuführen.

        Sollten Sie hierzu noch weitere Unterlagen oder Informationen benötigen, lassen Sie es mich bitte wissen.

        Vielen Dank für Ihre Unterstützung und Ihr Verständnis.
        """
            .trimIndent()

    fun body(signature: String): String {
        val sig = signature.trim()
        val hasClosing = listOf("mit freundlichen grüßen", "freundliche grüße").any { sig.lowercase().startsWith(it) }
        val closing = if (hasClosing) sig else listOf("Mit freundlichen Grüßen", sig).filter { it.isNotEmpty() }.joinToString("\n\n")
        return message + "\n\n" + closing
    }

    data class Pdf(val id: String, val name: String, val data: ByteArray, val pages: Int)

    /** Nur lesbare PDFs ohne Passwort; keine Duplikate; höchstens 10 Dateien und zusammen 100 MB. */
    fun validated(name: String, data: ByteArray): Pdf {
        require(data.isNotEmpty() && data.size <= FileLimits.MAIL_BYTES) { "$name: Eine PDF-Datei darf höchstens ${FileLimits.MAIL_LABEL} groß sein." }
        val pages =
            runCatching { PDDocument.load(data).use { if (it.isEncrypted) -1 else it.numberOfPages } }.getOrElse { throw IllegalArgumentException("$name: Die Datei ist kein lesbares PDF.") }
        require(pages > 0) { "$name: Bitte ein PDF ohne Passwortschutz mit mindestens einer Seite verwenden." }
        return Pdf(Mail.sha256(data), name, data, pages)
    }

    fun adding(incoming: Pdf, current: List<Pdf>): List<Pdf> {
        require(current.none { it.id == incoming.id }) { "${incoming.name}: Dieses PDF ist bereits angehängt." }
        require(current.size < MAX_COUNT) { "Höchstens $MAX_COUNT PDF-Anhänge pro E-Mail." }
        require(current.sumOf { it.data.size.toLong() } + incoming.data.size <= FileLimits.MAIL_BYTES) { "Die PDF-Anhänge dürfen zusammen höchstens ${FileLimits.MAIL_LABEL} groß sein." }
        return current + incoming
    }
}

@Composable
fun SofortmeldungScreen(vm: UGSViewModel) {
    var recipient by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf(SofortmeldungTemplate.SUBJECT) }
    var body by rememberSaveable { mutableStateOf(SofortmeldungTemplate.body(Mail.signature(vm.company))) }
    var pdfs by remember { mutableStateOf(emptyList<SofortmeldungTemplate.Pdf>()) }
    var importing by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var problems by remember { mutableStateOf("") }
    val canSend = vm.canExport("sofortmeldung")
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty() || importing || sending) return@rememberLauncherForActivityResult
            importing = true
            status = ""
            vm.launch {
                try {
                    val (accepted, messages) =
                        withContext(Dispatchers.IO) {
                            var list = pdfs
                            val errors = mutableListOf<String>()
                            // Arbeit begrenzen, auch wenn versehentlich sehr viele Dateien gewählt wurden.
                            for (u in uris.take(SofortmeldungTemplate.MAX_COUNT)) {
                                runCatching {
                                        val name = ImportService.name(vm.app, u)
                                        require(name.lowercase().endsWith(".pdf") || vm.app.contentResolver.getType(u) == "application/pdf") { "$name: Bitte nur PDF-Dateien hinzufügen." }
                                        list = SofortmeldungTemplate.adding(SofortmeldungTemplate.validated(name, ImportService.read(vm.app, u, FileLimits.MAIL_BYTES)), list)
                                    }
                                    .onFailure { errors += it.message.orEmpty() }
                            }
                            if (uris.size > SofortmeldungTemplate.MAX_COUNT) errors += "Nur die ersten ${SofortmeldungTemplate.MAX_COUNT} Dateien wurden geprüft."
                            list to errors
                        }
                    pdfs = accepted
                    problems = messages.joinToString("\n")
                } finally {
                    importing = false
                }
            }
        }
    val validation =
        when {
            importing -> "PDF-Dateien werden geprüft …"
            !Mail.isAddress(recipient) -> "Bitte eine gültige Empfängeradresse eingeben."
            subject.isBlank() -> "Bitte einen Betreff eingeben."
            subject.contains('\n') || subject.contains('\r') -> "Der Betreff darf keinen Zeilenumbruch enthalten."
            body.isBlank() -> "Bitte einen Nachrichtentext eingeben."
            pdfs.isEmpty() -> "Bitte die Bestätigung als PDF anhängen."
            MailService.smtp(vm).host.isBlank() -> "Der E-Mail-Versand ist noch nicht eingerichtet: Einstellungen → E-Mail-Versand."
            else -> null
        }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Sofortmeldung versenden", style = MaterialTheme.typography.headlineSmall) }
        item {
            Text(
                "Anschreiben mit Betriebsnummer ${SofortmeldungTemplate.COMPANY_NUMBER} und bis zu ${SofortmeldungTemplate.MAX_COUNT} PDF-Bestätigungen (zusammen max. ${FileLimits.MAIL_LABEL}).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { TextRow("Empfänger", recipient) { recipient = it } }
        item { TextRow("Betreff", subject) { subject = it } }
        item { TextRow("Nachricht", body, 12) { body = it } }
        item {
            TextButton(onClick = {
                subject = SofortmeldungTemplate.SUBJECT
                body = SofortmeldungTemplate.body(Mail.signature(vm.company))
                status = ""
            }) { Text("Vorlage wiederherstellen") }
        }
        item { SectionHeader("PDF-Anhänge (${pdfs.size}/${SofortmeldungTemplate.MAX_COUNT})") }
        items(pdfs, key = { it.id }) { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(UgsIcons.Attachment, null)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(p.name)
                    Text("${p.pages} ${if (p.pages == 1) "Seite" else "Seiten"} · ${android.text.format.Formatter.formatShortFileSize(vm.app, p.data.size.toLong())}", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { pdfs = pdfs.filterNot { it.id == p.id } }, enabled = !sending && !importing) { Icon(Icons.Default.Delete, "Entfernen") }
            }
        }
        item {
            OutlinedButton(onClick = { pick.launch(arrayOf("application/pdf")) }, enabled = !importing && !sending && pdfs.size < SofortmeldungTemplate.MAX_COUNT) {
                Text(if (importing) "Wird geprüft …" else "PDF hinzufügen")
            }
        }
        if (problems.isNotBlank()) item { Text(problems, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        validation?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        if (status.isNotBlank()) item { Text(status, style = MaterialTheme.typography.bodySmall) }
        item {
            Button(
                onClick = {
                    if (sending) return@Button
                    sending = true
                    val snapshot = pdfs
                    vm.launch {
                        try {
                            require(vm.canExport("sofortmeldung")) { "Keine Berechtigung zum Senden." }
                            withContext(Dispatchers.IO) {
                                MailService.send(
                                    vm,
                                    recipient,
                                    subject.trim(),
                                    body,
                                    snapshot.map { Mail.Attachment(it.name, it.data) },
                                    "sofortmeldung",
                                    Mail.Layout(eyebrow = "Sofortmeldung", headline = "Betriebsnummer ${SofortmeldungTemplate.COMPANY_NUMBER}"),
                                )
                                vm.repo.exported("Sofortmeldung vom SMTP-Server angenommen: $recipient · ${snapshot.size} PDF")
                            }
                            vm.refresh()
                            status = "Der SMTP-Server hat die Nachricht angenommen. Dies ist keine Zustell- oder Lesebestätigung."
                            pdfs = emptyList()
                        } finally {
                            sending = false
                        }
                    }
                },
                enabled = canSend && validation == null && !sending,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (sending) "Wird gesendet …" else "Sofortmeldung senden")
            }
        }
        if (!canSend) item { Text("Für den Versand ist das Recht „Exportieren“ für Sofortmeldung nötig.", color = MaterialTheme.colorScheme.error) }
    }
}
