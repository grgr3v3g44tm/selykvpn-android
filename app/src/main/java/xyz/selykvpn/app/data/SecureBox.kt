package xyz.selykvpn.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed Android Keystore AES-256-GCM encryption helper for local secrets,
 * subscription configs, account status, and session cookies.
 */
object SecureBox {
    private const val TAG = "SecureBox"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "selyk_master_aes_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "ENCv1:"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private val keyLock = Any()
    private val fileLock = Any()
    private val secureRandom = SecureRandom()

    @Volatile
    internal var testKeyProvider: (() -> SecretKey)? = null

    fun isEncrypted(value: String?): Boolean = value != null && value.startsWith(PREFIX)

    private fun getOrCreateKey(): SecretKey {
        testKeyProvider?.let { return it() }
        synchronized(keyLock) {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
            gen.init(spec)
            return gen.generateKey()
        }
    }

    fun encryptString(plain: String): String {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val encoded = plain.toByteArray(Charsets.UTF_8)
        val out: ByteArray
        if (testKeyProvider != null) {
            val iv = ByteArray(IV_LEN).also { secureRandom.nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            val ct = cipher.doFinal(encoded)
            out = ByteArray(IV_LEN + ct.size)
            System.arraycopy(iv, 0, out, 0, IV_LEN)
            System.arraycopy(ct, 0, out, IV_LEN, ct.size)
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(encoded)
            out = ByteArray(iv.size + ct.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(ct, 0, out, iv.size, ct.size)
        }
        return PREFIX + encodeBase64(out)
    }

    fun decryptString(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return null
        return runCatching {
            val payload = decodeBase64(stored.removePrefix(PREFIX)) ?: return@runCatching null
            if (payload.size <= IV_LEN) return@runCatching null
            val iv = payload.copyOfRange(0, IV_LEN)
            val ct = payload.copyOfRange(IV_LEN, payload.size)
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.onFailure { e ->
            runCatching { Log.w(TAG, "Decryption failed: ${e.message}") }
        }.getOrNull()
    }

    /** Writes [plainText] encrypted via Keystore AES-256-GCM to [file] atomically. */
    fun writeEncryptedAtomically(file: File, plainText: String) {
        val encrypted = encryptString(plainText)
        writeRawAtomically(file, encrypted)
    }

    /** Writes plaintext [content] atomically (for non-secret config caches). */
    fun writeRawAtomically(file: File, content: String) {
        synchronized(fileLock) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(tmp).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                runCatching { fos.fd.sync() }
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    /**
     * Reads an encrypted file. If [file] still contains legacy plaintext (pre-encryption upgrade),
     * reads it and transparently re-encrypts it in place.
     */
    fun readEncryptedOrMigrate(file: File): String? {
        val raw = synchronized(fileLock) {
            if (!file.exists()) return null
            runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        } ?: return null
        if (raw.isEmpty()) return null
        if (isEncrypted(raw)) {
            return decryptString(raw)
        }
        // Legacy plaintext migration -> re-encrypt immediately
        runCatching { writeEncryptedAtomically(file, raw) }
            .onFailure { runCatching { Log.w(TAG, "Migration re-encrypt failed for ${file.name}: ${it.message}") } }
        return raw
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun encodeBase64(bytes: ByteArray): String =
        kotlin.io.encoding.Base64.encode(bytes)

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun decodeBase64(encoded: String): ByteArray? =
        runCatching { kotlin.io.encoding.Base64.decode(encoded) }.getOrNull()
}
