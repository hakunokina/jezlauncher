package app.olauncher.data

import app.olauncher.data.BottomBar.IconSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomBarTest {

    @Test
    fun `slot count is coerced to 0-5`() {
        assertEquals(0, BottomBar.coerceSlotCount(0))
        assertEquals(3, BottomBar.coerceSlotCount(3))
        assertEquals(5, BottomBar.coerceSlotCount(5))
        assertEquals(0, BottomBar.coerceSlotCount(-1))
        assertEquals(5, BottomBar.coerceSlotCount(8))
    }

    @Test
    fun `only slots within the bar count are visible`() {
        assertFalse(BottomBar.isSlotVisible(0, 1))
        assertTrue(BottomBar.isSlotVisible(3, 1))
        assertTrue(BottomBar.isSlotVisible(3, 3))
        assertFalse(BottomBar.isSlotVisible(3, 4))
        assertFalse(BottomBar.isSlotVisible(3, 0))
        assertTrue(BottomBar.isSlotVisible(8, 5))
        assertFalse(BottomBar.isSlotVisible(8, 6))
    }

    @Test
    fun `monochrome layer is used only on API 33+ when present`() {
        assertEquals(IconSource.MONOCHROME, BottomBar.decideIconSource(33, true))
        assertEquals(IconSource.MONOCHROME, BottomBar.decideIconSource(36, true))
        assertEquals(IconSource.GRAYSCALE, BottomBar.decideIconSource(33, false))
        assertEquals(IconSource.GRAYSCALE, BottomBar.decideIconSource(32, true))
        assertEquals(IconSource.GRAYSCALE, BottomBar.decideIconSource(24, false))
    }

    @Test
    fun `slot serializes and parses the package activity user triple`() {
        val slot = BottomBar.Slot("com.example.app", "com.example.app.Main", "UserHandle{10}")
        val parsed = BottomBar.Slot.parse(slot.serialize())
        assertEquals(slot, parsed)
        assertFalse(parsed.isEmpty())
    }

    @Test
    fun `empty and malformed slot strings parse to an empty slot`() {
        assertTrue(BottomBar.Slot.parse(null).isEmpty())
        assertTrue(BottomBar.Slot.parse("").isEmpty())
        assertTrue(BottomBar.Slot.parse("com.example.app").isEmpty().not())
        assertEquals("com.example.app", BottomBar.Slot.parse("com.example.app").packageName)
    }

    @Test
    fun `blank package means empty slot`() {
        assertTrue(BottomBar.Slot().isEmpty())
        assertTrue(BottomBar.Slot("", "Some.Activity", "UserHandle{0}").isEmpty())
    }
}
