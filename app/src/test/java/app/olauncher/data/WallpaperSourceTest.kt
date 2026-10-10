package app.olauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class WallpaperSourceTest {
    private val light = Constants.WALL_TYPE_LIGHT
    private val dark = Constants.WALL_TYPE_DARK

    @Test
    fun `url is used for both themes when dark is missing`() {
        val body = """{"url": "https://x.test/a.jpg"}"""
        assertEquals("https://x.test/a.jpg", pickWallpaperUrl(body, light))
        assertEquals("https://x.test/a.jpg", pickWallpaperUrl(body, dark))
    }

    @Test
    fun `dark theme prefers dark and light theme ignores it`() {
        val body = """{"url": "https://x.test/a.jpg", "dark": "https://x.test/d.jpg"}"""
        assertEquals("https://x.test/a.jpg", pickWallpaperUrl(body, light))
        assertEquals("https://x.test/d.jpg", pickWallpaperUrl(body, dark))
    }

    @Test
    fun `invalid dark falls back to url`() {
        assertEquals("https://x.test/a.jpg", pickWallpaperUrl("""{"url": "https://x.test/a.jpg", "dark": "http://x.test/d.jpg"}""", dark))
        assertEquals("https://x.test/a.jpg", pickWallpaperUrl("""{"url": "https://x.test/a.jpg", "dark": 5}""", dark))
        assertEquals("https://x.test/a.jpg", pickWallpaperUrl("""{"url": "https://x.test/a.jpg", "dark": ""}""", dark))
    }

    @Test
    fun `unknown fields and date are ignored`() {
        val body = """{"date": "2026-10-03", "extra": {"a": 1}, "url": " https://x.test/a.jpg "}"""
        assertEquals("https://x.test/a.jpg", pickWallpaperUrl(body, light))
    }

    @Test
    fun `missing or unusable url gives null`() {
        assertNull(pickWallpaperUrl("{}", light))
        assertNull(pickWallpaperUrl("""{"dark": "https://x.test/d.jpg"}""", dark))
        assertNull(pickWallpaperUrl("""{"url": ""}""", light))
        assertNull(pickWallpaperUrl("""{"url": null}""", light))
        assertNull(pickWallpaperUrl("""{"url": 42}""", light))
        assertNull(pickWallpaperUrl("""{"url": ["https://x.test/a.jpg"]}""", light))
    }

    @Test
    fun `http and other schemes are rejected`() {
        assertNull(pickWallpaperUrl("""{"url": "http://x.test/a.jpg"}""", light))
        assertNull(pickWallpaperUrl("""{"url": "file:///sdcard/a.jpg"}""", light))
        assertNull(pickWallpaperUrl("""{"url": "javascript:alert(1)"}""", light))
        assertNull(pickWallpaperUrl("""{"url": "//x.test/a.jpg"}""", light))
    }

    @Test
    fun `malformed bodies give null`() {
        assertNull(pickWallpaperUrl("", light))
        assertNull(pickWallpaperUrl("not json", light))
        assertNull(pickWallpaperUrl("""{"url": "https://x.test/a.jpg"""", light))
        assertNull(pickWallpaperUrl("""["https://x.test/a.jpg"]""", light))
        assertNull(pickWallpaperUrl("<html>404</html>", light))
    }

    @Test
    fun `https check`() {
        assertTrue(isHttpsUrl("https://x.test/w.json"))
        assertTrue(isHttpsUrl("HTTPS://x.test/w.json"))
        assertFalse(isHttpsUrl("http://x.test/w.json"))
        assertFalse(isHttpsUrl("https://"))
        assertFalse(isHttpsUrl("https:///path"))
        assertFalse(isHttpsUrl("x.test/w.json"))
        assertFalse(isHttpsUrl("https://x.test/a b"))
        assertFalse(isHttpsUrl(""))
    }
}

class WallpaperFeedTest {
    private val light = Constants.WALL_TYPE_LIGHT
    private val dark = Constants.WALL_TYPE_DARK
    private val a = "https://x.test/a.jpg"
    private val b = "https://x.test/b.jpg"
    private val c = "https://x.test/c.jpg"

    @Test
    fun `next item advances by one`() {
        assertEquals(b, nextWallpaperUrl(listOf(a, b, c), a))
        assertEquals(c, nextWallpaperUrl(listOf(a, b, c), b))
    }

    @Test
    fun `next item wraps to the start`() {
        assertEquals(a, nextWallpaperUrl(listOf(a, b, c), c))
        assertEquals(a, nextWallpaperUrl(listOf(a, b), b))
    }

    @Test
    fun `current not in the list goes to the first item`() {
        assertEquals(a, nextWallpaperUrl(listOf(a, b), "https://x.test/other.jpg"))
        assertEquals(a, nextWallpaperUrl(listOf(a, b), ""))
    }

    @Test
    fun `empty or single item pool gives nothing`() {
        assertNull(nextWallpaperUrl(emptyList(), a))
        assertNull(nextWallpaperUrl(listOf(a), a))
        assertNull(nextWallpaperUrl(listOf(a), b))
    }

    @Test
    fun `feed day is new only when the url changed`() {
        assertTrue(isNewFeedDay(a, ""))
        assertTrue(isNewFeedDay(b, a))
        assertFalse(isNewFeedDay(a, a))
    }

    @Test
    fun `feed parses url and items, dropping non https and duplicates`() {
        val body = """{"url": "$a", "date": "2026-10-03", "items": ["$a", "$b", "http://x.test/n.jpg", 5, "$b", " $c "]}"""
        val feed = parseWallpaperFeed(body, light)!!
        assertEquals("light|$a", feed.dayKey)
        assertEquals(a, feed.wallpaperUrl)
        assertEquals(listOf(a, b, c), feed.items)
    }

    @Test
    fun `feed without items has an empty pool and keeps dark support`() {
        val feed = parseWallpaperFeed("""{"url": "$a", "dark": "$b"}""", dark)!!
        assertEquals("dark|$b", feed.dayKey)
        assertEquals(b, feed.wallpaperUrl)
        assertTrue(feed.items.isEmpty())
    }

    @Test
    fun `items that is not an array is ignored and bad feeds give null`() {
        assertTrue(parseWallpaperFeed("""{"url": "$a", "items": "oops"}""", light)!!.items.isEmpty())
        assertNull(parseWallpaperFeed("not json", light))
        assertNull(parseWallpaperFeed("""{"items": ["$a", "$b"]}""", light))
    }

    private val d1 = "https://x.test/d1.jpg"
    private val d2 = "https://x.test/d2.jpg"
    private val both = """{"url": "$a", "dark": "$d1", "items": ["$a", "$b"], "dark_items": ["$d1", "$d2"]}"""

    @Test
    fun `light mode uses url and items, dark mode uses dark and dark_items`() {
        val l = parseWallpaperFeed(both, light)!!
        assertEquals(a, l.wallpaperUrl)
        assertEquals(listOf(a, b), l.items)
        val d = parseWallpaperFeed(both, dark)!!
        assertEquals(d1, d.wallpaperUrl)
        assertEquals(listOf(d1, d2), d.items)
    }

    @Test
    fun `dark mode falls back to items when dark_items is missing or unusable`() {
        for (extra in listOf("", """, "dark_items": []""", """, "dark_items": "x"""", """, "dark_items": ["http://x.test/n.jpg", 3]""")) {
            val feed = parseWallpaperFeed("""{"url": "$a", "dark": "$d1", "items": ["$a", "$b"]$extra}""", dark)!!
            assertEquals(listOf(a, b), feed.items)
        }
    }

    @Test
    fun `light mode ignores dark_items`() {
        val feed = parseWallpaperFeed("""{"url": "$a", "dark_items": ["$d1", "$d2"]}""", light)!!
        assertTrue(feed.items.isEmpty())
    }

    @Test
    fun `next dark item comes from the dark pool`() {
        val d = parseWallpaperFeed(both, dark)!!
        assertEquals(d2, nextWallpaperUrl(d.items, d1))
        // the current light image is not in the dark pool: start at its first item
        assertEquals(d1, nextWallpaperUrl(d.items, a))
    }

    @Test
    fun `manual pick is kept within a mode and a mode switch applies the new mode's image`() {
        val night = parseWallpaperFeed(both, dark)!!
        val day = parseWallpaperFeed(both, light)!!
        // evening: the dark image of the day is applied, then a manual pick marks the same day key as seen
        assertTrue(isNewFeedDay(night.dayKey, day.dayKey))
        assertFalse(isNewFeedDay(night.dayKey, night.dayKey))
        // morning: light mode has another key, so the light image of the day replaces the night's pick
        assertTrue(isNewFeedDay(day.dayKey, night.dayKey))
        // a new day in the same mode applies again
        val nextNight = parseWallpaperFeed(both.replace(d1, d2), dark)!!
        assertTrue(isNewFeedDay(nextNight.dayKey, night.dayKey))
    }

    @Test
    fun `stored day key is recognised per mode`() {
        assertTrue(isOtherMode("", dark))
        assertTrue(isOtherMode(feedDayKey(light, a), dark))
        assertFalse(isOtherMode(feedDayKey(dark, d1), dark))
        assertTrue(isOtherMode(a, light))
    }

    private fun at(hour: Int, minute: Int, second: Int = 0, zone: String = "Europe/Istanbul", day: Int = 3, month: Int = Calendar.OCTOBER) =
        Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
            clear()
            set(2026, month, day, hour, minute, second)
        }

    private fun minutes(m: Long) = m * 60_000

    @Test
    fun `custom feed pool follows the local hour`() {
        assertEquals(dark, wallTypeAt(at(0, 0)))
        assertEquals(dark, wallTypeAt(at(4, 59)))
        assertEquals(light, wallTypeAt(at(5, 0)))
        assertEquals(light, wallTypeAt(at(12, 0)))
        assertEquals(light, wallTypeAt(at(18, 59)))
        assertEquals(dark, wallTypeAt(at(19, 0)))
        assertEquals(dark, wallTypeAt(at(23, 59)))
    }

    @Test
    fun `next pool switch is the next 05 00 or 19 00`() {
        assertEquals(minutes(1), millisToNextWallTypeSwitch(at(4, 59)))
        assertEquals(minutes(14 * 60), millisToNextWallTypeSwitch(at(5, 0)))
        assertEquals(minutes(1), millisToNextWallTypeSwitch(at(18, 59)))
        assertEquals(minutes(10 * 60), millisToNextWallTypeSwitch(at(19, 0)))
        assertEquals(minutes(5 * 60), millisToNextWallTypeSwitch(at(0, 0)))
        assertEquals(minutes(5 * 60 + 1), millisToNextWallTypeSwitch(at(23, 59)))
        assertEquals(1_000, millisToNextWallTypeSwitch(at(18, 59, 59)))
    }

    @Test
    fun `next pool switch counts real time over a daylight saving change`() {
        // Berlin, 2026-03-29 02:00 -> 03:00: the night is one hour shorter
        assertEquals(minutes(9 * 60), millisToNextWallTypeSwitch(at(19, 0, zone = "Europe/Berlin", day = 28, month = Calendar.MARCH)))
    }

    private fun hct(argb: Int) = com.google.android.material.color.utilities.Hct.fromInt(argb)

    @Test
    fun `text tone comes from the shifted wallpaper color and its brightness`() {
        val pink = 0xFFF06292.toInt()
        val blue = 0xFF1565C0.toInt()
        val onDark = wallpaperTextColor(darkText = false, wallpaperColors = listOf(pink))!!
        val onLight = wallpaperTextColor(darkText = true, wallpaperColors = listOf(blue))!!
        assertEquals(85.0, hct(onDark).tone, 1.0)
        assertEquals(30.0, hct(onLight).tone, 1.0)
        assertEquals(textHue(hct(pink).hue, darkText = false), hct(onDark).hue, 5.0)
        assertEquals(textHue(hct(blue).hue, darkText = true), hct(onLight).hue, 5.0)
        assertTrue(hct(onDark).chroma > 20)
        assertTrue(hct(onLight).chroma > 30)
    }

    @Test
    fun `grey colors are skipped for the seed`() {
        val grey = 0xFF808080.toInt()
        val pink = 0xFFF06292.toInt()
        val seeded = wallpaperTextColor(darkText = false, wallpaperColors = listOf(grey, pink))!!
        assertEquals(textHue(hct(pink).hue, darkText = false), hct(seeded).hue, 5.0)
    }

    @Test
    fun `grey wallpaper falls back to black and white`() {
        assertNull(wallpaperTextColor(darkText = false, wallpaperColors = listOf(0xFF808080.toInt(), 0xFF202124.toInt())))
        assertNull(wallpaperTextColor(darkText = true, wallpaperColors = listOf(0xFFFFFFFF.toInt())))
        assertNull(wallpaperTextColor(darkText = true, wallpaperColors = emptyList()))
    }

    @Test
    fun `stored wallpaper colors round trip`() {
        val pink = 0xFFF06292.toInt()
        val stored = decodeWallpaperColors(encodeWallpaperColors(64, 4, listOf(pink, -1)))!!
        assertEquals(64, stored.id)
        assertEquals(4, stored.hints)
        assertEquals(listOf(pink, -1), stored.colors)
    }

    @Test
    fun `broken stored wallpaper colors are ignored`() {
        assertNull(decodeWallpaperColors(""))
        assertNull(decodeWallpaperColors("64|4|"))
        assertNull(decodeWallpaperColors("x|4|1"))
        assertNull(decodeWallpaperColors("64|4|1,zz"))
    }

    @Test
    fun `brightness split for the text color`() {
        assertTrue(isLightRgb(0xFFFFFF))
        assertTrue(isLightRgb(0xF0E68C))
        assertFalse(isLightRgb(0x000000))
        assertFalse(isLightRgb(0x101820))
    }
}
