package app.olauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruptTest {

    private val key = Interrupt.key("com.example.app", "UserHandle{0}")
    private val now = 1_000_000_000L

    @Test
    fun `key joins package and user handle like hidden apps`() {
        assertEquals("com.example.app|UserHandle{0}", key)
    }

    @Test
    fun `not selected apps are never interrupted`() {
        assertFalse(
            Interrupt.shouldInterrupt(
                selected = emptySet(),
                bypasses = emptyMap(),
                key = key,
                nowMillis = now
            )
        )
    }

    @Test
    fun `selected app without bypass is interrupted`() {
        assertTrue(
            Interrupt.shouldInterrupt(
                selected = setOf(key),
                bypasses = emptyMap(),
                key = key,
                nowMillis = now
            )
        )
    }

    @Test
    fun `bypass within ten minutes skips the pause`() {
        val bypasses = mapOf(key to now - Interrupt.BYPASS_MILLIS + 1)
        assertFalse(Interrupt.shouldInterrupt(setOf(key), bypasses, key, now))
    }

    @Test
    fun `bypass exactly ten minutes old interrupts again`() {
        val bypasses = mapOf(key to now - Interrupt.BYPASS_MILLIS)
        assertTrue(Interrupt.shouldInterrupt(setOf(key), bypasses, key, now))
    }

    @Test
    fun `bypass of another app does not skip the pause`() {
        val other = Interrupt.key("com.other.app", "UserHandle{0}")
        val bypasses = mapOf(other to now)
        assertTrue(Interrupt.shouldInterrupt(setOf(key, other), bypasses, key, now))
    }

    @Test
    fun `toggleSelection adds and removes`() {
        val empty = emptySet<String>()
        val selected = Interrupt.toggleSelection(empty, key)
        assertTrue(key in selected)
        val removed = Interrupt.toggleSelection(selected, key)
        assertFalse(key in removed)
    }

    @Test
    fun `recordBypass stamps the key and keeps fresh entries`() {
        val fresh = mapOf(
            "other|UserHandle{0}" to now - 1000,
            "expired|UserHandle{0}" to now - Interrupt.BYPASS_MILLIS,
        )
        val updated = Interrupt.recordBypass(fresh, key, now)
        assertEquals(now, updated[key])
        assertTrue("other|UserHandle{0}" in updated)
        assertFalse("expired|UserHandle{0}" in updated)
    }

    @Test
    fun `bypass entries survive serialize and parse round trip`() {
        val bypasses = mapOf(
            key to now,
            "com.other.app|UserHandle{10}" to now - 5000,
        )
        val parsed = Interrupt.parseBypasses(Interrupt.serializeBypasses(bypasses))
        assertEquals(bypasses, parsed)
    }

    @Test
    fun `parseBypasses drops malformed entries`() {
        val parsed = Interrupt.parseBypasses(
            setOf("no-timestamp-suffix", "|123", "pkg|UserHandle{0}|not-a-number")
        )
        assertTrue(parsed.isEmpty())
    }
}
