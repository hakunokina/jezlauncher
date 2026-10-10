package app.olauncher.sync

import androidx.annotation.VisibleForTesting
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder

/**
 * The paired kiosk: [base] is `scheme://host:port` of its companion server, [token] the bearer token from its pairing
 * QR code. This is the one gate for the kiosk's plain http (kiosk sync builds allow cleartext app-wide, see
 * network_security_config_kiosk): an endpoint exists only for a tailnet host ([isTailnetHost]), plain http only for a
 * tailnet IP ([isTailnetIp]), and [KioskApi] sends requests only to URLs it [owns].
 */
class KioskEndpoint @VisibleForTesting internal constructor(val base: String, val token: String) {

    fun url(path: String) = base + path

    /** Whether [url] goes to this kiosk: same scheme, host and port, nothing else. */
    fun owns(url: String): Boolean {
        if (!url.startsWith("$base/")) return false
        val target = parseUri(url) ?: return false
        val own = parseUri(base) ?: return false
        return target.scheme == own.scheme && target.rawAuthority == own.rawAuthority
    }

    /** Never the token. */
    override fun toString() = base

    override fun equals(other: Any?) = other is KioskEndpoint && other.base == base && other.token == token

    override fun hashCode() = 31 * base.hashCode() + token.hashCode()

    companion object {
        /**
         * From the kiosk's pairing link `http://<host>:<port>/#t=<token>` (its QR code, or pasted); the token rides in
         * the fragment, which never goes over the wire. Null for any other link, and for a host outside the tailnet.
         */
        fun fromLink(link: String): KioskEndpoint? {
            val uri = parseUri(link.trim()) ?: return null
            val token = uri.rawFragment.orEmpty().split('&')
                .firstOrNull { it.startsWith("t=") }
                ?.let { decode(it.removePrefix("t=")) }
                ?: return null
            return create(uri, token)
        }

        /** From the stored pairing; null when it does not pass the same checks. */
        fun restore(base: String, token: String): KioskEndpoint? {
            val uri = parseUri(base) ?: return null
            if (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) return null
            return create(uri, token)
        }

        private fun create(uri: URI, token: String): KioskEndpoint? {
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (uri.rawUserInfo != null) return null
            val host = uri.host?.lowercase() ?: return null
            if (!isTailnetHost(host)) return null
            // A MagicDNS name resolves in public DNS too (Funnel): over plain http the token could leave the tailnet
            if (scheme == "http" && !isTailnetIp(host)) return null
            // Goes into a header: printable ASCII only, so it cannot break the request
            if (token.isEmpty() || !token.all { it in '!'..'~' }) return null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return KioskEndpoint("$scheme://$host$port", token)
        }

        private fun decode(value: String): String? = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/** Tailscale addresses ([isTailnetIp]) and MagicDNS names under ts.net. LAN, public and loopback addresses are not. */
fun isTailnetHost(host: String): Boolean {
    val name = host.lowercase().trimEnd('.')
    return isTailnetIp(name) ||
            name.endsWith(".ts.net") && name.removeSuffix(".ts.net").isNotEmpty() && name.all { it.isLetterOrDigit() || it in ".-" }
}

/** An IP literal Tailscale hands out: IPv4 in 100.64.0.0/10, IPv6 in fd7a:115c:a1e0::/48 (bracketed or not). */
fun isTailnetIp(host: String): Boolean {
    val name = host.lowercase().removeSurrounding("[", "]")
    IPV4.matchEntire(name)?.let { match ->
        val parts = match.groupValues.drop(1).map { it.toInt() }
        return parts.all { it in 0..255 } && parts[0] == 100 && parts[1] in 64..127
    }
    // A host with ':' can only be an IPv6 literal, so this parses it without a DNS lookup
    if (':' !in name || '%' in name || !name.all { it.isDigit() || it in "abcdef:." }) return false
    val address = try {
        InetAddress.getByName(name) as? Inet6Address
    } catch (e: Exception) {
        null
    } ?: return false
    return address.address.take(6) == TAILNET_V6_PREFIX
}

/**
 * The endpoint the sync uses: none in builds without kiosk sync ([enabled] is BuildConfig.KIOSK_SYNC) or while not
 * paired, so no sync work, stream or request starts then.
 */
fun activeEndpoint(enabled: Boolean, base: String?, token: String?): KioskEndpoint? =
    if (!enabled || base.isNullOrEmpty() || token.isNullOrEmpty()) null else KioskEndpoint.restore(base, token)

private fun parseUri(text: String): URI? = try {
    URI(text)
} catch (e: Exception) {
    null
}

// No leading zeros: some resolvers read those as octal
private val IPV4 = Regex("""(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})""")
private val TAILNET_V6_PREFIX = listOf(0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0).map { it.toByte() }
