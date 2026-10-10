package app.olauncher.sync

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/** Requests, answers and the change stream of the kiosk API, against a MockWebServer. */
class KioskApiTest {

    private val server = MockWebServer().also { it.start() }
    private val api = KioskApi(KioskEndpoint(server.url("/").toString().trimEnd('/'), "tok"))

    @After
    fun close() = server.shutdown()

    @Test
    fun listsCarryTheTokenAndParse() {
        server.enqueue(MockResponse().setBody("""{"lists":[{"id":"a b","name":"Shopping","color":"#fff000"}]}"""))
        assertEquals(KioskCall.Ok(listOf(KioskList("a b", "Shopping"))), runBlocking { api.lists() })
        val request = server.takeRequest()
        assertEquals("/api/sync/lists", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))
    }

    @Test
    fun listIdsArePathEncodedAndRevGoesAlong() {
        server.enqueue(MockResponse().setBody("""{"items":[],"rev":7}"""))
        assertEquals(KioskCall.Ok(ItemsPage(emptyList(), 7)), runBlocking { api.items("a b/c", 5) })
        assertEquals("/api/sync/lists/a%20b%2Fc/items?since=5", server.takeRequest().path)
    }

    @Test
    fun refusalsMapToWhatTheUserCanDo() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(KioskCall.Unauthorized, runBlocking { api.items("l", 0) })
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(KioskCall.ListMissing, runBlocking { api.items("l", 0) })
        // A 404 for the lists themselves is not about a list: it is not the sync API
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(KioskCall.Failed(404), runBlocking { api.lists() })
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(KioskCall.Failed(500), runBlocking { api.push("l", emptyList()) })
        server.enqueue(MockResponse().setBody("<html>not the api</html>"))
        assertEquals(KioskCall.Failed(200), runBlocking { api.lists() })
        // Redirects are not followed: they could lead off the paired host
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://example.com/"))
        assertEquals(KioskCall.Failed(302), runBlocking { api.lists() })
    }

    @Test
    fun aKioskThatIsOffIsUnreachable() {
        val port = ServerSocket(0).use { it.localPort }
        val off = KioskApi(KioskEndpoint("http://127.0.0.1:$port", "tok"))
        assertEquals(KioskCall.Unreachable, runBlocking { off.lists() })
        assertEquals(KioskCall.Unreachable, runBlocking { off.events("l", {}) {} })
    }

    @Test
    fun streamReportsChangesAndEndsWhenTheListIsDeleted() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(": connected\n\nevent: changed\ndata: {\"rev\":5}\n\n: ping\n\nevent: changed\ndata: {\"rev\":6}\n\nevent: deleted\ndata: {}\n\nevent: changed\ndata: {\"rev\":9}\n\n")
        )
        var opened = 0
        var pings = 0
        val revs = mutableListOf<Long?>()
        val result = runBlocking { api.events("l", { opened++ }, { pings++ }) { revs += changedRev(it) } }
        assertEquals(KioskCall.ListMissing, result)
        assertEquals(1, opened)
        assertEquals("the connected comment is no ping", 1, pings)
        assertEquals(listOf<Long?>(5, 6), revs)
        val request = server.takeRequest()
        assertEquals("/api/sync/lists/l/events", request.path)
        assertEquals("text/event-stream", request.getHeader("Accept"))
    }

    @Test
    fun streamThatEndsIsOkAndARefusedOneIsReported() {
        server.enqueue(MockResponse().setBody("event: changed\ndata: {\"rev\":1}\n\n"))
        assertEquals(KioskCall.Ok(Unit), runBlocking { api.events("l", {}) {} })
        server.enqueue(MockResponse().setResponseCode(401))
        var opened = false
        assertEquals(KioskCall.Unauthorized, runBlocking { api.events("l", { opened = true }) {} })
        assertTrue(!opened)
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(KioskCall.Failed(503), runBlocking { api.events("l", {}) {} })
    }

    @Test
    fun sseParserFollowsTheFormat() {
        val parser = SseParser()
        val events = "event: changed\ndata: {\"rev\":\ndata: 3}\n\n: comment\n\ndata:plain\n\nevent: empty\n\nretry: 10\nid: 4\ndata: x\n\n"
            .split("\n").mapNotNull { parser.feed(it) }
        assertEquals(listOf(SseEvent("changed", "{\"rev\":\n3}"), SseEvent("message", "plain"), SseEvent("message", "x")), events)
        assertEquals(null, changedRev(SseEvent("message", "{\"rev\":3}")))
        assertEquals(null, changedRev(SseEvent("changed", "junk")))
        assertEquals(3L, changedRev(SseEvent("changed", "{\"rev\":3}")))
    }
}
