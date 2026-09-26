package de.ugs.sicherheit

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTest {
    @Test
    fun understandsArabicAndGerman() {
        assertEquals(VoiceIntent.Todos(false), VoiceParser.parse("علاس، شو عندي اليوم؟"))
        assertEquals(VoiceIntent.Todos(true), VoiceParser.parse("شو عندي بكرا؟"))
        assertEquals(VoiceIntent.AddTodo("اتصل بالزبون", true), VoiceParser.parse("ضيف مهمة اتصل بالزبون بكرا"))
        assertEquals(VoiceIntent.Expenses(true, "Hotel / Übernachtung"), VoiceParser.parse("مصاريف الفندق الشهر الماضي"))
        assertEquals(VoiceIntent.MailReadLatest, VoiceParser.parse("اقرأ آخر رسالة"))
        assertEquals(VoiceIntent.AddTodo("Kunde anrufen", true), VoiceParser.parse("Neue Aufgabe Kunde anrufen morgen"))
        assertEquals(VoiceIntent.Expenses(true, "Hotel / Übernachtung"), VoiceParser.parse("Ausgaben letzten Monat für Hotel"))
        assertEquals(VoiceIntent.MailSummary, VoiceParser.parse("Neue Mails?"))
        assertEquals(VoiceIntent.Time, VoiceParser.parse("Wie spät ist es"))
        assertEquals(VoiceIntent.Todos(false), VoiceParser.parse("Guten Morgen, was steht an"))
        assertEquals(VoiceIntent.Stop, VoiceParser.parse("danke"))
    }

    @Test
    fun answersFromData() {
        val today = LocalDate.parse("2026-09-26")
        val todos =
            listOf(
                Entry(kind = Kind.TODO, fields = mapOf("title" to "Kunde anrufen", "date" to "2026-09-26", "status" to "Offen")),
                Entry(kind = Kind.TODO, fields = mapOf("title" to "Alt", "date" to "2026-09-01", "status" to "Offen")),
            )
        val de = VoiceAnswers.todos(todos, false, false, true, today).joinToString(" ") { it.text }
        assertTrue(de, de.contains("Kunde anrufen") && de.contains("überfällig"))
        val expenses = listOf(Entry(kind = Kind.EXPENSE, fields = mapOf("date" to "2026-08-03", "amount" to "49,90", "category" to "Hotel / Übernachtung")))
        val e = VoiceAnswers.expenses(expenses, true, null, false, true, YearMonth.parse("2026-09")).first().text
        assertTrue(e, e.startsWith("Ausgaben im August") && e.contains("1 Buchung"))
        assertEquals("49 يورو و90 سنت", VoiceAnswers.arabicEuro(49.9))
        assertEquals("Hallo Welt.", VoiceAnswers.speakableBody("> zitat\nHallo https://x.de Welt."))
    }
}
