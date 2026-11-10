package xyz.selykvpn.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvPairingTest {
    @Test
    fun codeFromTheTvLink() {
        assertEquals("ABC234", TvPairing.codeFromLink("https://tgapp.selykvpn.xyz/tv/ABC234"))
        assertEquals("ABC234", TvPairing.codeFromLink(" https://tgapp.selykvpn.xyz/tv/abc-234/ "))
        assertEquals("ABC234", TvPairing.codeFromLink("selykvpn://tv/abc234"))
        assertNull(TvPairing.codeFromLink("https://tgapp.selykvpn.xyz/tv/"))
        assertNull(TvPairing.codeFromLink("https://tgapp.selykvpn.xyz/tv/ABC2345"))
        assertNull(TvPairing.codeFromLink("https://evil.net/tv/ABC234"))
        assertNull(TvPairing.codeFromLink("https://sub.selykvpn.com/abcDEF123456"))
        assertNull(TvPairing.codeFromLink(null))
    }

    @Test
    fun displayedCode() {
        assertEquals("ABC 234", TvPairing.display("ABC234"))
    }
}
