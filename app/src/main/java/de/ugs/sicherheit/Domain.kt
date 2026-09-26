package de.ugs.sicherheit

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.json.JSONObject

enum class Kind(val title: String, val generic: Boolean = true) {
    WORKER("Mitarbeiter"),
    SITE("Objekte"),
    SHIFT("Dienste"),
    TIME("Zeiten"),
    ABSENCE("Abwesenheiten"),
    TODO("To Do"),
    PAYROLL("Gehalt"),
    REGISTRATION("Anmeldungen"),
    DOCUMENT("Dokumente"),
    DRAFT("Entwürfe", false),
    /** Tagesmeldungen wie am Mac: Krank, Nicht erschienen, Verspätet, Urlaub, Ersatz, Gekündigt. */
    STATUS("Statusmeldungen"),
    SOFORTMELDUNG("Sofortmeldungen", false),
    EXPENSE("Firma Ausgaben"),
    VACATION_ACCOUNT("Urlaubskonten", false),
    DUTY_PLAN("Monatsdienstpläne", false),
    PENALTY("Strafen", false),
    INSPECTION("Kontrollprotokolle", false),
    BUSINESS("Angebote und Kooperationsverträge", false),
    SENT_MAIL("Gesendete E-Mails", false),
}

data class Entry(
    val id: String = UUID.randomUUID().toString(),
    val kind: Kind,
    val fields: Map<String, String>,
    val revision: Int = 0,
    val deleted: Boolean = false,
) {
    operator fun get(key: String) = fields[key].orEmpty()

    val title
        get() =
            when (kind) {
                Kind.WORKER -> "${get("firstName")} ${get("lastName")}".trim()
                else -> get("title").ifBlank { kind.title }
            }

    fun json() =
        JSONObject()
            .put("id", id)
            .put("kind", kind.name)
            .put("fields", JSONObject(fields))
            .put("revision", revision)
            .put("deleted", deleted)

    companion object {
        fun from(o: JSONObject): Entry {
            val f = o.getJSONObject("fields")
            return Entry(
                o.getString("id"),
                Kind.valueOf(o.getString("kind")),
                f.keys().asSequence().associateWith { f.getString(it) },
                o.optInt("revision"),
                o.optBoolean("deleted"),
            )
        }
    }
}

data class Account(
    val id: String,
    val username: String,
    val role: String,
    val permissions: String = "[]",
    val active: Boolean = true,
)

enum class Input {
    TEXT,
    DATE,
    NUMBER,
    CHOICE,
    CHECK,
    WORKER,
    SITE,
    TIME,
    MULTILINE,
    PASSWORD,
    /** Freitext mit Vorschlägen aus einer Auswahlliste (Einstellungen → Auswahllisten). */
    LOOKUP,
}

data class Field(
    val key: String,
    val label: String,
    val input: Input = Input.TEXT,
    val required: Boolean = false,
    val options: List<String> = emptyList(),
    val initial: String = "",
    /** Auswahlliste für Input.LOOKUP. */
    val group: String = "",
    /** Abschnittsüberschrift, die im Formular vor diesem Feld erscheint. */
    val section: String = "",
)

fun field(key: String, label: String, required: Boolean = false) =
    Field(key, label, required = required)

fun date(key: String, label: String, required: Boolean = false, initial: String = "") =
    Field(key, label, Input.DATE, required, initial = initial)

fun num(key: String, label: String, initial: String = "", required: Boolean = false) =
    Field(key, label, Input.NUMBER, required, initial = initial)

fun choice(key: String, label: String, vararg options: String) =
    Field(
        key,
        label,
        Input.CHOICE,
        options = options.toList(),
        initial = options.firstOrNull().orEmpty(),
    )

fun check(key: String, label: String) = Field(key, label, Input.CHECK, initial = "false")

fun longText(key: String, label: String, required: Boolean = false) =
    Field(key, label, Input.MULTILINE, required)

fun lookup(key: String, label: String, group: String, initial: String = "", required: Boolean = false) =
    Field(key, label, Input.LOOKUP, required, initial = initial, group = group)

fun Field.inSection(title: String) = copy(section = title)

/** Fragebogen-Angaben (Bank, Steuer, Versicherung, Vergütung) wie am Mac. */
val questionnaireFields =
    listOf(
        field("iban", "IBAN").inSection("Fragebogen · Bank"),
        field("bic", "BIC"),
        field("taxId", "Steuer-ID").inSection("Fragebogen · Steuer"),
        field("taxClass", "Steuerklasse (1–6)"),
        field("religion", "Religion / Konfession"),
        field("childAllowance", "Kinderfreibeträge"),
        field("socialId", "Sozialversicherungsnummer").inSection("Fragebogen · Versicherung"),
        field("healthInsurance", "Krankenkasse"),
        field("birthCountry", "Geburtsland (nur ohne SV-Nummer nötig)"),
        field("birthName", "Geburtsname (nur ohne SV-Nummer nötig)"),
        num("weeklyHours", "Std. / Woche", "40").inSection("Fragebogen · Vergütung"),
        num("vacationDays", "Urlaubstage pro Jahr", "24"),
        field("grossPay", "Festlohn oder Stundenlohn (Brutto)"),
        choice("secondaryJob", "Nebenbeschäftigung", "Nein", "Ja"),
        field("secondaryEmployer", "Arbeitgeber (Nebenbeschäftigung)"),
    )

/** Statusmeldungen: gespeicherter Schlüssel → Anzeige. */
val statusKinds =
    linkedMapOf(
        "sick" to "Krank",
        "no_show" to "Nicht erschienen",
        "late" to "Verspätet",
        "leave" to "Urlaub",
        "replacement" to "Ersatz",
        "terminated" to "Gekündigt",
    )

val today
    get() = LocalDate.now().toString()
val personField = Field("workerId", "Mitarbeiter", Input.WORKER, true)
val schemas: Map<Kind, List<Field>> =
    mapOf(
        Kind.WORKER to
            listOf(
                field("bewacherId", "Bewacher-ID").inSection("Personal"),
                field(
                    "personnelNumber",
                    "Personalnummer (leer = automatisch dreistellig)",
                ),
                field("firstName", "Vorname", true),
                field("lastName", "Nachname", true),
                choice("gender", "Geschlecht", "keine Angabe", "männlich", "weiblich", "divers"),
                date("birthDate", "Geburtsdatum"),
                field("birthPlace", "Geburtsort"),
                lookup("nationality", "Staatsangehörigkeit", "nationalities"),
                field("identityNumber", "Ausweisnummer"),
                field("street", "Straße und Hausnummer").inSection("Kontakt"),
                field("postalCode", "PLZ"),
                field("city", "Ort"),
                field("email", "E-Mail"),
                field("phone", "Mobil / Telefon"),
                lookup("department", "Abteilung", "departments").inSection("Beschäftigung"),
                lookup("position", "Tätigkeit", "positions"),
                lookup("location", "Standort", "locations"),
                lookup("object", "Objekt / Ersatz", "objects"),
                lookup("contractType", "Vertragsart", "contract_types"),
                choice("employmentType", "Beschäftigung", "Vollzeit", "Teilzeit", "Minijob"),
                date("startDate", "Eintritt / Vertragsbeginn", true),
                date("endDate", "Vertragsende"),
                lookup("status", "Status", "worker_statuses", "Aktiv", true),
                num("hourlyRate", "Stundenlohn €"),
                num("baseSalary", "Grundgehalt €"),
                num("allowances", "Zulagen €"),
            ) +
                questionnaireFields +
                listOf(longText("notes", "Notizen").inSection("Notizen")),
        Kind.SITE to
            listOf(
                field("title", "Objektname", true),
                field("address", "Anschrift", true),
                field("customer", "Auftraggeber"),
                field("contact", "Kontakt"),
                longText("notes", "Hinweise"),
            ),
        Kind.SHIFT to
            listOf(
                personField,
                Field("siteId", "Objekt", Input.SITE, true),
                date("date", "Datum", true),
                Field("startTime", "Beginn", Input.TIME, true, initial = "08:00"),
                Field("endTime", "Ende", Input.TIME, true, initial = "16:00"),
                num("breakMinutes", "Pause in Minuten", "30", true),
                choice("status", "Status", "Geplant", "Bestätigt", "Abgesagt"),
                longText("notes", "Hinweise"),
            ),
        Kind.TIME to
            listOf(
                personField,
                date("date", "Datum", true),
                Field("startTime", "Beginn", Input.TIME, true, initial = "08:00"),
                Field("endTime", "Ende", Input.TIME, true, initial = "16:00"),
                num("breakMinutes", "Pause in Minuten", "30", true),
                choice("status", "Status", "Erfasst", "Geprüft"),
                longText("notes", "Tätigkeit / Objekt"),
            ),
        Kind.ABSENCE to
            listOf(
                personField,
                lookup("type", "Art", "absence_types", "Jahresurlaub", true),
                date("date", "Von", true),
                date("endDate", "Bis", true),
                lookup("status", "Status", "absence_statuses", "In Bearbeitung", true),
                longText("notes", "Notizen"),
            ),
        Kind.TODO to
            listOf(
                field("title", "Titel", true),
                choice("todoKind", "Art", "Aufgabe", "Termin"),
                date("date", "Fällig am"),
                Field("dueTime", "Uhrzeit (nur Termin)", Input.TIME),
                choice("priority", "Priorität", "Normal", "Hoch", "Niedrig"),
                choice("status", "Status", "Offen", "In Arbeit", "Erledigt"),
                longText("notes", "Beschreibung"),
            ),
        Kind.PAYROLL to
            listOf(
                personField,
                date("date", "Abrechnungsmonat", true),
                num("hours", "Vergütete Stunden", required = true),
                num("rate", "Stundenlohn €", required = true),
                num("allowances", "Zulagen €", "0"),
                num("bonus", "Bonus €", "0"),
                num("deductions", "Abzüge €", "0"),
                choice("status", "Status", "Entwurf", "Geprüft", "Bezahlt"),
                lookup("paymentStatus", "Zahlungsstatus", "payment_statuses", "Ausstehend"),
                longText("notes", "Notizen – keine automatische Steuerberechnung"),
            ),
        Kind.REGISTRATION to
            listOf(
                personField,
                choice("type", "Meldeart", "Anmeldung", "Abmeldung", "Sofortmeldung"),
                date("date", "Wirksam am", true),
                field("reference", "Externe Referenz"),
                choice("status", "Status", "Vorbereitet", "Extern übermittelt", "Bestätigt"),
                longText("notes", "Notizen"),
            ),
        Kind.DOCUMENT to
            listOf(
                field("title", "Dokumentname", true),
                Field("workerId", "Mitarbeiter (optional)", Input.WORKER),
                Field("category", "Kategorie", Input.LOOKUP, true, initial = "Sonstiges", group = "document_categories"),
                field("documentNumber", "Dokumentnummer"),
                date("date", "Ausgestellt am"),
                date("expiryDate", "Gültig bis"),
                check("extendedCertificate", "Erweitertes Führungszeugnis (Frist: Basisdatum + Erneuerung)"),
                date("renewalRequestedOn", "Neu beantragt am (Frist: + 6 Monate)"),
                check("archived", "Archiviert"),
                longText("notes", "Notizen"),
            ),
        Kind.STATUS to
            listOf(
                personField,
                Field(
                    "type",
                    "Meldung",
                    Input.CHOICE,
                    true,
                    options = statusKinds.keys.toList(),
                    initial = "sick",
                ),
                date("date", "Beginn", true),
                date("endDate", "Ende (bei Gekündigt ohne Ende)"),
                num("quantity", "Menge", "1"),
                field("unit", "Einheit"),
                longText("notes", "Notizen"),
            ),
        Kind.EXPENSE to
            listOf(
                field("title", "Bezeichnung", true),
                Field("category", "Wofür? (Kategorie)", Input.LOOKUP, true, initial = "IT / Software", group = "expense_categories"),
                field("amount", "Betrag €, z. B. 49,90", true),
                date("date", "Datum", true),
                longText("notes", "Notizen"),
            ),
    )

object Rules {
    fun number(s: String): Double =
        s.replace(" ", "").replace(",", ".").toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: throw IllegalArgumentException("Bitte eine gültige Zahl eingeben.")

    fun date(s: String): LocalDate =
        try {
            LocalDate.parse(s)
        } catch (e: Exception) {
            throw IllegalArgumentException("Bitte ein Datum im Kalender auswählen.")
        }

    fun german(s: String) =
        if (s.isBlank()) "" else date(s).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))

    fun validate(e: Entry, all: List<Entry>) {
        for (f in schemas[e.kind].orEmpty()) {
            val v = e[f.key]
            require(!f.required || v.isNotBlank()) { "${f.label} fehlt." }
            require(v.length <= 12000) { "${f.label} ist zu lang." }
            if (v.isBlank()) continue
            when (f.input) {
                Input.DATE -> date(v)
                Input.NUMBER -> require(number(v) >= 0) { "${f.label} darf nicht negativ sein." }
                Input.TIME ->
                    try {
                        LocalTime.parse(v)
                    } catch (ex: Exception) {
                        throw IllegalArgumentException("${f.label}: HH:mm erforderlich.")
                    }
                Input.WORKER ->
                    require(all.any { it.id == v && it.kind == Kind.WORKER && !it.deleted }) {
                        "Mitarbeiter nicht mehr vorhanden."
                    }
                Input.SITE ->
                    require(all.any { it.id == v && it.kind == Kind.SITE && !it.deleted }) {
                        "Objekt nicht mehr vorhanden."
                    }
                Input.CHOICE -> require(v in f.options) { "${f.label}: ungültige Auswahl." }
                else -> Unit
            }
        }
        if (e.kind == Kind.WORKER) {
            require(
                e["personnelNumber"].isBlank() ||
                    all.none {
                        it.kind == Kind.WORKER &&
                            it.id != e.id &&
                            it["personnelNumber"].equals(e["personnelNumber"], true)
                    }
            ) {
                "Personalnummer bereits vergeben."
            }
            require(
                e["bewacherId"].isBlank() ||
                    all.none {
                        it.kind == Kind.WORKER &&
                            !it.deleted &&
                            it.id != e.id &&
                            it["bewacherId"].trim().equals(e["bewacherId"].trim(), true)
                    }
            ) {
                "Bewacher-ID bereits vergeben."
            }
            if (e["email"].isNotBlank())
                require(Mail.isAddress(e["email"].trim())) { "E-Mail-Adresse ist ungültig." }
            if (e["birthDate"].isNotBlank())
                require(date(e["birthDate"]) <= LocalDate.now()) {
                    "Geburtsdatum liegt in der Zukunft."
                }
            if (e["endDate"].isNotBlank())
                require(date(e["endDate"]) >= date(e["startDate"])) {
                    "Vertragsende liegt vor dem Beginn."
                }
        }
        if (e.kind == Kind.ABSENCE || e.kind == Kind.STATUS && e["endDate"].isNotBlank())
            require(date(e["endDate"]) >= date(e["date"])) { "Ende liegt vor Beginn." }
        if (e.kind == Kind.DUTY_PLAN) {
            MonthCalendar.month(e["month"])
            require(all.none { it.kind == Kind.DUTY_PLAN && !it.deleted && it.id != e.id && it["siteId"] == e["siteId"] && it["month"] == e["month"] }) {
                "Für dieses Objekt und diesen Monat gibt es bereits einen Dienstplan."
            }
        }
        if (e.kind == Kind.VACATION_ACCOUNT) {
            require(e["year"].toIntOrNull() in 2000..2100) { "Jahr ungültig." }
            require(all.none { it.kind == Kind.VACATION_ACCOUNT && !it.deleted && it.id != e.id && it["workerId"] == e["workerId"] && it["year"] == e["year"] }) {
                "Für diesen Mitarbeiter gibt es bereits ein Urlaubskonto in diesem Jahr."
            }
        }
        if (e.kind == Kind.EXPENSE) {
            require(parseAmount(e["amount"]) != null) { "Bitte einen gültigen Betrag eingeben, z. B. 49,90 oder 1.234,56." }
            require(e["category"].length <= 60) { "Die Kategorie ist zu lang (höchstens 60 Zeichen)." }
        }
        if (e.kind == Kind.TODO && e["dueTime"].isNotBlank())
            require(e["todoKind"] == "Termin") { "Eine Uhrzeit gibt es nur bei einem Termin." }
        if (e.kind == Kind.SHIFT || e.kind == Kind.TIME) {
            val (s, t) = interval(e)
            val raw = ChronoUnit.MINUTES.between(s, t)
            require(raw in 1..1440) { "Die Dauer muss zwischen 1 Minute und 24 Stunden liegen." }
            require(number(e["breakMinutes"]) < raw && number(e["breakMinutes"]) % 1.0 == 0.0) {
                "Pause muss in ganzen Minuten kürzer als der Dienst sein."
            }
            if (e["status"] != "Abgesagt")
                require(
                    all.none { o ->
                        o.kind == e.kind &&
                            o.id != e.id &&
                            !o.deleted &&
                            o["workerId"] == e["workerId"] &&
                            o["status"] != "Abgesagt" &&
                            run {
                                val (a, b) = interval(o)
                                s < b && a < t
                            }
                    }
                ) {
                    "Zeitüberschneidung für diesen Mitarbeiter."
                }
        }
    }

    fun interval(e: Entry): Pair<LocalDateTime, LocalDateTime> {
        val d = date(e["date"])
        val a = d.atTime(LocalTime.parse(e["startTime"]))
        var b = d.atTime(LocalTime.parse(e["endTime"]))
        if (b <= a) b = b.plusDays(1)
        return a to b
    }

    fun hours(e: Entry): Double {
        val (a, b) = interval(e)
        return (ChronoUnit.MINUTES.between(a, b) - number(e["breakMinutes"])) / 60.0
    }

    fun weekdays(from: LocalDate, to: LocalDate): Int {
        require(!to.isBefore(from) && ChronoUnit.DAYS.between(from, to) <= 3660)
        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { it <= to }
            .count { it.dayOfWeek.value <= 5 }
    }

    fun fixedEnd(start: LocalDate, years: Long): LocalDate {
        val target = start.plusYears(years)
        return if (target.dayOfMonth < start.dayOfMonth) target else target.minusDays(1)
    }

    fun probation(start: String, months: Int, letter: String, receipt: String, end: String) {
        require(months in 1..6) { "Probezeit: 1 bis 6 Monate erforderlich." }
        val a = date(start)
        val b = date(letter)
        val c = date(receipt)
        val d = date(end)
        require(c >= a && c < a.plusMonths(months.toLong()) && c < a.plusMonths(6)) {
            "Zugang muss innerhalb der vereinbarten Probezeit liegen."
        }
        require(b <= c) { "Briefdatum darf nicht nach dem Zugang liegen." }
        require(d >= c.plusDays(14)) {
            "Mindestens zwei Wochen Kündigungsfrist ab Zugang erforderlich. Tarifliche Besonderheiten gesondert prüfen."
        }
    }

    fun resolve(text: String, tokens: Map<String, String>): String {
        val token = Regex("\\{\\{([a-z_]+)}}")
        val optional =
            Regex("\\[\\[(.*?)]]", RegexOption.DOT_MATCHES_ALL).replace(text) { m ->
                val keys = token.findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
                if (
                    keys.isEmpty() ||
                        keys.any { !tokens[it].orEmpty().all { c -> c == '_' || c.isWhitespace() } }
                )
                    m.groupValues[1]
                else ""
            }
        return token
            .replace(optional) {
                tokens[it.groupValues[1]]
                    ?: throw IllegalArgumentException("Vorlagenwert fehlt: ${it.groupValues[1]}")
            }
            .replace(Regex(" {2,}"), " ")
            .replace(Regex(" +([.,;:])"), "$1")
            .trim()
    }
}

val expenseCategories =
    listOf(
        "IT / Software",
        "Hardware",
        "Hotel / Übernachtung",
        "Auto / Leasing",
        "Tanken / Fahrtkosten",
        "Dienstkleidung",
        "Ausrüstung",
        "Telefon / Internet",
        "Versicherung",
        "Büro / Material",
        "Miete / Räume",
        "Weiterbildung / Schulung",
        "Marketing",
        "Beratung / Steuer",
        "Sonstiges",
    )

/** Kategorien der Mitarbeiterdokumente (auch für ältere Datenbestände). */
object DocumentCategories {
    val standard =
        listOf(
            "Arbeitsvertrag",
            "Anweisung",
            "Abmahnung",
            "Kündigung",
            "Arbeitskleidung",
            "Vertragsstrafe",
            "Kontrollprotokoll",
            "Krankmeldung",
            "Fragebogen",
            "Lebenslauf",
            "Freigabe",
            "Sofortmeldung",
            "Dienstplan",
            "Stundenzettel",
            "Erweitertes Führungszeugnis",
            "Ausweis",
            "Qualifikation",
            "Bescheinigung",
            "Sonstiges",
        )

    fun canonical(value: String): String {
        val clean = value.trim()
        return when (clean.lowercase()) {
            "cv",
            "lebenslauf",
            "cv / lebenslauf",
            "lebenslauf / cv" -> "Lebenslauf"
            "kundigung",
            "kuendigung",
            "kündigung" -> "Kündigung"
            "fragenbogen",
            "personalfragebogen",
            "fragebogen" -> "Fragebogen"
            "dinstplan",
            "dienstplan" -> "Dienstplan"
            "vertrag" -> "Arbeitsvertrag"
            else -> standard.firstOrNull { it.equals(clean, true) } ?: clean
        }
    }

    fun title(category: String) = if (category == "Lebenslauf") "CV / Lebenslauf" else category

    fun options(custom: List<String>): List<String> =
        standard +
            custom
                .map(::canonical)
                .filter { it.isNotEmpty() && it !in standard }
                .distinct()
                .sorted()
}

enum class DeadlineState {
    VALID,
    SOON,
    URGENT,
    EXPIRED,
    MISSING,
    ARCHIVED,
}

data class Deadline(val state: DeadlineState, val label: String, val date: String, val days: Long?)

/** Fristen der Dokumente: normale Gültigkeit oder Erneuerung des Führungszeugnisses. */
fun Entry.deadline(now: LocalDate = LocalDate.now()): Deadline {
    val extended = this["extendedCertificate"] == "true"
    val due = if (extended) this["renewalDueOn"] else this["expiryDate"]
    if (this["archived"] == "true") return Deadline(DeadlineState.ARCHIVED, "Archiviert", due, null)
    if (due.isBlank())
        return if (extended) Deadline(DeadlineState.MISSING, "Basisdatum fehlt", "", null)
        else Deadline(DeadlineState.VALID, "Ohne Frist", "", null)
    val target =
        runCatching { LocalDate.parse(due) }.getOrNull()
            ?: return Deadline(DeadlineState.VALID, "Ohne Frist", due, null)
    val days = ChronoUnit.DAYS.between(now, target)
    return when {
        days < 0 -> Deadline(DeadlineState.EXPIRED, "Abgelaufen", due, days)
        extended && days < 30 -> Deadline(DeadlineState.URGENT, "Neu beantragen (rot)", due, days)
        extended && days <= 60 -> Deadline(DeadlineState.SOON, "Erneuerung planen (gelb)", due, days)
        extended -> Deadline(DeadlineState.VALID, "Frist über 60 Tage (grün)", due, days)
        days < 30 -> Deadline(DeadlineState.SOON, "Bald ablaufend", due, days)
        else -> Deadline(DeadlineState.VALID, "Gültig", due, days)
    }
}

object Workers {
    /** Aktuelle Statusmeldung (Gekündigt hat Vorrang), sonst der gespeicherte Status. */
    fun currentStatus(w: Entry, all: List<Entry>, today: LocalDate = LocalDate.now()): String {
        val t = today.toString()
        val current =
            all.filter {
                    it.kind == Kind.STATUS &&
                        !it.deleted &&
                        it["workerId"] == w.id &&
                        it["date"] <= t &&
                        (it["type"] == "terminated" || it["endDate"].ifBlank { it["date"] } >= t)
                }
                .sortedWith(
                    compareBy<Entry> { if (it["type"] == "terminated") 0 else 1 }
                        .thenByDescending { it["date"] }
                )
                .firstOrNull()
        return when (current?.get("type")) {
            "sick" -> "Krank gemeldet"
            "no_show" -> "Nicht gekommen"
            "late" -> "Zu spät gekommen"
            "leave" -> "Urlaub"
            "terminated" -> "Gekündigt"
            "replacement" -> "Ersatz"
            else -> w["status"].ifBlank { "Aktiv" }
        }
    }

    fun active(status: String) = status in setOf("Aktiv", "Zu spät gekommen")

    fun address(w: Entry) =
        listOf(w["street"], listOf(w["postalCode"], w["city"]).filter { it.isNotBlank() }.joinToString(" "))
            .filter { it.isNotBlank() }
            .joinToString(", ")

    /** Mitarbeiter über Bewacher-ID oder Personalnummer finden. */
    fun find(input: String, all: List<Entry>, byBewacherId: Boolean): Entry? {
        val n = input.trim()
        if (n.isEmpty()) return null
        return all.firstOrNull {
            it.kind == Kind.WORKER &&
                !it.deleted &&
                (if (byBewacherId) it["bewacherId"].trim().equals(n, true)
                else it["personnelNumber"].trim() == n)
        }
    }

    fun isVacation(type: String) = type.trim().lowercase() in setOf("urlaub", "jahresurlaub")

    fun isApproved(status: String) = status.trim() == "Genehmigt"
}

/** Geldbetrag im deutschen Format, z. B. „1.234,50 €“. */
fun money(value: Double): String =
    java.text.NumberFormat.getCurrencyInstance(java.util.Locale.GERMANY)
        .apply { currency = java.util.Currency.getInstance("EUR") }
        .format(value)

/** Zahl im deutschen Format mit [digits] Nachkommastellen. */
fun decimal(value: Double, digits: Int = 2): String =
    String.format(java.util.Locale.GERMANY, "%,.${digits}f", value)

/**
 * Betrag aus einem Eingabefeld: deutsch mit Tausenderpunkten („1.234,50“) oder schlicht
 * („1234.50“). Mehrdeutiges wird abgelehnt statt still falsch übernommen.
 */
fun parseAmount(text: String): Double? {
    val v = text.trim().removeSuffix("€").trim()
    val german = Regex("(?:[0-9]+|[0-9]{1,3}(?:\\.[0-9]{3})+)(?:,[0-9]{1,2})?")
    val plain = Regex("[0-9]+(?:\\.[0-9]{1,2})?")
    val normalized =
        when {
            german.matches(v) -> v.replace(".", "").replace(",", ".")
            plain.matches(v) -> v
            else -> return null
        }
    return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
}

enum class ProofLevel {
    BAD,
    SOON,
    OK,
    NEUTRAL;

    companion object {
        fun of(state: DeadlineState) =
            when (state) {
                DeadlineState.VALID -> OK
                DeadlineState.SOON -> SOON
                DeadlineState.URGENT,
                DeadlineState.EXPIRED,
                DeadlineState.MISSING -> BAD
                DeadlineState.ARCHIVED -> NEUTRAL
            }
    }
}

data class ProofRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val until: String,
    val verdict: String,
    val level: ProofLevel,
    val document: Entry?,
)

/** „Nachweise & Fristen“ einer Personalakte, Pflichtnachweise zuerst sichtbar. */
object Proofs {
    val required = listOf("Arbeitsvertrag", "Fragebogen", "Erweitertes Führungszeugnis")

    fun title(d: Entry) =
        if (d["extendedCertificate"] == "true") "Erweitertes Führungszeugnis"
        else DocumentCategories.title(DocumentCategories.canonical(d["category"]))

    fun proves(title: String, d: Entry) =
        if (title == "Erweitertes Führungszeugnis")
            d["extendedCertificate"] == "true" || DocumentCategories.canonical(d["category"]) == title
        else DocumentCategories.canonical(d["category"]) == title

    fun build(documents: List<Entry>, now: LocalDate = LocalDate.now()): List<ProofRow> {
        val active = documents.filter { it["archived"] != "true" }
        val rows =
            active
                .map { d ->
                    val deadline = d.deadline(now)
                    val level = ProofLevel.of(deadline.state)
                    var verdict = deadline.label
                    if (deadline.days != null && deadline.days >= 0 && level != ProofLevel.OK)
                        verdict += " · ${deadline.days} Tage"
                    ProofRow(
                        "doc-${d.id}",
                        title(d),
                        listOf(d["documentNumber"], d["originalName"].ifBlank { d.title })
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        if (deadline.date.isEmpty()) "unbefristet" else DateText.german(deadline.date),
                        verdict,
                        level,
                        d,
                    )
                }
                .toMutableList()
        for (r in required) if (active.none { proves(r, it) })
            rows +=
                ProofRow("missing-$r", r, "Noch nicht in der Personalakte", "—", "Fehlt, Pflicht", ProofLevel.BAD, null)
        return rows.sortedWith(compareBy({ it.level.ordinal }, { it.title }))
    }
}

data class TimelineEvent(val id: String, val date: String, val title: String, val detail: String, val severity: Int)

/** Verlauf eines Mitarbeiters: Eintritt, Meldungen, Abwesenheiten, Dokumente, Gehalt. */
object Timeline {
    fun build(w: Entry, all: List<Entry>, permitted: (String) -> Boolean): List<TimelineEvent> {
        val out = mutableListOf<TimelineEvent>()
        if (w["startDate"].isNotBlank())
            out += TimelineEvent("hire-${w.id}", w["startDate"], "Eintritt", w["contractType"].ifBlank { w["employmentType"] }, 0)
        for (e in all.filter { it["workerId"] == w.id && !it.deleted }) {
            when (e.kind) {
                Kind.STATUS ->
                    if (permitted("registrations"))
                        out += TimelineEvent(
                            "reg-${e.id}",
                            e["date"],
                            "Anmeldung · ${statusKinds[e["type"]] ?: e["type"]}",
                            e["notes"],
                            if (e["type"] == "terminated") 2 else 0,
                        )
                Kind.REGISTRATION ->
                    if (permitted("registrations"))
                        out += TimelineEvent("meld-${e.id}", e["date"], "Meldung · ${e["type"]}", e["status"], 0)
                Kind.ABSENCE ->
                    if (permitted("absences"))
                        out += TimelineEvent(
                            "absence-${e.id}",
                            e["date"],
                            e["type"],
                            "${DateText.german(e["date"])}–${DateText.german(e["endDate"])} · ${e["status"]}",
                            if (e["status"] == "Abgelehnt") 1 else 0,
                        )
                Kind.DOCUMENT ->
                    if (permitted("documents")) {
                        val state = e.deadline().state
                        out += TimelineEvent(
                            "doc-${e.id}",
                            e["date"].ifBlank { e["expiryDate"] },
                            "Dokument · ${Proofs.title(e)}",
                            e["originalName"].ifBlank { e.title },
                            when (state) {
                                DeadlineState.EXPIRED -> 2
                                DeadlineState.URGENT,
                                DeadlineState.SOON -> 1
                                else -> 0
                            },
                        )
                    }
                Kind.PAYROLL ->
                    if (permitted("payrolls"))
                        out += TimelineEvent(
                            "pay-${e.id}",
                            e["date"].take(7) + "-01",
                            "Lohnabrechnung",
                            "${e["paymentStatus"].ifBlank { e["status"] }}",
                            0,
                        )
                else -> Unit
            }
        }
        return out.sortedWith(compareByDescending<TimelineEvent> { it.date }.thenByDescending { it.id })
    }
}
