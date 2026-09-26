@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.ugs.sicherheit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private fun LazyListScope.party(title: String, p: BusinessParty, change: (BusinessParty) -> Unit) {
    item { SectionHeader(title) }
    item { TextRow("Firma / Name", p.name) { change(p.copy(name = it)) } }
    item { TextRow("Anschrift", p.address, 2) { change(p.copy(address = it)) } }
    item { TextRow("Vertretung / Ansprechpartner", p.representative) { change(p.copy(representative = it)) } }
    item { TextRow("Telefon", p.phone) { change(p.copy(phone = it)) } }
    item { TextRow("E-Mail", p.email) { change(p.copy(email = it)) } }
    item { TextRow("Website", p.website) { change(p.copy(website = it)) } }
    item { TextRow("Register / Steuerangaben", p.registration, 2) { change(p.copy(registration = it)) } }
    item { TextRow("Bank / IBAN / BIC", p.bank, 2) { change(p.copy(bank = it)) } }
}

private fun LazyListScope.assignment(a: BusinessAssignment, change: (BusinessAssignment) -> Unit) {
    val rows: List<Triple<String, String, (String) -> BusinessAssignment>> =
        listOf(
            Triple("Objekt", a.site) { v -> a.copy(site = v) },
            Triple("Objektanschrift", a.address) { v -> a.copy(address = v) },
            Triple("Einsatzzeitraum", a.period) { v -> a.copy(period = v) },
            Triple("Einsatzzeiten", a.schedule) { v -> a.copy(schedule = v) },
            Triple("Besetzung", a.staffing) { v -> a.copy(staffing = v) },
            Triple("Qualifikationen", a.qualifications) { v -> a.copy(qualifications = v) },
            Triple("Leistungsumfang", a.scope) { v -> a.copy(scope = v) },
            Triple("Leistungsgrenzen", a.exclusions) { v -> a.copy(exclusions = v) },
            Triple("Ausstattung", a.equipment) { v -> a.copy(equipment = v) },
            Triple("Kontakte / Alarmweg", a.contacts) { v -> a.copy(contacts = v) },
            Triple("Preise / besondere Vereinbarungen", a.rates) { v -> a.copy(rates = v) },
        )
    for ((label, value, set) in rows) item { TextRow(label, value, 2) { change(set(it)) } }
}

@Composable
fun BusinessScreen(vm: UGSViewModel, kind: BusinessKind) {
    key(kind) { if (kind == BusinessKind.OFFER) OfferScreen(vm) else CooperationScreen(vm) }
}

@Composable
private fun AttachmentList(vm: UGSViewModel, attachments: SnapshotStateList<Pair<String, ByteArray>>) {
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            vm.run {
                for (uri in uris) {
                    val bytes = withContext(Dispatchers.IO) { ImportService.read(vm.app, uri) }
                    require(bytes.size > 4 && String(bytes, 0, 4) == "%PDF") { "Bitte eine lesbare PDF-Datei auswählen." }
                    attachments += ImportService.name(vm.app, uri) to bytes
                }
            }
        }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader("PDF-Anlagen")
        attachments.toList().forEachIndexed { i, (name, _) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f))
                TextButton(onClick = { attachments.removeAt(i) }) { Text("Entfernen") }
            }
        }
        OutlinedButton(onClick = { pick.launch(arrayOf("application/pdf")) }) { Text("PDF-Anlage hinzufügen") }
        Text("Anlagen werden in dieser Reihenfolge angehängt. Gespeicherte Entwürfe enthalten die Formularangaben; Anlagen bitte erneut auswählen.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DraftButtons(vm: UGSViewModel, key: String, save: () -> String, load: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                vm.run {
                    val json = save()
                    withContext(Dispatchers.IO) { vm.repo.personal(key, json) }
                    vm.refresh()
                    vm.notice = "Entwurf gespeichert."
                }
            }
        ) {
            Text("Entwurf speichern")
        }
        OutlinedButton(
            onClick = {
                val raw = vm.company[vm.repo.personalKey(key)]
                if (raw.isNullOrBlank()) vm.notice = "Kein Entwurf vorhanden."
                else runCatching { load(raw) }.onSuccess { vm.notice = "Entwurf geladen. Anlagen erneut auswählen." }.onFailure { vm.error = "Entwurf kann nicht gelesen werden." }
            }
        ) {
            Text("Entwurf laden")
        }
    }
}

@Composable
fun CooperationScreen(vm: UGSViewModel) {
    var d by remember {
        mutableStateOf(
            runCatching { CooperationDocument().applyTemplate(vm.app) }.getOrDefault(CooperationDocument())
                .copy(client = BusinessParty.of(vm.company), clientPlace = vm.company["city"].orEmpty())
        )
    }
    val attachments = remember { mutableStateListOf<Pair<String, ByteArray>>() }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("UGS Kooperationsrahmenvertrag", style = MaterialTheme.typography.headlineSmall) }
        item { TextRow("Vertragsnummer", d.reference) { d = d.copy(reference = it) } }
        item { TextRow("Titel", d.title) { d = d.copy(title = it) } }
        item { TextRow("Untertitel", d.subtitle) { d = d.copy(subtitle = it) } }
        item { CalendarInput("Vertragsdatum", d.date, true) { d = d.copy(date = it) } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(d.separateCover, { d = d.copy(separateCover = it) })
                Text("Separates Deckblatt", Modifier.padding(start = 10.dp))
            }
        }
        party("Auftraggeber", d.client) { d = d.copy(client = it) }
        party("Auftragnehmer", d.contractor) { d = d.copy(contractor = it) }
        item { SectionHeader("Vertragsangaben") }
        item { TextRow("Ort Auftraggeber", d.clientPlace) { d = d.copy(clientPlace = it) } }
        item { TextRow("Ort Auftragnehmer", d.contractorPlace) { d = d.copy(contractorPlace = it) } }
        item { TextRow("Gerichtsstand", d.jurisdiction) { d = d.copy(jurisdiction = it) } }
        item { TextRow("Datenschutz-E-Mail", d.privacyEmail) { d = d.copy(privacyEmail = it) } }
        item { TextRow("Datenschutz-Telefon", d.privacyPhone) { d = d.copy(privacyPhone = it) } }
        item { TextRow("Rechnungseingang-E-Mail", d.invoiceEmail) { d = d.copy(invoiceEmail = it) } }
        item {
            SectionHeader("Einsatzblatt")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(d.assignment.included, { d = d.copy(assignment = d.assignment.copy(included = it)) })
                Text("Einsatzblatt beifügen", Modifier.padding(start = 10.dp))
            }
        }
        if (d.assignment.included) assignment(d.assignment) { d = d.copy(assignment = it) }
        item { SectionHeader("Vertragsabschnitte (${d.clauses.size})") }
        itemsIndexed(d.clauses, key = { _, c -> c.id }) { i, c ->
            var open by remember { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { open = !open }) { Text(c.title.ifBlank { "Neuer Abschnitt" }, fontWeight = FontWeight.SemiBold) }
                    if (open) {
                        TextRow("Überschrift", c.title) { v -> d = d.copy(clauses = d.clauses.toMutableList().also { it[i] = c.copy(title = v) }) }
                        TextRow("Vertragstext", c.text, 6) { v -> d = d.copy(clauses = d.clauses.toMutableList().also { it[i] = c.copy(text = v) }) }
                        TextButton(onClick = { d = d.copy(clauses = d.clauses.filter { it.id != c.id }) }) { Text("Abschnitt entfernen") }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = { d = d.copy(clauses = d.clauses + BusinessClause(title = "", text = "")) }) { Text("Abschnitt hinzufügen") } }
        item { TextRow("Anlagenverzeichnis", d.annexes, 3) { d = d.copy(annexes = it) } }
        item { AttachmentList(vm, attachments) }
        item { DraftButtons(vm, "cooperationDraft", { d.json().toString() }) { d = CooperationDocument.from(JSONObject(it)); attachments.clear() } }
        item {
            Button(
                onClick = { vm.run { vm.showPdf(PdfKit.safeName("Kooperationsvertrag-${d.reference}")) { BusinessPdf.cooperation(vm.app, d, attachments.toList()) } } },
                enabled = vm.canExport("exports"),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("PDF erstellen")
            }
        }
    }
}

@Composable
fun OfferScreen(vm: UGSViewModel) {
    var d by remember { mutableStateOf(OfferDocument(issuer = BusinessParty.of(vm.company))) }
    val services = remember { runCatching { OfferService.load(vm.app) }.getOrDefault(emptyList()) }
    val attachments = remember { mutableStateListOf<Pair<String, ByteArray>>() }
    var menu by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("UGS Angebot", style = MaterialTheme.typography.headlineSmall) }
        item { TextRow("Angebotsnummer", d.number) { d = d.copy(number = it) } }
        item { TextRow("Kundennummer", d.customerNumber) { d = d.copy(customerNumber = it) } }
        item { TextRow("Titel", d.title) { d = d.copy(title = it) } }
        item { CalendarInput("Datum", d.date, true) { d = d.copy(date = it) } }
        item { CalendarInput("Gültig bis", d.validUntil, true) { d = d.copy(validUntil = it) } }
        party("UGS / Absender", d.issuer) { d = d.copy(issuer = it) }
        party("Kunde", d.customer) { d = d.copy(customer = it) }
        item { SectionHeader("Einsatzblatt") }
        assignment(d.assignment) { d = d.copy(assignment = it) }
        item { SectionHeader("Einleitung") }
        item { TextRow("Einleitung", d.introduction, 4) { d = d.copy(introduction = it) } }
        item { SectionHeader("Positionen") }
        itemsIndexed(d.lines, key = { _, l -> l.id }) { i, l ->
            fun set(n: OfferLine) {
                d = d.copy(lines = d.lines.toMutableList().also { it[i] = n })
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${l.position} · ${l.title.ifBlank { "Neue Position" }} · ${l.amount?.let { Money.text(it) } ?: "—"}", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.weight(1f)) { TextRow("Position", l.position) { set(l.copy(position = it)) } }
                        Box(Modifier.weight(3f)) { TextRow("Leistung", l.title) { set(l.copy(title = it)) } }
                    }
                    TextRow("Beschreibung", l.details, 3) { set(l.copy(details = it)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.weight(1f)) { TextRow("Menge", l.quantity) { set(l.copy(quantity = it)) } }
                        Box(Modifier.weight(1f)) { TextRow("Einheit", l.unit) { set(l.copy(unit = it)) } }
                        Box(Modifier.weight(1f)) { TextRow("Einzelpreis netto", l.unitPrice) { set(l.copy(unitPrice = it)) } }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(l.included, { set(l.copy(included = it)) })
                        Text("Im Gesamtpreis enthalten", Modifier.weight(1f).padding(start = 8.dp))
                        TextButton(onClick = { d = d.copy(lines = d.lines.filter { it.id != l.id }) }) { Text("Entfernen") }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { d = d.copy(lines = d.lines + OfferLine(position = "${d.lines.size + 1}")) }) { Text("Leere Position") }
                Box {
                    OutlinedButton(onClick = { menu = true }, enabled = services.isNotEmpty()) { Text("Leistungsvorlage") }
                    DropdownMenu(menu, { menu = false }) {
                        services.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.title) },
                                onClick = {
                                    d = d.copy(lines = d.lines + OfferLine(position = "${d.lines.size + 1}", title = s.title, details = s.details, unit = s.unit, included = s.included))
                                    menu = false
                                },
                            )
                        }
                    }
                }
            }
        }
        item { TextRow("Umsatzsteuer %", d.taxPercent) { d = d.copy(taxPercent = it) } }
        item { Text("Netto: ${Money.text(d.net)} · Umsatzsteuer: ${Money.text(d.tax)} · Gesamt: ${Money.text(d.total)}", fontWeight = FontWeight.SemiBold) }
        item { SectionHeader("Konditionen und Abschluss") }
        item { TextRow("Konditionen", d.terms, 8) { d = d.copy(terms = it) } }
        item { TextRow("Abschluss", d.closing, 3) { d = d.copy(closing = it) } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(d.includeAcceptance, { d = d.copy(includeAcceptance = it) })
                Text("Annahmefeld beifügen", Modifier.padding(start = 10.dp))
            }
        }
        item { AttachmentList(vm, attachments) }
        item { DraftButtons(vm, "offerDraft", { d.json().toString() }) { d = OfferDocument.from(JSONObject(it)); attachments.clear() } }
        item {
            Button(
                onClick = { vm.run { vm.showPdf(PdfKit.safeName("Angebot-${d.number}")) { BusinessPdf.offer(vm.app, d, attachments.toList()) } } },
                enabled = vm.canExport("exports"),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("PDF erstellen")
            }
        }
    }
}
