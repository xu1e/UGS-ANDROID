package de.ugs.sicherheit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractStampTest {
    @Test
    fun pageListsAreParsed() {
        assertEquals(listOf(0, 1, 2, 3), ContractStamp.pageIndices("alle", 4))
        assertEquals(listOf(0, 1, 2, 4), ContractStamp.pageIndices("1-3, 5", 6))
        assertTrue(ContractStamp.pageIndices("0", 3).isEmpty())
        assertTrue(ContractStamp.pageIndices("2-9", 3).isEmpty())
        assertTrue(ContractStamp.pageIndices("x", 3).isEmpty())
    }

    @Test
    fun workersAreFoundByBewacherIdOrPersonnelNumber() {
        val a = Entry("a", Kind.WORKER, mapOf("firstName" to "Max", "bewacherId" to "483452", "personnelNumber" to "123", "lastName" to "Muster"))
        val b = Entry("b", Kind.WORKER, mapOf("bewacherId" to "", "personnelNumber" to "456"))
        assertEquals("a", ContractStamp.findWorker(listOf(a, b), " 483452 ")?.id)
        assertEquals("b", ContractStamp.findWorker(listOf(a, b), "456")?.id)
        assertEquals(null, ContractStamp.findWorker(listOf(a, b), "999"))
        assertEquals("Arbeitsvertrag-483452-Muster-gestempelt.pdf", ContractStamp.fileName(a))
    }
}
