package xyz.selykvpn.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SecureBoxTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private lateinit var testKey: SecretKey

    @Before
    fun setUp() {
        val gen = KeyGenerator.getInstance("AES")
        gen.init(256)
        testKey = gen.generateKey()
        SecureBox.testKeyProvider = { testKey }
    }

    @After
    fun tearDown() {
        SecureBox.testKeyProvider = null
    }

    @Test
    fun encryptDecryptRoundtripAndTamperRejection() {
        val secret = """{"cookie":"remixsid=abc123","pass":"wrapA"}"""
        val enc1 = SecureBox.encryptString(secret)
        val enc2 = SecureBox.encryptString(secret)
        assertTrue(SecureBox.isEncrypted(enc1))
        assertNotEquals("Randomized IV must differ between encryptions", enc1, enc2)
        assertEquals(secret, SecureBox.decryptString(enc1))
        assertEquals(secret, SecureBox.decryptString(enc2))

        // Corrupt last char -> GCM auth tag check rejects it cleanly
        val tampered = enc1.dropLast(1) + if (enc1.last() == 'A') 'B' else 'A'
        assertNull(SecureBox.decryptString(tampered))
        assertNull(SecureBox.decryptString("not-encrypted-plaintext"))
    }

    @Test
    fun readEncryptedOrMigrateReEncryptsLegacyPlaintext() {
        val file = File(tmpDir.root, "legacy.json")
        val plain = """{"url":"https://sub.selykvpn.com/key123"}"""
        file.writeText(plain)
        assertFalse(SecureBox.isEncrypted(file.readText()))

        val loaded = SecureBox.readEncryptedOrMigrate(file)
        assertEquals(plain, loaded)

        val storedAfter = file.readText()
        assertTrue(SecureBox.isEncrypted(storedAfter))
        assertEquals(plain, SecureBox.decryptString(storedAfter))
        assertNotNull(SecureBox.readEncryptedOrMigrate(file))
    }
}
