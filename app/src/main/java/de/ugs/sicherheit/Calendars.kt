package de.ugs.sicherheit

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.IsoFields
import java.util.concurrent.ConcurrentHashMap

/** Gesetzliche Feiertage, Vorgabe Köln / NRW (wie Mac und iOS). */
object GermanHolidays {
    enum class Scope {
        NRW_COLOGNE,
        ALL_STATES,
    }

    private val cache = ConcurrentHashMap<Pair<Int, Scope>, Map<LocalDate, String>>()

    fun name(date: LocalDate, scope: Scope = Scope.NRW_COLOGNE): String? =
        cache.getOrPut(date.year to scope) { compute(date.year, scope) }[date]

    fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }

    private fun compute(year: Int, scope: Scope): Map<LocalDate, String> {
        val out = linkedMapOf<LocalDate, String>()
        fun fixed(m: Int, d: Int, n: String) {
            out[LocalDate.of(year, m, d)] = n
        }
        fixed(1, 1, "Neujahr")
        fixed(5, 1, "Tag der Arbeit")
        fixed(10, 3, "Tag der Deutschen Einheit")
        fixed(12, 25, "1. Weihnachtstag")
        fixed(12, 26, "2. Weihnachtstag")
        val easter = easterSunday(year)
        out[easter.minusDays(2)] = "Karfreitag"
        out[easter.plusDays(1)] = "Ostermontag"
        out[easter.plusDays(39)] = "Christi Himmelfahrt"
        out[easter.plusDays(50)] = "Pfingstmontag"
        out[easter.plusDays(60)] = "Fronleichnam"
        fixed(11, 1, "Allerheiligen")
        if (scope == Scope.ALL_STATES) {
            fixed(1, 6, "Heilige Drei Könige")
            fixed(3, 8, "Internationaler Frauentag")
            fixed(8, 8, "Augsburger Friedensfest")
            fixed(8, 15, "Mariä Himmelfahrt")
            fixed(9, 20, "Weltkindertag")
            fixed(10, 31, "Reformationstag")
            // Mittwoch vor dem 23. November.
            var d = LocalDate.of(year, 11, 22)
            while (d.dayOfWeek != DayOfWeek.WEDNESDAY) d = d.minusDays(1)
            out[d] = "Buß- und Bettag"
        }
        return out
    }
}

data class MonthDay(
    val date: LocalDate,
    val weekday: String,
    val weekend: Boolean,
    val holiday: String?,
) {
    val iso
        get() = date.toString()

    val display
        get() = Rules.german(iso)

    val workFree
        get() = weekend || holiday != null
}

object MonthCalendar {
    private val names =
        listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")

    fun month(value: String): YearMonth {
        require(value.matches(Regex("(20\\d{2}|2100)-(0[1-9]|1[0-2])"))) {
            "Ungültiger Monat (2000–2100)."
        }
        return YearMonth.parse(value)
    }

    fun days(month: String): List<MonthDay> {
        val ym = month(month)
        return (1..ym.lengthOfMonth()).map {
            val d = ym.atDay(it)
            MonthDay(
                d,
                names[d.dayOfWeek.value - 1],
                d.dayOfWeek.value >= 6,
                GermanHolidays.name(d),
            )
        }
    }

    /** Monatsraster für den To-Do-Kalender: Montag zuerst, immer ganze Wochen. */
    fun grid(month: String): List<LocalDate?> {
        val ym = month(month)
        val leading = ym.atDay(1).dayOfWeek.value - 1
        val total = ((leading + ym.lengthOfMonth() + 6) / 7) * 7
        return (0 until total).map { i ->
            val day = i - leading + 1
            if (day in 1..ym.lengthOfMonth()) ym.atDay(day) else null
        }
    }

    fun isoWeek(d: LocalDate) = d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
}

object ClockTime {
    fun minutes(value: String): Int? {
        if (!value.matches(Regex("([01]\\d|2[0-3]):[0-5]\\d"))) return null
        val (h, m) = value.split(":").map { it.toInt() }
        return h * 60 + m
    }

    /** Dauer über Mitternacht; null wenn leer, ungültig oder gleich. */
    fun duration(start: String, end: String): Int? {
        if (start.isEmpty() && end.isEmpty()) return null
        val s = minutes(start) ?: return null
        val e = minutes(end) ?: return null
        if (s == e) return null
        return (e - s + 1440) % 1440
    }
}

object DateText {
    private val months = listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")
    private val weekdays = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")

    fun monthName(month: Int) = months.getOrElse(month - 1) { month.toString() }

    /** "2026-09-26" → "Samstag, 26. September 2026". */
    fun long(iso: String): String =
        runCatching { java.time.LocalDate.parse(iso) }.getOrNull()?.let { "${weekdays[it.dayOfWeek.value - 1]}, ${it.dayOfMonth}. ${monthName(it.monthValue)} ${it.year}" } ?: iso

    /** "2026-09-01" → "01.09.2026"; Unlesbares bleibt unverändert. */
    fun german(iso: String): String {
        val p = iso.split("-")
        return if (p.size == 3 && p[0].length == 4 && p[1].length == 2 && p[2].length == 2)
            "${p[2]}.${p[1]}.${p[0]}"
        else iso
    }

    /** "01.09.2026" → "2026-09-01"; null solange unvollständig oder ungültig. */
    fun iso(german: String): String? {
        val p = german.trim().split(".")
        if (p.size != 3 || p[0].length !in 1..2 || p[1].length !in 1..2 || p[2].length != 4)
            return null
        if (!p.all { s -> s.all { it.isDigit() } }) return null
        return runCatching { LocalDate.of(p[2].toInt(), p[1].toInt(), p[0].toInt()).toString() }
            .getOrNull()
    }

    /** Werktage Montag–Freitag in [from, to], auf [lower, upper] begrenzt. */
    fun workdays(from: LocalDate, to: LocalDate, lower: LocalDate, upper: LocalDate): Int {
        val a = maxOf(from, lower)
        val b = minOf(to, upper)
        if (a > b) return 0
        var n = 0
        var d = a
        var guard = 0
        while (d <= b && guard < 800) {
            if (d.dayOfWeek.value <= 5) n++
            d = d.plusDays(1)
            guard++
        }
        return n
    }
}
