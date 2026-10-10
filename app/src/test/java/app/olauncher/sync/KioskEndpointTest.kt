package app.olauncher.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one gate for the kiosk's plain http: tailnet hosts only, and only the paired one. */
class KioskEndpointTest {

    @Test
    fun pairingLinkGivesBaseAndToken() {
        val endpoint = KioskEndpoint.fromLink("http://100.101.102.103:8787/#t=s3cr3t-T0ken_x")
        assertEquals("http://100.101.102.103:8787", endpoint?.base)
        assertEquals("s3cr3t-T0ken_x", endpoint?.token)
        // Path, other fragment parts, spaces and upper case around it do not matter
        val other = KioskEndpoint.fromLink("  HTTP://100.64.0.1:8787/companion?x=1#a=b&t=abc%2Bdef  ")
        assertEquals("http://100.64.0.1:8787", other?.base)
        assertEquals("abc+def", other?.token)
    }

    @Test
    fun tailnetHostsPass() {
        for (host in listOf("100.64.0.0", "100.127.255.255", "100.100.100.100", "kiosk.tail1234.ts.net", "KIOSK.TAIL1234.TS.NET.", "[fd7a:115c:a1e0::1]", "fd7a:115c:a1e0:ab12::42"))
            assertTrue(host, isTailnetHost(host))
        assertEquals("http://[fd7a:115c:a1e0::1]:8787", KioskEndpoint.fromLink("http://[fd7a:115c:a1e0::1]:8787/#t=abc")?.base)
    }

    @Test
    fun plainHttpOnlyToATailnetIpAndNamesOnlyOverHttps() {
        assertTrue(isTailnetIp("100.64.0.1"))
        assertTrue(isTailnetIp("[fd7a:115c:a1e0::1]"))
        assertFalse(isTailnetIp("kiosk.tail1234.ts.net"))
        // A MagicDNS name also resolves in public DNS (Funnel): the token must not go to it in plain text
        assertNull(KioskEndpoint.fromLink("http://kiosk.tail1234.ts.net:8787/#t=abc"))
        assertNull(KioskEndpoint.fromLink("http://kiosk.tail1234.ts.net/#t=abc"))
        assertEquals("https://kiosk.tail1234.ts.net", KioskEndpoint.fromLink("https://kiosk.tail1234.ts.net/#t=abc")?.base)
        assertEquals("https://100.64.0.1:8787", KioskEndpoint.fromLink("https://100.64.0.1:8787/#t=abc")?.base)
        assertNull(KioskEndpoint.restore("http://kiosk.tail1234.ts.net:8787", "abc"))
        assertNotNull(KioskEndpoint.restore("https://kiosk.tail1234.ts.net", "abc"))
    }

    @Test
    fun lanPublicLoopbackAndLookalikesAreRejected() {
        for (host in listOf(
            "192.168.1.20", "10.0.0.5", "172.16.0.1", "127.0.0.1", "localhost", "8.8.8.8",
            "100.63.255.255", "100.128.0.0", "101.64.0.1", "100.064.0.1", "100.64.0", "100.64.0.1.2", "100.64.0.256",
            "ts.net", ".ts.net", "evil.com", "ts.net.evil.com", "kiosk.ts.net.evil.com",
            "[fd7a:115c:a1e1::1]", "[fe80::1]", "[::1]", "fd7a:115c:a1e0::1%wlan0", "",
        )) assertFalse(host, isTailnetHost(host))
        assertNull(KioskEndpoint.fromLink("http://192.168.1.20:8787/#t=abc"))
        assertNull(KioskEndpoint.fromLink("http://localhost:8787/#t=abc"))
    }

    @Test
    fun linksWithoutAUsableTokenOrSchemeAreRejected() {
        for (link in listOf(
            "http://100.64.0.1:8787/", "http://100.64.0.1:8787/#t=", "http://100.64.0.1:8787/?t=abc",
            "http://100.64.0.1:8787/#t=a%0D%0AX-Evil:1", "http://100.64.0.1:8787/#t=a%20b",
            "ftp://100.64.0.1/#t=abc", "100.64.0.1:8787/#t=abc", "http://user@100.64.0.1:8787/#t=abc", "not a link", "",
        )) assertNull(link, KioskEndpoint.fromLink(link))
    }

    @Test
    fun requestsGoOnlyToThePairedHost() {
        val endpoint = KioskEndpoint.fromLink("http://100.64.0.1:8787/#t=abc")!!
        assertTrue(endpoint.owns(endpoint.url("/api/sync/lists")))
        for (url in listOf(
            "http://100.64.0.2:8787/api/sync/lists", "http://100.64.0.1:8788/api/sync/lists", "https://100.64.0.1:8787/api",
            "http://100.64.0.1:8787@evil.com/api", "http://100.64.0.1:8787.evil.com/api", "http://100.64.0.1:8787",
        )) assertFalse(url, endpoint.owns(url))
    }

    @Test
    fun storedPairingIsCheckedAgain() {
        assertEquals("http://100.64.0.1:8787", KioskEndpoint.restore("http://100.64.0.1:8787", "abc")?.base)
        assertNull(KioskEndpoint.restore("http://192.168.1.20:8787", "abc"))
        assertNull(KioskEndpoint.restore("http://100.64.0.1:8787/api", "abc"))
        assertNull(KioskEndpoint.restore("http://100.64.0.1:8787", "a b"))
    }

    @Test
    fun theTokenNeverShowsInText() {
        val endpoint = KioskEndpoint.fromLink("http://100.64.0.1:8787/#t=topsecret")!!
        assertFalse(endpoint.toString().contains("topsecret"))
    }
}
