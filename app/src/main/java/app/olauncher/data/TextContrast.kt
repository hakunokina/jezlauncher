package app.olauncher.data

import android.annotation.SuppressLint
import com.google.android.material.color.utilities.Hct
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToInt

/*
 * Home text colors that stay readable on the part of the wallpaper behind them. The wallpaper image the app set is
 * reduced to a coarse grid of average colors and luminance spreads once (when it is set); the home screen then looks up
 * the cells behind its texts and picks the softest tone of a shifted wallpaper hue that keeps MIN_TEXT_CONTRAST on the
 * darker and the lighter quarter of what is behind them, not only on its average (a textured image is both).
 */

/** WCAG 2's 4.5:1 for body text plus a margin for the grid's estimate of the background (about 5%, measured). */
const val MIN_TEXT_CONTRAST = 5.0

/**
 * Where the text hue sits relative to the wallpaper's dominant hue (HCT/CAM16 degrees). Readability comes from the
 * tone search; the hue only has to leave the background's color family.
 */
enum class HueStrategy(val shift: Double) {
    /** Material You's tertiary accent: a neighbouring, harmonious color (blue -> pink, pink -> peach). */
    TERTIARY(60.0),

    /** The opposite color (pink -> mint, orange -> blue); strongest separation. */
    COMPLEMENT(180.0),
}

/** The one switch between the two hue options. */
val TEXT_HUE_STRATEGY = HueStrategy.TERTIARY

// Dark mustard and olive (Material's disliked yellow-greens, widened to the browns): no dark text there
private const val MUDDY_HUE_FROM = 65.0
private const val MUDDY_HUE_TO = 115.0

private fun isMuddy(hue: Double) = hue > MUDDY_HUE_FROM && hue < MUDDY_HUE_TO

/**
 * Text hue: the wallpaper hue shifted by [strategy]. A dark text that would land on a muddy hue takes the other side
 * (seed - shift), or the nearest clean hue when that is muddy too.
 */
fun textHue(seedHue: Double, darkText: Boolean, strategy: HueStrategy = TEXT_HUE_STRATEGY): Double {
    val hue = (seedHue + strategy.shift).mod(360.0)
    if (!darkText || !isMuddy(hue)) return hue
    val mirrored = (seedHue - strategy.shift).mod(360.0)
    if (!isMuddy(mirrored)) return mirrored
    return if (hue - MUDDY_HUE_FROM < MUDDY_HUE_TO - hue) MUDDY_HUE_FROM else MUDDY_HUE_TO
}

// Preferred (softest) tones: a pastel light text and a deep dark text; the search only moves them toward white/black
const val LIGHT_TEXT_TONE = 85.0
const val DARK_TEXT_TONE = 30.0
private const val TONE_STEP = 0.5

// Visibly tinted but not loud: pastel light text, deep dark text
private const val MIN_SEED_CHROMA = 10.0
private const val LIGHT_TEXT_CHROMA = 24.0
private const val MAX_LIGHT_TEXT_CHROMA = 32.0
private const val DARK_TEXT_CHROMA = 40.0
private const val MAX_DARK_TEXT_CHROMA = 48.0

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()

private val LINEAR = DoubleArray(256) {
    val c = it / 255.0
    if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

private fun linear(channel: Int): Double = LINEAR[channel and 0xFF]

private fun srgbChannel(linear: Double): Int {
    val c = if (linear <= 0.0031308) linear * 12.92 else 1.055 * linear.pow(1 / 2.4) - 0.055
    return (c * 255).roundToInt().coerceIn(0, 255)
}

/** WCAG relative luminance (0 black .. 1 white); alpha is ignored. */
fun relativeLuminance(rgb: Int): Double =
    0.2126 * linear(rgb shr 16) + 0.7152 * linear(rgb shr 8) + 0.0722 * linear(rgb)

fun contrastRatio(luminanceA: Double, luminanceB: Double): Double =
    (max(luminanceA, luminanceB) + 0.05) / (min(luminanceA, luminanceB) + 0.05)

/** The wallpaper's dominant tinted color: the first of [wallpaperColors] (most common first) that is not grey. */
@SuppressLint("RestrictedApi") // Material's bundled color utilities; there is no official standalone artifact
fun textSeed(wallpaperColors: List<Int>): Hct? =
    wallpaperColors.map { Hct.fromInt(it) }.firstOrNull { it.chroma >= MIN_SEED_CHROMA }

@SuppressLint("RestrictedApi")
fun textChroma(seed: Hct, darkText: Boolean): Double =
    if (darkText) seed.chroma.coerceIn(DARK_TEXT_CHROMA, MAX_DARK_TEXT_CHROMA)
    else seed.chroma.coerceIn(LIGHT_TEXT_CHROMA, MAX_LIGHT_TEXT_CHROMA)

/**
 * The softest text color of [hue]/[chroma] readable on every background luminance in [backgrounds]: from the
 * preferred tone (light T85, dark T30) toward white or black until each contrast is at least [MIN_TEXT_CONTRAST].
 * Ends at pure white/black when nothing softer is enough (also the best effort when even that is not).
 */
@SuppressLint("RestrictedApi")
fun softestTextColor(hue: Double, chroma: Double, darkText: Boolean, backgrounds: List<Double>): Int {
    var tone = if (darkText) DARK_TEXT_TONE else LIGHT_TEXT_TONE
    while (if (darkText) tone > 0 else tone < 100) {
        val color = Hct.from(hue, chroma, tone).toInt()
        val text = relativeLuminance(color)
        if (backgrounds.all { contrastRatio(text, it) >= MIN_TEXT_CONTRAST }) return color
        tone += if (darkText) -TONE_STEP else TONE_STEP
    }
    return if (darkText) BLACK else WHITE
}

/** The color of one text; [darkText] also picks its shadow (a light shadow under dark text). */
data class TextChoice(val color: Int, val darkText: Boolean)

/**
 * Dark text when black reads better on the darkest background than white does on the brightest one. When neither
 * reaches [MIN_TEXT_CONTRAST] there (a text over both dark and light parts), the side that reads better on the
 * middle one, where most of the text is.
 */
fun prefersDarkText(backgrounds: List<Double>): Boolean {
    val dark = contrastRatio(0.0, backgrounds.min())
    val light = contrastRatio(1.0, backgrounds.max())
    if (dark >= MIN_TEXT_CONTRAST || light >= MIN_TEXT_CONTRAST) return dark > light
    val middle = backgrounds.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }
    return contrastRatio(0.0, middle) > contrastRatio(1.0, middle)
}

private fun extremeContrast(darkText: Boolean, backgrounds: List<Double>): Double =
    if (darkText) contrastRatio(0.0, backgrounds.min()) else contrastRatio(1.0, backgrounds.max())

private fun reachesMinimum(backgrounds: List<Double>) =
    extremeContrast(prefersDarkText(backgrounds), backgrounds) >= MIN_TEXT_CONTRAST

/**
 * Text colors for blocks of home texts (clock, apps, bottom bar...), [blocks] holding per block, per text, the
 * background luminances behind that text (its quartiles and median, see backgroundLuminances, for each way the
 * device may show the image: zoomed or dimmed).
 * A block is colored as a whole when one side, light or dark text, can reach [MIN_TEXT_CONTRAST] on all of it; a block
 * running over light and dark parts is colored text by text instead. Each of these takes light or dark text by its own
 * backgrounds, and all on the same side share one color, the softest one readable on all of their backgrounds, so the
 * screen has at most one light and one dark text color. A text that cannot reach the minimum even with white/black
 * (light and dark parts under the same text) gets the better of those and does not push the shared color.
 * [seed] null (grey wallpaper) keeps white/black; [strategy] places the hue. A text without backgrounds follows its
 * block's first colored text.
 */
@SuppressLint("RestrictedApi")
fun pickTextColors(
    seed: Hct?,
    blocks: List<List<List<Double>>>,
    strategy: HueStrategy = TEXT_HUE_STRATEGY,
): List<List<TextChoice?>> {
    // Parts colored as one: (block, its texts, their backgrounds)
    val units = blocks.flatMapIndexed { b, texts ->
        val all = texts.flatten()
        when {
            all.isEmpty() -> emptyList()
            reachesMinimum(all) -> listOf(Triple(b, texts.indices.toList(), all))
            else -> texts.mapIndexedNotNull { t, backgrounds ->
                if (backgrounds.isEmpty()) null else Triple(b, listOf(t), backgrounds)
            }
        }
    }
    val dark = units.map { prefersDarkText(it.third) }
    val reaches = units.mapIndexed { i, unit -> extremeContrast(dark[i], unit.third) >= MIN_TEXT_CONTRAST }
    val shared = listOf(false, true).associateWith { darkText ->
        val backgrounds = units.filterIndexed { i, _ -> dark[i] == darkText && reaches[i] }.flatMap { it.third }
        when {
            backgrounds.isEmpty() -> null
            seed == null -> if (darkText) BLACK else WHITE
            else -> softestTextColor(textHue(seed.hue, darkText, strategy), textChroma(seed, darkText), darkText, backgrounds)
        }
    }
    val choices = blocks.map { arrayOfNulls<TextChoice>(it.size) }
    units.forEachIndexed { i, (b, texts, _) ->
        val color = shared[dark[i]].takeIf { reaches[i] } ?: if (dark[i]) BLACK else WHITE
        texts.forEach { choices[b][it] = TextChoice(color, dark[i]) }
    }
    return choices.map { block ->
        val first = block.firstOrNull { it != null }
        block.map { it ?: first }
    }
}

/**
 * The texts to color again from an earlier pick made for the same places ([earlier], by box): texts rebuilt with a
 * copied color, as the home notes are on every notes update. For each text, given as its current color (null for an
 * icon, which is never stale) and box, the earlier choice when the color differs from it. Null when a box is not in
 * the pick: the texts moved and need a new pick.
 */
fun staleTextChoices(earlier: Map<Box, TextChoice>, texts: List<Pair<Int?, Box>>): List<TextChoice?>? =
    texts.map { (color, box) ->
        val choice = earlier[box] ?: return null
        choice.takeIf { color != null && color != it.color }
    }

/**
 * One UI dims the wallpaper in dark mode (measured on the SM-F776B: every sRGB channel x0.78); apps cannot read the
 * amount, so a slightly stronger factor is assumed.
 */
const val NIGHT_WALLPAPER_DIM = 0.75

/**
 * Dimming factors the home wallpaper may be shown with on One UI: none by day or with its "apply dark mode to wallpaper"
 * setting off, [NIGHT_WALLPAPER_DIM] with it on, both when the setting cannot be read.
 */
fun oneUiWallpaperDims(night: Boolean, darkModeWallpaperSetting: Int?): List<Double> = when {
    !night || darkModeWallpaperSetting == 0 -> listOf(1.0)
    darkModeWallpaperSetting == 1 -> listOf(NIGHT_WALLPAPER_DIM)
    else -> listOf(1.0, NIGHT_WALLPAPER_DIM)
}

fun dimmed(rgb: Int, factor: Double): Int {
    fun channel(shift: Int) = (((rgb shr shift) and 0xFF) * factor).roundToInt().coerceIn(0, 255) shl shift
    return (rgb and 0xFF000000.toInt()) or channel(16) or channel(8) or channel(0)
}

/** A rectangle in pixels (screen or image), right/bottom exclusive. */
data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val isEmpty get() = right <= left || bottom <= top
}

/**
 * Maps a screen rectangle onto a wallpaper image of [imageWidth] x [imageHeight] shown on a [screenWidth] x
 * [screenHeight] display the way the system shows a static wallpaper: scaled to fill, centered, the overflow cropped,
 * then zoomed in by [zoom] around the center (AOSP's home screen zoom, see config_wallpaperMaxScale). The app sets
 * images already cropped to the screen, so on that screen without zoom this is the identity.
 */
fun screenToImage(box: Box, screenWidth: Int, screenHeight: Int, imageWidth: Int, imageHeight: Int, zoom: Double = 1.0): Box {
    val scale = max(screenWidth.toDouble() / imageWidth, screenHeight.toDouble() / imageHeight) * zoom
    val dx = (screenWidth - imageWidth * scale) / 2
    val dy = (screenHeight - imageHeight * scale) / 2
    return Box((box.left - dx) / scale, (box.top - dy) / scale, (box.right - dx) / scale, (box.bottom - dy) / scale)
}

/**
 * Average colors of a wallpaper image of [width] x [height] in [cols] x [rows] cells (row-major), for wallpaper [id];
 * [tones] holds TONE_POINTS luminance points per cell (see averageGrid), null in grids stored before they were.
 */
class WallpaperGrid(
    val id: Int,
    val width: Int,
    val height: Int,
    val cols: Int,
    val rows: Int,
    val cells: IntArray,
    val tones: ByteArray? = null,
)

// 30 px cells on a 1080 px wide screen: about the height of a home app name
const val GRID_COLUMNS = 36

fun gridRows(width: Int, height: Int): Int = max(1, (GRID_COLUMNS.toDouble() * height / width).roundToInt())

/**
 * Luminance points kept per grid cell: the quantiles of its pixels' luminance at (i + 0.5) / TONE_POINTS. A cell's
 * average hides texture (a cratered moon averages to a mid grey that no text reads well on); these keep its spread.
 */
const val TONE_POINTS = 4

// A luminance as the sRGB code of the grey with it: fine steps where the eye sees them
private fun toneCode(luminance: Double): Int {
    val i = LINEAR.binarySearch(luminance)
    if (i >= 0) return i
    val above = -i - 1
    return when {
        above == 0 -> 0
        above == 256 -> 255
        luminance - LINEAR[above - 1] < LINEAR[above] - luminance -> above - 1
        else -> above
    }
}

/**
 * Cell averages of an image read one row at a time ([readRow] fills `width` ARGB pixels of row y), so a full-screen
 * bitmap is never copied whole. Averaged in linear light, as the eye blurs it. [tones], when given (TONE_POINTS bytes
 * per cell), receives each cell's luminance points as sRGB-coded greys.
 */
fun averageGrid(
    width: Int,
    height: Int,
    cols: Int,
    rows: Int,
    tones: ByteArray? = null,
    readRow: (y: Int, pixels: IntArray) -> Unit,
): IntArray {
    val sums = Array(3) { DoubleArray(cols * rows) }
    val counts = IntArray(cols * rows)
    val pixels = IntArray(width)
    // Luminance histograms (by tone code) of the cells in the current band of rows
    val histograms = if (tones != null) IntArray(cols * 256) else null
    var band = 0
    fun closeBand() {
        if (tones == null || histograms == null) return
        for (col in 0 until cols) {
            val cell = band * cols + col
            var below = 0
            var code = 0
            for (point in 0 until TONE_POINTS) {
                val target = (point + 0.5) * counts[cell] / TONE_POINTS
                while (code < 255 && below + histograms[col * 256 + code] < target) below += histograms[col * 256 + code++]
                tones[cell * TONE_POINTS + point] = code.toByte()
            }
        }
        histograms.fill(0)
    }
    for (y in 0 until height) {
        readRow(y, pixels)
        val rowBand = min(rows - 1, y * rows / height)
        if (rowBand != band) {
            closeBand()
            band = rowBand
        }
        for (x in 0 until width) {
            val col = min(cols - 1, x * cols / width)
            val cell = rowBand * cols + col
            val p = pixels[x]
            val r = linear(p shr 16)
            val g = linear(p shr 8)
            val b = linear(p)
            sums[0][cell] += r
            sums[1][cell] += g
            sums[2][cell] += b
            counts[cell]++
            if (histograms != null) histograms[col * 256 + toneCode(0.2126 * r + 0.7152 * g + 0.0722 * b)]++
        }
    }
    closeBand()
    return IntArray(cols * rows) { cell ->
        val n = max(1, counts[cell])
        (0xFF shl 24) or (srgbChannel(sums[0][cell] / n) shl 16) or
                (srgbChannel(sums[1][cell] / n) shl 8) or srgbChannel(sums[2][cell] / n)
    }
}

/**
 * Luminance of the image area under [box] (image pixels, clipped to the image), weighted by cell overlap, with the
 * image dimmed by [dim] (see NIGHT_WALLPAPER_DIM); null outside.
 */
fun WallpaperGrid.luminance(box: Box, dim: Double = 1.0): Double? {
    var sum = 0.0
    var area = 0.0
    forEachCell(box) { cell, overlap ->
        sum += relativeLuminance(if (dim == 1.0) cells[cell] else dimmed(cells[cell], dim)) * overlap
        area += overlap
    }
    return if (area > 0) sum / area else null
}

/**
 * The background luminances a text over [box] has to read on (box and [dim] as in luminance): the lower quartile,
 * median and upper quartile of the area's luminance, so dark text is checked on its darker quarter and light text on
 * its lighter one. Estimated from the cells' luminance points weighted by overlap (measured within about 5% of the
 * pixels' quartiles). Only the average for a grid without points; null outside.
 */
fun WallpaperGrid.backgroundLuminances(box: Box, dim: Double = 1.0): List<Double>? {
    val tones = tones ?: return luminance(box, dim)?.let { listOf(it) }
    val points = mutableListOf<Pair<Double, Double>>()
    forEachCell(box) { cell, overlap ->
        for (point in 0 until TONE_POINTS) {
            val code = tones[cell * TONE_POINTS + point].toInt() and 0xFF
            points.add(LINEAR[if (dim == 1.0) code else (code * dim).roundToInt().coerceIn(0, 255)] to overlap)
        }
    }
    if (points.isEmpty()) return null
    points.sortBy { it.first }
    val total = points.sumOf { it.second }
    fun quantile(q: Double): Double {
        var below = 0.0
        return points.firstOrNull { below += it.second; below >= q * total }?.first ?: points.last().first
    }
    return listOf(quantile(0.25), quantile(0.5), quantile(0.75))
}

// Each grid cell under [box] (image pixels, clipped to the image) with the area they share
private inline fun WallpaperGrid.forEachCell(box: Box, action: (cell: Int, overlap: Double) -> Unit) {
    val cellWidth = width.toDouble() / cols
    val cellHeight = height.toDouble() / rows
    val left = box.left.coerceIn(0.0, width.toDouble())
    val right = box.right.coerceIn(0.0, width.toDouble())
    val top = box.top.coerceIn(0.0, height.toDouble())
    val bottom = box.bottom.coerceIn(0.0, height.toDouble())
    if (right <= left || bottom <= top) return
    for (row in (top / cellHeight).toInt()..min(rows - 1, (bottom / cellHeight).toInt())) {
        val h = min(bottom, (row + 1) * cellHeight) - max(top, row * cellHeight)
        if (h <= 0) continue
        for (col in (left / cellWidth).toInt()..min(cols - 1, (right / cellWidth).toInt())) {
            val w = min(right, (col + 1) * cellWidth) - max(left, col * cellWidth)
            if (w > 0) action(row * cols + col, w * h)
        }
    }
}

// Cells as base64 RGB bytes, then their luminance points: about 28 KB for a phone screen
@OptIn(ExperimentalEncodingApi::class)
fun encodeWallpaperGrid(grid: WallpaperGrid): String {
    val bytes = ByteArray(grid.cells.size * 3)
    grid.cells.forEachIndexed { i, cell ->
        bytes[i * 3] = (cell shr 16).toByte()
        bytes[i * 3 + 1] = (cell shr 8).toByte()
        bytes[i * 3 + 2] = cell.toByte()
    }
    val tones = grid.tones?.let { "|" + Base64.encode(it) }.orEmpty()
    return "${grid.id}|${grid.width}|${grid.height}|${grid.cols}|${grid.rows}|" + Base64.encode(bytes) + tones
}

@OptIn(ExperimentalEncodingApi::class)
fun decodeWallpaperGrid(value: String): WallpaperGrid? {
    val parts = value.split("|")
    if (parts.size != 6 && parts.size != 7) return null
    val numbers = parts.take(5).map { it.toIntOrNull() ?: return null }
    val (id, width, height, cols, rows) = numbers
    if (width <= 0 || height <= 0 || cols <= 0 || rows <= 0) return null
    val (bytes, tones) = try {
        Base64.decode(parts[5]) to parts.getOrNull(6)?.let { Base64.decode(it) }
    } catch (e: IllegalArgumentException) {
        return null
    }
    if (bytes.size != cols * rows * 3) return null
    if (tones != null && tones.size != cols * rows * TONE_POINTS) return null
    return WallpaperGrid(id, width, height, cols, rows, IntArray(cols * rows) {
        (0xFF shl 24) or ((bytes[it * 3].toInt() and 0xFF) shl 16) or
                ((bytes[it * 3 + 1].toInt() and 0xFF) shl 8) or (bytes[it * 3 + 2].toInt() and 0xFF)
    }, tones)
}
