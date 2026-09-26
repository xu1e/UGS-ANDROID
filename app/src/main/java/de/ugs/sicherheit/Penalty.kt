package de.ugs.sicherheit

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Beträge in Forderungen: genaue Dezimalarithmetik, kaufmännisch auf Cent gerundet. */
object Money {
    fun number(input: String, places: Int = 2): BigDecimal? {
        val t = input.trim()
        if (!t.matches(Regex("[0-9]{1,9}([,.][0-9]{1,$places})?"))) return null
        return t.replace(",", ".").toBigDecimalOrNull()
    }

    fun round(v: BigDecimal): BigDecimal = v.setScale(2, RoundingMode.HALF_UP)

    fun text(v: BigDecimal) = money(v.toDouble())
}

data class PenaltyLine(
    val id: String = UUID.randomUUID().toString(),
    val description: String = "",
    val quantity: String = "1",
    val unitPrice: String = "",
) {
    val amount: BigDecimal?
        get() {
            val q = Money.number(quantity, 3) ?: return null
            val p = Money.number(unitPrice) ?: return null
            return Money.round(q * p)
        }

    fun json() = JSONObject().put("id", id).put("description", description).put("quantity", quantity).put("unitPrice", unitPrice)

    companion object {
        fun from(o: JSONObject) = PenaltyLine(o.optString("id", UUID.randomUUID().toString()), o.optString("description"), o.optString("quantity", "1"), o.optString("unitPrice"))
    }
}

enum class TaxTreatment(val title: String) {
    UNSELECTED("Bitte einordnen"),
    DAMAGES("Echter Schadenersatz / Vertragsstrafe – nicht steuerbar"),
    TAXABLE("Steuerpflichtiges Leistungsentgelt"),
}

/** Vom Nutzer vorbereitete Forderung: bucht nichts und ändert kein Gehalt. */
data class PenaltyDocument(
    val issuer: String = "",
    val issuerAddress: String = "",
    val representative: String = "",
    val phone: String = "",
    val email: String = "",
    val website: String = "",
    val registration: String = "",
    val bank: String = "",
    val recipient: String = "",
    val recipientAddress: String = "",
    val customerNumber: String = "",
    val invoiceNumber: String = "",
    val date: String = today,
    val title: String = "Vertragsstrafe",
    val servicePeriod: String = "",
    val site: String = "",
    val incidentDate: String = "",
    val incident: String = "",
    val lines: List<PenaltyLine> = listOf(PenaltyLine()),
    val taxPercent: String = "19",
    val taxTreatment: TaxTreatment = TaxTreatment.UNSELECTED,
    val legalBasis: String = "",
    val paymentTerms: String = "Zahlung innerhalb von 14 Tagen ab Zugang dieser Forderung.",
    val closing: String = "Bitte geben Sie bei Rückfragen und Zahlungen die oben genannte Forderungsnummer an.",
) {
    fun fillCompany(c: Map<String, String>) =
        copy(
            issuer = issuer.ifBlank { c["name"].orEmpty() },
            issuerAddress = issuerAddress.ifBlank { address(c["street"].orEmpty(), c["postalCode"].orEmpty(), c["city"].orEmpty()) },
            representative = representative.ifBlank { c["representative"].orEmpty() },
            phone = phone.ifBlank { c["phone"].orEmpty() },
            email = email.ifBlank { c["email"].orEmpty() },
            website = website.ifBlank { c["website"].orEmpty() },
            registration = registration.ifBlank { listOf(c["registration"].orEmpty(), c["taxNumber"].orEmpty().let { if (it.isBlank()) "" else "Steuernummer $it" }).filter { it.isNotBlank() }.joinToString("\n") },
            bank = bank.ifBlank { c["bank"].orEmpty() },
        )

    fun fillRecipient(w: Entry) =
        copy(recipient = w.title, recipientAddress = address(w["street"], w["postalCode"], w["city"]), customerNumber = w["personnelNumber"])

    val net: BigDecimal
        get() = lines.fold(BigDecimal.ZERO) { a, l -> a + (l.amount ?: BigDecimal.ZERO) }

    val tax: BigDecimal
        get() =
            if (taxTreatment == TaxTreatment.TAXABLE)
                Money.round(net * (Money.number(taxPercent) ?: BigDecimal.ZERO) / BigDecimal(100))
            else BigDecimal.ZERO

    val total: BigDecimal
        get() = net + tax

    val validationError: String?
        get() {
            for ((label, v) in
                listOf(
                    "Absender" to issuer,
                    "Absenderanschrift" to issuerAddress,
                    "Empfänger" to recipient,
                    "Empfängeranschrift" to recipientAddress,
                    "Rechnungsnummer" to invoiceNumber,
                    "Leistungstitel" to title,
                    "Leistungszeitraum" to servicePeriod,
                    "Vertragliche Grundlage / Klausel / Begründung" to legalBasis,
                )) if (v.isBlank()) return "Bitte ergänzen: $label."
            if (runCatching { LocalDate.parse(date) }.isFailure) return "Bitte ein gültiges Rechnungsdatum eintragen."
            if (incidentDate.isNotEmpty() && runCatching { LocalDate.parse(incidentDate) }.isFailure) return "Bitte ein gültiges Vorfalldatum eintragen."
            if (lines.isEmpty()) return "Bitte mindestens eine Position ergänzen."
            lines.forEachIndexed { i, l ->
                val q = Money.number(l.quantity, 3)
                val p = Money.number(l.unitPrice)
                if (l.description.isBlank() || q == null || q <= BigDecimal.ZERO || q > BigDecimal(99999) || p == null || p > BigDecimal("9999999.99"))
                    return "Position ${i + 1}: Beschreibung, Menge (größer als 0, bis 99.999) und Pauschale (bis 9.999.999,99) prüfen. Beträge ohne Tausendertrennzeichen eingeben."
            }
            if (taxTreatment == TaxTreatment.UNSELECTED) return "Bitte die steuerliche Einordnung der Forderung auswählen."
            if (taxTreatment == TaxTreatment.TAXABLE) {
                val rate = Money.number(taxPercent)
                if (rate == null || rate > BigDecimal(100)) return "Bitte einen Steuersatz zwischen 0 und 100 eingeben."
            }
            return null
        }

    fun json(): JSONObject {
        val o = JSONObject()
        for ((k, v) in
            mapOf(
                "issuer" to issuer, "issuerAddress" to issuerAddress, "representative" to representative, "phone" to phone, "email" to email,
                "website" to website, "registration" to registration, "bank" to bank, "recipient" to recipient, "recipientAddress" to recipientAddress,
                "customerNumber" to customerNumber, "invoiceNumber" to invoiceNumber, "date" to date, "title" to title, "servicePeriod" to servicePeriod,
                "site" to site, "incidentDate" to incidentDate, "incident" to incident, "taxPercent" to taxPercent, "taxTreatment" to taxTreatment.name,
                "legalBasis" to legalBasis, "paymentTerms" to paymentTerms, "closing" to closing,
            )) o.put(k, v)
        return o.put("lines", JSONArray(lines.map { it.json() }))
    }

    companion object {
        fun address(street: String, postal: String, city: String) =
            listOf(street, listOf(postal, city).filter { it.isNotEmpty() }.joinToString(" ")).filter { it.isNotEmpty() }.joinToString("\n")

        fun from(o: JSONObject): PenaltyDocument {
            fun s(k: String, d: String = "") = o.optString(k, d)
            val a = o.optJSONArray("lines") ?: JSONArray()
            return PenaltyDocument(
                s("issuer"), s("issuerAddress"), s("representative"), s("phone"), s("email"), s("website"), s("registration"), s("bank"),
                s("recipient"), s("recipientAddress"), s("customerNumber"), s("invoiceNumber"), s("date", today), s("title", "Vertragsstrafe"),
                s("servicePeriod"), s("site"), s("incidentDate"), s("incident"),
                (0 until a.length()).map { PenaltyLine.from(a.getJSONObject(it)) }.ifEmpty { listOf(PenaltyLine()) },
                s("taxPercent", "19"), runCatching { TaxTreatment.valueOf(s("taxTreatment")) }.getOrDefault(TaxTreatment.UNSELECTED),
                s("legalBasis"), s("paymentTerms"), s("closing"),
            )
        }
    }
}

enum class InspectionAnswer(val title: String) {
    UNCHECKED("Offen"),
    YES("Ja"),
    NO("Nein"),
    NOT_APPLICABLE("Entfällt"),
}

enum class InspectionLanguage(val title: String) {
    UNCHECKED("Offen"),
    VERY_GOOD("1 · Sehr gut"),
    GOOD("2 · Gut"),
    POOR("3 · Nicht gut"),
    NOT_APPLICABLE("Entfällt"),
}

enum class InspectionOverall(val title: String) {
    UNCHECKED("Noch nicht bewertet"),
    OKAY("Alles in Ordnung"),
    DEFECTS("Mängel festgestellt (siehe oben)"),
}

data class InspectionPoint(
    val id: String,
    val number: String,
    val title: String,
    val adverseWhenYes: Boolean = false,
    val answer: InspectionAnswer = InspectionAnswer.UNCHECKED,
    val remarks: String = "",
) {
    val adverse
        get() = if (adverseWhenYes) answer == InspectionAnswer.YES else answer == InspectionAnswer.NO

    companion object {
        val definitions =
            listOf(
                InspectionPoint("uniform", "1", "Dienstkleidung vollständig"),
                InspectionPoint("vest", "", "– Weste"),
                InspectionPoint("shoes", "", "– Stiefel/Schuhe"),
                InspectionPoint("trousers", "", "– Hose"),
                InspectionPoint("jacket", "", "– Pullover/Jacke"),
                InspectionPoint("badge", "2", "Dienstausweis vorhanden"),
                InspectionPoint("punctual", "3", "Pünktlichkeit"),
                InspectionPoint("present", "4", "Mitarbeiter auf Arbeitsplatz anwesend"),
                InspectionPoint("logbook", "5", "Wachbuch richtig geführt"),
                InspectionPoint("roster", "6", "Richtiger Mitarbeiter laut Dienstplan\n(Falls andere Person: Name:)"),
                InspectionPoint("clean", "7", "Sauberkeit des Objektes"),
                InspectionPoint("light", "8", "Sicherheitszimmer beleuchtet"),
                InspectionPoint("radio", "9", "Funkgerät vorhanden/funktioniert"),
                InspectionPoint("language", "10", "Deutschkenntnisse\n1. Sehr gut / 2. Gut / 3. Nicht gut"),
                InspectionPoint("friendly", "11", "Freundlichkeit gegenüber Kontrolleur"),
                InspectionPoint("briefed", "12", "Einweisung erfolgt / Aufgaben bekannt"),
                InspectionPoint("patrol", "13", "Kontrollrunde durchgeführt"),
                InspectionPoint("phone", "14", "Mitarbeiter am Handy", adverseWhenYes = true),
                InspectionPoint("sleep", "15", "Mitarbeiter beim Schlafen erwischt", adverseWhenYes = true),
            )
    }
}

data class InspectionReport(
    val reference: String = "",
    val site: String = "",
    val address: String = "",
    val badgeNumber: String = "",
    val workerName: String = "",
    val staffNumber: String = "",
    val controller: String = "",
    val date: String = today,
    val time: String = "",
    val points: List<InspectionPoint> = InspectionPoint.definitions,
    val language: InspectionLanguage = InspectionLanguage.UNCHECKED,
    val alternateWorker: String = "",
    val overall: InspectionOverall = InspectionOverall.UNCHECKED,
    val remarks: String = "",
    /** Unterschrift als Striche mit Punkten 0…1 relativ zum Feld. */
    val signature: List<List<Pair<Float, Float>>> = emptyList(),
) {
    fun fillWorker(w: Entry) = copy(workerName = w.title, staffNumber = w["personnelNumber"], badgeNumber = w["bewacherId"], site = w["object"].ifBlank { site })

    val adverseCount
        get() = points.count { it.id != "language" && it.adverse } + if (language == InspectionLanguage.POOR) 1 else 0

    val validationError: String?
        get() {
            for ((label, v) in listOf("Protokollnummer" to reference, "Objekt" to site, "Adresse" to address, "Kontrolleur" to controller))
                if (v.isBlank()) return "Kontrollprotokoll: Bitte $label ergänzen."
            if (runCatching { LocalDate.parse(date) }.isFailure) return "Kontrollprotokoll: Datum prüfen."
            if (!time.matches(Regex("([01][0-9]|2[0-3]):[0-5][0-9]"))) return "Uhrzeit bitte als HH:mm eintragen."
            if (points.map { it.id } != InspectionPoint.definitions.map { it.id }) return "Die Prüfpunkte des Entwurfs stimmen nicht mit dem UGS-Formular überein."
            for ((p, d) in points.zip(InspectionPoint.definitions))
                if (p.title != d.title || p.number != d.number || p.adverseWhenYes != d.adverseWhenYes) return "Die Definition eines Prüfpunkts wurde verändert."
            if (!signature.flatten().all { (x, y) -> x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f }) return "Ungültige Unterschrift im Entwurf."
            return null
        }

    fun json(): JSONObject =
        JSONObject()
            .put("reference", reference).put("site", site).put("address", address).put("badgeNumber", badgeNumber)
            .put("workerName", workerName).put("staffNumber", staffNumber).put("controller", controller).put("date", date).put("time", time)
            .put("points", JSONArray(points.map { JSONObject().put("id", it.id).put("answer", it.answer.name).put("remarks", it.remarks) }))
            .put("language", language.name).put("alternateWorker", alternateWorker).put("overall", overall.name).put("remarks", remarks)
            .put("signature", JSONArray(signature.map { s -> JSONArray(s.map { (x, y) -> JSONArray().put(x.toDouble()).put(y.toDouble()) }) }))

    companion object {
        fun from(o: JSONObject): InspectionReport {
            val saved = o.optJSONArray("points") ?: JSONArray()
            val byId = (0 until saved.length()).map { saved.getJSONObject(it) }.associateBy { it.optString("id") }
            val sig = o.optJSONArray("signature") ?: JSONArray()
            return InspectionReport(
                o.optString("reference"), o.optString("site"), o.optString("address"), o.optString("badgeNumber"),
                o.optString("workerName"), o.optString("staffNumber"), o.optString("controller"), o.optString("date", today), o.optString("time"),
                InspectionPoint.definitions.map { d ->
                    byId[d.id]?.let { p ->
                        d.copy(answer = runCatching { InspectionAnswer.valueOf(p.optString("answer")) }.getOrDefault(InspectionAnswer.UNCHECKED), remarks = p.optString("remarks"))
                    } ?: d
                },
                runCatching { InspectionLanguage.valueOf(o.optString("language")) }.getOrDefault(InspectionLanguage.UNCHECKED),
                o.optString("alternateWorker"),
                runCatching { InspectionOverall.valueOf(o.optString("overall")) }.getOrDefault(InspectionOverall.UNCHECKED),
                o.optString("remarks"),
                (0 until sig.length()).map { i ->
                    val s = sig.getJSONArray(i)
                    (0 until s.length()).map { j -> s.getJSONArray(j).let { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() } }
                },
            )
        }
    }
}

enum class PenaltyMode(val title: String) {
    REPORT("Kontrollprotokoll"),
    CLAIM_AND_REPORT("Forderung + Kontrollprotokoll"),
    CLAIM("Nur Forderung");

    val includesReport
        get() = this != CLAIM

    val includesClaim
        get() = this != REPORT
}
