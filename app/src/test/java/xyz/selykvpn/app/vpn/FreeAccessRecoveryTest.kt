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

import xyz.selykvpn.app.data.AccountStatus
import xyz.selykvpn.app.model.FreeAccessConfig
import xyz.selykvpn.app.model.TunnelMode

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
            "Шлюз Свободного доступа не отвечает на рукопожатие (DTLS timeout). Проверьте ключ в боте или попробуйте позже.",
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

    @Test
    fun modeReadyToStartChecksTariffAndWrapAPassword() {
        val noPassWrapA = FreeAccessConfig(
            mode = TunnelMode.SRTP_WRAP_A,
            stealthLink = "https://vk.ru/call/join/validCall123",
            needsStealthLink = false,
            serverPassword = "",
        )
        val validWrapA = noPassWrapA.copy(serverPassword = "p_secret")
        val whitelistAcc = AccountStatus(found = true, isWhitelist = true)
        val regularAcc = AccountStatus(found = true, isWhitelist = false)

        // Missing WRAP-A password -> tile/shortcut must NOT start free_access directly
        assertEquals(
            false,
            VpnController.isModeReadyToStart("free_access", hasSubscription = true, account = whitelistAcc, freeAccessConfig = noPassWrapA),
        )
        // Non-whitelist subscription -> tile/shortcut must NOT start free_access even if password exists
        assertEquals(
            false,
            VpnController.isModeReadyToStart("free_access", hasSubscription = true, account = regularAcc, freeAccessConfig = validWrapA),
        )
        // Whitelist subscription + valid call link + WRAP-A password -> ready
        assertEquals(
            true,
            VpnController.isModeReadyToStart("free_access", hasSubscription = true, account = whitelistAcc, freeAccessConfig = validWrapA),
        )
        // Standalone free_access (no subscription) + valid config -> ready
        assertEquals(
            true,
            VpnController.isModeReadyToStart("free_access", hasSubscription = false, account = null, freeAccessConfig = validWrapA),
        )
        // Regular mode requires subscription
        assertEquals(false, VpnController.isModeReadyToStart("regular", hasSubscription = false, account = null, freeAccessConfig = null))
        assertEquals(true, VpnController.isModeReadyToStart("regular", hasSubscription = true, account = regularAcc, freeAccessConfig = null))
    }

    @Test
    fun parseNormalizesKnownServerIpToPublicDomain() {
        val conf = """
            [Interface]
            PrivateKey = cPrivKey=
            Address = 10.77.77.12/16
            [Peer]
            PublicKey = sPubKey=
            Endpoint = 144.31.79.239:56000
        """.trimIndent()
        val parsed = FreeAccessConfig.parse(conf)
        assertEquals("media-stream.xamudhabibi.qzz.io:56000", parsed?.peerAddress)
    }
}
