package app.olauncher.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** How a call to the kiosk went. */
sealed interface KioskCall<out T> {
    data class Ok<T>(val value: T) : KioskCall<T>

    /** No answer: the kiosk is off, Tailscale is down or there is no network. Changes wait and go later. */
    data object Unreachable : KioskCall<Nothing>

    /** The token no longer works (401/403): pair again. */
    data object Unauthorized : KioskCall<Nothing>

    /** The list is gone from the kiosk (404, or deleted while streaming): choose another. */
    data object ListMissing : KioskCall<Nothing>

    /** Any other refusal, or an answer that is not the sync API's. */
    data class Failed(val code: Int) : KioskCall<Nothing>
}

/** A call that did not go through, as a call of any type. */
fun KioskCall<*>.asFailure(): KioskCall<Nothing> = when (this) {
    is KioskCall.Ok -> KioskCall.Failed(HttpURLConnection.HTTP_OK)
    KioskCall.Unreachable -> KioskCall.Unreachable
    KioskCall.Unauthorized -> KioskCall.Unauthorized
    KioskCall.ListMissing -> KioskCall.ListMissing
    is KioskCall.Failed -> this
}

/** One server-sent event: [type] from `event:` ("message" without one) and its `data:` lines joined. */
data class SseEvent(val type: String, val data: String)

/** Reads a text/event-stream line by line; comments (`: ping`) and other fields are skipped. */
class SseParser {
    private var type: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** Feeds one line without its line break; returns the event an empty line completes. */
    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) {
            val event = if (hasData) SseEvent(type ?: "message", data.toString()) else null
            type = null
            data.setLength(0)
            hasData = false
            return event
        }
        if (line.startsWith(":")) return null
        val field = line.substringBefore(':')
        val value = if (':' in line) line.substringAfter(':').removePrefix(" ") else ""
        when (field) {
            "event" -> type = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }
}

/** The new rev of a `changed` event, null for any other event. */
fun changedRev(event: SseEvent): Long? = if (event.type != "changed") null else try {
    JSONObject(event.data).getLong("rev")
} catch (e: Exception) {
    null
}

/**
 * The kiosk's sync API (OpenSkyLight companion server): lists, item deltas by rev, pushes settled by last writer wins,
 * and a change stream. Every request carries the bearer token and goes only to the paired [endpoint].
 */
class KioskApi(private val endpoint: KioskEndpoint) {

    suspend fun lists(): KioskCall<List<KioskList>> = call("GET", "/api/sync/lists", listScoped = false, parse = ::parseKioskLists)

    /** Items changed after [since] (every item, tombstones too, for 0) and the kiosk's rev now. */
    suspend fun items(listId: String, since: Long): KioskCall<ItemsPage> =
        call("GET", "${listPath(listId)}/items?since=$since", parse = ::parseItemsPage)

    suspend fun push(listId: String, items: List<SyncItem>): KioskCall<PushResult> =
        call("POST", "${listPath(listId)}/items", body = pushBody(items), parse = ::parsePushResult)

    /**
     * Follows the list's change stream until it ends or the caller is cancelled: [onOpen] once connected, [onPing] for
     * each keep-alive comment, [onEvent] for each event. Returns [KioskCall.ListMissing] when the kiosk says the list
     * was deleted, [KioskCall.Ok] when the stream just ended.
     */
    suspend fun events(
        listId: String,
        onOpen: () -> Unit,
        onPing: () -> Unit = {},
        onEvent: (SseEvent) -> Unit,
    ): KioskCall<Unit> = coroutineScope {
        val connection = try {
            open("GET", "${listPath(listId)}/events", STREAM_READ_TIMEOUT_MS)
        } catch (e: IOException) {
            return@coroutineScope KioskCall.Unreachable
        }
        connection.setRequestProperty("Accept", "text/event-stream")
        // A blocking read stops only when the connection closes; close it when the caller goes
        val closer = launch {
            try {
                awaitCancellation()
            } finally {
                connection.disconnect()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) return@withContext refusal(code, listScoped = true)
                onOpen()
                val parser = SseParser()
                connection.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith(":") && line.drop(1).trim() == "ping") onPing()
                        val event = parser.feed(line) ?: continue
                        if (event.type == "deleted") return@withContext KioskCall.ListMissing
                        onEvent(event)
                    }
                }
                KioskCall.Ok(Unit)
            }
        } catch (e: IOException) {
            KioskCall.Unreachable
        } finally {
            closer.cancel()
            connection.disconnect()
        }
    }

    private suspend fun <T> call(
        method: String,
        path: String,
        body: String? = null,
        listScoped: Boolean = true,
        parse: (String) -> T?,
    ): KioskCall<T> = withContext(Dispatchers.IO) {
        try {
            val connection = open(method, path, READ_TIMEOUT_MS)
            try {
                connection.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) return@withContext refusal(code, listScoped)
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                parse(text)?.let { KioskCall.Ok(it) } ?: KioskCall.Failed(code)
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            KioskCall.Unreachable
        }
    }

    private fun open(method: String, path: String, readTimeoutMs: Int): HttpURLConnection {
        val url = endpoint.url(path)
        if (!endpoint.owns(url)) throw IOException("Not the paired kiosk")
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            // A redirect could lead off the paired host
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Authorization", "Bearer ${endpoint.token}")
        }
    }

    private fun refusal(code: Int, listScoped: Boolean): KioskCall<Nothing> = when {
        code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN -> KioskCall.Unauthorized
        code == HttpURLConnection.HTTP_NOT_FOUND && listScoped -> KioskCall.ListMissing
        else -> KioskCall.Failed(code)
    }

    private fun listPath(listId: String) = "/api/sync/lists/" + URLEncoder.encode(listId, "UTF-8").replace("+", "%20")

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000

        /** The kiosk pings every 30 s; two missed pings mean the stream is dead. */
        const val STREAM_READ_TIMEOUT_MS = 75_000
    }
}
