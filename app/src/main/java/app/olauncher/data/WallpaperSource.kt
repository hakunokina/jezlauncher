package app.olauncher.data

import android.annotation.SuppressLint
import com.google.android.material.color.utilities.Hct
import org.json.JSONArray
import org.json.JSONObject
import java.net.MalformedURLException
import java.net.URI
import java.net.URL
import java.util.Calendar

/** Largest wallpaper image the app downloads. */
const val MAX_WALLPAPER_BYTES = 25L * 1024 * 1024

/**
 * Largest power of two the image can be scaled down by and still cover [reqWidth] x [reqHeight]; 1 when the size is
 * unknown. Full size images can be thousands of pixels wide and would not fit in memory decoded as they are.
 */
fun coverSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
    if (width <= 0 || height <= 0 || reqWidth <= 0 || reqHeight <= 0) return 1
    var sample = 1
    while (width / (sample * 2) >= reqWidth && height / (sample * 2) >= reqHeight) sample *= 2
    return sample
}

/** Only https URLs are accepted for the JSON source and for the images it points to. */
fun isHttpsUrl(url: String): Boolean = try {
    val uri = URI(url.trim())
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty()
} catch (e: Exception) {
    false
}

/**
 * The URL of every network call outside the kiosk sync, https only: kiosk sync builds permit cleartext app-wide for
 * the paired tailnet host (KioskEndpoint), so this keeps everything else off plain http. Throws for anything else.
 */
fun httpsUrl(url: String?): URL {
    if (url == null || !isHttpsUrl(url)) throw MalformedURLException("https only")
    return URL(url.trim())
}

/**
 * Picks the image URL from a wallpaper source JSON: `{"url": "...", "dark": "...", "date": "..."}`.
 * Dark themes use `dark` when it is a valid https URL, otherwise `url`. Unknown fields are ignored.
 * Returns null when the body is not JSON or has no usable https `url`.
 */
fun pickWallpaperUrl(body: String, wallType: String): String? {
    val json = try {
        JSONObject(body)
    } catch (e: Exception) {
        return null
    }
    val url = (json.opt("url") as? String)?.trim()?.takeIf { isHttpsUrl(it) } ?: return null
    if (wallType != Constants.WALL_TYPE_DARK) return url
    return (json.opt("dark") as? String)?.trim()?.takeIf { isHttpsUrl(it) } ?: url
}

/** Custom feed: the pool follows the local hour only; the app theme and the system dark mode do not count. */
fun wallTypeAtHour(hour: Int): String =
    if (hour >= Constants.NIGHT_START_HOUR || hour < Constants.NIGHT_END_HOUR) Constants.WALL_TYPE_DARK
    else Constants.WALL_TYPE_LIGHT

fun wallTypeAt(now: Calendar): String = wallTypeAtHour(now.get(Calendar.HOUR_OF_DAY))

/** Time from [now] to the next pool switch (NIGHT_END_HOUR or NIGHT_START_HOUR sharp) in [now]'s time zone; never zero. */
fun millisToNextWallTypeSwitch(now: Calendar): Long {
    val next = now.clone() as Calendar
    next.set(Calendar.MINUTE, 0)
    next.set(Calendar.SECOND, 0)
    next.set(Calendar.MILLISECOND, 0)
    val hour = now.get(Calendar.HOUR_OF_DAY)
    when {
        hour < Constants.NIGHT_END_HOUR -> next.set(Calendar.HOUR_OF_DAY, Constants.NIGHT_END_HOUR)
        hour < Constants.NIGHT_START_HOUR -> next.set(Calendar.HOUR_OF_DAY, Constants.NIGHT_START_HOUR)
        else -> {
            next.add(Calendar.DAY_OF_MONTH, 1)
            next.set(Calendar.HOUR_OF_DAY, Constants.NIGHT_END_HOUR)
        }
    }
    return next.timeInMillis - now.timeInMillis
}

/**
 * A parsed wallpaper source for one mode. [dayKey] identifies the mode's image of the day (mode + that image's
 * url) and is how the daily job detects a new day or a mode switch, [wallpaperUrl] is that image,
 * [items] is the optional https pool of the mode for manual changes.
 */
class WallpaperFeed(val dayKey: String, val wallpaperUrl: String, val items: List<String>)

/**
 * Light mode: `url` and `items`. Dark mode: `dark` and `dark_items`; each falls back to the light one when
 * it is missing or unusable, so older feeds keep working.
 */
fun parseWallpaperFeed(body: String, wallType: String): WallpaperFeed? {
    val wallpaperUrl = pickWallpaperUrl(body, wallType) ?: return null
    val json = try {
        JSONObject(body)
    } catch (e: Exception) {
        return null
    }
    var items = httpsList(json.optJSONArray("items"))
    if (wallType == Constants.WALL_TYPE_DARK)
        items = httpsList(json.optJSONArray("dark_items")).ifEmpty { items }
    return WallpaperFeed(feedDayKey(wallType, wallpaperUrl), wallpaperUrl, items)
}

private fun httpsList(array: JSONArray?): List<String> = if (array == null) emptyList() else (0 until array.length())
    .mapNotNull { (array.opt(it) as? String)?.trim()?.takeIf { url -> isHttpsUrl(url) } }
    .distinct()

fun feedDayKey(wallType: String, wallpaperUrl: String): String = "$wallType|$wallpaperUrl"

/** True when the stored day key was recorded in a different mode than [wallType] (or nothing was recorded). */
fun isOtherMode(storedDayKey: String, wallType: String): Boolean = !storedDayKey.startsWith("$wallType|")

/**
 * The image after [current] in [items], wrapping to the first one; the first one when [current] is not in
 * the list. Null when there is nothing to switch to (fewer than two items).
 */
fun nextWallpaperUrl(items: List<String>, current: String): String? {
    if (items.size < 2) return null
    val index = items.indexOf(current)
    return if (index < 0) items[0] else items[(index + 1) % items.size]
}

/**
 * The daily job applies the image only when the day key differs from the last one it has seen. The key holds
 * the mode, so a manual pick survives until the feed's next image of that mode or the next pool switch (05:00/19:00).
 */
fun isNewFeedDay(dayKey: String, lastSeenDayKey: String): Boolean = dayKey != lastSeenDayKey

/** Perceived brightness above one half; used on API 27-30 where the wallpaper's dark-text hint is not public. */
fun isLightRgb(rgb: Int): Boolean {
    val r = (rgb shr 16) and 0xFF
    val g = (rgb shr 8) and 0xFF
    val b = rgb and 0xFF
    return (0.299 * r + 0.587 * g + 0.114 * b) / 255 > 0.5
}

/**
 * Home text color from the wallpaper's own colors when the image behind the texts is unknown (a wallpaper the app did
 * not set; see pickTextColors for the measured way): the shifted hue of the first of [wallpaperColors] (most common
 * first) that is not grey, at the preferred dark tone when the wallpaper wants dark text and the light one otherwise.
 * Null when every color is grey, so the caller keeps black/white.
 */
@SuppressLint("RestrictedApi") // Material's bundled color utilities; there is no official standalone artifact
fun wallpaperTextColor(darkText: Boolean, wallpaperColors: List<Int>): Int? {
    val seed = textSeed(wallpaperColors) ?: return null
    val tone = if (darkText) DARK_TEXT_TONE else LIGHT_TEXT_TONE
    return Hct.from(textHue(seed.hue, darkText), textChroma(seed, darkText), tone).toInt()
}

/** Colors of a wallpaper image the app set: the wallpaper [id], WallpaperColors hints and its colors (most common first). */
class StoredWallpaperColors(val id: Int, val hints: Int, val colors: List<Int>)

fun encodeWallpaperColors(id: Int, hints: Int, colors: List<Int>): String = "$id|$hints|" + colors.joinToString(",")

fun decodeWallpaperColors(value: String): StoredWallpaperColors? {
    val parts = value.split("|")
    if (parts.size != 3) return null
    val id = parts[0].toIntOrNull() ?: return null
    val hints = parts[1].toIntOrNull() ?: return null
    val colors = parts[2].split(",").map { it.toIntOrNull() ?: return null }
    return if (colors.isEmpty()) null else StoredWallpaperColors(id, hints, colors)
}
