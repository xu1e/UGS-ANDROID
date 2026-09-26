@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.ugs.sicherheit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun TextRow(label: String, value: String, lines: Int = 1, change: (String) -> Unit) {
    OutlinedTextField(
        value,
        change,
        label = { Text(label) },
        singleLine = lines == 1,
        minLines = lines,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Unterschriftsfeld: mit dem Finger zeichnen, Punkte relativ 0…1 gespeichert. */
@Composable
fun SignaturePad(strokes: List<List<Pair<Float, Float>>>, change: (List<List<Pair<Float, Float>>>) -> Unit) {
    var current by remember { mutableStateOf(listOf<Pair<Float, Float>>()) }
    val ink = MaterialTheme.colorScheme.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Unterschrift Kontrolleur", Modifier.weight(1f))
            TextButton(onClick = { change(emptyList()); current = emptyList() }) { Text("Löschen") }
        }
        Canvas(
            Modifier.fillMaxWidth()
                .height(120.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                .pointerInput(strokes) {
                    fun rel(o: Offset) = (o.x / size.width).coerceIn(0f, 1f) to (o.y / size.height).coerceIn(0f, 1f)
                    detectDragGestures(
                        onDragStart = { current = listOf(rel(it)) },
                        onDragEnd = {
                            if (current.size > 1) change(strokes + listOf(current))
                            current = emptyList()
                        },
                        onDragCancel = { current = emptyList() },
                    ) { pointer, _ -> current = current + rel(pointer.position) }
                }
        ) {
            for (s in strokes + listOf(current)) {
                val first = s.firstOrNull() ?: continue
                val p = Path().apply {
                    moveTo(first.first * size.width, first.second * size.height)
                    for ((x, y) in s.drop(1)) lineTo(x * size.width, y * size.height)
                }
                drawPath(p, ink, style = Stroke(width = 3f))
            }
        }
        Text("Mit dem Finger unterschreiben. Leer lassen, um später auf Papier zu unterschreiben.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun <T : Enum<T>> EnumChoice(label: String, value: T, values: List<T>, title: (T) -> String, change: (T) -> Unit) {
    ChoiceInput(label, value.name, values.map { it.name to title(it) }) { n -> values.firstOrNull { it.name == n }?.let(change) }
}

@Composable
fun PenaltyScreen(vm: UGSViewModel) {
    var worker by remember { mutableStateOf<Entry?>(null) }
    var mode by remember { mutableStateOf(PenaltyMode.REPORT) }
    var report by remember { mutableStateOf(InspectionReport(controller = vm.user?.username.orEmpty())) }
    var claim by remember { mutableStateOf(PenaltyDocument().fillCompany(vm.company)) }
    val attachments = remember { mutableStateListOf<Pair<String, ByteArray>>() }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            vm.run {
                for (uri in uris) {
                    val bytes = withContext(Dispatchers.IO) { ImportService.read(vm.app, uri) }
                    require(bytes.size > 4 && String(bytes, 0, 4) == "%PDF") { "Anlagen müssen PDF-Dateien sein." }
                    attachments += ImportService.name(vm.app, uri) to bytes
                }
            }
        }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Strafe · Kontrollprotokoll", style = MaterialTheme.typography.headlineSmall) }
        item { EnumChoice("Export", mode, PenaltyMode.entries, { it.title }) { mode = it } }
        item {
            Text("Mitarbeiter für die automatische Profilablage übernehmen.", style = MaterialTheme.typography.bodySmall)
            WorkerLookup(vm, worker) { w ->
                worker = w
                report = report.fillWorker(w)
                claim = claim.fillRecipient(w).copy(site = w["object"].ifBlank { claim.site })
            }
        }
        if (mode.includesReport) {
            item { SectionHeader("Kontrollprotokoll – Sicherheitsdienst") }
            item { TextRow("Protokollnummer", report.reference) { report = report.copy(reference = it) } }
            item { TextRow("Objektname", report.site) { report = report.copy(site = it) } }
            item { TextRow("Adresse", report.address) { report = report.copy(address = it) } }
            item { TextRow("Ausweis-Nr. / Bewacher ID", report.badgeNumber) { report = report.copy(badgeNumber = it) } }
            item { TextRow("Mitarbeiter", report.workerName) { report = report.copy(workerName = it) } }
            item { TextRow("Personalnummer", report.staffNumber) { report = report.copy(staffNumber = it) } }
            item { TextRow("Kontrolleur", report.controller) { report = report.copy(controller = it) } }
            item { CalendarInput("Datum", report.date, true) { report = report.copy(date = it) } }
            item { TextRow("Uhrzeit (HH:mm)", report.time) { report = report.copy(time = it) } }
            item {
                SectionHeader("Kontrollpunkte")
                Text(
                    "Ja / Nein beziehen sich auf die Frage. Bei Handy und Schlafen bedeutet Ja eine beobachtete Auffälligkeit. Offen bleibt unmarkiert; Entfällt wird als Bemerkung gedruckt.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            itemsIndexed(report.points, key = { _, p -> p.id }) { i, p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text((if (p.number.isEmpty()) "" else "${p.number}. ") + p.title.replace("\n", " "), fontWeight = FontWeight.SemiBold)
                        if (p.id == "language")
                            EnumChoice("Deutschkenntnisse", report.language, InspectionLanguage.entries, { it.title }) { report = report.copy(language = it) }
                        else
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (a in InspectionAnswer.entries)
                                    FilterChip(p.answer == a, { report = report.copy(points = report.points.toMutableList().also { it[i] = p.copy(answer = a) }) }, label = { Text(a.title) })
                            }
                        if (p.id == "roster") TextRow("Andere Person: Name", report.alternateWorker) { report = report.copy(alternateWorker = it) }
                        TextRow("Anmerkungen / Bemerkungen", p.remarks) { v -> report = report.copy(points = report.points.toMutableList().also { it[i] = p.copy(remarks = v) }) }
                    }
                }
            }
            item {
                SectionHeader("Gesamtbewertung")
                EnumChoice("Bewertung", report.overall, InspectionOverall.entries, { it.title }) { report = report.copy(overall = it) }
            }
            item { TextRow("Bericht / Bemerkungen", report.remarks, 4) { report = report.copy(remarks = it) } }
            item { Text("Auffälligkeiten: ${report.adverseCount}", style = MaterialTheme.typography.bodySmall) }
            item { SignaturePad(report.signature) { report = report.copy(signature = it) } }
        }
        if (mode.includesClaim) {
            item { SectionHeader("Absender") }
            item { TextRow("Firma", claim.issuer) { claim = claim.copy(issuer = it) } }
            item { TextRow("Anschrift", claim.issuerAddress, 2) { claim = claim.copy(issuerAddress = it) } }
            item { TextRow("Vertreten durch", claim.representative) { claim = claim.copy(representative = it) } }
            item { TextRow("Telefon", claim.phone) { claim = claim.copy(phone = it) } }
            item { TextRow("E-Mail", claim.email) { claim = claim.copy(email = it) } }
            item { TextRow("Website", claim.website) { claim = claim.copy(website = it) } }
            item { TextRow("Register / Steuern", claim.registration, 2) { claim = claim.copy(registration = it) } }
            item { TextRow("Bankverbindung", claim.bank, 2) { claim = claim.copy(bank = it) } }
            item { SectionHeader("Forderung") }
            item { TextRow("Empfänger", claim.recipient) { claim = claim.copy(recipient = it) } }
            item { TextRow("Empfängeranschrift", claim.recipientAddress, 2) { claim = claim.copy(recipientAddress = it) } }
            item { TextRow("Kunden- / Personalnummer", claim.customerNumber) { claim = claim.copy(customerNumber = it) } }
            item { TextRow("Forderungsnummer", claim.invoiceNumber) { claim = claim.copy(invoiceNumber = it) } }
            item { TextRow("Titel", claim.title) { claim = claim.copy(title = it) } }
            item { TextRow("Leistungszeitraum", claim.servicePeriod) { claim = claim.copy(servicePeriod = it) } }
            item { TextRow("Objekt", claim.site) { claim = claim.copy(site = it) } }
            item { CalendarInput("Datum", claim.date, true) { claim = claim.copy(date = it) } }
            item { CalendarInput("Vorfalldatum", claim.incidentDate) { claim = claim.copy(incidentDate = it) } }
            item { TextRow("Sachverhalt", claim.incident, 3) { claim = claim.copy(incident = it) } }
            item { TextRow("Vertragliche Grundlage / Klausel / Begründung", claim.legalBasis, 3) { claim = claim.copy(legalBasis = it) } }
            item {
                Text(
                    "§ 23 im hinterlegten Arbeitsvertrag betrifft den schuldhaften endgültigen Nichtantritt bzw. vorzeitigen Abbruch. Einzelne Verspätungen, Kleidungs- oder Kontrollmängel lösen daraus keine automatische Vertragsstrafe aus.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item { SectionHeader("Positionen") }
            itemsIndexed(claim.lines, key = { _, l -> l.id }) { i, l ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        fun set(n: PenaltyLine) { claim = claim.copy(lines = claim.lines.toMutableList().also { it[i] = n }) }
                        TextRow("Beschreibung", l.description, 2) { set(l.copy(description = it)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.weight(1f)) { TextRow("Menge", l.quantity) { set(l.copy(quantity = it)) } }
                            Box(Modifier.weight(1f)) { TextRow("Pauschale / Einzelbetrag", l.unitPrice) { set(l.copy(unitPrice = it)) } }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Betrag: ${l.amount?.let { Money.text(it) } ?: "—"}", Modifier.weight(1f))
                            TextButton(onClick = { claim = claim.copy(lines = claim.lines.filter { it.id != l.id }) }) { Text("Position entfernen") }
                        }
                    }
                }
            }
            item { OutlinedButton(onClick = { claim = claim.copy(lines = claim.lines + PenaltyLine()) }) { Text("Position hinzufügen") } }
            item { EnumChoice("Steuerliche Einordnung", claim.taxTreatment, TaxTreatment.entries, { it.title }) { claim = claim.copy(taxTreatment = it) } }
            if (claim.taxTreatment == TaxTreatment.TAXABLE) item { TextRow("Umsatzsteuer %", claim.taxPercent) { claim = claim.copy(taxPercent = it) } }
            item {
                Text("Netto: ${Money.text(claim.net)} · Steuer: ${Money.text(claim.tax)} · Gesamt: ${Money.text(claim.total)}", fontWeight = FontWeight.SemiBold)
            }
            item { TextRow("Zahlungsbedingungen", claim.paymentTerms, 2) { claim = claim.copy(paymentTerms = it) } }
            item { TextRow("Abschluss", claim.closing, 2) { claim = claim.copy(closing = it) } }
        }
        item { SectionHeader("PDF-Anlagen") }
        itemsIndexed(attachments.toList()) { i, (name, _) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f))
                TextButton(onClick = { attachments.removeAt(i) }) { Text("Entfernen") }
            }
        }
        item { OutlinedButton(onClick = { pick.launch(arrayOf("application/pdf")) }) { Text("PDF-Anlage hinzufügen") } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        vm.run {
                            val json = JSONObject().put("report", report.json()).put("claim", claim.json()).toString()
                            withContext(Dispatchers.IO) { vm.repo.personal("penaltyDraft", json) }
                            vm.refresh()
                            vm.notice = "Entwurf gespeichert."
                        }
                    }
                ) {
                    Text("Entwurf speichern")
                }
                OutlinedButton(
                    onClick = {
                        val raw = vm.company[vm.repo.personalKey("penaltyDraft")]
                        if (raw.isNullOrBlank()) vm.notice = "Kein Entwurf vorhanden."
                        else runCatching {
                                val o = JSONObject(raw)
                                report = InspectionReport.from(o.getJSONObject("report"))
                                claim = PenaltyDocument.from(o.getJSONObject("claim"))
                                worker = null
                                attachments.clear()
                                vm.notice = "Entwurf geladen. Mitarbeiter und Anlagen erneut auswählen."
                            }
                            .onFailure { vm.error = "Entwurf kann nicht gelesen werden." }
                    }
                ) {
                    Text("Entwurf laden")
                }
            }
        }
        item {
            Button(
                onClick = {
                    vm.run {
                        val w = worker ?: error("Bitte den Mitarbeiter für die Profilablage übernehmen.")
                        require(vm.can(AccessAction.CREATE, "documents")) { "Keine Berechtigung zum Ablegen in der Personalakte." }
                        val name = if (mode.includesClaim) "Vertragsstrafe-${claim.invoiceNumber}" else "Kontrollprotokoll-${report.reference}"
                        vm.showPdf(PdfKit.safeName(name), archiveTo = w.id, category = if (mode.includesClaim) "Vertragsstrafe" else "Kontrollprotokoll") {
                            PenaltyPackage.render(vm.app, claim, report, mode, attachments.map { it.second })
                        }
                        vm.notice = "Im Mitarbeiterprofil gespeichert."
                    }
                },
                enabled = vm.canExport("exports"),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("PDF erstellen")
            }
        }
    }
}
