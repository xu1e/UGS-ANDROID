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
