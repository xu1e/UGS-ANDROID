package de.ugs.sicherheit

import java.time.LocalDate
import java.time.YearMonth

/** Kennzahlen der Übersicht (wie iOS MobileDashboard). Rein, damit sie testbar sind. */
data class DashboardFigures(
    val workers: Int,
    val active: Int,
    val leave: Int,
    val inactive: Int,
    val openTodos: Int,
    val pendingAbsences: Int,
    val expiredDocuments: Int,
    val criticalCertificates: Int,
    val soonDocuments: Int,
    val monthlyPayroll: Double,
    val averageSalary: Double,
    val departments: Map<String, Int>,
    val statuses: Map<String, Int>,
) {
    companion object {
        val inactiveStatuses = setOf("Inaktiv", "Ausgeschieden", "Vertrag beendet", "Gekündigt")

        fun payrollTotal(p: Entry): Double {
            fun n(k: String) = parseAmount(p[k]) ?: 0.0
            return n("hours") * n("rate") + n("baseSalary") + n("allowances") + n("bonus") - n("deductions")
        }

        fun of(all: List<Entry>, today: LocalDate = LocalDate.now()): DashboardFigures {
            val iso = today.toString()
            val workers = all.filter { it.kind == Kind.WORKER && !it.deleted }
            val onLeave =
                all.filter { it.kind == Kind.ABSENCE && !it.deleted && it["status"] != "Abgelehnt" && it["date"] <= iso && it["endDate"].ifBlank { it["date"] } >= iso }
                    .map { it["workerId"] }
                    .toSet()
            val inactive = workers.count { it["status"] in inactiveStatuses }
            val leave = workers.count { it["status"] !in inactiveStatuses && (it.id in onLeave || it["status"] == "Urlaub") }
            val docs = all.filter { it.kind == Kind.DOCUMENT && !it.deleted && it["archived"] != "true" && it["expiryDate"].isNotBlank() }
            val soonLimit = today.plusDays(60).toString()
            val criticalLimit = today.plusDays(30).toString()
            val month = YearMonth.from(today).toString()
            val activeWorkers = workers.filter { it["status"] !in inactiveStatuses }
            val salaries = activeWorkers.mapNotNull { w -> parseAmount(w["baseSalary"])?.takeIf { it > 0 }?.let { it + (parseAmount(w["allowances"]) ?: 0.0) } }
            return DashboardFigures(
                workers = workers.size,
                active = activeWorkers.size - leave,
                leave = leave,
                inactive = inactive,
                openTodos = all.count { it.kind == Kind.TODO && !it.deleted && !TodoModel.done(it) },
                pendingAbsences = all.count { it.kind == Kind.ABSENCE && !it.deleted && it["status"] == "In Bearbeitung" },
                expiredDocuments = docs.count { it["expiryDate"] < iso },
                criticalCertificates = docs.count { it["extendedCertificate"] == "true" && it["expiryDate"] <= criticalLimit && it["renewalRequestedOn"].isBlank() },
                soonDocuments = docs.count { it["expiryDate"] in iso..soonLimit },
                monthlyPayroll = all.filter { it.kind == Kind.PAYROLL && !it.deleted && it["date"].startsWith(month) }.sumOf { payrollTotal(it) },
                averageSalary = if (salaries.isEmpty()) 0.0 else salaries.average(),
                departments = activeWorkers.groupingBy { it["department"].ifBlank { "Ohne Abteilung" } }.eachCount().toList().sortedByDescending { it.second }.toMap(),
                statuses = workers.groupingBy { it["status"].ifBlank { "Aktiv" } }.eachCount(),
            )
        }
    }
}

/** Hinweise des Kontrollzentrums (wie iOS MobileOperations: Integrität, Personal, Planung). */
object OperationsCheck {
    data class Issue(val title: String, val detail: String, val page: String, val critical: Boolean = false)

    fun integrity(all: List<Entry>): List<Issue> {
        val live = all.filterNot { it.deleted }
        val ids = live.map { it.id }.toSet()
        val out = mutableListOf<Issue>()
        val orphans = live.filter { it["workerId"].isNotBlank() && it["workerId"] !in ids && it.kind != Kind.WORKER }
        if (orphans.isNotEmpty()) out += Issue("${orphans.size} Einträge ohne Mitarbeiter", orphans.take(5).joinToString(" · ") { "${it.kind.title}: ${it.title}" }, "control", true)
        val workers = live.filter { it.kind == Kind.WORKER }
        workers.filter { it["bewacherId"].isNotBlank() }.groupBy { it["bewacherId"].trim().lowercase() }.filterValues { it.size > 1 }.forEach { (id, list) ->
            out += Issue("Bewacher-ID $id mehrfach vergeben", list.joinToString(" · ") { it.title }, "workers", true)
        }
        val missingFiles = live.filter { it.kind == Kind.DOCUMENT && it["blob"].isBlank() }
        if (missingFiles.isNotEmpty()) out += Issue("${missingFiles.size} Dokumente ohne Datei", missingFiles.take(5).joinToString(" · ") { it.title }, "documents")
        return out
    }

    fun compliance(all: List<Entry>, today: LocalDate = LocalDate.now()): List<Issue> {
        val live = all.filterNot { it.deleted }
        val iso = today.toString()
        val names = live.filter { it.kind == Kind.WORKER }.associate { it.id to it.title }
        val out = mutableListOf<Issue>()
        for (w in live.filter { it.kind == Kind.WORKER && it["status"] !in DashboardFigures.inactiveStatuses }) {
            if (w["bewacherId"].isBlank()) out += Issue("${w.title} · Bewacher-ID fehlt", "Für Einsätze im Bewachungsgewerbe erforderlich (§ 34a GewO, Bewacherregister).", "workers")
            if (w["startDate"].isBlank()) out += Issue("${w.title} · Eintrittsdatum fehlt", "Für Vertrag, Urlaubskonto und Meldungen benötigt.", "workers")
        }
        for (d in live.filter { it.kind == Kind.DOCUMENT && it["archived"] != "true" && it["expiryDate"].isNotBlank() }) {
            val who = names[d["workerId"]] ?: "Ohne Mitarbeiter"
            when {
                d["expiryDate"] < iso -> out += Issue("$who · ${d.title}", "Abgelaufen am ${DateText.german(d["expiryDate"])}.", "documents", true)
                d["extendedCertificate"] == "true" && d["expiryDate"] <= today.plusDays(30).toString() && d["renewalRequestedOn"].isBlank() ->
                    out += Issue("$who · ${d.title}", "Erweitertes Führungszeugnis läuft am ${DateText.german(d["expiryDate"])} ab – Neuantrag noch nicht vermerkt.", "documents", true)
            }
        }
        return out.sortedByDescending { it.critical }
    }

    /** Planungskonflikte des laufenden und des nächsten Monats. */
    fun dutyConflicts(all: List<Entry>, today: LocalDate = LocalDate.now()): List<DutyConflict> {
        val live = all.filterNot { it.deleted }
        val absences = live.filter { it.kind == Kind.ABSENCE }
        val months = setOf(YearMonth.from(today).toString(), YearMonth.from(today).plusMonths(1).toString())
        val plans = live.filter { it.kind == Kind.DUTY_PLAN && it["month"] in months }
        val sites = live.filter { it.kind == Kind.SITE }.associate { it.id to it.title }
        return plans.flatMap { e ->
            val plan = runCatching { DutyPlan.from(e["payload"], e["month"]) }.getOrNull() ?: return@flatMap emptyList()
            val others = plans.filter { it.id != e.id && it["month"] == e["month"] }.mapNotNull { o -> runCatching { "${sites[o["siteId"]] ?: "Objekt"} ${o["month"]}" to DutyPlan.from(o["payload"], o["month"]) }.getOrNull() }
            runCatching { plan.conflicts(absences, others) }.getOrDefault(emptyList()).map { it.copy(message = "${sites[e["siteId"]] ?: "Objekt"}: ${it.message}") }
        }.distinctBy { it.date + it.workerName + it.message }
    }
}
