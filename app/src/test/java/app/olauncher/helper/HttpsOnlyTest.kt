package app.olauncher.helper

import app.olauncher.data.APOD_POSTS_URL
import app.olauncher.data.httpsUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.MalformedURLException

/**
 * Kiosk sync builds allow cleartext app-wide (for the paired tailnet kiosk only, see KioskEndpoint), so every other
 * network call must ask for https itself: through httpsUrl.
 */
class HttpsOnlyTest {

    @Test
    fun httpsUrlTakesOnlyHttps() {
        assertEquals("https://example.com/feed.json", httpsUrl(" https://example.com/feed.json ").toString())
        for (url in listOf(null, "", "http://example.com/feed.json", "ftp://example.com/x", "https://", "example.com", "file:///etc/hosts"))
            try {
                httpsUrl(url)
                fail("accepted $url")
            } catch (e: MalformedURLException) {
                // expected
            }
    }

    @Test
    fun builtInNetworkAddressesAreHttps() {
        assertTrue(APOD_POSTS_URL, APOD_POSTS_URL.startsWith("https://"))
    }

    /** No connection is opened from a bare URL(...) outside httpsUrl itself and the kiosk client. */
    @Test
    fun everyConnectionGoesThroughHttpsUrl() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("sources not found from ${File("").absolutePath}", sources.size > 20)
        val allowed = setOf("WallpaperSource.kt", "KioskApi.kt")
        val bare = Regex("""\bURL\(""")
        val offenders = sources.filter { it.name !in allowed && bare.containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(emptyList<String>(), offenders)
    }
}
