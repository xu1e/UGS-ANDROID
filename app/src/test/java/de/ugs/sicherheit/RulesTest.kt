package de.ugs.sicherheit

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RulesTest {
    private val worker =
        Entry(
            id = "worker-1",
            kind = Kind.WORKER,
            fields =
                mapOf(
                    "personnelNumber" to "1",
                    "firstName" to "Ada",
                    "lastName" to "Test",
                    "startDate" to "2026-01-01",
                ),
        )
    private val site =
        Entry(
            id = "site-1",
            kind = Kind.SITE,
            fields = mapOf("title" to "Objekt", "address" to "Berlin"),
        )

    private fun shift(date: String, start: String, end: String, breaks: String = "30") =
        Entry(
            kind = Kind.SHIFT,
            fields =
                mapOf(
                    "workerId" to worker.id,
                    "siteId" to site.id,
                    "date" to date,
                    "startTime" to start,
                    "endTime" to end,
                    "breakMinutes" to breaks,
                    "status" to "Geplant",
                ),
        )

    @Test
    fun overnightHours() {
        assertEquals(7.5, Rules.hours(shift("2026-09-15", "22:00", "06:00")), .00001)
    }

    @Test
    fun overlappingNextDayRejected() {
        val one = shift("2026-09-15", "22:00", "06:00")
        val two = shift("2026-09-16", "05:30", "08:00")
        assertThrows(IllegalArgumentException::class.java) {
            Rules.validate(two, listOf(worker, site, one))
        }
    }

    @Test
    fun adjacentShiftsAllowed() {
        val one = shift("2026-09-15", "22:00", "06:00")
        val two = shift("2026-09-16", "06:00", "08:00")
        Rules.validate(two, listOf(worker, site, one))
    }

    @Test
    fun excessiveBreakRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Rules.validate(shift("2026-09-15", "08:00", "09:00", "60"), listOf(worker, site))
        }
    }

    @Test
    fun leapYearInclusiveEnd() {
        assertEquals(LocalDate.of(2025, 2, 28), Rules.fixedEnd(LocalDate.of(2024, 2, 29), 1))
        assertEquals(LocalDate.of(2027, 9, 14), Rules.fixedEnd(LocalDate.of(2026, 9, 15), 1))
    }

    @Test
    fun probationNotice() {
        Rules.probation("2026-04-01", 6, "2026-09-15", "2026-09-16", "2026-09-30")
        assertThrows(IllegalArgumentException::class.java) {
            Rules.probation("2026-04-01", 6, "2026-09-15", "2026-09-16", "2026-09-29")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Rules.probation("2026-04-01", 6, "2026-09-30", "2026-10-01", "2026-10-15")
        }
    }

    @Test
    fun optionalSegment() {
        assertEquals(
            "Hallo Ada.",
            Rules.resolve("Hallo {{name}}[[ in {{city}}]].", mapOf("name" to "Ada", "city" to "")),
        )
        assertEquals(
            "Hallo Ada in Bonn.",
            Rules.resolve(
                "Hallo {{name}}[[ in {{city}}]].",
                mapOf("name" to "Ada", "city" to "Bonn"),
            ),
        )
    }

    @Test
    fun unknownTokenRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Rules.resolve("{{unknown}}", emptyMap())
        }
    }

    @Test
    fun csvQuotesAndNewlines() {
        val rows =
            ImportService.csv(
                "Vorname;Nachname;Notiz\r\nAda;Test;\"Zeile 1\nZeile 2; \"\"Zitat\"\"\""
            )
        assertEquals("Vorname", rows[0][0])
        assertEquals("Ada", rows[1][0])
        assertEquals("Zeile 1\nZeile 2; \"Zitat\"", rows[1][2])
    }

    @Test
    fun duplicateNumberRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Rules.validate(worker.copy(id = "worker-2"), listOf(worker))
        }
    }

    @Test
    fun weekendNotCounted() {
        assertEquals(2, Rules.weekdays(LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 21)))
    }

    @Test
    fun absenceEndBeforeStartRejected() {
        val e =
            Entry(
                kind = Kind.ABSENCE,
                fields =
                    mapOf(
                        "workerId" to worker.id,
                        "type" to "Urlaub",
                        "date" to "2026-09-20",
                        "endDate" to "2026-09-19",
                        "status" to "Beantragt",
                    ),
            )
        assertThrows(IllegalArgumentException::class.java) { Rules.validate(e, listOf(worker)) }
    }
}
