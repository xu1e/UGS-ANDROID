package de.ugs.sicherheit

import android.content.Context
import android.content.ContextWrapper
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun isolated(): Context =
        object : ContextWrapper(context) {
            private val root =
                File(context.noBackupFilesDir, "integration-${UUID.randomUUID()}").apply {
                    mkdirs()
                }

            override fun getNoBackupFilesDir() = root
        }

    private val company =
        mapOf(
            "name" to "UGS Sicherheit GmbH",
            "street" to "Musterstraße 1",
            "postalCode" to "10115",
            "city" to "Berlin",
            "representative" to "Max Mustermann",
        )

    private fun worker() =
        Entry(
            kind = Kind.WORKER,
            fields =
                schemas.getValue(Kind.WORKER).associate { it.key to it.initial } +
                    mapOf(
                        "personnelNumber" to "TEST-001",
                        "firstName" to "Erika",
                        "lastName" to "Müller",
                        "street" to "Teststraße 2",
                        "postalCode" to "10115",
                        "city" to "Berlin",
                        "startDate" to "2026-09-16",
                        "hourlyRate" to "18.50",
                        "birthDate" to "1990-01-02",
                        "bewacherId" to "123456",
                    ),
        )

    @Test
    fun authenticationPersistenceRolesAndEncryptedBackup() {
        val ctx = isolated()
        val repo = Repository(ctx)
        assertTrue(repo.needsSetup())
        repo.setup("test-admin", "Integration-Password-2026")
        val w = worker()
        repo.save(w)
        repo.settings(company)
        assertEquals(1, repo.entries().size)
        val database = File(ctx.noBackupFilesDir, "ugs.db").readBytes()
        assertFalse(String(database.take(16).toByteArray()).startsWith("SQLite format 3"))
        val backup = repo.backup("Backup-Password-2026")
        assertFalse(String(backup).contains("Müller"))
        repo.delete(repo.entries().first())
        assertTrue(repo.entries().isEmpty())
        repo.restoreBackup(backup, "Backup-Password-2026")
        assertEquals("Müller", repo.entries().first()["lastName"])
        repo.addUser("reader", "Reader-Password-2026", "Lesen")
        repo.logout()
        assertThrows(IllegalStateException::class.java) { repo.entries() }
        repo.login("reader", "Reader-Password-2026")
        assertEquals(1, repo.entries().size)
        assertThrows(IllegalStateException::class.java) { repo.save(worker()) }
        assertThrows(IllegalStateException::class.java) { repo.backup("Backup-Password-2026") }
        repo.logout()
        assertThrows(IllegalArgumentException::class.java) {
            repo.login("reader", "Wrong-Password")
        }
        repo.login("test-admin", "Integration-Password-2026")
        assertEquals("ok", repo.integrity())
    }

    @Test
    fun nativePdfExportsAndPurposeContract() {
        val pdf = PdfService(context)
        val w = worker()
        val v = contractFields.associate { it.key to it.initial }.toMutableMap()
        v.putAll(
            mapOf(
                "workerId" to w.id,
                "signingDate" to "2026-09-15",
                "signingPlace" to "Berlin",
                "reviewed" to "true",
            )
        )
        val tokens = Contracts.tokens(context, w, company, v)
        val contract = pdf.template("ugs-arbeitsvertrag", tokens, company)
        assertTrue(contract.size > 10000)
        fun verify(bytes: ByteArray, name: String): Int {
            val f = File(context.getExternalFilesDir(null), name)
            f.writeBytes(bytes)
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    assertTrue(r.pageCount > 0)
                    r.pageCount
                }
            }
        }
        verify(contract, "test-arbeitsvertrag.pdf")
        v.putAll(
            mapOf(
                "duration" to "Bewachungsauftrag",
                "object" to "Musterobjekt, Teststraße 3, Berlin",
                "customer" to "Musterkunde",
                "orderReference" to "Baustellenbewachung A",
                "completionEvent" to
                    "Abnahme und Übergabe der vollständig fertiggestellten Baustelle",
                "temporaryNeed" to
                    "Zusätzlicher Bewachungsbedarf ausschließlich während der konkret geplanten Bauarbeiten",
                "expectedDuration" to "Voraussichtlich sechs Monate",
                "purposeReviewed" to "true",
            )
        )
        val purpose = Contracts.tokens(context, w, company, v)
        assertTrue(purpose.getValue("duration_clause").contains("frühestens zwei Wochen"))
        assertFalse(purpose.getValue("einsatzort_clause").contains("wechselnden"))
        verify(pdf.template("ugs-arbeitsvertrag", purpose, company), "test-zweckbefristung.pdf")
        assertEquals(2, verify(pdf.badge(w, "2026-09-15", "2027-09-15"), "test-dienstausweis.pdf"))
        val notice =
            mapOf(
                "workerId" to w.id,
                "letterDate" to "2026-10-01",
                "contractStart" to "2026-09-16",
                "probationMonths" to "6",
                "receiptDate" to "2026-10-02",
                "endDate" to "2026-10-16",
                "signingPlace" to "Berlin",
                "reviewed" to "true",
            )
        verify(pdf.probation(w, company, notice), "test-probezeit.pdf")
        val brochure = pdf.asset("ugs-broschuere-hoch.pdf")
        assertEquals(12, verify(brochure, "test-broschuere.pdf"))
        assertEquals(
            12,
            verify(
                pdf.stamp(brochure, company, 0, .1f, .7f, .34f, "2026-09-15", false),
                "test-stempel.pdf",
            ),
        )
    }
}
