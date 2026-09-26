package de.ugs.sicherheit

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.json.JSONArray
import org.json.JSONObject

data class DutyShift(val label: String, val start: String, val end: String)

/** Belegung einer Schicht; [workerId] verknüpft sicher mit der Personalakte. */
data class DutySlot(
    val workerId: String = "",
    val bewacherId: String = "",
    val name: String = "",
    val shiftLabel: String = "",
    val start: String = "",
    val end: String = "",
) {
    val assigned
        get() = workerId.isNotBlank() || name.isNotBlank()
}

data class DutyDay(val date: String, val slots: List<DutySlot>)

data class DutyConflict(val id: String, val date: String, val workerName: String, val message: String, val critical: Boolean = true)

/** Monatsdienstplan eines Objekts (wie Mac/iOS: bis zu vier Schichten je Tag). */
data class DutyPlan(
    val customer: String = "",
    val order: String = "",
    val serviceNumber: String = "",
    val address: String = "",
    val shiftCount: Int = 3,
    val shifts: List<DutyShift> = DEFAULT_SHIFTS,
    val days: List<DutyDay> = emptyList(),
) {
    companion object {
        val DEFAULT_SHIFTS =
            listOf(
                DutyShift("Tagschicht", "06:00", "18:00"),
                DutyShift("Mittelschicht", "12:00", "00:00"),
                DutyShift("Nachtschicht", "18:00", "06:00"),
                DutyShift("Benutzerdefiniert", "", ""),
            )

        fun fresh(month: String): DutyPlan =
            DutyPlan(days = MonthCalendar.days(month).map { d -> DutyDay(d.iso, DEFAULT_SHIFTS.map { DutySlot(shiftLabel = it.label, start = it.start, end = it.end) }) })

        /** Gespeicherte Pläne an den Monat anpassen: 4 Schichten, jeder Tag genau einmal. */
        fun normalized(input: DutyPlan, month: String): DutyPlan {
            val calendar = MonthCalendar.days(month)
            val defaults = fresh(month)
            val shifts = (input.shifts + DEFAULT_SHIFTS.drop(input.shifts.size)).take(4)
            val byDate = input.days.associateBy { it.date }
            val days =
                calendar.mapIndexed { i, d ->
                    val old = byDate[d.iso]?.slots ?: defaults.days[i].slots
                    val slots =
                        (old + (old.size until 4).map { DutySlot(shiftLabel = shifts[it].label, start = shifts[it].start, end = shifts[it].end) })
                            .take(4)
                            .mapIndexed { n, s -> if (s.shiftLabel.isEmpty()) s.copy(shiftLabel = shifts[n].label) else s }
                    DutyDay(d.iso, slots)
                }
            return input.copy(shiftCount = input.shiftCount.coerceIn(1, 4), shifts = shifts, days = days)
        }

        fun from(json: String, month: String): DutyPlan {
            if (json.isBlank()) return fresh(month)
            val o = JSONObject(json)
            val sa = o.optJSONArray("shifts") ?: JSONArray()
            val da = o.optJSONArray("days") ?: JSONArray()
            val plan =
                DutyPlan(
                    o.optString("customer"),
                    o.optString("order"),
                    o.optString("service_number"),
                    o.optString("address"),
                    o.optInt("shift_count", 3),
                    (0 until sa.length()).map { sa.getJSONObject(it).let { s -> DutyShift(s.optString("label"), s.optString("start"), s.optString("end")) } },
                    (0 until da.length()).map { i ->
                        val d = da.getJSONObject(i)
                        val sl = d.optJSONArray("slots") ?: JSONArray()
                        DutyDay(
                            d.optString("date"),
                            (0 until sl.length()).map { j ->
                                sl.getJSONObject(j).let { s ->
                                    DutySlot(
                                        s.optString("worker_id"),
                                        s.optString("personnel_number"),
                                        s.optString("name"),
                                        s.optString("shift_label"),
                                        s.optString("start"),
                                        s.optString("end"),
                                    )
                                }
                            },
                        )
                    },
                )
            return normalized(plan, month)
        }
    }

    /** Gleiche Schlüssel wie Mac/iOS (personnel_number = Bewacher-ID), dazu worker_id. */
    fun json(): String =
        JSONObject()
            .put("customer", customer)
            .put("order", order)
            .put("service_number", serviceNumber)
            .put("address", address)
            .put("shift_count", shiftCount)
            .put("shifts", JSONArray(shifts.map { JSONObject().put("label", it.label).put("start", it.start).put("end", it.end) }))
            .put(
                "days",
                JSONArray(
                    days.map { d ->
                        JSONObject()
                            .put("date", d.date)
                            .put(
                                "slots",
                                JSONArray(
                                    d.slots.map {
                                        JSONObject()
                                            .put("worker_id", it.workerId)
                                            .put("personnel_number", it.bewacherId)
                                            .put("name", it.name)
                                            .put("shift_label", it.shiftLabel)
                                            .put("start", it.start)
                                            .put("end", it.end)
                                    }
                                ),
                            )
                    }
                ),
            )
            .toString()

    fun withSlot(date: String, index: Int, slot: DutySlot): DutyPlan =
        copy(days = days.map { d -> if (d.date == date && index in d.slots.indices) d.copy(slots = d.slots.toMutableList().also { it[index] = slot }) else d })

    /** Werktage (ohne Wochenende/Feiertag) einer Schicht mit einem Mitarbeiter belegen. */
    fun fillWeekdays(month: String, shift: Int, w: Entry): DutyPlan {
        val weekdays = MonthCalendar.days(month).filter { !it.workFree }.map { it.iso }.toSet()
        val s = shifts[shift]
        return copy(
            days =
                days.map { d ->
                    if (d.date in weekdays && shift in d.slots.indices)
                        d.copy(slots = d.slots.toMutableList().also { it[shift] = DutySlot(w.id, w["bewacherId"], w.title, s.label, s.start, s.end) })
                    else d
                }
        )
    }

    fun cleared() = copy(days = days.map { d -> d.copy(slots = d.slots.map { it.copy(workerId = "", bewacherId = "", name = "") }) })

    /** Belegungen einer anderen Planung (Vorlage oder Vormonat) nach Tagesposition übernehmen. */
    fun adopt(source: DutyPlan, month: String): DutyPlan {
        val target = fresh(month)
        return normalized(
            target.copy(
                customer = source.customer,
                order = source.order,
                serviceNumber = source.serviceNumber,
                address = source.address,
                shiftCount = source.shiftCount,
                shifts = source.shifts,
                days = target.days.mapIndexed { i, d -> if (i in source.days.indices) d.copy(slots = source.days[i].slots) else d },
            ),
            month,
        )
    }

    fun activeSlots(d: DutyDay) = d.slots.take(shiftCount.coerceIn(1, d.slots.size.coerceAtLeast(1)))

    private data class Interval(val key: String, val day: String, val slot: DutySlot, val start: Long, val end: Long)

    private fun intervals(): List<Interval> =
        days.flatMap { d ->
            val base = runCatching { LocalDate.parse(d.date).toEpochDay() * 1440 }.getOrNull() ?: return@flatMap emptyList()
            activeSlots(d).mapNotNull { s ->
                val key = s.workerId.ifBlank { s.bewacherId.lowercase() }
                val a = ClockTime.minutes(s.start)
                val b = ClockTime.minutes(s.end)
                if (key.isBlank() || a == null || b == null) null
                else Interval(key, d.date, s, base + a, base + if (b > a) b else b + 1440)
            }
        }

    /**
     * Planungskonflikte: doppelt am selben Tag, trotz Abwesenheit eingeplant und
     * Überschneidungen mit Dienstplänen anderer Objekte (auch über Monatsgrenzen).
     */
    fun conflicts(absences: List<Entry>, others: List<Pair<String, DutyPlan>>): List<DutyConflict> {
        val out = mutableListOf<DutyConflict>()
        for (d in days) {
            val seen = mutableMapOf<String, Int>()
            for (s in activeSlots(d)) {
                val key = s.workerId.ifBlank { s.bewacherId.lowercase() }
                if (key.isBlank()) continue
                val n = (seen[key] ?: 0) + 1
                seen[key] = n
                if (n > 1) out += DutyConflict("duplicate-${d.date}-$key-$n", d.date, s.name, "Mehrfach am selben Tag eingeplant.")
                if (absences.any { a -> a["workerId"] == s.workerId && a["status"] != "Abgelehnt" && a["date"] <= d.date && a["endDate"] >= d.date })
                    out += DutyConflict("absence-${d.date}-$key", d.date, s.name, "Trotz Abwesenheit eingeplant.")
            }
        }
        val mine = intervals()
        for ((label, other) in others) {
            val byKey = other.intervals().groupBy { it.key }
            mine.forEachIndexed { i, x ->
                if (byKey[x.key].orEmpty().any { x.start < it.end && it.start < x.end })
                    out += DutyConflict("overlap-$label-$i", x.day, x.slot.name, "Überschneidung mit Dienstplan: $label.")
            }
        }
        return out
    }

    data class Timesheet(val worker: Entry, val rows: List<TimesheetRow>, val part: Int, val parts: Int)

    /** Ein Stundenzettel je eingeplantem Mitarbeiter; zweite Einsätze am Tag auf Fortsetzungsblatt. */
    fun timesheets(month: String, workers: List<Entry>): List<Timesheet> {
        val calendar = MonthCalendar.days(month)
        val dates = calendar.map { it.iso }.toSet()
        val assigned = linkedMapOf<String, MutableMap<String, MutableList<DutySlot>>>()
        val selected = linkedMapOf<String, Entry>()
        for (d in days) {
            require(d.date in dates) { "Der Dienstplan enthält ein ungültiges Datum: ${d.date}." }
            for (s in activeSlots(d)) {
                if (!s.assigned) continue
                val matches =
                    if (s.workerId.isNotBlank()) workers.filter { it.id == s.workerId }
                    else if (s.bewacherId.isNotBlank()) workers.filter { it["bewacherId"].equals(s.bewacherId.trim(), true) }
                    else workers.filter { it.title.equals(s.name.trim(), true) }
                require(matches.size == 1) {
                    "Mitarbeiter für ${s.name} (${s.bewacherId}) nicht eindeutig gefunden. Bitte die Dienstplan-Zuordnung prüfen."
                }
                val w = matches[0]
                require(w["personnelNumber"].isNotBlank()) { "Bitte bei Mitarbeiter ${w.title} die Personalnummer eintragen." }
                if (s.start.isNotEmpty() || s.end.isNotEmpty())
                    require(ClockTime.duration(s.start, s.end) != null) {
                        "${d.date}, ${w.title}: Beginn und Ende bitte als unterschiedliche Uhrzeiten (HH:mm) angeben."
                    }
                selected[w.id] = w
                assigned.getOrPut(w.id) { mutableMapOf() }.getOrPut(d.date) { mutableListOf() } += s
            }
        }
        require(selected.isNotEmpty()) { "Im Dienstplan sind keine Mitarbeiter eingeplant." }
        val obj = listOf(customer, address).filter { it.isNotBlank() }.joinToString(" · ")
        return selected.values.sortedBy { it.title.lowercase() }.flatMap { w ->
            val a = assigned[w.id].orEmpty()
            val parts = a.values.maxOfOrNull { it.size } ?: 1
            (0 until parts).map { part ->
                val rows =
                    calendar.mapIndexed { i, day ->
                        val slots = a[day.iso]
                        if (slots == null || part >= slots.size)
                            TimesheetRow(i + 1, notes = if (day.workFree) "arbeitsfrei" else "", weekday = day.weekday, workFree = day.workFree)
                        else {
                            val s = slots[part]
                            val note = obj.ifBlank { w["object"] }
                            TimesheetRow(
                                i + 1,
                                s.start,
                                s.end,
                                "60",
                                notes = if (day.workFree) listOf("arbeitsfrei", note).filter { it.isNotEmpty() }.joinToString(" · ") else note,
                                weekday = day.weekday,
                                workFree = day.workFree,
                            )
                        }
                    }
                Timesheet(w, rows, part + 1, parts)
            }
        }
    }

    data class ExportRow(val weekday: String, val date: String, val employee: String, val time: String, val minutes: Int?, val workFree: Boolean)

    fun exportRows(month: String, shift: Int): List<ExportRow> {
        val saved = days.associateBy { it.date }
        return MonthCalendar.days(month).map { d ->
            val slot = saved[d.iso]?.slots?.getOrNull(shift)
            val start = slot?.start.orEmpty()
            val end = slot?.end.orEmpty()
            val name = slot?.name?.trim().orEmpty()
            // Rote Tage (Wochenende/Feiertag) zählen nie als geplante Stunden.
            val minutes = if (d.workFree) 0 else ClockTime.duration(start, end)?.let { maxOf(0, it - 60) }
            val employee =
                if (d.workFree) listOf("arbeitsfrei", name).filter { it.isNotEmpty() && it != "arbeitsfrei" }.joinToString(" · ").ifEmpty { d.holiday ?: "arbeitsfrei" }
                else name
            ExportRow(d.weekday, d.display, employee, if (start.isEmpty() && end.isEmpty()) "" else "$start-$end", minutes, d.workFree)
        }
    }
}

fun hoursText(minutes: Int) = if (minutes % 60 == 0) "${minutes / 60}" else String.format(java.util.Locale.GERMANY, "%.2f", minutes / 60.0)

/** Urlaubskonto eines Jahres: Anspruch, Übertrag, Korrektur, genommen und beantragt. */
data class VacationSummary(
    val worker: Entry,
    val year: Int,
    val entitlement: Double,
    val carryOver: Double,
    val adjustment: Double,
    val taken: Int,
    val requested: Int,
    val other: Int,
    val account: Entry?,
) {
    val total
        get() = entitlement + carryOver + adjustment

    val remaining
        get() = total - taken

    val afterRequests
        get() = remaining - requested

    companion object {
        fun build(w: Entry, year: Int, all: List<Entry>): VacationSummary {
            val lower = LocalDate.of(year, 1, 1)
            val upper = LocalDate.of(year, 12, 31)
            val account = all.firstOrNull { it.kind == Kind.VACATION_ACCOUNT && it["workerId"] == w.id && it["year"] == "$year" }
            var taken = 0
            var requested = 0
            var other = 0
            for (a in all.filter { it.kind == Kind.ABSENCE && it["workerId"] == w.id }) {
                val from = runCatching { LocalDate.parse(a["date"]) }.getOrNull() ?: continue
                val to = runCatching { LocalDate.parse(a["endDate"]) }.getOrNull() ?: from
                val n = DateText.workdays(from, to, lower, upper)
                when {
                    !Workers.isVacation(a["type"]) -> if (a["status"] != "Abgelehnt") other += n
                    Workers.isApproved(a["status"]) -> taken += n
                    a["status"] != "Abgelehnt" -> requested += n
                }
            }
            fun n(k: String) = account?.get(k)?.let { parseAmount(it) ?: it.replace(",", ".").toDoubleOrNull() } ?: 0.0
            val entitlement = if (account != null) n("entitlement") else w["vacationDays"].replace(",", ".").toDoubleOrNull() ?: 0.0
            return VacationSummary(w, year, entitlement, n("carryOver"), n("adjustment"), taken, requested, other, account)
        }
    }
}

/** Plan-Ist-Prüfung einer Lohnabrechnung (geplante Stunden, erfasste Zeiten, Abwesenheiten). */
fun payrollCheck(worker: Entry, month: String, all: List<Entry>): String {
    val plans = all.filter { it.kind == Kind.DUTY_PLAN && it["month"] == month }.map { DutyPlan.from(it["payload"], month) }
    var planned = 0
    for (p in plans) for (d in p.days) for (s in p.activeSlots(d)) if (s.workerId == worker.id) planned += ClockTime.duration(s.start, s.end)?.let { maxOf(0, it - 60) } ?: 0
    val recorded = all.filter { it.kind == Kind.TIME && it["workerId"] == worker.id && it["date"].startsWith(month) }.sumOf { runCatching { Rules.hours(it) }.getOrDefault(0.0) }
    val ym = MonthCalendar.month(month)
    val absent =
        all.filter { it.kind == Kind.ABSENCE && it["workerId"] == worker.id && it["status"] != "Abgelehnt" }.sumOf { a ->
            val from = runCatching { LocalDate.parse(a["date"]) }.getOrNull() ?: return@sumOf 0
            val to = runCatching { LocalDate.parse(a["endDate"]) }.getOrNull() ?: from
            DateText.workdays(from, to, ym.atDay(1), ym.atEndOfMonth())
        }
    val warnings = mutableListOf<String>()
    val plannedHours = planned / 60.0
    if (plannedHours > 0 && recorded == 0.0) warnings += "Geplante Dienste, aber keine erfassten Zeiten."
    if (recorded > 0 && kotlin.math.abs(recorded - plannedHours) > 8) warnings += "Erfasste Zeiten weichen mehr als 8 Stunden vom Plan ab."
    if (absent > 0) warnings += "$absent Abwesenheitstage im Monat."
    return "Geplant: ${decimal(plannedHours)} h · Erfasst: ${decimal(recorded)} h" + if (warnings.isEmpty()) "" else "\n" + warnings.joinToString("\n")
}

fun daysBetween(a: String, b: String): Long? =
    runCatching { ChronoUnit.DAYS.between(LocalDate.parse(a), LocalDate.parse(b)) }.getOrNull()
