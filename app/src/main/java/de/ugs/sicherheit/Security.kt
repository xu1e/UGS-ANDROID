package de.ugs.sicherheit

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.*
import javax.crypto.*
import javax.crypto.spec.*

object Crypto {
    private val random = SecureRandom()
    const val ITERATIONS = 310000

    fun bytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    fun encode(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    fun decode(s: String) = Base64.decode(s, Base64.NO_WRAP)

    fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("ugs-vault-v1", null) as? SecretKey)
            ?: KeyGenerator.getInstance("AES", "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                "ugs-vault-v1",
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
    }

    fun seal(data: ByteArray, key: SecretKey): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        return c.iv + c.doFinal(data)
    }

    fun open(data: ByteArray, key: SecretKey): ByteArray {
        require(data.size >= 28) { "Verschlüsselte Datei beschädigt." }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(0, 12)))
            doFinal(data, 12, data.size - 12)
        }
    }

    fun backup(data: ByteArray, password: String): ByteArray {
        require(password.length >= 12) { "Sicherungspasswort: mindestens 12 Zeichen." }
        val salt = bytes(16)
        val derived = derive(password.toCharArray(), salt)
        return try {
            "UGSB01".toByteArray() + salt + seal(data, SecretKeySpec(derived, "AES"))
        } finally {
            derived.fill(0)
        }
    }

    fun restore(data: ByteArray, password: String): ByteArray {
        require(data.size > 50 && String(data, 0, 6) == "UGSB01") { "Keine UGS-Android-Sicherung." }
        val derived = derive(password.toCharArray(), data.copyOfRange(6, 22))
        return try {
            open(data.copyOfRange(22, data.size), SecretKeySpec(derived, "AES"))
        } finally {
            derived.fill(0)
        }
    }

    private val CHUNKED = "UGC1".toByteArray()
    private const val CHUNK = 1024 * 1024

    private fun aad(index: Long, last: Boolean) =
        java.nio.ByteBuffer.allocate(9).putLong(index).put(if (last) 1 else 0).array()

    /**
     * Verschlüsselt einen Datenstrom in 1-MB-Blöcken (AES-GCM je Block). Blocknummer und
     * Endekennung sind authentifiziert, damit weder Reihenfolge noch Länge unbemerkt
     * verändert werden können. So bleiben auch 250-MB-Dateien speicherschonend.
     */
    fun sealStream(input: java.io.InputStream, target: File, key: SecretKey, limit: Long): Long {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        var total = 0L
        try {
            java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(tmp))).use {
                out ->
                out.write(CHUNKED)
                val buf = ByteArray(CHUNK)
                var next = readFully(input, buf)
                var index = 0L
                while (true) {
                    val n = next
                    total += n.coerceAtLeast(0)
                    require(total <= limit) { "Datei überschreitet ${limit / 1024 / 1024} MB." }
                    val following = if (n == CHUNK) ByteArray(CHUNK) else null
                    val m = if (following != null) readFully(input, following) else -1
                    val last = following == null || m <= 0
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.ENCRYPT_MODE, key)
                    c.updateAAD(aad(index, last))
                    val sealed = c.doFinal(buf, 0, n.coerceAtLeast(0))
                    out.writeInt(sealed.size)
                    out.write(c.iv)
                    out.write(sealed)
                    if (last) break
                    System.arraycopy(following!!, 0, buf, 0, m)
                    next = m
                    index++
                }
                out.flush()
            }
            java.io.RandomAccessFile(tmp, "rw").use { it.fd.sync() }
            require(tmp.renameTo(target)) { "Datei konnte nicht gespeichert werden." }
            return total
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    /** Entschlüsselt blockweise in einen Ausgabestrom; ältere Einzelblock-Dateien bleiben lesbar. */
    fun openStream(source: File, key: SecretKey, out: java.io.OutputStream) {
        java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(source))).use {
            input ->
            val magic = ByteArray(4)
            input.mark(8)
            if (readFully(input, magic) != 4 || !magic.contentEquals(CHUNKED)) {
                input.reset()
                out.write(open(input.readBytes(), key))
                return
            }
            var index = 0L
            while (true) {
                val size =
                    try {
                        input.readInt()
                    } catch (e: java.io.EOFException) {
                        error("Verschlüsselte Datei ist unvollständig.")
                    }
                require(size in 16..(CHUNK + 16)) { "Verschlüsselte Datei beschädigt." }
                val iv = ByteArray(12).also { input.readFully(it) }
                val sealed = ByteArray(size).also { input.readFully(it) }
                input.mark(1)
                val last = input.read() < 0
                if (!last) input.reset()
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                c.updateAAD(aad(index, last))
                out.write(c.doFinal(sealed))
                if (last) break
                index++
            }
        }
    }

    fun openFile(source: File, key: SecretKey): ByteArray =
        java.io.ByteArrayOutputStream().also { openStream(source, key, it) }.toByteArray()

    fun writeAtomic(file: File, data: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        java.io.FileOutputStream(tmp).use {
            it.write(data)
            it.fd.sync()
        }
        require(tmp.renameTo(file)) { "Datei konnte nicht gespeichert werden." }
    }
}
