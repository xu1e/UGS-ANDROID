package de.ugs.sicherheit

import java.time.LocalDate
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrayerTodoTest {
    @Test
    fun cologneTimesArePlausibleAndOrdered() {
        for (d in listOf("2026-01-15", "2026-06-21", "2026-09-26", "2026-12-21")) {
            val t = PrayerTimes.koeln(LocalDate.parse(d))
            val m = t.list.map { it.minutes }
            assertEquals(m.sorted(), m)
            assertTrue(t.dhuhr in 12 * 60 + 20..13 * 60 + 45)
        }
        // Kurze Sommernacht: Ischa bleibt vor Mitternacht, Fajr nach 2 Uhr.
        val june = PrayerTimes.koeln(LocalDate.parse("2026-06-21"))
        assertTrue(june.isha < 24 * 60 && june.fajr > 2 * 60)
        assertEquals("13:34", PrayerTimes.clock(june.dhuhr))
    }

    @Test
    fun afterIshaNextPrayerIsFajrTomorrow() {
        val s = PrayerSchedule.at(ZonedDateTime.parse("2026-09-26T23:30:00+02:00[Europe/Berlin]"))
        assertEquals(null, s.nextIndex)
        assertEquals("Fajr", s.nextName)
        assertTrue(s.minutesUntilNext in 5 * 60..7 * 60)
    }

    @Test
    fun todoSummaryCountsColoursLikeCalendar() {
        fun t(vararg p: Pair<String, String>) = Entry(kind = Kind.TODO, fields = mapOf(*p))
        val todos =
            listOf(
                t("priority" to "Hoch", "status" to "Offen", "date" to "2026-01-01"),
                t("todoKind" to "Termin", "status" to "Offen"),
                t("todoKind" to "Aufgabe", "status" to "Offen"),
                t("status" to "Erledigt"),
            )
        val s = TodoModel.summary(todos, "2026-09-26")
        assertEquals(TodoModel.Summary(4, 3, 1, 1, 1, 1, 1), s)
        assertEquals(25, s.percent)
        assertEquals(3, TodoModel.visible(todos, "Alle", "", false).size)
        assertEquals(1, TodoModel.visible(todos, "Termine", "", true).size)
    }
}
