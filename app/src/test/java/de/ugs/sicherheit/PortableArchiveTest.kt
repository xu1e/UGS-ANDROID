package de.ugs.sicherheit

import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableArchiveTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun hkdfMatchesRfc5869() {
        val okm = PortableArchive.hkdf(ByteArray(22) { 0x0b }, ByteArray(13) { it.toByte() }, ByteArray(10) { (0xf0 + it).toByte() })
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf", hex(okm))
    }

    @Test
    fun roundTripAndWrongPassword() {
        val salt = ByteArray(32) { 3 }
        val keys = PortableArchive.derive("Passwort-für-Test", salt, 1000)
        val out = ByteArrayOutputStream()
        val big = ByteArray(2_300_000) { (it * 7).toByte() }
        PortableArchive.Writer(out, keys, salt, 1000, mapOf("platform" to "Mac", "created_at" to "2026-09-26T10:00:00Z")).apply {
            add(PortableArchive.MANIFEST, "{}".toByteArray())
            add("documents/1/vertrag.pdf", big)
            finish()
        }
        val r = PortableArchive.Reader(out.toByteArray().inputStream(), "Passwort-für-Test")
        assertEquals("Mac", r.info.platform)
        assertEquals(PortableArchive.MANIFEST, r.next()!!.name)
        assertEquals("{}", String(r.readBody(100)))
        assertEquals("documents/1/vertrag.pdf", r.next()!!.name)
        assertArrayEquals(big, r.body().readBytes())
        assertNull(r.next())
        val wrong = runCatching { PortableArchive.Reader(out.toByteArray().inputStream(), "falsches-Passwort") }.exceptionOrNull()
        assertTrue(wrong?.message.orEmpty().contains("Passwort"))
    }

    @Test
    fun dutyPlanWorkerIdsAreRemapped() {
        val json = """{"days":[{"slots":[{"worker_id":7},{"worker_id":"8"},{"worker_id":99}]}]}"""
        val out = JSONObject(PortableImport.remapWorkers(json, mapOf(7L to "a", 8L to "b")))
        val slots = out.getJSONArray("days").getJSONObject(0).getJSONArray("slots")
        assertEquals("a", slots.getJSONObject(0).getString("worker_id"))
        assertEquals("b", slots.getJSONObject(1).getString("worker_id"))
        assertEquals("", slots.getJSONObject(2).getString("worker_id"))
    }
}
