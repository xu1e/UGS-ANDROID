package de.ugs.sicherheit

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationsTest {
    private val today = LocalDate.parse("2026-09-26")

    private fun e(kind: Kind, id: String = java.util.UUID.randomUUID().toString(), vararg f: Pair<String, String>) = Entry(id, kind, mapOf(*f))

    @Test
    fun dashboardFigures() {
        val all =
            listOf(
                e(Kind.WORKER, "a", "status" to "Aktiv", "department" to "Objektschutz", "baseSalary" to "2.500,00", "bewacherId" to "1"),
                e(Kind.WORKER, "b", "status" to "Aktiv", "department" to "Objektschutz", "bewacherId" to "2", "startDate" to "2026-01-01"),
                e(Kind.WORKER, "c", "status" to "Inaktiv"),
                e(Kind.ABSENCE, "x", "workerId" to "b", "date" to "2026-09-20", "endDate" to "2026-09-30", "status" to "Genehmigt"),
                e(Kind.ABSENCE, "y", "workerId" to "a", "date" to "2026-10-20", "endDate" to "2026-10-30", "status" to "In Bearbeitung"),
                e(Kind.DOCUMENT, "d1", "workerId" to "a", "expiryDate" to "2026-09-01", "blob" to "z"),
                e(Kind.DOCUMENT, "d2", "workerId" to "a", "expiryDate" to "2026-10-10", "extendedCertificate" to "true", "blob" to "z"),
                e(Kind.PAYROLL, "p", "workerId" to "a", "date" to "2026-09-01", "hours" to "10", "rate" to "15", "bonus" to "50"),
                e(Kind.TODO, "t", "status" to "Offen"),
            )
        val f = DashboardFigures.of(all, today)
        assertEquals(3, f.workers)
        assertEquals(1, f.active)
        assertEquals(1, f.leave)
        assertEquals(1, f.inactive)
        assertEquals(1, f.pendingAbsences)
        assertEquals(1, f.expiredDocuments)
        assertEquals(1, f.criticalCertificates)
        assertEquals(1, f.soonDocuments)
        assertEquals(200.0, f.monthlyPayroll, 0.001)
        assertEquals(2500.0, f.averageSalary, 0.001)
        assertEquals(mapOf("Objektschutz" to 2), f.departments)
        val issues = OperationsCheck.compliance(all, today)
        assertTrue(issues.any { it.title.contains("Eintrittsdatum") })
        assertTrue(issues.first().critical)
        assertTrue(OperationsCheck.integrity(all).isEmpty())
    }
}
