package de.ugs.sicherheit

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Passwortgeschütztes, geräteunabhängiges UGS-Archiv (".ugsarchive") vom Mac oder iPhone.
 *
 *     master    = PBKDF2-HMAC-SHA256(Passwort NFC, salt, iterations, 32)
 *     streamKey = HKDF-SHA256(master, salt, "UGS-Archive-Stream-v1")
 *     dbKey     = HKDF-SHA256(master, salt, "UGS-Archive-Database-v1")
 *
 * Datei: "UGSARCH1", UInt32 BE Kopflänge, JSON-Kopf, dann Blöcke (UInt32 BE Rahmen,
 * oberstes Bit = letzter Block; nonce ‖ ciphertext ‖ tag). AAD = SHA256(Kopf) ‖ UInt64 Index ‖ final.
 * Klartext: Präambel (Block 0), Einträge (1, UInt16 Namenslänge, Name, UInt64 Größe, Inhalt), 0.
 */
object PortableArchive {
    const val EXTENSION = "ugsarchive"
    val magic = "UGSARCH1".toByteArray()
    val preamble = "UGS-ARCHIVE-01\r\n".toByteArray()
    const val FORMAT = 1
    const val CHUNK = 1 shl 20
    const val DATABASE = "database.sqlite3"
    const val MANIFEST = "manifest.json"
    const val DOCUMENTS = "documents/"
    const val MIN_PASSWORD = 10
    private const val MAX_CHUNK = 64 shl 20
    private const val MAX_HEADER = 64 shl 10

    class Keys(val stream: ByteArray, val database: ByteArray)

    data class Info(val createdAt: String, val platform: String, val appVersion: String, val format: Int)

    fun error(text: String) = IllegalStateException(text)

    fun isArchive(head: ByteArray) = head.size >= magic.size && head.copyOf(magic.size).contentEquals(magic)

    fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }

    /** HKDF-SHA256 (RFC 5869) für eine Ausgabe von 32 Bytes. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val prk = hmac(salt, ikm)
        return hmac(prk, info + byteArrayOf(1))
    }

    fun derive(password: String, salt: ByteArray, iterations: Int): Keys {
        val normalized = Normalizer.normalize(password, Normalizer.Form.NFC)
        val spec = PBEKeySpec(normalized.toCharArray(), salt, iterations, 256)
        val master = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return Keys(hkdf(master, salt, "UGS-Archive-Stream-v1".toByteArray()), hkdf(master, salt, "UGS-Archive-Database-v1".toByteArray()))
    }

    fun be64(v: Long) = ByteArray(8) { (v ushr (56 - 8 * it)).toByte() }

    fun nonce(index: Long) = ByteArray(4) + be64(index)

    fun aad(digest: ByteArray, index: Long, final: Boolean) = digest + be64(index) + byteArrayOf(if (final) 1 else 0)

    fun info(header: JSONObject) = Info(header.optString("created_at"), header.optString("platform"), header.optString("app_version"), header.optInt("format"))

    /** Liest nur den unverschlüsselten Kopf (zur Anzeige vor der Passworteingabe). */
    fun readHeader(input: DataInputStream): Pair<ByteArray, JSONObject> {
        val head = ByteArray(magic.size)
        runCatching { input.readFully(head) }.onFailure { throw error("Diese Datei ist kein UGS-Archiv.") }
        if (!head.contentEquals(magic)) throw error("Diese Datei ist kein UGS-Archiv.")
        val length = input.readInt()
        if (length <= 0 || length > MAX_HEADER) throw error("Der Kopfsatz des Archivs ist beschädigt.")
        val raw = ByteArray(length)
        runCatching { input.readFully(raw) }.onFailure { throw error("Der Kopfsatz des Archivs ist unvollständig.") }
        val json = runCatching { JSONObject(String(raw)) }.getOrElse { throw error("Der Kopfsatz des Archivs ist beschädigt.") }
        if (json.optInt("format") != FORMAT) throw error("Dieses Archiv wurde mit einer neueren Programmversion erstellt. Bitte zuerst die App aktualisieren.")
        return raw to json
    }

    class Reader(stream: InputStream, password: String) : AutoCloseable {
        data class Entry(val name: String, val size: Long)

        private val input = DataInputStream(stream.buffered(256 * 1024))
        val info: Info
        val databaseKey: ByteArray
        private val key: SecretKeySpec
        private val digest: ByteArray
        private var buffer = ByteArray(0)
        private var position = 0
        private var index = 0L
        private var ended = false
        private var pending = 0L

        init {
            val (raw, json) = readHeader(input)
            val salt = runCatching { Base64.getDecoder().decode(json.getString("salt")) }.getOrNull()
            val iterations = json.optInt("iterations")
            if (salt == null || salt.isEmpty() || iterations !in 1..10_000_000) throw error("Der Kopfsatz des Archivs ist beschädigt.")
            val keys = derive(password, salt, iterations)
            info = info(json)
            databaseKey = keys.database
            key = SecretKeySpec(keys.stream, "AES")
            digest = MessageDigest.getInstance("SHA-256").digest(raw)
            // Block 0 enthält nur die Präambel: unterscheidet falsches Passwort von beschädigter Datei.
            val first = runCatching { chunk() }.getOrElse { throw error("Das Passwort ist falsch oder die Datei ist kein vollständiges UGS-Archiv.") }
            if (first == null || !first.contentEquals(preamble)) throw error("Das Archiv ist beschädigt und kann nicht gelesen werden.")
        }

        private fun chunk(): ByteArray? {
            if (ended) return null
            val frame = try { input.readInt() } catch (e: EOFException) { throw error("Das Archiv ist unvollständig. Bitte die Datei erneut übertragen.") }
            val final = frame and 0x80000000.toInt() != 0
            val length = frame and 0x7fffffff
            if (length < 28 || length > MAX_CHUNK + 28) throw error("Das Archiv ist beschädigt.")
            val sealed = ByteArray(length)
            try { input.readFully(sealed) } catch (e: EOFException) { throw error("Das Archiv ist unvollständig. Bitte die Datei erneut übertragen.") }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
            cipher.updateAAD(aad(digest, index, final))
            val plain =
                try { cipher.doFinal(sealed, 12, length - 12) } catch (e: Exception) { throw error("Das Archiv ist beschädigt oder wurde verändert.") }
            index++
            if (final) ended = true
            return plain
        }

        private fun fill(minimum: Int) {
            while (buffer.size - position < minimum) {
                val next = chunk() ?: throw error("Das Archiv ist unvollständig. Bitte die Datei erneut übertragen.")
                buffer = buffer.copyOfRange(position, buffer.size) + next
                position = 0
            }
        }

        private fun take(count: Int): ByteArray {
            fill(count)
            return buffer.copyOfRange(position, position + count).also { position += count }
        }

        fun next(): Entry? {
            if (pending > 0) skip()
            val marker = take(1)[0].toInt()
            if (marker == 0) return null
            if (marker != 1) throw error("Das Archiv ist beschädigt.")
            val nameLength = take(2).let { ((it[0].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff) }
            val name = String(take(nameLength), Charsets.UTF_8)
            val size = take(8).fold(0L) { a, b -> (a shl 8) or (b.toLong() and 0xff) }
            if (size < 0) throw error("Das Archiv ist beschädigt.")
            pending = size
            return Entry(name, size)
        }

        fun readBody(limit: Int): ByteArray {
            if (pending > limit) throw error("Ein Eintrag im Archiv ist unerwartet groß.")
            return take(pending.toInt()).also { pending = 0 }
        }

        fun copyBody(out: OutputStream) {
            while (pending > 0) {
                if (buffer.size == position) fill(1)
                val n = minOf((buffer.size - position).toLong(), pending).toInt()
                out.write(buffer, position, n)
                position += n
                pending -= n
            }
        }

        /** Inhalt des aktuellen Eintrags als Datenstrom (ein Durchlauf). */
        fun body(): InputStream =
            object : InputStream() {
                override fun read(): Int {
                    if (pending <= 0) return -1
                    if (buffer.size == position) fill(1)
                    pending--
                    return buffer[position++].toInt() and 0xff
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (pending <= 0) return -1
                    if (len == 0) return 0
                    if (buffer.size == position) fill(1)
                    val n = minOf(len.toLong(), (buffer.size - position).toLong(), pending).toInt()
                    System.arraycopy(buffer, position, b, off, n)
                    position += n
                    pending -= n
                    return n
                }
            }

        private fun skip() =
            copyBody(
                object : OutputStream() {
                    override fun write(b: Int) {}

                    override fun write(b: ByteArray, off: Int, len: Int) {}
                }
            )

        override fun close() = input.close()
    }

    /** Schreiber im selben Format (für Tests und künftige Exporte). */
    class Writer(private val out: OutputStream, keys: Keys, salt: ByteArray, iterations: Int, extra: Map<String, Any> = emptyMap()) {
        private val key = SecretKeySpec(keys.stream, "AES")
        private val digest: ByteArray
        private var buffer = java.io.ByteArrayOutputStream()
        private var index = 0L

        init {
            val header = JSONObject(extra + mapOf("format" to FORMAT, "kdf" to "PBKDF2-HMAC-SHA256", "cipher" to "AES-GCM", "iterations" to iterations, "salt" to Base64.getEncoder().encodeToString(salt), "chunk_size" to CHUNK))
            val raw = header.toString().toByteArray()
            digest = MessageDigest.getInstance("SHA-256").digest(raw)
            out.write(magic)
            out.write(java.nio.ByteBuffer.allocate(4).putInt(raw.size).array())
            out.write(raw)
            buffer.write(preamble)
            emit(false)
        }

        fun add(name: String, data: ByteArray) {
            val n = name.toByteArray()
            append(byteArrayOf(1, (n.size shr 8).toByte(), n.size.toByte()) + n + be64(data.size.toLong()))
            append(data)
        }

        private fun append(bytes: ByteArray) {
            var off = 0
            while (off < bytes.size) {
                val n = minOf(CHUNK - buffer.size(), bytes.size - off)
                buffer.write(bytes, off, n)
                off += n
                if (buffer.size() == CHUNK) emit(false)
            }
        }

        fun finish() {
            append(byteArrayOf(0))
            emit(true)
            out.flush()
        }

        private fun emit(final: Boolean) {
            val plain = buffer.toByteArray()
            buffer = java.io.ByteArrayOutputStream()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = nonce(index)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad(digest, index, final))
            val sealed = iv + cipher.doFinal(plain)
            var frame = sealed.size
            if (final) frame = frame or 0x80000000.toInt()
            out.write(java.nio.ByteBuffer.allocate(4).putInt(frame).array())
            out.write(sealed)
            index++
        }
    }
}
