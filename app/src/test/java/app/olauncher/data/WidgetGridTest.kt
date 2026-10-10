package app.olauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetGridTest {

    // Galaxy Z Flip7: 360dp wide screen, wall has 16dp side padding -> 328dp: 70dp wide, 82dp high cells
    private val phone = WidgetGrid.metrics(328f)

    // Providers on the phone (AppWidgetProviderInfo; minResize defaults to min when unset)
    private val tall = WidgetGrid.Provider(352f, 339f, 250f, 140f, resizeHorizontal = true, resizeVertical = true, targetCellWidth = 4, targetCellHeight = 3)
    private val strip = WidgetGrid.Provider(110f, 40f, 110f, 40f, resizeHorizontal = true, targetCellWidth = 2, targetCellHeight = 1)
    private val wide = WidgetGrid.Provider(270f, 110f, 270f, 110f, resizeHorizontal = true, resizeVertical = true)

    @Test
    fun `cells fill the width with gaps and are a quarter of it high`() {
        assertEquals(70f, phone.cellWidth, 0.01f)
        assertEquals(82f, phone.cellHeight, 0.01f)
        assertEquals(328f, phone.width(4), 0.01f)
        assertEquals(156f, phone.width(2), 0.01f)
        assertEquals(180f, phone.height(2), 0.01f)
    }

    @Test
    fun `tall uses its 4x3 target cells`() {
        assertEquals(WidgetGrid.Span(4, 3), WidgetGrid.span(phone, tall))
        assertEquals(3 * 82f + 2 * WidgetGrid.GAP_DP, phone.height(3), 0.01f)
    }

    @Test
    fun `strip is 2x1`() {
        assertEquals(WidgetGrid.Span(2, 1), WidgetGrid.span(phone, strip))
    }

    @Test
    fun `without target cells spans come from the minimum size`() {
        // A 270x110dp widget without target cells: One UI places it 4x2
        assertEquals(WidgetGrid.Span(4, 2), WidgetGrid.span(phone, wide))
    }

    @Test
    fun `width never exceeds the screen`() {
        assertEquals(WidgetGrid.COLUMNS, WidgetGrid.span(phone, WidgetGrid.Provider(600f, 40f, targetCellWidth = 5, targetCellHeight = 1)).x)
    }

    @Test
    fun `target smaller than the minimum resize size is ignored`() {
        val p = WidgetGrid.Provider(300f, 200f, 250f, 140f, resizeHorizontal = true, resizeVertical = true, targetCellWidth = 1, targetCellHeight = 1)
        assertEquals(WidgetGrid.Span(4, 3), WidgetGrid.span(phone, p))
    }

    @Test
    fun `target may go below min size when the widget resizes down to it`() {
        assertEquals(2, WidgetGrid.span(phone, tall.copy(targetCellHeight = 2)).y)
    }

    @Test
    fun `empty provider gets one cell`() {
        assertEquals(WidgetGrid.Span(1, 1), WidgetGrid.span(phone, WidgetGrid.Provider(0f, 0f)))
    }

    @Test
    fun `exact cell multiples do not round up`() {
        assertEquals(1, WidgetGrid.cellsFor(82f, 82f, 16f))
        assertEquals(2, WidgetGrid.cellsFor(180f, 82f, 16f))
        assertEquals(3, WidgetGrid.cellsFor(180.5f, 82f, 16f))
    }

    @Test
    fun `tall resizes from 4x2 up to the full width and the visible rows`() {
        val limits = WidgetGrid.limits(phone, tall, maxRows = 6)
        // minResize 250x140dp: 4 columns (3 are 242dp), 2 rows (180dp)
        assertEquals(WidgetGrid.Limits(minX = 4, maxX = 4, minY = 2, maxY = 6), limits)
        assertFalse(limits.resizesX)
        assertTrue(limits.resizesY)
    }

    @Test
    fun `strip resizes only across, from its 2 cells to the full width`() {
        val limits = WidgetGrid.limits(phone, strip, maxRows = 6)
        assertEquals(WidgetGrid.Limits(minX = 2, maxX = 4, minY = 1, maxY = 1), limits)
        assertTrue(limits.resizesX)
        assertFalse(limits.resizesY)
    }

    @Test
    fun `a provider that does not resize has fixed limits`() {
        val limits = WidgetGrid.limits(phone, WidgetGrid.Provider(140f, 140f, targetCellWidth = 2, targetCellHeight = 2), maxRows = 6)
        assertEquals(WidgetGrid.Limits(2, 2, 2, 2), limits)
        assertFalse(limits.resizesX || limits.resizesY)
    }

    @Test
    fun `max resize size caps the span but never below the default`() {
        val p = WidgetGrid.Provider(70f, 82f, 70f, 82f, maxResizeWidth = 160f, maxResizeHeight = 100f, resizeHorizontal = true, resizeVertical = true)
        // 160dp fits 2 columns (156dp), 100dp fits 1 row
        assertEquals(WidgetGrid.Limits(minX = 1, maxX = 2, minY = 1, maxY = 1), WidgetGrid.limits(phone, p, maxRows = 6))
        val wide = p.copy(targetCellWidth = 3)
        assertEquals(3, WidgetGrid.limits(phone, wide, maxRows = 6).maxX)
    }
}
