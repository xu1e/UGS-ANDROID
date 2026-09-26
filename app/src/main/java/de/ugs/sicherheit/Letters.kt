package de.ugs.sicherheit

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Abmahnung, Kündigung und Kündigung in der Probezeit (wie iPhone/iPad 1.9.0). */
data class LetterData(
    val kind: Kind = Kind.WARNING,
    val letterDate: String = today,
    val incidentDate: String = today,
    val immediate: Boolean = false,
    val endDate: String = "",
    val reason: String = "",
    val expected: String = "",
    val reviewed: Boolean = false,
    val salutation: String = "neutral",
    val startDate: String = "",
    val probationEndDate: String = "",
    val receiptDate: String = "",
    val individualNotice: Boolean = false,
    val noticeRule: String = "",
) {
    enum class Kind(val title: String) {
        WARNING("Abmahnung"),
        TERMINATION("Kündigung"),
        PROBATION("Kündigung innerhalb der Probezeit"),
    }

    val title
        get() =
            when {
                kind == Kind.PROBATION -> "Kündigung innerhalb der Probezeit"
                kind == Kind.WARNING -> "Abmahnung"
                immediate -> "Fristlose Kündigung"
                else -> "Kündigung"
            }

    val category
        get() = if (kind == Kind.WARNING) "Abmahnung" else "Kündigung"

    val fileLabel
        get() =
            when (kind) {
                Kind.PROBATION -> "Kuendigung-Probezeit"
                Kind.WARNING -> "Abmahnung"
                Kind.TERMINATION -> "Kündigung"
            }

    private fun date(v: String, label: String): LocalDate =
        runCatching { LocalDate.parse(v) }.getOrNull() ?: throw IllegalArgumentException(label)

    /** Datumsangaben werden ausdrücklich eingegeben: ein Briefdatum beweist keinen Zugang. */
    fun validateProbation() {
        val msg = "Bitte Arbeitsbeginn, Probezeitende, Zugang, Briefdatum und Beendigung als gültige Daten eintragen."
        val start = date(startDate, msg)
        val probationEnd = date(probationEndDate, msg)
        val receipt = date(receiptDate, msg)
        val letter = date(letterDate, msg)
        val end = date(endDate, msg)
        require(probationEnd >= start && receipt >= start && receipt <= probationEnd) {
            "Der Zugang muss innerhalb der vereinbarten Probezeit liegen."
        }
        require(receipt >= letter && end > receipt) {
            "Der Zugang darf nicht vor dem Briefdatum liegen; die Beendigung muss nach dem Zugang liegen."
        }
        if (individualNotice) {
            val rule = noticeRule.trim()
            require(rule.isNotEmpty() && rule.length <= 500 && "[" !in rule && "]" !in rule && "{{" !in rule && "}}" !in rule) {
                "Bitte die geprüfte vertragliche / tarifliche Kündigungsregel ohne Platzhalter angeben (max. 500 Zeichen)."
            }
        } else {
            require(receipt < start.plusMonths(6)) {
                "Die gesetzliche Probezeitfrist gilt längstens während der ersten sechs Monate. Bitte die maßgebliche Kündigungsregel prüfen."
            }
            require(end >= receipt.plusDays(14)) {
                "Bei der gesetzlichen Probezeitfrist muss die Beendigung mindestens 14 Kalendertage nach dem Zugang liegen."
            }
        }
    }

    fun validate(worker: Entry, company: Map<String, String>) {
        require(worker.title.isNotBlank()) { "Bitte zuerst einen Mitarbeiter wählen." }
        require(company["name"].orEmpty().isNotBlank()) { "Bitte den Firmennamen prüfen." }
        require(listOf(worker["street"], worker["postalCode"], worker["city"]).all { it.isNotBlank() }) {
            "Mitarbeiter: vollständige Anschrift erforderlich."
        }
        require(listOf("street", "postalCode", "city").all { company[it].orEmpty().isNotBlank() }) {
            "Firma: vollständige Anschrift erforderlich."
        }
        require(company["representative"].orEmpty().isNotBlank()) {
            "Bitte die unterschriftsberechtigte Person der Firma eintragen."
        }
        val letter = date(letterDate, "Bitte ein gültiges Briefdatum angeben.")
        if (kind == Kind.PROBATION) {
            require(reviewed) { "Bitte bestätigen, dass Angaben und rechtliche Verwendung geprüft wurden." }
            validateProbation()
            return
        }
        require(reason.isNotBlank()) { "Bitte den konkreten Grund / Sachverhalt eingeben." }
        require(reason.length <= 12000 && expected.length <= 4000) { "Der Text ist zu lang." }
        val printed = if (kind == Kind.WARNING) reason + "\n" + expected else reason
        require(!Regex("\\[[^\\]]{2,}]").containsMatchIn(printed)) {
            "Noch nicht ersetzte Platzhalter [..] vorhanden. Bitte durch tatsächliche Angaben ersetzen."
        }
        require(reviewed) { "Bitte bestätigen, dass Angaben und rechtliche Verwendung geprüft wurden." }
        when {
            kind == Kind.WARNING -> {
                val incident = date(incidentDate, "Bitte das Vorfalldatum eingeben.")
                require(incident <= letter) { "Der Vorfall darf nicht nach dem Briefdatum liegen." }
                require(expected.isNotBlank()) {
                    "Bitte die beanstandete Pflicht und das künftig erwartete Verhalten beschreiben."
                }
            }
            immediate -> {
                val knowledge = date(incidentDate, "Bitte das Datum der Kenntnis vom Kündigungsgrund eingeben.")
                require(knowledge <= letter) { "Die Kenntnis vom Kündigungsgrund darf nicht nach dem Briefdatum liegen." }
                require(ChronoUnit.DAYS.between(knowledge, letter) <= 14) {
                    "Die Zwei-Wochen-Frist des § 626 Abs. 2 BGB ist überschritten. Bitte rechtlich prüfen lassen."
                }
            }
            else -> {
                val end = date(endDate, "Bitte das Beendigungsdatum eingeben.")
                require(end >= letter) { "Beendigungsdatum darf nicht vor dem Briefdatum liegen. Kündigungsfrist prüfen." }
            }
        }
    }

    fun salutationLine(w: Entry) =
        when (salutation) {
            "female" -> "Sehr geehrte Frau ${w["lastName"]},"
            "male" -> "Sehr geehrter Herr ${w["lastName"]},"
            else -> "Guten Tag ${w.title},"
        }

    /** Absätze des Schreibens (fett = Zwischenüberschrift). */
    fun paragraphs(w: Entry): List<Pair<String, Boolean>> =
        buildList {
            add(salutationLine(w) to false)
            when {
                kind == Kind.WARNING -> {
                    add("hiermit mahnen wir Sie wegen des nachfolgend beschriebenen Verhaltens ab." to false)
                    add("Sachverhalt vom ${DateText.german(incidentDate)}" to true)
                    add(reason to false)
                    add("Beanstandete Pflicht und künftig erwartetes Verhalten" to true)
                    add(expected to false)
                    add(
                        "Wir fordern Sie auf, das beschriebene Fehlverhalten künftig zu unterlassen und Ihre arbeitsvertraglichen Pflichten einzuhalten. Bei einem erneuten gleichartigen Pflichtverstoß müssen Sie mit weiteren arbeitsrechtlichen Maßnahmen bis hin zur Kündigung des Arbeitsverhältnisses rechnen." to
                            false
                    )
                }
                kind == Kind.PROBATION -> {
                    add(
                        "hiermit kündigen wir das mit Ihnen seit dem ${DateText.german(startDate)} bestehende Arbeitsverhältnis innerhalb der vereinbarten Probezeit ordentlich und fristgerecht zum ${DateText.german(endDate)}, hilfsweise zum nächstzulässigen Zeitpunkt." to
                            false
                    )
                    add(
                        "Bitte melden Sie sich spätestens drei Monate vor Beendigung des Arbeitsverhältnisses bei der Agentur für Arbeit arbeitsuchend. Liegen zwischen der Kenntnis des Beendigungszeitpunkts und der Beendigung weniger als drei Monate, melden Sie sich innerhalb von drei Tagen nach Kenntnis arbeitsuchend. Die Arbeitslosmeldung ist zusätzlich spätestens am ersten Tag der Arbeitslosigkeit erforderlich." to
                            false
                    )
                }
                immediate -> {
                    add(
                        "hiermit kündigen wir das mit Ihnen bestehende Arbeitsverhältnis außerordentlich und fristlos mit sofortiger Wirkung, hilfsweise ordentlich zum nächstzulässigen Zeitpunkt." to
                            false
                    )
                    add("Wichtiger Grund" to true)
                    add(reason to false)
                    add("Von den vorstehenden Tatsachen haben wir am ${DateText.german(incidentDate)} Kenntnis erlangt." to false)
                }
                else -> {
                    add("hiermit kündigen wir das mit Ihnen bestehende Arbeitsverhältnis ordentlich zum ${DateText.german(endDate)}." to false)
                    add("Begründung" to true)
                    add(reason to false)
                }
            }
        }
}

/** Anforderung eines erweiterten Führungszeugnisses (Originalvorlage mit Feldern). */
data class CertificateRequest(
    val date: String = today,
    val requester: String = "",
    val requesterAddress: String = "",
    val lastName: String = "",
    val firstName: String = "",
    val birthDate: String = "",
    val address: String = "",
    val activity: String = "",
    val client: String = "",
) {
    companion object {
        fun of(w: Entry, company: Map<String, String>) =
            CertificateRequest(
                requester = company["name"].orEmpty(),
                requesterAddress =
                    listOf(company["street"].orEmpty().trim(), "${company["postalCode"].orEmpty()} ${company["city"].orEmpty()}".trim())
                        .filter { it.isNotEmpty() }
                        .joinToString(", "),
                lastName = w["lastName"],
                firstName = w["firstName"],
                birthDate = w["birthDate"],
                address = Workers.address(w),
                activity = w["position"],
                client = w["object"],
            )
    }

    fun validate() {
        val missing =
            listOf(
                    "Firma" to requester,
                    "Firmenanschrift" to requesterAddress,
                    "Nachname" to lastName,
                    "Vorname" to firstName,
                    "Geburtsdatum" to birthDate,
                    "Mitarbeiteranschrift" to address,
                    "Datum" to date,
                    "Tätigkeit" to activity,
                )
                .filter { it.second.isBlank() }
                .map { it.first }
        require(missing.isEmpty()) { "Bitte ergänzen: ${missing.joinToString(", ")}." }
        require(runCatching { LocalDate.parse(date) }.isSuccess) { "Bitte ein gültiges Ausstellungsdatum eintragen." }
        require(runCatching { LocalDate.parse(birthDate) }.isSuccess) { "Bitte ein gültiges Geburtsdatum eintragen." }
    }

    val values
        get() =
            mapOf(
                "date" to DateText.german(date),
                "requester" to requester,
                "requesterAddress" to requesterAddress,
                "lastName" to lastName,
                "firstName" to firstName,
                "birthDate" to DateText.german(birthDate),
                "address" to address,
                "activity" to activity,
                "client" to client,
            )
}

/** Zeile des Stundenzettels. */
data class TimesheetRow(
    val day: Int,
    val start: String = "",
    val end: String = "",
    val breakMinutes: String = "",
    val code: String = "",
    val recordedOn: String = "",
    val notes: String = "",
    val weekday: String = "",
    val workFree: Boolean = false,
) {
    val duration: Int?
        get() {
            if (start.isEmpty() && end.isEmpty()) return null
            val s = ClockTime.minutes(start) ?: return null
            val e = ClockTime.minutes(end) ?: return null
            val pause = breakMinutes.toIntOrNull()?.takeIf { it >= 0 } ?: return null
            if (s == e) return null
            val span = (e - s + 1440) % 1440
            return if (pause <= span) span - pause else null
        }

    companion object {
        val codes = listOf("", "K", "U", "UU", "F", "SA", "SU")

        fun validate(month: String, rows: List<TimesheetRow>) {
            val days = MonthCalendar.days(month)
            require(rows.size == days.size) { "Die Tageszeilen passen nicht zum ausgewählten Monat." }
            rows.forEachIndexed { i, r ->
                require(r.day == i + 1) { "Ungültige Tagesreihenfolge." }
                require(r.start.isEmpty() == r.end.isEmpty()) { "Tag ${r.day}: Beginn und Ende gemeinsam angeben." }
                if (r.start.isNotEmpty()) {
                    require(ClockTime.minutes(r.start) != null && ClockTime.minutes(r.end) != null) {
                        "Tag ${r.day}: Uhrzeiten bitte als HH:mm eingeben."
                    }
                    require(r.breakMinutes.isNotEmpty()) { "Tag ${r.day}: Pause in Minuten eintragen, auch wenn sie 0 ist." }
                    require(r.duration != null) { "Tag ${r.day}: Schichtdauer oder Pause ist ungültig." }
                }
                if (r.recordedOn.isNotEmpty())
                    require(runCatching { LocalDate.parse(r.recordedOn) }.isSuccess) { "Tag ${r.day}: Aufzeichnungsdatum ist ungültig." }
                require(r.code in codes) { "Unbekanntes Stundenkürzel an Tag ${r.day}." }
            }
        }
    }
}
