package app.olauncher.data

import com.google.android.material.color.utilities.Hct
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextContrastTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val pink = Hct.fromInt(0xFFF06292.toInt())

    private fun luminanceOf(rgb: Int) = relativeLuminance(rgb)
    private fun contrast(text: Int, background: Double) = contrastRatio(relativeLuminance(text), background)

    @Test
    fun `contrast ratio follows WCAG`() {
        assertEquals(1.0, luminanceOf(white), 1e-9)
        assertEquals(0.0, luminanceOf(black), 1e-9)
        assertEquals(21.0, contrastRatio(1.0, 0.0), 1e-9)
        assertEquals(21.0, contrastRatio(0.0, 1.0), 1e-9)
        assertEquals(1.0, contrastRatio(0.3, 0.3), 1e-9)
        // #767676 is the classic lightest grey that passes 4.5:1 on white, #777777 just fails
        assertTrue(contrastRatio(luminanceOf(0xFF767676.toInt()), 1.0) >= 4.5)
        assertTrue(contrastRatio(luminanceOf(0xFF777777.toInt()), 1.0) < 4.5)
    }

    @Test
    fun `text tone search always reaches the minimum contrast or falls to black or white`() {
        for (hue in 0 until 360 step 15) for (step in 0..100) {
            val background = step / 100.0
            for (darkText in listOf(false, true)) {
                val color = softestTextColor(hue.toDouble(), 44.0, darkText, listOf(background))
                assertTrue(
                    "hue $hue bg $background dark $darkText",
                    contrast(color, background) >= MIN_TEXT_CONTRAST || color == (if (darkText) black else white)
                )
            }
        }
    }

    @Test
    fun `text tone stays soft when the background allows it`() {
        val onNight = softestTextColor(pink.hue, 44.0, darkText = false, listOf(0.01))
        val onSky = softestTextColor(pink.hue, 44.0, darkText = true, listOf(0.85))
        assertEquals(LIGHT_TEXT_TONE, Hct.fromInt(onNight).tone, 0.6)
        assertEquals(DARK_TEXT_TONE, Hct.fromInt(onSky).tone, 0.6)
    }

    @Test
    fun `text tone moves only as far as needed`() {
        val background = 0.15 // too bright for the T85 pastel, dark enough for a lighter one
        val color = softestTextColor(pink.hue, 44.0, darkText = false, listOf(background))
        val tone = Hct.fromInt(color).tone
        assertTrue(tone > LIGHT_TEXT_TONE && tone < 100)
        assertTrue(contrast(color, background) >= MIN_TEXT_CONTRAST)
        val softer = Hct.from(pink.hue, 44.0, tone - 1).toInt()
        assertTrue(contrast(softer, background) < MIN_TEXT_CONTRAST)
    }

    @Test
    fun `text tone checks every background of the block`() {
        val color = softestTextColor(pink.hue, 44.0, darkText = false, listOf(0.01, 0.12, 0.05))
        assertTrue(contrast(color, 0.12) >= MIN_TEXT_CONTRAST)
    }

    @Test
    fun `unreachable contrast ends at pure white or black`() {
        assertEquals(white, softestTextColor(pink.hue, 44.0, darkText = false, listOf(0.5)))
        assertEquals(black, softestTextColor(pink.hue, 44.0, darkText = true, listOf(0.1)))
    }

    @Test
    fun `text hue leaves the wallpaper hue`() {
        assertEquals(50.0, textHue(350.0, darkText = false, HueStrategy.TERTIARY), 1e-9)
        assertEquals(330.0, textHue(270.0, darkText = true, HueStrategy.TERTIARY), 1e-9)
        assertEquals(170.0, textHue(350.0, darkText = true, HueStrategy.COMPLEMENT), 1e-9)
        assertEquals(10.0, textHue(190.0, darkText = false, HueStrategy.COMPLEMENT), 1e-9)
        assertEquals((120.0 + TEXT_HUE_STRATEGY.shift) % 360, textHue(120.0, darkText = false), 1e-9)
    }

    @Test
    fun `light text may be any hue`() {
        // Red seed: +60 is a yellow, fine as a pastel
        assertEquals(87.0, textHue(27.0, darkText = false, HueStrategy.TERTIARY), 1e-9)
        assertEquals(90.0, textHue(270.0, darkText = false, HueStrategy.COMPLEMENT), 1e-9)
    }

    @Test
    fun `dark text avoids mustard and olive`() {
        // Red seed: +60 would be a dark mustard, -60 is a plum
        assertEquals(327.0, textHue(27.0, darkText = true, HueStrategy.TERTIARY), 1e-9)
        // Blue seed: the complement is olive on both sides, so the nearest clean hue
        assertEquals(65.0, textHue(265.0, darkText = true, HueStrategy.COMPLEMENT), 1e-9)
        assertEquals(115.0, textHue(290.0, darkText = true, HueStrategy.COMPLEMENT), 1e-9)
    }

    @Test
    fun `picked colors have the shifted hue and moderate chroma`() {
        val vivid = Hct.from(pink.hue, 90.0, 60.0)
        val (light, dark) = pickTextColors(vivid, listOf(listOf(listOf(0.01)), listOf(listOf(0.9)))).map { it[0]!! }
        assertFalse(light.darkText)
        assertTrue(dark.darkText)
        assertEquals(textHue(vivid.hue, darkText = false), Hct.fromInt(light.color).hue, 3.0)
        assertEquals(textHue(vivid.hue, darkText = true), Hct.fromInt(dark.color).hue, 3.0)
        assertTrue(Hct.fromInt(light.color).chroma <= 32.5)
        assertTrue(Hct.fromInt(dark.color).chroma <= 48.5)
    }

    @Test
    fun `dark or light text follows the background`() {
        assertFalse(prefersDarkText(listOf(0.02)))
        assertTrue(prefersDarkText(listOf(0.7)))
        // The bright spot decides for light text, the dark one for dark text
        assertTrue(prefersDarkText(listOf(0.4, 0.9)))
        assertFalse(prefersDarkText(listOf(0.01, 0.08)))
    }

    private fun blocks(vararg block: List<Double>) = block.map { texts -> texts.map { listOf(it) } }

    @Test
    fun `blocks on the same side share one color`() {
        val choices = pickTextColors(pink, blocks(listOf(0.01, 0.02), listOf(0.12), listOf(0.03))).flatten()
        assertEquals(1, choices.map { it!!.color }.distinct().size)
        assertTrue(choices.all { !it!!.darkText })
        assertTrue(contrast(choices[0]!!.color, 0.12) >= MIN_TEXT_CONTRAST)
    }

    @Test
    fun `blocks on dark and light parts get a light and a dark text`() {
        val (clock, apps) = pickTextColors(pink, blocks(listOf(0.02), listOf(0.8))).map { it[0]!! }
        assertFalse(clock.darkText)
        assertTrue(apps.darkText)
        assertTrue(contrast(clock.color, 0.02) >= MIN_TEXT_CONTRAST)
        assertTrue(contrast(apps.color, 0.8) >= MIN_TEXT_CONTRAST)
        assertNotEquals(clock.color, apps.color)
    }

    @Test
    fun `a block over dark and light parts is colored text by text`() {
        // An app list running from a dark part into a light one: no single color reads on both
        val apps = pickTextColors(pink, blocks(listOf(0.02, 0.03, 0.6, 0.7)))[0].map { it!! }
        assertEquals(listOf(false, false, true, true), apps.map { it.darkText })
        listOf(0.02, 0.03, 0.6, 0.7).forEachIndexed { i, background ->
            assertTrue(contrast(apps[i].color, background) >= MIN_TEXT_CONTRAST)
        }
        assertEquals(1, apps.take(2).map { it.color }.distinct().size)
    }

    @Test
    fun `a block that reads with one color stays one color`() {
        val apps = pickTextColors(pink, blocks(listOf(0.01, 0.12)))[0].map { it!! }
        assertEquals(apps[0], apps[1])
    }

    @Test
    fun `a text on light and dark parts at once does not push the others to white`() {
        // The first text's own backgrounds disagree (its box covers both); white/black is the best it can get
        val (mixed, clock) = pickTextColors(pink, listOf(listOf(listOf(0.02, 0.6)), listOf(listOf(0.01)))).map { it[0]!! }
        assertEquals(if (mixed.darkText) black else white, mixed.color)
        assertEquals(LIGHT_TEXT_TONE, Hct.fromInt(clock.color).tone, 0.6)
    }

    @Test
    fun `grey wallpaper keeps black and white`() {
        assertNull(textSeed(listOf(0xFF808080.toInt(), 0xFF202124.toInt(), white)))
        val (onDark, onLight) = pickTextColors(null, blocks(listOf(0.1), listOf(0.7))).map { it[0] }
        assertEquals(TextChoice(white, false), onDark)
        assertEquals(TextChoice(black, true), onLight)
    }

    @Test
    fun `texts without backgrounds follow their block`() {
        val choices = pickTextColors(pink, listOf(emptyList(), listOf(emptyList(), listOf(0.02))))
        assertTrue(choices[0].isEmpty())
        assertEquals(choices[1][1], choices[1][0])
        assertFalse(choices[1][1]!!.darkText)
        assertNull(pickTextColors(pink, listOf(listOf(emptyList())))[0][0])
        assertTrue(pickTextColors(pink, emptyList()).isEmpty())
    }

    @Test
    fun `a text rebuilt in place gets its earlier color back`() {
        // A home note rebuilt on a notes update with the first app's color, at the same place as before
        val note = Box(796.0, 1010.0, 1008.0, 1100.0)
        val app = Box(450.0, 890.0, 630.0, 980.0)
        val icon = Box(276.0, 2304.0, 372.0, 2400.0)
        val light = TextChoice(0xFFF9CE84.toInt(), darkText = false)
        val dark = TextChoice(0xFF521D67.toInt(), darkText = true)
        val earlier = mapOf(note to dark, app to light, icon to light)
        val stale = staleTextChoices(earlier, listOf(light.color to note, light.color to app, null to icon))
        assertEquals(listOf(dark, null, null), stale)
    }

    @Test
    fun `texts that moved need a new pick`() {
        val earlier = mapOf(Box(0.0, 0.0, 10.0, 10.0) to TextChoice(black, darkText = true))
        assertNull(staleTextChoices(earlier, listOf(black to Box(0.0, 0.0, 12.0, 10.0))))
        assertEquals(listOf<TextChoice?>(null), staleTextChoices(earlier, listOf(black to Box(0.0, 0.0, 10.0, 10.0))))
    }

    @Test
    fun `an image cropped to the screen maps one to one`() {
        val box = Box(24.0, 300.0, 400.0, 420.0)
        assertEquals(box, screenToImage(box, 1080, 2520, 1080, 2520))
    }

    @Test
    fun `a wider image is centered and cropped at the sides`() {
        val mapped = screenToImage(Box(0.0, 0.0, 1080.0, 2520.0), 1080, 2520, 2000, 2520)
        assertEquals(Box(460.0, 0.0, 1540.0, 2520.0), mapped)
    }

    @Test
    fun `a smaller image is scaled up`() {
        assertEquals(Box(50.0, 100.0, 60.0, 110.0), screenToImage(Box(100.0, 200.0, 120.0, 220.0), 1080, 2520, 540, 1260))
    }

    @Test
    fun `a squarer screen shows the middle band of the image`() {
        // Phone image on the 948 x 1048 cover screen: scaled to its width, cropped top and bottom
        val mapped = screenToImage(Box(0.0, 0.0, 948.0, 1048.0), 948, 1048, 1080, 2520)
        assertEquals(0.0, mapped.left, 1e-6)
        assertEquals(1080.0, mapped.right, 1e-6)
        assertEquals((2520 - 1048 * 1080.0 / 948) / 2, mapped.top, 1e-6)
        assertEquals(2520 - mapped.top, mapped.bottom, 1e-6)
    }

    @Test
    fun `a zoomed wallpaper shows less of the image around the center`() {
        // AOSP home: the cropped image zoomed in 1.1x; the screen center stays, the edges move inward
        val center = screenToImage(Box(530.0, 1250.0, 550.0, 1270.0), 1080, 2520, 1080, 2520, zoom = 1.1)
        assertEquals(540.0, (center.left + center.right) / 2, 1e-6)
        assertEquals(1260.0, (center.top + center.bottom) / 2, 1e-6)
        val corner = screenToImage(Box(0.0, 0.0, 1080.0, 2520.0), 1080, 2520, 1080, 2520, zoom = 1.1)
        assertEquals(540 - 540 / 1.1, corner.left, 1e-6)
        assertEquals(1260 - 1260 / 1.1, corner.top, 1e-6)
        assertEquals(540 + 540 / 1.1, corner.right, 1e-6)
    }

    private fun image(width: Int, height: Int, pixel: (Int, Int) -> Int) =
        IntArray(width * height) { pixel(it % width, it / width) }

    private fun grid(width: Int, height: Int, cols: Int, rows: Int, pixels: IntArray) =
        averageGrid(width, height, cols, rows) { y, row -> System.arraycopy(pixels, y * width, row, 0, width) }

    @Test
    fun `grid cells average their part of the image`() {
        val halves = image(8, 4) { x, _ -> if (x < 4) black else white }
        assertArrayEquals(intArrayOf(black, white), grid(8, 4, 2, 1, halves))
    }

    @Test
    fun `grid averages in linear light`() {
        val checker = image(4, 4) { x, y -> if ((x + y) % 2 == 0) black else white }
        val cell = grid(4, 4, 1, 1, checker)[0]
        assertEquals(0.5, relativeLuminance(cell), 0.01)
        assertEquals(0xBC, cell and 0xFF) // not 0x80: half the light, not half the code value
    }

    @Test
    fun `grid rows follow the image shape`() {
        assertEquals(84, gridRows(1080, 2520))
        assertEquals(GRID_COLUMNS, gridRows(500, 500))
        assertEquals(1, gridRows(5000, 10))
    }

    @Test
    fun `grid keeps each cell's luminance spread`() {
        val halves = image(4, 4) { x, _ -> if (x < 2) black else white }
        val tones = ByteArray(TONE_POINTS)
        averageGrid(4, 4, 1, 1, tones) { y, row -> System.arraycopy(halves, y * 4, row, 0, 4) }
        assertArrayEquals(byteArrayOf(0, 0, -1, -1), tones) // codes 0, 0, 255, 255
        val grey = image(4, 4) { _, _ -> 0xFF777777.toInt() }
        averageGrid(4, 4, 1, 1, tones) { y, row -> System.arraycopy(grey, y * 4, row, 0, 4) }
        assertTrue(tones.all { it == 0x77.toByte() })
    }

    // A grey moon-like texture, finer than a grid cell: 30% deep shadow, the rest a mid grey (luminance 0.25)
    private val shadowGrey = 0xFF898989.toInt()
    private fun texture(width: Int, height: Int) = image(width, height) { x, y -> if ((x + 7 * y) % 10 < 3) black else shadowGrey }

    private fun textureGrid(): WallpaperGrid {
        val pixels = texture(360, 840)
        val tones = ByteArray(36 * 84 * TONE_POINTS)
        val cells = averageGrid(360, 840, 36, 84, tones) { y, row -> System.arraycopy(pixels, y * 360, row, 0, 360) }
        return WallpaperGrid(1, 360, 840, 36, 84, cells, tones)
    }

    @Test
    fun `text backgrounds are the quartiles of the area`() {
        val box = Box(40.0, 300.0, 200.0, 330.0)
        val (low, middle, high) = textureGrid().backgroundLuminances(box)!!
        val grey = relativeLuminance(shadowGrey)
        assertEquals(0.0, low, 1e-9)
        assertEquals(grey, middle, 1e-9)
        assertEquals(grey, high, 1e-9)
        // The average is a darker grey that is nowhere in the image
        assertEquals(0.7 * grey, textureGrid().luminance(box)!!, 0.01)
    }

    @Test
    fun `a textured grey wallpaper takes the side that reads on most of it`() {
        // The 1309 Lunar Farside case: by its average the area is dark enough for white (about 4.7:1), but the text
        // mostly sits on the mid grey, where white is 3.5:1 and black 6:1
        val grid = textureGrid()
        val box = Box(40.0, 300.0, 200.0, 330.0)
        val byAverage = pickTextColors(null, listOf(listOf(listOf(grid.luminance(box)!!))))[0][0]!!
        assertFalse(byAverage.darkText)
        val backgrounds = grid.backgroundLuminances(box)!!
        val choice = pickTextColors(null, listOf(listOf(backgrounds)))[0][0]!!
        assertEquals(TextChoice(black, darkText = true), choice)
        assertTrue(contrast(choice.color, relativeLuminance(shadowGrey)) >= MIN_TEXT_CONTRAST)
        assertTrue(contrast(white, relativeLuminance(shadowGrey)) < 4.5)
    }

    @Test
    fun `light text is checked on the lighter quarter of a texture`() {
        // Night sky with bright specks: the pastel tone reads on the average but not on the specks
        val specks = relativeLuminance(0xFF7C7C7C.toInt()) // 0.2
        val sky = relativeLuminance(0xFF1A1A1A.toInt()) // 0.01
        val average = 0.7 * sky + 0.3 * specks
        val soft = softestTextColor(pink.hue, 28.0, darkText = false, listOf(average))
        assertEquals(LIGHT_TEXT_TONE, Hct.fromInt(soft).tone, 0.6)
        val choice = pickTextColors(pink, listOf(listOf(listOf(sky, sky, specks))))[0][0]!!
        assertFalse(choice.darkText)
        assertTrue(Hct.fromInt(choice.color).tone > Hct.fromInt(soft).tone)
        assertTrue(contrast(choice.color, specks) > contrast(soft, specks))
    }

    @Test
    fun `a dark side that reaches the minimum on its quarter wins over the middle`() {
        // Mostly bright with a dark quarter that black still reads on: no need to look at the middle
        assertTrue(prefersDarkText(listOf(0.25, 0.6, 0.9)))
        // Neither side reads on its worse quarter: the middle decides
        assertTrue(prefersDarkText(listOf(0.0, 0.25, 0.25)))
        assertFalse(prefersDarkText(listOf(0.03, 0.06, 0.6)))
    }

    @Test
    fun `dimmed luminance points are darker`() {
        val g = WallpaperGrid(1, 10, 10, 1, 1, intArrayOf(white), ByteArray(TONE_POINTS) { -1 })
        val dim = relativeLuminance(dimmed(white, NIGHT_WALLPAPER_DIM))
        assertEquals(listOf(dim, dim, dim), g.backgroundLuminances(Box(0.0, 0.0, 10.0, 10.0), NIGHT_WALLPAPER_DIM))
        assertNull(g.backgroundLuminances(Box(20.0, 0.0, 30.0, 10.0)))
    }

    @Test
    fun `a grid stored without luminance points uses the average`() {
        val old = WallpaperGrid(1, 100, 10, 2, 1, intArrayOf(black, white))
        assertEquals(listOf(0.5), old.backgroundLuminances(Box(25.0, 0.0, 75.0, 10.0)))
        val decoded = decodeWallpaperGrid(encodeWallpaperGrid(old))!!
        assertNull(decoded.tones)
    }

    @Test
    fun `region luminance is weighted by cell overlap`() {
        val g = WallpaperGrid(1, 100, 10, 2, 1, intArrayOf(black, white))
        assertEquals(0.5, g.luminance(Box(25.0, 0.0, 75.0, 10.0))!!, 1e-9)
        assertEquals(2.0 / 3, g.luminance(Box(25.0, 2.0, 100.0, 8.0))!!, 1e-9)
        assertEquals(1.0, g.luminance(Box(60.0, 0.0, 300.0, 50.0))!!, 1e-9) // clipped to the image
        assertNull(g.luminance(Box(120.0, 0.0, 200.0, 10.0)))
        assertNull(g.luminance(Box(10.0, 5.0, 10.0, 9.0)))
    }

    @Test
    fun `dimming scales every channel and keeps alpha`() {
        assertEquals(0xFFBFBFBF.toInt(), dimmed(white, 0.75))
        assertEquals(0x80785030.toInt(), dimmed(0x80A06B40.toInt(), 0.75))
        assertEquals(black, dimmed(black, 0.75))
    }

    @Test
    fun `dimmed region is darker`() {
        val g = WallpaperGrid(1, 10, 10, 1, 1, intArrayOf(white))
        assertEquals(relativeLuminance(dimmed(white, NIGHT_WALLPAPER_DIM)), g.luminance(Box(0.0, 0.0, 10.0, 10.0), NIGHT_WALLPAPER_DIM)!!, 1e-9)
    }

    @Test
    fun `dark text stays readable on the dimmed wallpaper`() {
        // A light area that One UI dims in dark mode: both the image and the dimmed image are backgrounds
        val area = relativeLuminance(0xFFB0B0B0.toInt())
        val dim = relativeLuminance(dimmed(0xFFB0B0B0.toInt(), NIGHT_WALLPAPER_DIM))
        val choice = pickTextColors(pink, listOf(listOf(listOf(area, dim))))[0][0]!!
        assertTrue(choice.darkText)
        assertTrue(contrast(choice.color, dim) >= MIN_TEXT_CONTRAST)
        assertTrue(contrast(softestTextColor(pink.hue, 44.0, true, listOf(area)), dim) < MIN_TEXT_CONTRAST)
    }

    @Test
    fun `wallpaper grid round trip`() {
        val cells = intArrayOf(black, white, 0xFFF06292.toInt(), 0xFF0A0B0C.toInt(), 0xFF123456.toInt(), white)
        val decoded = decodeWallpaperGrid(encodeWallpaperGrid(WallpaperGrid(77, 1080, 2520, 2, 3, cells)))!!
        assertEquals(77, decoded.id)
        assertEquals(1080, decoded.width)
        assertEquals(2520, decoded.height)
        assertEquals(2, decoded.cols)
        assertEquals(3, decoded.rows)
        assertArrayEquals(cells, decoded.cells)
    }

    @Test
    fun `wallpaper grid round trip keeps the luminance points`() {
        val tones = ByteArray(2 * TONE_POINTS) { (it * 37).toByte() }
        val decoded = decodeWallpaperGrid(encodeWallpaperGrid(WallpaperGrid(5, 20, 10, 2, 1, intArrayOf(black, white), tones)))!!
        assertArrayEquals(tones, decoded.tones)
        assertArrayEquals(intArrayOf(black, white), decoded.cells)
        val short = encodeWallpaperGrid(WallpaperGrid(5, 20, 10, 2, 1, intArrayOf(black, white), ByteArray(TONE_POINTS)))
        assertNull(decodeWallpaperGrid(short))
    }

    @Test
    fun `broken wallpaper grids are ignored`() {
        val one = encodeWallpaperGrid(WallpaperGrid(1, 10, 10, 1, 1, intArrayOf(white))) // "1|10|10|1|1|////"
        assertEquals(1, decodeWallpaperGrid(one)!!.cells.size)
        assertNull(decodeWallpaperGrid(""))
        assertNull(decodeWallpaperGrid("1|10|10|1|1"))
        assertNull(decodeWallpaperGrid(one.replace("|1|1|", "|2|1|"))) // too few cells
        assertNull(decodeWallpaperGrid("1|10|10|1|1|*!*!"))
        assertNull(decodeWallpaperGrid(one.replace("|10|10|", "|0|10|")))
        assertNull(decodeWallpaperGrid(one.replaceFirst("1|", "x|")))
    }

    @Test
    fun `one ui dims the wallpaper only in dark mode with its setting on`() {
        assertEquals(listOf(1.0), oneUiWallpaperDims(night = false, darkModeWallpaperSetting = 1))
        assertEquals(listOf(1.0), oneUiWallpaperDims(night = true, darkModeWallpaperSetting = 0))
        assertEquals(listOf(NIGHT_WALLPAPER_DIM), oneUiWallpaperDims(night = true, darkModeWallpaperSetting = 1))
        assertEquals(listOf(1.0, NIGHT_WALLPAPER_DIM), oneUiWallpaperDims(night = true, darkModeWallpaperSetting = null))
    }
}
