package de.ugs.sicherheit

import org.json.JSONArray

/** Gemeinsame Größengrenzen (wie iOS Build 36 / Mac 5.9.67). */
object FileLimits {
    private const val MB = 1024 * 1024
    /** Eine Datei: Dokumente, Vertrag stempeln, Ausweis einlesen, Anlagen, Profilfoto. */
    const val FILE_BYTES = 250 * MB
    const val FILE_LABEL = "250 MB"
    /** Alle Anhänge einer ausgehenden E-Mail zusammen. */
    const val MAIL_BYTES = 100 * MB
    const val MAIL_LABEL = "100 MB"
    /** Eine eingehende E-Mail im Posteingang. */
    const val INCOMING_MAIL_BYTES = 150 * MB
    const val INCOMING_MAIL_LABEL = "150 MB"
    /** Ein Dokument beim Wiederherstellen einer Datensicherung. */
    const val BACKUP_DOCUMENT_BYTES = FILE_BYTES + MB
    /** Tabellen für den Personalimport bleiben klein. */
    const val IMPORT_BYTES = 20 * MB
}

enum class AccessAction(val title: String) {
    VIEW("Ansehen"),
    CREATE("Anlegen"),
    EDIT("Bearbeiten"),
    DELETE("Löschen"),
    EXPORT("Exportieren"),
}

object AccessControl {
    val all =
        listOf(
            "dashboard",
            "workers",
            "import",
            "registrations",
            "sofortmeldung",
            "absences",
            "documents",
            "expenses",
            "contract",
            "exports",
            "sent_mail",
            "inbox",
            "duty",
            "time_entries",
            "todo",
            "payrolls",
            "reports",
            "operations",
            "settings",
            "users",
            "backup",
        )

    val labels =
        mapOf(
            "dashboard" to "Übersicht",
            "workers" to "Mitarbeiter",
            "import" to "Mitarbeiter-Import",
            "registrations" to "Anmeldung",
            "sofortmeldung" to "Sofortmeldung",
            "absences" to "Abwesenheiten",
            "documents" to "Dokumente",
            "expenses" to "Firma Ausgaben",
            "contract" to "Arbeitsvertrag",
            "exports" to "Dokument-Export",
            "sent_mail" to "Gesendete E-Mails",
            "inbox" to "Posteingang",
            "duty" to "Dienstplan",
            "time_entries" to "Zeiten",
            "todo" to "To Do",
            "payrolls" to "Gehalt",
            "reports" to "Berichte",
            "operations" to "Kontrollzentrum",
            "settings" to "Einstellungen",
            "users" to "Benutzerverwaltung",
            "backup" to "Datensicherung",
        )

    val roles = listOf("Administrator", "Personal", "Planung", "Lesen", "Benutzerdefiniert")

    val rolePresets: Map<String, Set<String>> =
        mapOf(
            "Administrator" to all.toSet(),
            "Personal" to
                setOf(
                    "dashboard",
                    "workers",
                    "import",
                    "registrations",
                    "sofortmeldung",
                    "absences",
                    "documents",
                    "expenses",
                    "contract",
                    "exports",
                    "time_entries",
                    "todo",
                    "reports",
                    "operations",
                ),
            "Planung" to setOf("dashboard", "workers", "duty", "time_entries", "todo", "operations"),
            "Lesen" to setOf("dashboard", "workers", "documents", "duty", "reports", "operations"),
            "Benutzerdefiniert" to emptySet(),
        )

    fun parse(permissions: String): Set<String> =
        if (permissions == "*") all.toSet()
        else
            runCatching {
                    val a = JSONArray(permissions)
                    (0 until a.length()).map { a.getString(it) }.toSet()
                }
                .getOrDefault(emptySet())

    fun encode(values: Set<String>) = JSONArray(values.sorted()).toString()

    fun permissionSet(a: Account): Set<String> =
        when {
            a.role == "Administrator" -> all.toSet()
            a.role == "Benutzerdefiniert" -> parse(a.permissions)
            // Ältere Android-Konten speichern keine Rechte: Vorgabe der Rolle.
            else -> rolePresets[a.role].orEmpty() + parse(a.permissions).filter { '.' !in it }
        }

    fun allows(a: Account?, action: AccessAction, page: String): Boolean {
        if (a == null || !a.active) return false
        if (page == "users") return a.role == "Administrator"
        if (a.role == "Administrator") return true
        val set = permissionSet(a)
        if (page !in set) return false
        if (action == AccessAction.VIEW) return true
        // Lesen bleibt immer ohne Schreibrecht.
        if (a.role == "Lesen") return action == AccessAction.EXPORT
        if (a.role == "Benutzerdefiniert") return "$page.${action.name.lowercase()}" in set
        return rolePresets.containsKey(a.role)
    }

    /** Welche Rechte-Seite gehört zu einer Datensatzart. */
    fun page(kind: Kind): String =
        when (kind) {
            Kind.WORKER -> "workers"
            Kind.SITE,
            Kind.SHIFT,
            Kind.DUTY_PLAN -> "duty"
            Kind.TIME -> "time_entries"
            Kind.ABSENCE,
            Kind.VACATION_ACCOUNT -> "absences"
            Kind.TODO -> "todo"
            Kind.PAYROLL -> "payrolls"
            Kind.REGISTRATION,
            Kind.STATUS -> "registrations"
            Kind.SOFORTMELDUNG -> "sofortmeldung"
            Kind.DOCUMENT -> "documents"
            Kind.DRAFT -> "contract"
            Kind.EXPENSE -> "expenses"
            Kind.PENALTY,
            Kind.INSPECTION,
            Kind.BUSINESS -> "exports"
            Kind.SENT_MAIL -> "sent_mail"
        }
}

/** Auswahllisten wie am Mac (Einstellungen → Auswahllisten). */
object Lookups {
    val defaults: Map<String, List<String>> =
        linkedMapOf(
            "departments" to
                listOf(
                    "Personalwesen",
                    "Finanzen",
                    "Informationstechnologie",
                    "Vertrieb",
                    "Betrieb",
                    "Marketing",
                    "Kundenservice",
                    "Verwaltung",
                    "Rechtsabteilung",
                    "Sicherheit",
                ),
            "positions" to
                listOf(
                    "Geschäftsführer",
                    "Abteilungsleiter",
                    "Teamleiter",
                    "Mitarbeiter",
                    "Buchhalter",
                    "Ingenieur",
                    "Techniker",
                    "Sekretär",
                    "Vertriebsmitarbeiter",
                    "Personalfachkraft",
                ),
            "contract_types" to
                listOf("Unbefristet", "Befristet", "Probezeit", "Zeitvertrag", "Duales Praktikum"),
            "worker_statuses" to
                listOf("Aktiv", "Ersatz", "Urlaub", "Ausgeschieden", "Vertrag beendet", "Inaktiv"),
            "absence_types" to
                listOf(
                    "Jahresurlaub",
                    "Krankheitsurlaub",
                    "Sonderurlaub",
                    "Mutterschaftsurlaub",
                    "Unbezahlter Urlaub",
                    "Pilgerurlaub",
                    "Hochzeitsurlaub",
                    "Trauerurlaub",
                ),
            "absence_statuses" to listOf("In Bearbeitung", "Genehmigt", "Abgelehnt"),
            "document_types" to
                listOf(
                    "Lebenslauf",
                    "Personalausweis",
                    "Reisepass",
                    "Arbeitsvertrag",
                    "Erweitertes Führungszeugnis",
                    "Zertifikat",
                    "Sonstiges",
                ),
            "nationalities" to
                listOf("Deutsch", "Sudanesisch", "Ägyptisch", "Syrisch", "Jemenitisch", "Pakistanisch"),
            "locations" to listOf("Köln", "Hamburg", "Bamberg"),
            "objects" to listOf("Objekt 1", "Objekt 2"),
            "payment_statuses" to listOf("Ausstehend", "Ausgezahlt", "Nicht ausgezahlt"),
        )

    val titles =
        mapOf(
            "departments" to "Abteilungen",
            "positions" to "Positionen",
            "contract_types" to "Vertragsarten",
            "worker_statuses" to "Mitarbeiterstatus",
            "absence_types" to "Abwesenheitsarten",
            "absence_statuses" to "Abwesenheitsstatus",
            "document_types" to "Dokumentarten",
            "nationalities" to "Staatsangehörigkeiten",
            "locations" to "Standorte",
            "objects" to "Objekte",
            "payment_statuses" to "Zahlungsstatus",
        )

    fun title(group: String) =
        titles[group] ?: group.replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** Gespeicherte Listen (Einstellung „lookups“) oder die Vorgaben. */
    fun all(settings: Map<String, String>): Map<String, List<String>> {
        val stored =
            runCatching {
                    val o = org.json.JSONObject(settings["lookups"].orEmpty().ifBlank { "{}" })
                    o.keys().asSequence().associateWith { k ->
                        val a = o.getJSONArray(k)
                        (0 until a.length()).map { a.getString(it) }
                    }
                }
                .getOrDefault(emptyMap())
        return defaults + stored
    }

    fun encode(groups: Map<String, List<String>>): String {
        val o = org.json.JSONObject()
        for ((k, v) in groups) {
            require(k.matches(Regex("[a-z0-9_]{1,40}"))) { "Ungültiger Listenname." }
            val clean = v.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            require(clean.size <= 500 && clean.all { it.length <= 120 }) { "Liste zu lang." }
            o.put(k, JSONArray(clean))
        }
        return o.toString()
    }
}

/** Farbton eines Mitarbeiterstatus (Foto-Ring, Punkt und Textplakette). */
enum class StatusTone {
    GREEN,
    GRAY,
    RED,
    ORANGE,
    YELLOW,
    BLUE;

    companion object {
        fun of(raw: String): StatusTone =
            when (raw.trim().lowercase()) {
                "aktiv",
                "active" -> GREEN
                "inaktiv",
                "inactive",
                "nicht aktiv" -> GRAY
                "krank",
                "krank gemeldet",
                "sick",
                "vertrag beendet",
                "gekündigt",
                "gekuendigt",
                "terminated",
                "nicht gekommen" -> RED
                "urlaub",
                "leave",
                "zu spät gekommen" -> ORANGE
                "ersatz",
                "replacement",
                "ausgeschieden" -> YELLOW
                else -> BLUE
            }
    }
}

/** Avatar aus dem Atlas: nur ausdrücklich erfasste Angaben, nie Name oder Foto. */
object Avatars {
    fun group(gender: String, salutation: String = ""): Int =
        when (gender.trim().lowercase()) {
            "m",
            "männlich",
            "maennlich",
            "male",
            "mann" -> 0
            "w",
            "f",
            "weiblich",
            "female",
            "frau" -> 1
            "" ->
                when (salutation.trim().lowercase()) {
                    "herr",
                    "mr",
                    "mr." -> 0
                    "frau",
                    "ms",
                    "ms.",
                    "mrs",
                    "mrs." -> 1
                    else -> 2
                }
            else -> 2
        }

    /** SplitMix64 wie iOS: stabil über Neustarts, Filter und Namensänderungen. */
    fun index(group: Int, seedSource: String): Int {
        var seed = seedSource.fold(1125899906842597L) { h, c -> 31 * h + c.code }
        seed += -0x61c8864680b583ebL
        seed = (seed xor (seed ushr 30)) * -0x40a7b892e31b1a47L
        seed = (seed xor (seed ushr 27)) * -0x6b2fb644ecceee15L
        seed = seed xor (seed ushr 31)
        return group * 4 + java.lang.Long.remainderUnsigned(seed, 4).toInt()
    }
}
