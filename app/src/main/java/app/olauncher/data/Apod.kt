package app.olauncher.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The latest APOD posts on science.nasa.gov (WordPress REST, newest first) with their featured image. APOD moved there
 * from apod.nasa.gov, and the api.nasa.gov APOD API now answers every date with a NASA logo. Not an official API, so
 * [pickApodImage] reads it defensively.
 */
const val APOD_POSTS_URL = "https://science.nasa.gov/wp-json/wp/v2/image-article" +
        "?search=APOD&per_page=10&_embed=wp:featuredmedia&_fields=date,slug,link,title,content,_links,_embedded"

/** Smallest short side, in pixels, of an image worth setting as a phone wallpaper. */
const val APOD_MIN_SHORT_SIDE = 1080

/** Day key of an answered APOD request made on [today]; the daily job asks at most once per local day. */
fun apodDayKey(today: Calendar): String =
    "apod|" + SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = today.timeZone }.format(today.time)

/** A usable APOD: the image to set and its attribution ([title], [credit], [pageUrl]). */
data class ApodImage(val imageUrl: String, val title: String, val credit: String, val pageUrl: String)

/**
 * The newest post that is an image the app may set: no "copyright" or © anywhere in the post (APOD marks copyrighted
 * images with "Credit & Copyright"), a credit line, no video, an https jpeg/png/webp of at least APOD_MIN_SHORT_SIDE
 * on its short side, at most twice as wide as high, and not over MAX_WALLPAPER_BYTES. Null when the body is not
 * such a list or nothing qualifies; anything unexpected in a post only skips that post.
 */
fun pickApodImage(body: String): ApodImage? {
    val posts = try {
        JSONArray(body)
    } catch (e: Exception) {
        return null
    }
    return (0 until posts.length())
        .mapNotNull { posts.optJSONObject(it) }
        .mapNotNull { post -> apodImageOf(post)?.let { post.optString("date") to it } }
        .maxByOrNull { it.first }
        ?.second
}

private fun apodImageOf(post: JSONObject): ApodImage? {
    if (!post.optString("slug").startsWith("apod-")) return null
    val pageUrl = post.optString("link").trim().takeIf { isHttpsUrl(it) } ?: return null
    val content = post.optJSONObject("content")?.optString("rendered").orEmpty()
    val text = htmlText(content)
    if (text.contains("copyright", ignoreCase = true) || text.contains('©')) return null
    if (content.contains("<iframe", ignoreCase = true) || content.contains("<video", ignoreCase = true)) return null
    val credit = apodCredit(text) ?: return null

    val media = post.optJSONObject("_embedded")?.optJSONArray("wp:featuredmedia")?.optJSONObject(0) ?: return null
    if (media.optString("media_type") != "image") return null
    if (media.optString("mime_type") !in setOf("image/jpeg", "image/png", "image/webp")) return null
    val details = media.optJSONObject("media_details") ?: return null
    // Numbers can come as strings (filesize does)
    val width = details.opt("width")?.toString()?.toIntOrNull() ?: return null
    val height = details.opt("height")?.toString()?.toIntOrNull() ?: return null
    if (minOf(width, height) < APOD_MIN_SHORT_SIDE || width > 2 * height) return null
    val size = details.opt("filesize")?.toString()?.toLongOrNull()
    if (size != null && size > MAX_WALLPAPER_BYTES) return null
    val imageUrl = media.optString("source_url").trim().takeIf { isHttpsUrl(it) } ?: return null

    val title = htmlText(post.optJSONObject("title")?.optString("rendered").orEmpty())
        .replace(Regex("""^APOD:\s*\d{4}\s+\p{L}+\s+\d{1,2}\s*[–—-]\s*"""), "")
    return ApodImage(imageUrl, title, credit, pageUrl)
}

/**
 * The credit of a post as plain text: what follows "Credit:" (or "Credit & License") up to "Authors & editors", with
 * a repeated "Image Credit:" dropped. Null when there is no such line or it is a video's.
 */
fun apodCredit(text: String): String? {
    val match = Regex("""(\p{L}+\s+)?Credit(\s*&\s*\p{L}+)?\s*:?\s+(.+?)\s+Authors\s*&\s*editors""", RegexOption.IGNORE_CASE)
        .find(text) ?: return null
    if (match.groupValues[1].trim().equals("video", ignoreCase = true)) return null
    val credit = match.groupValues[3]
        .replace(Regex("""^(Image\s+)?Credit\s*:\s*""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+([,.;)])"""), "$1")
        .replace(Regex("""\(\s+"""), "(")
        .trim()
    return credit.ifEmpty { null }
}

/** Text of an HTML fragment: tags dropped, the usual entities decoded, white space collapsed. */
fun htmlText(html: String): String {
    val text = html.replace(Regex("""<[^>]*>"""), " ")
        .replace(Regex("""&#(\d+);""")) { it.groupValues[1].toIntOrNull()?.let { code -> String(Character.toChars(code)) } ?: it.value }
        .replace(Regex("""&#[xX]([0-9a-fA-F]+);""")) { it.groupValues[1].toIntOrNull(16)?.let { code -> String(Character.toChars(code)) } ?: it.value }
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&copy;", "©")
        .replace("&amp;", "&")
    return text.replace(Regex("""\s+"""), " ").trim()
}
