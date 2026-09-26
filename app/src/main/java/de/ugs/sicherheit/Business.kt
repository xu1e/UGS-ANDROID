package de.ugs.sicherheit

import android.content.Context
import android.graphics.Color
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

enum class BusinessKind {
    COOPERATION,
    OFFER,
}

private fun JSONObject.str(k: String, d: String = "") = optString(k, d)

data class BusinessParty(
    val name: String = "",
    val address: String = "",
    val representative: String = "",
    val phone: String = "",
    val email: String = "",
    val website: String = "",
    val registration: String = "",
    val bank: String = "",
) {
    val postalBlock
        get() = listOf(name, address).filter { it.isNotEmpty() }.joinToString("\n")

    fun json() =
        JSONObject().put("name", name).put("address", address).put("representative", representative).put("phone", phone)
            .put("email", email).put("website", website).put("registration", registration).put("bank", bank)

    companion object {
        fun of(c: Map<String, String>) =
            BusinessParty(
                c["name"].orEmpty(),
                PenaltyDocument.address(c["street"].orEmpty(), c["postalCode"].orEmpty(), c["city"].orEmpty()),
                c["representative"].orEmpty(),
                c["phone"].orEmpty(),
                c["email"].orEmpty(),
                c["website"].orEmpty(),
                listOf(c["registration"].orEmpty(), c["taxNumber"].orEmpty().let { if (it.isBlank()) "" else "Steuernummer $it" }).filter { it.isNotBlank() }.joinToString("\n"),
                c["bank"].orEmpty(),
            )

        fun from(o: JSONObject?) =
            if (o == null) BusinessParty()
            else BusinessParty(o.str("name"), o.str("address"), o.str("representative"), o.str("phone"), o.str("email"), o.str("website"), o.str("registration"), o.str("bank"))
    }
}

data class BusinessClause(val id: String = UUID.randomUUID().toString(), val title: String, val text: String)

/** Gemeinsames Einsatzblatt für Angebote und Rahmenverträge. */
data class BusinessAssignment(
    val included: Boolean = true,
    val site: String = "",
    val address: String = "",
    val period: String = "",
    val schedule: String = "",
    val staffing: String = "",
    val qualifications: String = "",
    val scope: String = "",
    val exclusions: String = "",
    val equipment: String = "",
    val contacts: String = "",
    val rates: String = "",
) {
    val validationError: String?
        get() {
            for ((label, v) in
                listOf(
                    "Objekt" to site, "Objektanschrift" to address, "Einsatzzeitraum" to period, "Einsatzzeiten" to schedule,
                    "Besetzung" to staffing, "Qualifikation" to qualifications, "Leistungsumfang" to scope, "Ausstattung" to equipment,
                    "Kontakt / Alarmweg" to contacts,
                )) if (v.isBlank()) return "Einsatzblatt: Bitte $label ergänzen."
            return null
        }

    val fields
        get() =
            listOf(
                "Objekt" to site, "Anschrift" to address, "Zeitraum" to period, "Einsatzzeiten" to schedule, "Besetzung" to staffing,
                "Qualifikation" to qualifications, "Leistungsumfang" to scope, "Leistungsgrenzen" to exclusions,
                "Ausstattung / Bereitstellung" to equipment, "Ansprechpartner / Alarmweg" to contacts, "Preise / besondere Vereinbarungen" to rates,
            )

    fun json() =
        JSONObject().put("included", included).put("site", site).put("address", address).put("period", period).put("schedule", schedule)
            .put("staffing", staffing).put("qualifications", qualifications).put("scope", scope).put("exclusions", exclusions)
            .put("equipment", equipment).put("contacts", contacts).put("rates", rates)

    companion object {
        fun from(o: JSONObject?) =
            if (o == null) BusinessAssignment()
            else
                BusinessAssignment(
                    o.optBoolean("included", true), o.str("site"), o.str("address"), o.str("period"), o.str("schedule"), o.str("staffing"),
                    o.str("qualifications"), o.str("scope"), o.str("exclusions"), o.str("equipment"), o.str("contacts"), o.str("rates"),
                )
    }
}

data class CooperationDocument(
    val title: String = "KOOPERATIONSVERTRAG",
    val subtitle: String = "über SICHERHEITSDIENSTLEISTUNGEN",
    val separateCover: Boolean = false,
    val reference: String = "",
    val date: String = today,
    val client: BusinessParty = BusinessParty(),
    val contractor: BusinessParty = BusinessParty(),
    val clientPlace: String = "",
    val contractorPlace: String = "",
    val jurisdiction: String = "",
    val privacyEmail: String = "",
    val privacyPhone: String = "",
    val invoiceEmail: String = "",
    val clauses: List<BusinessClause> = emptyList(),
    val annexes: String = "",
    val assignment: BusinessAssignment = BusinessAssignment(),
) {
    fun applyTemplate(c: Context): CooperationDocument {
        val o = JSONObject(c.assets.open("business/ugs-template.json").bufferedReader().readText())
        val a = o.getJSONArray("clauses")
        return copy(
            title = o.str("title", title),
            subtitle = o.str("subtitle", subtitle),
            separateCover = o.optBoolean("separateCover"),
            jurisdiction = o.str("jurisdiction"),
            clauses = (0 until a.length()).map { a.getJSONObject(it).let { x -> BusinessClause(x.str("id", UUID.randomUUID().toString()), x.str("title"), x.str("text")) } },
            annexes = o.str("annexes"),
        )
    }

    val replacements
        get() =
            mapOf(
                "AG" to client.name, "AN" to contractor.name, "GERICHTSSTAND" to jurisdiction,
                "DATENSCHUTZ_EMAIL" to privacyEmail, "DATENSCHUTZ_TELEFON" to privacyPhone, "RECHNUNG_EMAIL" to invoiceEmail,
            )

    /** Ein Durchgang: eingegebene Werte können keinen weiteren Platzhalter erzeugen. */
    fun resolved(text: String) =
        Regex("\\{\\{([^{}]+)}}").replace(text) { m -> replacements[m.groupValues[1]] ?: m.value }

    val validationError: String?
        get() {
            for ((t, v) in
                listOf(
                    "Vertragsnummer" to reference, "Vertragstitel" to title, "Auftraggeber" to client.name, "Anschrift Auftraggeber" to client.address,
                    "Auftragnehmer" to contractor.name, "Anschrift Auftragnehmer" to contractor.address, "Ort Auftraggeber" to clientPlace,
                    "Ort Auftragnehmer" to contractorPlace,
                )) if (v.isBlank()) return "Bitte ergänzen: $t."
            if (runCatching { LocalDate.parse(date) }.isFailure) return "Bitte ein gültiges Vertragsdatum eintragen."
            if (assignment.included) {
                assignment.validationError?.let { return it }
                if (assignment.rates.isBlank()) return "Einsatzblatt: Bitte Vergütung / Preise vereinbaren."
            }
            if (clauses.isEmpty()) return "Bitte Vertragsabschnitte laden oder ergänzen."
            val content = clauses.joinToString("\n") { it.title + "\n" + it.text } + "\n" + annexes
            val labels =
                mapOf("AG" to "Auftraggeber", "AN" to "Auftragnehmer", "GERICHTSSTAND" to "Gerichtsstand", "DATENSCHUTZ_EMAIL" to "Datenschutz-E-Mail", "DATENSCHUTZ_TELEFON" to "Datenschutz-Telefon", "RECHNUNG_EMAIL" to "Rechnungseingang-E-Mail")
            for (k in replacements.keys.sorted()) if (content.contains("{{$k}}") && replacements[k].orEmpty().isBlank()) return "Bitte ergänzen: ${labels[k] ?: k}."
            val r = resolved(content)
            if (r.contains("{{") || r.contains("}}")) return "Ein Vertragsplatzhalter ist unbekannt. Bitte im Vertragstext korrigieren."
            if (clauses.any { it.title.isBlank() || it.text.isBlank() }) return "Bitte Überschrift und Text aller Vertragsabschnitte ergänzen."
            return null
        }

    fun json(): JSONObject =
        JSONObject().put("title", title).put("subtitle", subtitle).put("separateCover", separateCover).put("reference", reference).put("date", date)
            .put("client", client.json()).put("contractor", contractor.json()).put("clientPlace", clientPlace).put("contractorPlace", contractorPlace)
            .put("jurisdiction", jurisdiction).put("privacyEmail", privacyEmail).put("privacyPhone", privacyPhone).put("invoiceEmail", invoiceEmail)
            .put("clauses", JSONArray(clauses.map { JSONObject().put("id", it.id).put("title", it.title).put("text", it.text) }))
            .put("annexes", annexes).put("assignment", assignment.json())

    companion object {
        fun from(o: JSONObject): CooperationDocument {
            val a = o.optJSONArray("clauses") ?: JSONArray()
            return CooperationDocument(
                o.str("title", "KOOPERATIONSVERTRAG"), o.str("subtitle"), o.optBoolean("separateCover"), o.str("reference"), o.str("date", today),
                BusinessParty.from(o.optJSONObject("client")), BusinessParty.from(o.optJSONObject("contractor")), o.str("clientPlace"),
                o.str("contractorPlace"), o.str("jurisdiction"), o.str("privacyEmail"), o.str("privacyPhone"), o.str("invoiceEmail"),
                (0 until a.length()).map { a.getJSONObject(it).let { x -> BusinessClause(x.str("id"), x.str("title"), x.str("text")) } },
                o.str("annexes"), BusinessAssignment.from(o.optJSONObject("assignment")),
            )
        }
    }
}

data class OfferLine(
    val id: String = UUID.randomUUID().toString(),
    val position: String = "1",
    val title: String = "",
    val details: String = "",
    val quantity: String = "1",
    val unit: String = "Stunden",
    val unitPrice: String = "",
    val included: Boolean = true,
) {
    val amount: BigDecimal?
        get() {
            val q = Money.number(quantity, 3) ?: return null
            val p = Money.number(unitPrice) ?: return null
            return Money.round(q * p)
        }
}

data class OfferService(val id: String, val title: String, val details: String, val unit: String, val included: Boolean) {
    companion object {
        fun load(c: Context): List<OfferService> {
            val a = JSONArray(c.assets.open("business/offer-services.json").bufferedReader().readText())
            return (0 until a.length()).map { a.getJSONObject(it).let { o -> OfferService(o.str("id"), o.str("title"), o.str("details"), o.str("unit"), o.optBoolean("included", true)) } }
        }
    }
}

data class OfferDocument(
    val issuer: BusinessParty = BusinessParty(),
    val customer: BusinessParty = BusinessParty(),
    val number: String = "",
    val customerNumber: String = "",
    val date: String = today,
    val validUntil: String = "",
    val title: String = "Ihr Sicherheitskonzept · Angebot",
    val assignment: BusinessAssignment = BusinessAssignment(),
    val introduction: String =
        "Sehr geehrte Damen und Herren,\n\nUGS Sicherheit bietet Ihnen die nachstehend beschriebenen Sicherheitsdienstleistungen an. Einsatzumfang und Leistungsgrenzen ergeben sich aus dem Einsatzblatt und den einzelnen Positionen.",
    val lines: List<OfferLine> = listOf(OfferLine()),
    val taxPercent: String = "19",
    val terms: String =
        "Dieses Angebot ist bis zum angegebenen Gültigkeitsdatum bindend und kann durch unveränderte Bestätigung in Textform angenommen werden. Änderungen oder die Beauftragung optionaler Positionen bedürfen einer gesonderten Vereinbarung.\nDie Preise sind Nettopreise zuzüglich der ausgewiesenen Umsatzsteuer. Erforderliche Nacht-, Sonn- und Feiertagszuschläge, Fahrtkosten und Ausstattung sind entweder im Positionspreis enthalten oder ausdrücklich als eigene Position ausgewiesen; nicht ausgewiesene Zuschläge werden nicht zusätzlich berechnet.\nMengen sind die für den beschriebenen Einsatz kalkulierten Mengen. Bei Abrechnung nach Aufwand werden die tatsächlich beauftragten und dokumentierten Leistungen zu den vereinbarten Einheitspreisen abgerechnet; Pauschalpositionen bleiben unverändert. Erweiterungen werden vor Ausführung abgestimmt.\nDie Abrechnung erfolgt monatlich nach Leistungserbringung, bei einmaligen Einsätzen nach Abschluss. Zahlungsziel: 30 Kalendertage nach Zugang der prüffähigen Rechnung, ohne Skonto. Es gelten die gesetzlichen Haftungsregelungen. Ein bestimmter Sicherheits- oder Schadensverhinderungserfolg wird nicht zugesagt.",
    val closing: String =
        "Wir freuen uns auf die Zusammenarbeit. Bitte bestätigen Sie bei Annahme die Angebotsnummer und den vereinbarten Einsatzumfang. Für die Einsatzabstimmung steht Ihnen der oben genannte Ansprechpartner zur Verfügung.",
    val includeAcceptance: Boolean = true,
) {
    val net: BigDecimal
        get() = lines.filter { it.included }.fold(BigDecimal.ZERO) { a, l -> a + (l.amount ?: BigDecimal.ZERO) }

    val tax: BigDecimal
        get() = Money.round(net * (Money.number(taxPercent) ?: BigDecimal.ZERO) / BigDecimal(100))

    val total: BigDecimal
        get() = net + tax

    val validationError: String?
        get() {
            for ((n, v) in listOf("Angebotsnummer" to number, "Angebotstitel" to title, "Absender" to issuer.name, "Absenderanschrift" to issuer.address, "Kunde" to customer.name, "Kundenanschrift" to customer.address))
                if (v.isBlank()) return "Bitte ergänzen: $n."
            val d = runCatching { LocalDate.parse(date) }.getOrNull()
            val u = runCatching { LocalDate.parse(validUntil) }.getOrNull()
            if (d == null || u == null || u < d) return "Gültig bis muss ein gültiges Datum ab dem Angebotsdatum sein."
            assignment.validationError?.let { return it }
            if (lines.isEmpty()) return "Bitte mindestens eine Position ergänzen."
            val positions = mutableSetOf<String>()
            lines.forEachIndexed { i, l ->
                val p = l.position.trim()
                if (p.isEmpty() || !positions.add(p)) return "Positionsnummern müssen ausgefüllt und eindeutig sein."
                val q = Money.number(l.quantity, 3)
                val price = Money.number(l.unitPrice)
                if (l.title.isBlank() || l.unit.isBlank() || q == null || q <= BigDecimal.ZERO || q > BigDecimal(99999) || price == null || price > BigDecimal("9999999.99"))
                    return "Position ${i + 1}: Bezeichnung, Einheit, positive Menge (bis 99999) und Preis (bis 9999999,99) prüfen. Keine Tausendertrennzeichen verwenden."
            }
            val rate = Money.number(taxPercent)
            if (rate == null || rate > BigDecimal(100)) return "Bitte einen Steuersatz zwischen 0 und 100 eingeben."
            return null
        }

    fun json(): JSONObject =
        JSONObject().put("issuer", issuer.json()).put("customer", customer.json()).put("number", number).put("customerNumber", customerNumber)
            .put("date", date).put("validUntil", validUntil).put("title", title).put("assignment", assignment.json()).put("introduction", introduction)
            .put("lines", JSONArray(lines.map { JSONObject().put("id", it.id).put("position", it.position).put("title", it.title).put("details", it.details).put("quantity", it.quantity).put("unit", it.unit).put("unitPrice", it.unitPrice).put("included", it.included) }))
            .put("taxPercent", taxPercent).put("terms", terms).put("closing", closing).put("includeAcceptance", includeAcceptance)

    companion object {
        fun from(o: JSONObject): OfferDocument {
            val a = o.optJSONArray("lines") ?: JSONArray()
            val d = OfferDocument()
            return OfferDocument(
                BusinessParty.from(o.optJSONObject("issuer")), BusinessParty.from(o.optJSONObject("customer")), o.str("number"), o.str("customerNumber"),
                o.str("date", today), o.str("validUntil"), o.str("title", d.title), BusinessAssignment.from(o.optJSONObject("assignment")),
                o.str("introduction", d.introduction),
                (0 until a.length()).map { a.getJSONObject(it).let { x -> OfferLine(x.str("id"), x.str("position"), x.str("title"), x.str("details"), x.str("quantity"), x.str("unit"), x.str("unitPrice"), x.optBoolean("included", true)) } },
                o.str("taxPercent", "19"), o.str("terms", d.terms), o.str("closing", d.closing), o.optBoolean("includeAcceptance", true),
            )
        }
    }
}

/** Seitenweise Zeichnung der Geschäftsdokumente (Angebot, Kooperationsvertrag). */
object BusinessPdf {
    private const val W = 595.2f
    private const val H = 841.8f
    private const val BOTTOM = 748f
    private val FILL = Color.rgb(227, 240, 252)

    private sealed class Mark {
        data class Text(val v: String, val x: Float, val top: Float, val size: Float, val bold: Boolean) : Mark()

        data class Rule(val x: Float, val top: Float, val w: Float, val h: Float, val fill: Boolean) : Mark()

        data class Logo(val x: Float, val top: Float, val w: Float) : Mark()
    }

    private val probe by lazy { Sheet(android.graphics.Canvas(), 10f, 10f) }

    fun measure(v: String, size: Float, bold: Boolean = false) = probe.measure(v, size, if (bold) Face.BOLD else Face.REGULAR)

    fun wrap(t: String, w: Float, size: Float, bold: Boolean = false) = PdfKit.wrap(t, w) { measure(it, size, bold) }

    private class Board {
        val pages = mutableListOf(mutableListOf<Mark>())
        var y = 105f

        fun add(m: Mark) = pages.last().add(m)

        fun text(v: String, x: Float, top: Float, size: Float = 10.5f, bold: Boolean = false) = add(Mark.Text(v, x, top, size, bold))

        fun line(x: Float, top: Float, w: Float) = add(Mark.Rule(x, top, w, .5f, false))

        fun box(x: Float, top: Float, w: Float, h: Float, fill: Boolean = false) = add(Mark.Rule(x, top, w, h, fill))

        fun nextPage() {
            pages += mutableListOf<Mark>()
            y = 105f
        }

        fun ensure(h: Float) {
            if (y + h > BOTTOM) nextPage()
        }

        fun fixed(v: String, x: Float, top: Float, w: Float, size: Float = 10.5f, bold: Boolean = false, maxLines: Int = 4, label: String) {
            val lines = wrap(v, w, size, bold)
            require(lines.size <= maxLines) { "$label: Text zu lang für dieses Feld. Bitte kürzen." }
            lines.forEachIndexed { i, l -> text(l, x, top + i * (size + 3), size, bold) }
        }

        fun paragraph(v: String, size: Float = 10.5f, bold: Boolean = false, x: Float = 44f, w: Float = 507f, gap: Float = 8f) {
            if (v.isEmpty()) return
            for (l in wrap(v, w, size, bold)) {
                ensure(size + 4)
                text(l, x, y, size, bold)
                y += size + 4
            }
            y += gap
        }

        fun heading(v: String) {
            ensure(wrap(v, 507f, 11f, true).size * 15f + 35)
            paragraph(v, 11f, true)
        }

        fun footers(reference: String, date: String, issuer: BusinessParty?, contract: Boolean) {
            val existing = pages.map { it.toList() }
            existing.indices.forEach { i ->
                val d = Board()
                d.add(Mark.Logo(378f, 30f, 145f))
                d.box(44f, 94f, 507f, 3f, true)
                if (contract) {
                    d.fixed(reference, 71f, 790f, 185f, 8f, maxLines = 2, label = "Vertragsnummer")
                    d.text("${i + 1} / ${existing.size}", 282f, 790f, 8f)
                    d.text(DateText.german(date), 447f, 790f, 8f)
                } else if (issuer != null) {
                    d.fixed(issuer.postalBlock.replace("\n", " · "), 44f, 79f, 305f, 7f, maxLines = 2, label = "Absenderzeile")
                    d.line(44f, 771f, 507f)
                    d.fixed(issuer.bank, 44f, 784f, 157f, 7f, maxLines = 5, label = "Bankverbindung")
                    d.fixed(listOf(issuer.representative, issuer.registration).filter { it.isNotEmpty() }.joinToString("\n"), 219f, 784f, 157f, 7f, maxLines = 5, label = "Registerangaben")
                    d.fixed(issuer.postalBlock, 395f, 784f, 156f, 7f, maxLines = 4, label = "Firmenfußzeile")
                    d.text("Seite ${i + 1} / ${existing.size}", 489f, 830f, 7f)
                }
                pages[i] = (existing[i] + d.pages[0]).toMutableList()
            }
        }

        fun write(c: Context, attachments: List<ByteArray>): ByteArray {
            val writer = PdfWriter(W, H)
            for (page in pages) {
                val s = writer.begin(W, H)
                for (m in page)
                    when (m) {
                        is Mark.Text -> s.fit(m.v, m.x, m.top, W, m.size, m.size, if (m.bold) Face.BOLD else Face.REGULAR)
                        is Mark.Rule -> if (m.fill) s.fill(m.x, m.top, m.w, m.h, FILL) else s.rect(m.x, m.top, m.w, m.h, Color.GRAY, .5f)
                        is Mark.Logo -> PdfKit.logo(c, s, m.x, m.top, m.w)
                    }
            }
            return PdfKit.merge(listOf(writer.bytes()) + attachments)
        }
    }

    private fun assignment(a: BusinessAssignment, b: Board) {
        for ((label, value) in a.fields) {
            if (value.isBlank()) continue
            val labels = wrap(label.uppercase(), 143f, 7.5f, true)
            val values = wrap(value, 352f, 9.5f)
            b.ensure(minOf(maxOf(labels.size * 10f, values.size * 13.5f) + 10, BOTTOM - 105))
            val top = b.y
            val page = b.pages.size
            labels.forEachIndexed { i, l -> b.text(l, 44f, top + i * 10, 7.5f, true) }
            b.paragraph(value, 9.5f, x = 199f, w = 352f, gap = 10f)
            if (b.pages.size == page && b.y < top + labels.size * 10 + 10) b.y = top + labels.size * 10 + 10
        }
    }

    fun cooperation(c: Context, d: CooperationDocument, attachments: List<Pair<String, ByteArray>>): ByteArray {
        d.validationError?.let { throw IllegalArgumentException(it) }
        val b = Board()
        b.y = 120f
        b.paragraph(d.title, 21f, true)
        b.paragraph(d.subtitle, 11f)
        b.paragraph("Vertrag ${d.reference} · ${DateText.german(d.date)}", 9f)
        b.y += 10
        val partyTop = b.y
        for ((party, x, label) in listOf(Triple(d.client, 44f, "AUFTRAGGEBER"), Triple(d.contractor, 306f, "AUFTRAGNEHMER"))) {
            b.box(x, partyTop, 245f, 147f, true)
            b.text(label, x + 12, partyTop + 12, 8f, true)
            b.fixed(party.postalBlock, x + 12, partyTop + 32, 221f, 10f, maxLines = 5, label = label)
            b.fixed(party.representative, x + 12, partyTop + 108, 221f, 9f, maxLines = 2, label = "Vertretung")
        }
        b.y = partyTop + 167
        if (d.separateCover) b.nextPage()
        for (clause in d.clauses) {
            val block = wrap(d.resolved(clause.title), 507f, 11f, true).size * 15f + 8 + wrap(d.resolved(clause.text), 507f, 10f).size * 14f + 7
            if (block <= BOTTOM - 105) b.ensure(block)
            b.heading(d.resolved(clause.title))
            b.paragraph(d.resolved(clause.text), 10f, gap = 7f)
        }
        if (d.assignment.included) {
            b.nextPage()
            b.heading("Anlage 1 · Einsatzblatt / Einzelauftrag")
            b.paragraph("Zum Rahmenvertrag ${d.reference}", 9f)
            assignment(d.assignment, b)
        }
        if (d.annexes.isNotEmpty()) {
            b.heading("Anlagen")
            b.paragraph(d.resolved(d.annexes))
        }
        if (attachments.isNotEmpty()) b.paragraph("Beigefügte PDF-Anlagen:\n" + attachments.joinToString("\n") { it.first }, 9f)
        b.ensure(175f)
        b.heading("Unterzeichnung")
        val top = b.y + 8
        for ((party, place, x, label) in listOf(Quad(d.client, d.clientPlace, 71f, "Auftraggeber"), Quad(d.contractor, d.contractorPlace, 331f, "Auftragnehmer / Kooperationspartner"))) {
            b.fixed("$place, ${DateText.german(d.date)}", x, top, 193f, 9f, maxLines = 2, label = "Ort / Datum")
            b.line(x, top + 33, 193f)
            b.text("Ort / Datum", x, top + 36, 8f)
            b.fixed(party.representative, x, top + 59, 193f, 9f, maxLines = 2, label = "Vertreter")
            b.line(x, top + 109, 193f)
            b.text(label, x, top + 113, 8f)
            b.text("Unterschrift & Stempel", x, top + 125, 8f)
        }
        b.footers(d.reference, d.date, null, true)
        return b.write(c, attachments.map { it.second })
    }

    private data class Quad(val party: BusinessParty, val place: String, val x: Float, val label: String)

    fun offer(c: Context, d: OfferDocument, attachments: List<Pair<String, ByteArray>>): ByteArray {
        d.validationError?.let { throw IllegalArgumentException(it) }
        val b = Board()
        b.fixed(d.customer.postalBlock, 44f, 126f, 300f, 11f, maxLines = 7, label = "Kundenanschrift")
        val contact = listOf("Ihr Ansprechpartner:", d.issuer.representative, d.issuer.phone, d.issuer.email, d.issuer.website).filter { it.isNotEmpty() }.joinToString("\n")
        b.fixed(contact, 378f, 126f, 173f, 9f, maxLines = 10, label = "Ansprechpartner")
        b.y = 280f
        b.paragraph(d.title, 12f, true, gap = 13f)
        b.paragraph(d.introduction, 10f, gap = 13f)
        b.heading("Einsatzübersicht")
        assignment(d.assignment, b)
        b.ensure(57f)
        b.line(44f, b.y, 507f)
        for ((label, value, x, w) in listOf(
            Quad4("Angebotsnr.", d.number, 44f, 145f),
            Quad4("Kundennr.", d.customerNumber, 199f, 120f),
            Quad4("Datum", DateText.german(d.date), 329f, 100f),
            Quad4("gültig bis", DateText.german(d.validUntil), 439f, 112f),
        )) {
            b.text(label, x, b.y + 5, 9f, true)
            b.fixed(value, x, b.y + 22, w, 9f, maxLines = 2, label = label)
        }
        b.y += 51
        b.line(44f, b.y, 507f)
        b.y += 18
        val xs = listOf(44f, 82f, 302f, 349f, 403f, 475f, 551f)
        fun header() {
            b.box(44f, b.y, 507f, 24f, true)
            listOf("Pos.", "Bezeichnung", "Menge", "Einheit", "Einzel (€)", "Gesamt (€)").forEachIndexed { i, t ->
                b.text(t, xs[i] + 4, b.y + 7, 8f, true)
                b.box(xs[i], b.y, xs[i + 1] - xs[i], 24f)
            }
            b.y += 24
        }
        fun newTablePage() {
            b.nextPage()
            header()
        }
        b.ensure(65f)
        header()
        for (row in d.lines) {
            val detail = (if (row.included) "" else "Bedarfsposition (nicht in Gesamtsumme)\n") + row.title + if (row.details.isEmpty()) "" else "\n\n" + row.details
            val values =
                listOf(
                    row.position, detail, row.quantity.replace(".", ","), row.unit,
                    Money.text(Money.number(row.unitPrice)!!).replace("€", "").trim(), Money.text(row.amount!!).replace("€", "").trim(),
                )
            val cells = values.mapIndexed { i, v -> wrap(v, xs[i + 1] - xs[i] - 8, 8.5f) }
            val count = cells.maxOf { it.size }
            if (count * 11.5f + 12 <= 615 && b.y + count * 11.5f + 12 > BOTTOM) newTablePage()
            var offset = 0
            var guard = 0
            while (offset < count && guard++ < 1000) {
                val capacity = ((BOTTOM - b.y - 12) / 11.5f).toInt()
                if (capacity < 1) {
                    newTablePage()
                    continue
                }
                val chunk = minOf(capacity, count - offset)
                val h = chunk * 11.5f + 12
                for (i in cells.indices) {
                    b.box(xs[i], b.y, xs[i + 1] - xs[i], h)
                    for (n in 0 until chunk) if (offset + n < cells[i].size) b.text(cells[i][offset + n], xs[i] + 4, b.y + 6 + n * 11.5f, 8.5f)
                }
                b.y += h
                offset += chunk
                if (offset < count) newTablePage()
            }
        }
        b.y += 14
        b.ensure(81f)
        listOf("Zwischensumme (netto)" to d.net, "Umsatzsteuer ${d.taxPercent} %" to d.tax, "Gesamtbetrag" to d.total).forEachIndexed { i, (label, v) ->
            b.box(44f, b.y, 507f, 25f, i == 2)
            b.text(label, 52f, b.y + 6, 10f, i == 2)
            val amount = Money.text(v)
            require(measure(amount, 10f, i == 2) < 175) { "Gesamtbetrag ist zu groß für die PDF-Spalte." }
            b.text(amount, 541 - measure(amount, 10f, i == 2), b.y + 6, 10f, i == 2)
            b.y += 25
        }
        b.y += 18
        if (d.lines.any { !it.included }) b.paragraph("Bedarfspositionen sind nicht in der Gesamtsumme enthalten.", 9f)
        b.paragraph(d.terms, 10f)
        val farewell = "Mit freundlichen Grüßen\n\n" + listOf(d.issuer.representative, d.issuer.name).filter { it.isNotEmpty() }.joinToString("\n")
        val farewellHeight = wrap(farewell, 507f, 10f).size * 14f + 8
        val acceptance = if (d.includeAcceptance) 96f else 0f
        val ending = wrap(d.closing, 507f, 10f).size * 14f + 8 + farewellHeight + acceptance
        if (ending <= BOTTOM - 105) b.ensure(ending)
        b.paragraph(d.closing, 10f)
        b.ensure(minOf(farewellHeight + acceptance, BOTTOM - 105))
        b.paragraph(farewell, 10f)
        if (d.includeAcceptance) {
            b.ensure(96f)
            b.paragraph("Annahme: Hiermit beauftragen wir die enthaltenen Leistungen gemäß Angebot ${d.number}. Bedarfspositionen nur nach gesonderter Bestätigung.", 9f, gap = 5f)
            b.y += 28
            b.line(44f, b.y, 186f)
            b.text("Datum, Unterschrift Kunde", 44f, b.y + 5, 9f)
        }
        b.footers(d.number, d.date, d.issuer, false)
        return b.write(c, attachments.map { it.second })
    }

    private data class Quad4(val label: String, val value: String, val x: Float, val w: Float)
}
