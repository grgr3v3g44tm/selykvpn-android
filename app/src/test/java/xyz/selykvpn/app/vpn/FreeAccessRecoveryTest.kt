package xyz.selykvpn.app.vpn

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FreeAccessRecoveryTest {

    @Before
    @After
    fun resetController() {
        VpnController.recoverFromFreeAccessDeath(unexpected = false)
    }

    @Test
    fun applyFreeAccessStatusTracksStagesCaptchaConnectedAndIdle() {
        VpnController.applyFreeAccessStatus(
            VpnController.FreeAccessStatusSnapshot(
                faState = FreeAccessVpnService.STATE_CONNECTING,
                stage = "Подключение к VK…",
            )
        )
        assertEquals(VpnController.Backend.FREE_ACCESS, VpnController.activeBackend)
        assertEquals(VpnController.State.Connecting, VpnController.state.value)
        assertEquals("Подключение к VK…", VpnController.freeAccessStage.value)
        assertNull(VpnController.captcha.value)

        VpnController.applyFreeAccessStatus(
            VpnController.FreeAccessStatusSnapshot(
                faState = FreeAccessVpnService.STATE_CONNECTING,
                stage = "Требуется капча VK…",
                captchaUrl = "https://api.vk.com/captcha.php?sid=123",
                captchaSid = "123",
            )
        )
        assertEquals(
            VpnController.CaptchaChallenge("https://api.vk.com/captcha.php?sid=123", "123"),
            VpnController.captcha.value,
        )

        VpnController.applyFreeAccessStatus(
            VpnController.FreeAccessStatusSnapshot(
                faState = FreeAccessVpnService.STATE_CONNECTED,
                since = 999L,
                upSpeed = 120L,
                downSpeed = 450L,
                totalUp = 1000L,
                totalDown = 2000L,
            )
        )
        assertEquals(VpnController.State.Connected(since = 999L, serverKey = "free_access"), VpnController.state.value)
        assertNull(VpnController.freeAccessStage.value)
        assertNull(VpnController.captcha.value)
        assertEquals(450L, VpnController.traffic.value.down)

        VpnController.applyFreeAccessStatus(
            VpnController.FreeAccessStatusSnapshot(faState = FreeAccessVpnService.STATE_IDLE)
        )
        assertEquals(VpnController.Backend.NONE, VpnController.activeBackend)
        assertEquals(VpnController.State.Idle, VpnController.state.value)
        assertEquals(VpnController.Traffic.ZERO, VpnController.traffic.value)
    }

    @Test
    fun staleFreeAccessIdleIgnoredWhenRegularBackendActive() {
        VpnController.activeBackend = VpnController.Backend.REGULAR
        VpnController.state.value = VpnController.State.Connected(since = 42L, serverKey = "fi")

        VpnController.applyFreeAccessStatus(
            VpnController.FreeAccessStatusSnapshot(faState = FreeAccessVpnService.STATE_IDLE)
        )
        assertEquals(VpnController.Backend.REGULAR, VpnController.activeBackend)
        assertTrue(VpnController.state.value is VpnController.State.Connected)
    }

    @Test
    fun recoverFromFreeAccessDeathResetsUiAndEmitsErrorOnlyWhenUnexpected() = runBlocking {
        VpnController.activeBackend = VpnController.Backend.FREE_ACCESS
        VpnController.state.value = VpnController.State.Connecting
        VpnController.freeAccessStage.value = "Подключение к VK…"
        VpnController.captcha.value = VpnController.CaptchaChallenge("https://api.vk.com/captcha.php?sid=1")

        val errors = mutableListOf<String>()
        val collector = launch {
            errors.add(VpnController.errors.first())
        }
        // Give collector a chance to subscribe before tryEmit.
        kotlinx.coroutines.yield()

        VpnController.recoverFromFreeAccessDeath(
            unexpected = true,
            errorMessage = "Служба Свободного доступа остановлена системой",
        )
        collector.join()

        assertEquals(VpnController.Backend.NONE, VpnController.activeBackend)
        assertEquals(VpnController.State.Idle, VpnController.state.value)
        assertNull(VpnController.freeAccessStage.value)
        assertNull(VpnController.captcha.value)
        assertEquals(VpnController.Traffic.ZERO, VpnController.traffic.value)
        assertEquals(listOf("Служба Свободного доступа остановлена системой"), errors)
    }

    @Test
    fun mapBootstrapErrorHidesInternalPollTimeoutAndPreservesRealDiagnostics() {
        assertEquals(
            "Таймаут подключения к прямому шлюзу",
            FreeAccessEngine.mapBootstrapError(readyCode = 0, authError = "bootstrap timeout after 1s"),
        )
        assertEquals(
            "Таймаут подключения к прямому шлюзу",
            FreeAccessEngine.mapBootstrapError(readyCode = 0, authError = ""),
        )
        assertEquals(
            "WRAP-A DTLS: handshake error: context deadline exceeded",
            FreeAccessEngine.mapBootstrapError(
                readyCode = 0,
                authError = "WRAP-A DTLS: handshake error: context deadline exceeded",
            ),
        )
        assertEquals(
            "Ссылка на звонок устарела. Создайте новый звонок VK.",
            FreeAccessEngine.mapBootstrapError(readyCode = -1, authError = "Invalid join link (954)"),
        )
        assertEquals(
            "Сессия VK истекла. Войдите через VK заново или вставьте ссылку вручную.",
            FreeAccessEngine.mapBootstrapError(readyCode = -1, authError = "cookie rejected by VK"),
        )
    }
}
