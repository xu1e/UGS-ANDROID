package de.ugs.sicherheit

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.json.JSONObject

enum class Kind(val title: String) {
    WORKER("Mitarbeiter"),
    SITE("Objekte"),
    SHIFT("Dienstplan"),
    TIME("Zeiten"),
    ABSENCE("Abwesenheiten"),
    TODO("Aufgaben"),
    PAYROLL("Gehalt"),
    REGISTRATION("Anmeldungen"),
    DOCUMENT("Dokumente"),
    DRAFT("Entwürfe"),
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

data class Account(val id: String, val username: String, val role: String)

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
}

data class Field(
    val key: String,
    val label: String,
    val input: Input = Input.TEXT,
    val required: Boolean = false,
    val options: List<String> = emptyList(),
    val initial: String = "",
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

val today
    get() = LocalDate.now().toString()
val personField = Field("workerId", "Mitarbeiter", Input.WORKER, true)
val schemas: Map<Kind, List<Field>> =
    mapOf(
        Kind.WORKER to
            listOf(
                field("personnelNumber", "Personalnummer", true),
                field("firstName", "Vorname", true),
                field("lastName", "Nachname", true),
                date("birthDate", "Geburtsdatum"),
                field("street", "Straße und Hausnummer"),
                field("postalCode", "PLZ"),
                field("city", "Ort"),
                field("email", "E-Mail"),
                field("phone", "Telefon"),
                field("bewacherId", "Bewacher-ID"),
                field("nationality", "Staatsangehörigkeit"),
                field("taxId", "Steuer-ID"),
                field("socialId", "Sozialversicherungsnummer"),
                field("healthInsurance", "Krankenkasse"),
                field("iban", "IBAN"),
                choice("employmentType", "Beschäftigung", "Vollzeit", "Teilzeit", "Minijob"),
                date("startDate", "Vertragsbeginn", true),
                date("endDate", "Vertragsende"),
                num("weeklyHours", "Wochenstunden", "40"),
                num("hourlyRate", "Stundenlohn €"),
                num("vacationDays", "Urlaubstage pro Jahr", "24"),
                choice("status", "Status", "Aktiv", "Inaktiv"),
                longText("notes", "Notizen"),
            ),
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
                choice("type", "Art", "Urlaub", "Krankheit", "Unbezahlt", "Sonstiges"),
                date("date", "Von", true),
                date("endDate", "Bis", true),
                choice("status", "Status", "Beantragt", "Genehmigt", "Abgelehnt"),
                longText("notes", "Notizen"),
            ),
        Kind.TODO to
            listOf(
                field("title", "Aufgabe", true),
                date("date", "Fällig am"),
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
                num("deductions", "Abzüge €", "0"),
                choice("status", "Status", "Entwurf", "Geprüft", "Bezahlt"),
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
                choice(
                    "category",
                    "Kategorie",
                    "Vertrag",
                    "Ausweis",
                    "Qualifikation",
                    "Bescheinigung",
                    "Sonstiges",
                ),
                date("date", "Dokumentdatum"),
                date("expiryDate", "Gültig bis"),
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
                all.none {
                    it.kind == Kind.WORKER &&
                        !it.deleted &&
                        it.id != e.id &&
                        it["personnelNumber"].equals(e["personnelNumber"], true)
                }
            ) {
                "Personalnummer bereits vergeben."
            }
            if (e["birthDate"].isNotBlank())
                require(date(e["birthDate"]) <= LocalDate.now()) {
                    "Geburtsdatum liegt in der Zukunft."
                }
            if (e["endDate"].isNotBlank())
                require(date(e["endDate"]) >= date(e["startDate"])) {
                    "Vertragsende liegt vor dem Beginn."
                }
        }
        if (e.kind == Kind.ABSENCE)
            require(date(e["endDate"]) >= date(e["date"])) { "Ende liegt vor Beginn." }
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
