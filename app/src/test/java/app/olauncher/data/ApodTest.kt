package app.olauncher.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ApodTest {

    // A real answer of APOD_POSTS_URL (2026-10-10), cut to five posts and the fields the app reads
    private val fixture = javaClass.getResource("/apod_posts.json")!!.readText()

    private fun fixturePosts() = JSONArray(fixture)

    private fun post(slug: String) = (0 until fixturePosts().length()).map { fixturePosts().getJSONObject(it) }.first { it.getString("slug").startsWith(slug) }

    private fun body(vararg posts: JSONObject) = JSONArray(posts.toList()).toString()

    private fun JSONObject.media(): JSONObject = getJSONObject("_embedded").getJSONArray("wp:featuredmedia").getJSONObject(0)

    private fun JSONObject.details(): JSONObject = media().getJSONObject("media_details")

    private fun farside() = post("apod-2026-october-10")

    @Test
    fun `the fixture gives the newest usable post with its attribution`() {
        val apod = pickApodImage(fixture)!!
        assertEquals("https://assets.science.nasa.gov/dynamicimage/assets/science/cds/apod/apod/2026/october/farside_lro1600.jpg", apod.imageUrl)
        assertEquals("Lunar Farside", apod.title)
        assertEquals("NASA / GSFC / Arizona State Univ. / Lunar Reconnaissance Orbiter", apod.credit)
        assertEquals("https://science.nasa.gov/image-article/apod-2026-october-10-lunar-farside/", apod.pageUrl)
    }

    @Test
    fun `copyrighted posts are skipped`() {
        // "Credit & Copyright: Engelbert Vollmer"
        val sombrero = post("apod-2026-october-5-")
        assertNull(pickApodImage(body(sombrero)))
        // Anywhere in the post, also as the sign
        val signed = farside().put("content", JSONObject().put("rendered", farside().getJSONObject("content").getString("rendered") + "<p>&copy; 2026</p>"))
        assertNull(pickApodImage(body(signed)))
    }

    @Test
    fun `a credit and license line counts as a credit`() {
        val pa30 = pickApodImage(body(post("apod-2026-october-7-")))!!
        assertTrue(pa30.credit, pa30.credit.startsWith("International Gemini Observatory / NOIRLab / NSF / AURA."))
        assertEquals("Supernova Remnant Pa 30", pa30.title)
    }

    @Test
    fun `posts without a credit line are skipped`() {
        val content = farside().getJSONObject("content").getString("rendered").replace("Credit:", "Source:")
        assertNull(pickApodImage(body(farside().put("content", JSONObject().put("rendered", content)))))
    }

    @Test
    fun `videos are skipped`() {
        val withPlayer = farside().getJSONObject("content").getString("rendered") + "<iframe src=\"https://www.youtube.com/embed/x\"></iframe>"
        assertNull(pickApodImage(body(farside().put("content", JSONObject().put("rendered", withPlayer)))))
        assertNull(apodCredit("Date: October 1, 2026 Video Credit: NASA, JPL Authors & editors: Someone"))
        val asVideo = farside().also { it.media().put("media_type", "file").put("mime_type", "video/mp4") }
        assertNull(pickApodImage(body(asVideo)))
    }

    @Test
    fun `small and very wide images are skipped`() {
        // The fixture's 512x512 auroral oval and 1600x800 panorama
        assertNull(pickApodImage(body(post("apod-2026-october-6-"))))
        assertNull(pickApodImage(body(post("apod-2026-october-3-"))))
        val wide = farside().also { it.details().put("width", 3300).put("height", 1600) }
        assertNull(pickApodImage(body(wide)))
        val twiceAsWide = farside().also { it.details().put("width", 3200).put("height", 1600) }
        assertEquals("Lunar Farside", pickApodImage(body(twiceAsWide))!!.title)
        val tall = farside().also { it.details().put("width", 1080).put("height", 4000) }
        assertEquals("Lunar Farside", pickApodImage(body(tall))!!.title)
    }

    @Test
    fun `images over the size limit are skipped, sizes may be strings`() {
        val big = farside().also { it.details().put("filesize", (MAX_WALLPAPER_BYTES + 1).toString()) }
        assertNull(pickApodImage(body(big)))
        val unknownSize = farside().also { it.details().remove("filesize") }
        assertEquals("Lunar Farside", pickApodImage(body(unknownSize))!!.title)
        val stringSides = farside().also { it.details().put("width", "1600").put("height", "1600") }
        assertEquals("Lunar Farside", pickApodImage(body(stringSides))!!.title)
    }

    @Test
    fun `missing or odd fields only skip that post`() {
        val noMedia = farside().also { it.remove("_embedded") }
        val httpImage = farside().also { it.media().put("source_url", "http://example.com/a.jpg") }
        val gif = farside().also { it.media().put("mime_type", "image/gif") }
        val notApod = farside().also { it.put("slug", "hubble-image") }
        val noSides = farside().also { it.details().remove("width") }
        for (broken in listOf(noMedia, httpImage, gif, notApod, noSides)) assertNull(pickApodImage(body(broken)))
        // A broken newer post does not hide an older usable one
        val older = post("apod-2026-october-7-")
        assertEquals("Supernova Remnant Pa 30", pickApodImage(body(noMedia, older))!!.title)
    }

    @Test
    fun `error answers and bodies that are not a list give null`() {
        assertNull(pickApodImage("""{"code":"rest_no_route","message":"No route was found matching the URL and request method.","data":{"status":404}}"""))
        assertNull(pickApodImage("<!doctype html><html><body>Service unavailable</body></html>"))
        assertNull(pickApodImage(""))
        assertNull(pickApodImage("[]"))
        assertNull(pickApodImage("[1, \"x\", null]"))
    }

    @Test
    fun `newest usable post wins whatever the order`() {
        val newer = farside()
        val older = post("apod-2026-october-7-")
        assertEquals("Lunar Farside", pickApodImage(body(older, newer))!!.title)
    }

    @Test
    fun `request asks for recent APOD posts with their image`() {
        assertTrue(APOD_POSTS_URL.startsWith("https://science.nasa.gov/wp-json/wp/v2/image-article?"))
        assertTrue(APOD_POSTS_URL.contains("search=APOD"))
        assertTrue(APOD_POSTS_URL.contains("_embed=wp:featuredmedia"))
        assertTrue(APOD_POSTS_URL.contains("per_page=10"))
    }

    @Test
    fun `day key changes once a local day`() {
        fun at(day: Int, hour: Int) = Calendar.getInstance(TimeZone.getTimeZone("Europe/Istanbul")).apply {
            clear()
            set(2026, Calendar.OCTOBER, day, hour, 30)
        }
        assertEquals("apod|2026-10-10", apodDayKey(at(10, 0)))
        assertEquals(apodDayKey(at(10, 0)), apodDayKey(at(10, 23)))
        assertEquals("apod|2026-10-11", apodDayKey(at(11, 0)))
    }

    @Test
    fun `credit text is cleaned up`() {
        assertEquals(
            "NASA, JPL-Caltech, MSSS – Panorama: Andrew Bodrov",
            apodCredit("Date October 3, 2026 Credit: Image Credit: NASA , JPL-Caltech , MSSS – Panorama: Andrew Bodrov Authors & editors: X")
        )
        assertNull(apodCredit("Credit: NASA"))
    }

    @Test
    fun `html text drops tags and decodes entities`() {
        assertEquals("APOD: 2026 October 10 – Lunar Farside", htmlText("APOD: 2026 October 10 &#8211; Lunar Farside"))
        assertEquals("A & B <c> \"d\" ©", htmlText("<p>A &amp; B</p> &lt;c&gt; &quot;d&quot; &#xA9;"))
        assertEquals("one two", htmlText("one\n\t<br/>  two"))
    }

    @Test
    fun `sample size keeps the image covering the screen`() {
        // A 6000x4000 image for a 1080x2640 screen: halving once would leave 2000 < 2640 rows
        assertEquals(1, coverSampleSize(6000, 4000, 1080, 2640))
        assertEquals(4, coverSampleSize(8000, 12000, 1080, 2640))
        assertEquals(2, coverSampleSize(2160, 5280, 1080, 2640))
        assertEquals(1, coverSampleSize(1000, 1000, 1080, 2640))
        assertEquals(1, coverSampleSize(-1, -1, 1080, 2640))
    }
}
