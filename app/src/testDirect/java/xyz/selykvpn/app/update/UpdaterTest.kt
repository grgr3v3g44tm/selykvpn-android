package xyz.selykvpn.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.selykvpn.app.data.UpdateInfo
import java.io.File
import java.security.MessageDigest

class UpdaterTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    @Test
    fun findCachedApkValidatesAbiSha256AndDeletesMismatch() {
        val dir = tmpDir.newFolder("updates")
        val payload = "fake-apk-payload-2.0.3".toByteArray()
        val expectedSha = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }

        val apkFile = File(dir, "SelykVPN-2.0.3.apk").apply { writeBytes(payload) }
        val info = UpdateInfo(
            versionCode = 20003,
            versionName = "2.0.3",
            url = "https://example.com/universal.apk",
            abiUrls = mapOf("arm64-v8a" to "https://example.com/arm64.apk"),
            abiSha256 = mapOf("arm64-v8a" to expectedSha.uppercase()),
            sha256 = "0000",
        )

        val matched = Updater.findCachedApk(dir, info, arrayOf("arm64-v8a"))
        assertNotNull(matched)
        assertEquals(apkFile, matched)

        // Mismatched hash removes the corrupt cached file.
        val badInfo = info.copy(abiSha256 = mapOf("arm64-v8a" to "deadbeef"))
        val rejected = Updater.findCachedApk(dir, badInfo, arrayOf("arm64-v8a"))
        assertNull(rejected)
        assertFalse(apkFile.exists())
    }
}
