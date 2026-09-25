package de.ugs.sicherheit

import android.content.Context
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

val companyFields =
    listOf(
        field("name", "Firmenname", true),
        field("street", "Straße und Hausnummer", true),
        field("postalCode", "PLZ", true),
        field("city", "Ort", true),
        field("representative", "Vertretungsberechtigte Person", true),
        field("email", "E-Mail"),
        field("phone", "Telefon"),
        field("website", "Website"),
        field("taxNumber", "Steuernummer"),
        field("registration", "Handelsregister"),
    )
val contractFields =
    listOf(
        personField,
        date("signingDate", "Unterzeichnung am", true),
        field("signingPlace", "Unterzeichnungsort", true),
        choice(
            "duration",
            "Vertragsdauer",
            "Unbefristet",
            "1 Jahr",
            "2 Jahre",
            "Bewachungsauftrag",
        ),
        check(
            "noPriorEmployment",
            "Keine Vorbeschäftigung bei diesem Arbeitgeber (Kalenderbefristung)",
        ),
        num("workingDays", "Arbeitstage pro Woche", "5", true),
        num("probationMonths", "Probezeit in Monaten (0 = keine)", "6", true),
        choice("hoursMode", "Arbeitszeitbasis", "Wöchentlich", "Monatlich"),
        num("monthlyHours", "Monatsstunden"),
        choice(
            "mobility",
            "Mobilität",
            "Keine zusätzliche Anforderung",
            "Führerschein",
            "Pkw",
            "Führerschein und Pkw",
        ),
        field("licenceClass", "Führerscheinklasse"),
        check("privateCar", "Dienstfahrten mit privatem Pkw vereinbaren"),
        num("kilometreRate", "Erstattung € / km"),
        longText("object", "Objekt und vollständige Anschrift", true),
        field("customer", "Auftraggeber", true),
        field("orderReference", "Auftragsbezeichnung / Referenz", true),
        longText("completionEvent", "Konkreter Zweck und objektiv feststellbarer Abschluss", true),
        longText(
            "temporaryNeed",
            "Tatsachen und Prognose des vorübergehenden Personalbedarfs",
            true,
        ),
        field("expectedDuration", "Voraussichtliche Dauer", true),
        check(
            "purposeReviewed",
            "Sachgrund, Prognose, Zweck, Form und Probezeit im Einzelfall geprüft",
        ),
        check("reviewed", "Vertragsdaten, Tarifgeltung und Verwendung geprüft"),
    )
val probationFields =
    listOf(
        personField,
        date("letterDate", "Briefdatum", true),
        date("contractStart", "Vertragsbeginn", true),
        num("probationMonths", "Vereinbarte Probezeit in Monaten", "6", true),
        date("receiptDate", "Geplanter Zugang", true),
        date("endDate", "Beendigung zum", true),
        field("signingPlace", "Ort", true),
        check("reviewed", "Probezeit, Kündigungsfrist, Sonderkündigungsschutz und Zugang geprüft"),
    )
val agreementFields =
    listOf(
        personField,
        date("signingDate", "Unterzeichnung am", true),
        field("signingPlace", "Ort", true),
        date("endDate", "Beendigung zum", true),
        check("beforeStart", "Aufhebung vor Arbeitsaufnahme – noch keine Arbeitsleistung"),
        check(
            "employerInitiated",
            "Betriebsbedingte Veranlassung des Arbeitgebers tatsächlich zutreffend",
        ),
        check("releaseEnabled", "Freistellung vereinbaren"),
        date("releaseStart", "Freistellung ab"),
        choice("releaseMode", "Freistellung", "widerruflich", "unwiderruflich"),
        longText("vacationSettlement", "Behandlung des Resturlaubs"),
        num("severance", "Abfindung € brutto", "0"),
        field("severanceDue", "Fällig mit Abrechnung für (Monat / Jahr)"),
        check("settlement", "Ausgleichsklausel vereinbaren"),
        choice("reference", "Zeugnis", "Auf Verlangen", "Qualifiziertes Zeugnis"),
        field("referenceGrade", "Gesamtbeurteilung (optional)"),
        check("reviewed", "Angaben, rechtliche Folgen und Verwendung im Einzelfall geprüft"),
    )

object Contracts {
    private fun clean(v: String) = v.trim().replace(Regex("\\s+"), " ")

    fun detailFields(c: Context): List<Field> {
        val a = JSONArray(c.assets.open("contracts/detail-fields.json").bufferedReader().readText())
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            longText(o.getString("key"), o.getString("label"))
        }
    }

    fun visibleFields(fields: List<Field>, v: Map<String, String>): List<Field> =
        fields.filter {
            (it.key !in
                setOf(
                    "object",
                    "customer",
                    "orderReference",
                    "completionEvent",
                    "temporaryNeed",
                    "expectedDuration",
                    "purposeReviewed",
                ) || v["duration"] == "Bewachungsauftrag") &&
                (it.key != "noPriorEmployment" || v["duration"] in listOf("1 Jahr", "2 Jahre")) &&
                (it.key != "monthlyHours" || v["hoursMode"] == "Monatlich") &&
                (it.key != "licenceClass" || v["mobility"].orEmpty().contains("Führerschein")) &&
                (it.key != "kilometreRate" || v["privateCar"] == "true") &&
                (it.key !in setOf("releaseStart", "releaseMode") || v["releaseEnabled"] == "true")
        }

    fun validateForm(fields: List<Field>, v: Map<String, String>) {
        for (f in visibleFields(fields, v)) {
            val value = v[f.key].orEmpty()
            require(!f.required || value.isNotBlank()) { "${f.label} fehlt." }
            if (value.isNotBlank() && f.input == Input.DATE) Rules.date(value)
        }
        require(v["reviewed"] == "true") { "Bitte die Prüfung der Angaben bestätigen." }
        for ((k, x) in v) {
            require(
                !x.contains("{{") && !x.contains("}}") && !x.contains("[") && !x.contains("]")
            ) {
                "Platzhalter im Feld $k ersetzen."
            }
        }
    }

    fun base(
        worker: Entry,
        company: Map<String, String>,
        v: Map<String, String>,
    ): MutableMap<String, String> {
        for (f in companyFields.filter { it.required }) require(!company[f.key].isNullOrBlank()) {
            "Einstellungen → Firma: ${f.label} fehlt."
        }
        for (k in listOf("firstName", "lastName", "street", "postalCode", "city")) require(
            worker[k].isNotBlank()
        ) {
            "Mitarbeiter: $k fehlt."
        }
        val m =
            mutableMapOf(
                "full_name" to worker.title,
                "first_name" to worker["firstName"],
                "last_name" to worker["lastName"],
                "salutation_name" to worker.title,
                "street" to worker["street"],
                "postal_code" to worker["postalCode"],
                "city" to worker["city"],
                "birth_date" to Rules.german(worker["birthDate"]),
                "personnel_number" to worker["personnelNumber"],
                "signing_date" to Rules.german(v["signingDate"] ?: v["letterDate"].orEmpty()),
                "signing_place" to v["signingPlace"].orEmpty(),
            )
        for (k in listOf("name", "street", "postalCode", "city", "representative")) m[
            "employer_" + if (k == "postalCode") "postal_code" else k] = company[k].orEmpty()
        return m
    }

    fun tokens(
        c: Context,
        w: Entry,
        company: Map<String, String>,
        v: Map<String, String>,
    ): Map<String, String> =
        tokens(
            w,
            company,
            v,
            detailFields(c),
            JSONObject(
                c.assets.open("contracts/deployment.json").bufferedReader().use { it.readText() }
            ),
        )

    internal fun tokens(
        w: Entry,
        company: Map<String, String>,
        v: Map<String, String>,
        details: List<Field>,
        deployments: JSONObject,
    ): Map<String, String> {
        validateForm(contractFields, v)
        val m = base(w, company, v)
        val start = Rules.date(w["startDate"])
        val signing = Rules.date(v.getValue("signingDate"))
        val duration = v["duration"] ?: "Unbefristet"
        val purpose = duration == "Bewachungsauftrag"
        val fixed = duration != "Unbefristet"
        val hourly = Rules.number(w["hourlyRate"])
        val weekly = Rules.number(w["weeklyHours"])
        val monthlyMode = v["hoursMode"] == "Monatlich"
        val hours = if (monthlyMode) Rules.number(v["monthlyHours"].orEmpty()) else weekly
        require(hourly in 0.01..9999.99) { "Stundenlohn fehlt oder ist ungültig." }
        require(hours >= 0.01 && hours <= if (monthlyMode) 208.0 else 48.0) {
            "Arbeitszeit außerhalb des unterstützten Bereichs."
        }
        val days = v["workingDays"]?.toIntOrNull() ?: 0
        val probation = v["probationMonths"]?.toIntOrNull() ?: -1
        val vacation = w["vacationDays"].toIntOrNull() ?: 0
        require(days in 1..6 && probation in 0..6) { "Arbeitstage: 1–6; Probezeit: 0–6 Monate." }
        require(vacation in days * 4..366) { "Mindestens ${days*4} Urlaubstage erforderlich." }
        for (f in details) {
            val x = clean(v[f.key].orEmpty())
            require(x.length <= 600) { "${f.label}: maximal 600 Zeichen." }
            m[f.key] = x
        }
        fun n(d: Double) = String.format(Locale.GERMANY, "%.2f", d)
        m["hourly_rate"] = n(hourly)
        m["working_days"] = days.toString()
        m["vacation_days"] = vacation.toString()
        m["duration_clause"] = "Das Arbeitsverhältnis wird auf unbestimmte Zeit geschlossen."
        m["fixed_term_basis_clause"] =
            "Eine kalendermäßige Befristungsgrundlage und eine darauf bezogene Vorbeschäftigungserklärung werden nicht vereinbart."
        if (fixed)
            require(signing <= start) {
                "Befristung muss vor Arbeitsaufnahme formwirksam vereinbart werden."
            }
        if (purpose) {
            for (k in
                listOf(
                    "object",
                    "customer",
                    "orderReference",
                    "completionEvent",
                    "temporaryNeed",
                    "expectedDuration",
                )) require(v[k].orEmpty().isNotBlank() && v[k]!!.length <= 600) {
                "Bewachungszweck: $k konkret ausfüllen, maximal 600 Zeichen."
            }
            require(v["purposeReviewed"] == "true") {
                "Einzelfallprüfung des Bewachungszwecks erforderlich."
            }
            m["duration_clause"] =
                "Das Arbeitsverhältnis wird zweckbefristet für den vorübergehenden Bewachungsauftrag „${clean(v.getValue("orderReference"))}“ des Auftraggebers ${clean(v.getValue("customer"))} für das Objekt ${clean(v.getValue("object"))} geschlossen. Es endet mit Beendigung dieses Bewachungsauftrags durch vollständige Erreichung des folgenden konkret vereinbarten Zwecks: ${clean(v.getValue("completionEvent"))}. Die Beendigung tritt frühestens zwei Wochen nach Zugang der schriftlichen Unterrichtung des Arbeitnehmers durch den Arbeitgeber über den Zeitpunkt der Zweckerreichung ein (§ 15 Abs. 2 TzBfG). Die bloße Kündigung oder Nichtverlängerung des Kundenauftrags, ein Austauschverlangen des Kunden oder die Abberufung des Arbeitnehmers stellt keine Zweckerreichung dar."
            m["fixed_term_basis_clause"] =
                "Sachgrund der Zweckbefristung ist der nur vorübergehende betriebliche Bedarf an der Arbeitsleistung (§ 14 Abs. 1 Satz 2 Nr. 1 TzBfG). Die bei Vertragsschluss zugrunde gelegten konkreten Tatsachen und die Prognose für den Wegfall des zusätzlichen Beschäftigungsbedarfs lauten: ${clean(v.getValue("temporaryNeed"))}. Voraussichtliche Dauer: ${clean(v.getValue("expectedDuration"))}. Diese Zeitangabe ist eine Prognose und kein zusätzlich vereinbartes kalendermäßiges Vertragsende. Die Unsicherheit über Folgeaufträge allein trägt die Befristung nicht."
            m["first_object"] = v.getValue("object")
        } else if (fixed) {
            require(v["noPriorEmployment"] == "true") { "Vorbeschäftigungserklärung erforderlich." }
            val years = if (duration == "1 Jahr") 1L else 2L
            val end = Rules.fixedEnd(start, years)
            m["duration_clause"] =
                "Das Arbeitsverhältnis wird für ${if(years==1L)"ein Jahr" else "zwei Jahre"} befristet geschlossen und endet mit Ablauf des ${Rules.german(end.toString())}, ohne dass es einer Kündigung bedarf."
            m["fixed_term_basis_clause"] =
                "Die sachgrundlose Befristung wird auf § 14 Abs. 2 TzBfG gestützt. Der Arbeitnehmer erklärt nach bestem Wissen, zuvor nicht bei diesem Arbeitgeber beschäftigt gewesen zu sein. Die tatsächlichen Voraussetzungen sind vor Vertragsschluss zu prüfen; die Erklärung ersetzt diese Prüfung nicht."
        }
        val deployment = deployments.getJSONObject(if (purpose) "purpose" else "normal")
        for (k in deployment.keys()) m[k] =
            Rules.resolve(
                deployment.getString(k),
                mapOf("city" to w["city"], "object" to v["object"].orEmpty()),
            )
        val phrase = "${n(hours)} Stunden ${if(monthlyMode)"pro Monat"else"pro Woche"}"
        m["employment_scope_clause"] =
            "Das Arbeitsverhältnis wird in ${w["employmentType"]} mit einer regelmäßigen Arbeitszeit von $phrase begründet und ist ${if(fixed)"befristet"else"unbefristet"}."
        m["regular_hours_clause"] =
            "Die regelmäßige Arbeitszeit beträgt ${n(hours)} Stunden ${if(monthlyMode)"monatlich"else"wöchentlich"} ohne Ruhepausen."
        m["working_days_phrase"] =
            "${listOf("","einen Arbeitstag","zwei Arbeitstage","drei Arbeitstage","vier Arbeitstage","fünf Arbeitstage","sechs Arbeitstage")[days]} pro Woche"
        m["vacation_minimum_clause"] =
            if (vacation == 4 * days)
                "Dies entspricht dem gesetzlichen Mindesturlaub nach dem Bundesurlaubsgesetz."
            else
                "Der gesetzliche Mindesturlaub nach dem Bundesurlaubsgesetz beträgt hierbei ${days*4} Arbeitstage."
        val month = if (monthlyMode) hours else hours * 52 / 12
        m["monthly_hours"] = n(month)
        m["account_monthly_limit"] = n(month / 2)
        m["monthly_pay_clause"] =
            if (monthlyMode)
                "Bei ${n(month)} vergütungspflichtigen Monatsstunden entspricht dies rechnerisch ${n(month*hourly)} € brutto."
            else
                "Bei der vereinbarten Wochenarbeitszeit ergeben sich rechnerisch durchschnittlich ${n(month)} Monatsstunden (Wochenstunden × 52 ÷ 12) und ${n(month*hourly)} € brutto; dies ist keine Vereinbarung eines festen Monatsentgelts."
        m["start_clause"] =
            "Das Arbeitsverhältnis beginnt am ${start.format(DateTimeFormatter.ofPattern("d. MMMM yyyy",Locale.GERMANY))}."
        m["probation_clause"] =
            if (probation == 0)
                "Es wird keine Probezeit vereinbart. Die Kündigungsregeln ergeben sich aus § 7 Abs. 3."
            else
                "${if(probation==1)"Der erste Monat gilt"else"Die ersten $probation Monate gelten"} als Probezeit. Bei Befristung muss die gewählte Dauer zur Vertragsdauer und Tätigkeit passen. Die Kündigungsregeln ergeben sich aus § 7 Abs. 3."
        val tariff = v["tariff_notice"].orEmpty()
        m["notice_clause"] =
            "${if(probation>0)"Während einer wirksam vereinbarten Probezeit gilt grundsätzlich eine Frist von zwei Wochen. Danach gilt für die beschäftigte Person die Frist von vier Wochen zum 15. oder zum Ende eines Kalendermonats."else"Für die beschäftigte Person gilt die Frist von vier Wochen zum 15. oder zum Ende eines Kalendermonats."} Für den Arbeitgeber gelten die gesetzlichen Fristen einschließlich der Verlängerungen nach § 622 Abs. 2 BGB. Abweichende zwingend geltende Tarifregeln gehen vor${if(tariff.isBlank())"."else"; maßgebliche tarifliche Kündigungsregel: $tariff."}"
        val mobility = v["mobility"].orEmpty()
        val licence = v["licenceClass"].orEmpty()
        if (mobility.contains("Führerschein"))
            require(licence.isNotBlank() && licence.length <= 20) {
                "Führerscheinklasse erforderlich."
            }
        m["mobility_clause"] =
            when (mobility) {
                "Führerschein" ->
                    "Gültige Fahrerlaubnis der Klasse $licence für die vereinbarten Fahrdienste. Die Bereitstellung eines privaten Pkw wird nicht geschuldet."
                "Führerschein und Pkw" ->
                    "Gültige Fahrerlaubnis der Klasse $licence und ein verlässlich verfügbares, zugelassenes und versichertes Fahrzeug für den Weg zu den vereinbarten Einsatzorten. Eigentum am Fahrzeug ist nicht erforderlich."
                "Pkw" ->
                    "Für den Weg zu den vereinbarten Einsatzorten wird ein verlässlich verfügbares, zugelassenes und versichertes Fahrzeug vorausgesetzt. Eigentum am Fahrzeug ist nicht erforderlich. Eine vertragliche Pflicht zum Besitz einer Fahrerlaubnis wird nicht vereinbart; die Anfahrt kann durch eine fahrberechtigte andere Person erfolgen. Eigene Fahrten setzen eine gültige Fahrerlaubnis voraus."
                else -> "Keine vertragliche Führerschein- oder Pkw-Pflicht."
            }
        m["car_expenses_clause"] =
            if (v["privateCar"] == "true") {
                require(mobility.contains("Führerschein"))
                val km = Rules.number(v["kilometreRate"].orEmpty())
                require(km > 0 && km <= 10) { "Erstattung: über 0 bis 10 € / km." }
                "Genehmigte Nutzung eines Privatwagens für Dienstfahrten: ${n(km)} EUR je gefahrenem Kilometer; erforderliche Park- und Mautkosten zusätzlich gegen Nachweis. Eine Haftungsverlagerung auf die beschäftigte Person ist damit nicht verbunden."
            } else
                "Die Nutzung eines Privatwagens für Dienstfahrten wird nicht angeordnet. Sie bedarf einer gesonderten Vereinbarung über die Nutzung und Kostenerstattung. Eine Haftungsverlagerung auf die beschäftigte Person ist damit nicht verbunden."
        return m
    }

    fun agreement(
        w: Entry,
        company: Map<String, String>,
        v: Map<String, String>,
    ): Map<String, String> {
        validateForm(agreementFields, v)
        val m = base(w, company, v)
        val start = Rules.date(w["startDate"])
        val sign = Rules.date(v.getValue("signingDate"))
        val end = Rules.date(v.getValue("endDate"))
        val before = v["beforeStart"] == "true"
        require(if (before) sign < start else end >= start && end >= sign) {
            "Beginn, Unterzeichnung und Beendigung passen nicht zur gewählten Variante."
        }
        fun flag(k: String, b: Boolean) {
            m[k] = b.toString()
        }
        flag("before_start", before)
        flag("after_start", !before)
        flag("employer_initiated", v["employerInitiated"] == "true")
        flag("release_enabled", !before && v["releaseEnabled"] == "true")
        flag("vacation_note", !before && !v["vacationSettlement"].isNullOrBlank())
        val severance = Rules.number(v["severance"] ?: "0")
        require(severance >= 0)
        if (severance > 0)
            require(!v["severanceDue"].isNullOrBlank()) { "Fälligkeit der Abfindung fehlt." }
        flag("severance_enabled", severance > 0)
        flag("severance_disabled", severance == 0.0)
        flag("settlement_enabled", v["settlement"] == "true")
        flag("reference_none", before)
        flag("reference_issued", !before && v["reference"] == "Qualifiziertes Zeugnis")
        flag("reference_on_request", !before && v["reference"] != "Qualifiziertes Zeugnis")
        if (m["release_enabled"] == "true")
            require(Rules.date(v["releaseStart"].orEmpty()) in sign..end) {
                "Freistellung muss zwischen Unterzeichnung und Ende liegen."
            }
        m.putAll(
            mapOf(
                "header_summary" to
                    if (before) "Aufhebung vor Arbeitsaufnahme"
                    else "Beendigung zum ${Rules.german(end.toString())}",
                "contract_start" to Rules.german(start.toString()),
                "contract_start_long" to Rules.german(start.toString()),
                "contract_end" to Rules.german(w["endDate"]),
                "end_date_long" to Rules.german(end.toString()),
                "release_start_long" to
                    if (m["release_enabled"] == "true") Rules.german(v["releaseStart"].orEmpty())
                    else "",
                "release_mode" to v["releaseMode"].orEmpty(),
                "vacation_settlement" to v["vacationSettlement"].orEmpty(),
                "severance_amount" to String.format(Locale.GERMANY, "%.2f", severance),
                "severance_due" to v["severanceDue"].orEmpty(),
                "reference_grade" to v["referenceGrade"].orEmpty(),
            )
        )
        return m
    }
}
