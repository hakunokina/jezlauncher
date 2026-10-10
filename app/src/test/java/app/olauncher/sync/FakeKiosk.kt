package app.olauncher.sync

import app.olauncher.data.Note
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject

/**
 * The kiosk's sync API as OpenSkyLight's companion server answers it (one list, rev deltas, last writer wins on
 * updatedAt with ties kept, required fields, ids of other lists ignored), on a local MockWebServer. The endpoint skips
 * the tailnet check, which a test server on localhost cannot pass.
 */
class FakeKiosk : Dispatcher() {

    private val server = MockWebServer().apply { dispatcher = this@FakeKiosk }.also { it.start() }
    val endpoint = KioskEndpoint(server.url("/").toString().trimEnd('/'), TOKEN)

    var token = TOKEN

    var listGone = false

    /** Ids that live in another list of the kiosk: a push ignores them. */
    val foreignIds = mutableSetOf<String>()

    /** Runs once, inside the next push, before the kiosk looks at it. */
    var beforePush: () -> Unit = {}

    /** Requests the kiosk answered so far. */
    val requestCount get() = server.requestCount

    /** The items field of every push. */
    val pushes = mutableListOf<JSONArray>()

    private val items = linkedMapOf<String, JSONObject>()
    private val revs = mutableMapOf<String, Long>()
    var rev = 0L
        private set

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.getHeader("Authorization") != "Bearer $token") return answer(401, """{"ok":false}""")
        val url = request.requestUrl!!
        if (url.encodedPath == "/api/sync/lists") return answer(200, """{"lists":[{"id":"$LIST","name":"Groceries","color":"#22aa66"}]}""")
        if (listGone || url.encodedPath != "/api/sync/lists/$LIST/items") return answer(404, """{"ok":false}""")
        return when (request.method) {
            "GET" -> {
                val since = url.queryParameter("since")!!.toLong()
                val changed = items.values.filter { since == 0L || revs.getValue(it.getString("id")) > since }
                    .sortedBy { revs.getValue(it.getString("id")) }
                answer(200, JSONObject().put("items", JSONArray(changed.map(::copy))).put("rev", rev).toString())
            }
            "POST" -> push(JSONObject(request.body.readUtf8()).getJSONArray("items"))
            else -> answer(405, "{}")
        }
    }

    private fun push(incoming: JSONArray): MockResponse {
        beforePush().also { beforePush = {} }
        pushes += incoming
        val rejected = JSONArray()
        val ignored = JSONArray()
        for (i in 0 until incoming.length()) {
            val item = incoming.getJSONObject(i)
            if (FIELDS.any { !item.has(it) } || item.getString("text").isBlank()) return answer(400, """{"ok":false}""")
            val id = item.getString("id")
            if (id in foreignIds) {
                ignored.put(id)
                continue
            }
            val stored = items[id]
            if (stored != null && time(item, "updatedAt") <= time(stored, "updatedAt")) {
                rejected.put(copy(stored))
                continue
            }
            if (stored != null) item.put("createdAt", stored.getString("createdAt"))
            store(item)
        }
        val body = JSONObject().put("rev", rev).put("rejected", rejected)
        if (ignored.length() > 0) body.put("ignored", ignored)
        return answer(200, body.toString())
    }

    /** An item added on the kiosk's own screen. */
    @Synchronized
    fun add(id: String, text: String, at: Long, sortOrder: Int = items.size) = store(
        JSONObject().put("id", id).put("text", text).put("checked", false).put("checkedAt", JSONObject.NULL)
            .put("sortOrder", sortOrder).put("createdAt", formatIsoInstant(at)).put("updatedAt", formatIsoInstant(at))
            .put("deletedAt", JSONObject.NULL).put("meta", JSONObject.NULL)
    )

    /** A change made on the kiosk's own screen at [at]. */
    @Synchronized
    fun edit(id: String, at: Long, change: JSONObject.() -> Unit) =
        store(copy(items.getValue(id)).apply(change).put("updatedAt", formatIsoInstant(at)))

    @Synchronized
    fun check(id: String, at: Long) = edit(id, at) { put("checked", true).put("checkedAt", formatIsoInstant(at)) }

    @Synchronized
    fun delete(id: String, at: Long) = edit(id, at) { put("deletedAt", formatIsoInstant(at)) }

    @Synchronized
    fun item(id: String): JSONObject? = items[id]?.let(::copy)

    /** Texts of the items that are not deleted. */
    @Synchronized
    fun texts(): List<String> = items.values.filter { it.isNull("deletedAt") }.map { it.getString("text") }

    @Synchronized
    fun itemOf(note: Note) = item(note.syncId)

    fun close() = server.shutdown()

    private fun store(item: JSONObject) {
        rev++
        items[item.getString("id")] = item
        revs[item.getString("id")] = rev
    }

    private fun time(item: JSONObject, name: String) = parseIsoInstant(item.getString(name))!!

    private fun copy(item: JSONObject) = JSONObject(item.toString())

    private fun answer(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    companion object {
        const val TOKEN = "test-token"
        const val LIST = "list-1"
        private val FIELDS = listOf("id", "text", "checked", "checkedAt", "sortOrder", "createdAt", "updatedAt", "deletedAt", "meta")
    }
}
