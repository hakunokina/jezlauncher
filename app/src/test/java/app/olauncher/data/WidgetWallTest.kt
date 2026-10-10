package app.olauncher.data

import app.olauncher.data.WidgetWall.Cell
import app.olauncher.data.WidgetWall.Edge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WidgetWallTest {

    private fun assertNoOverlap(cells: List<Cell>) {
        cells.forEachIndexed { i, a ->
            cells.drop(i + 1).forEach { b -> assertFalse("$a overlaps $b", a.overlaps(b)) }
        }
    }

    // Layout and ids

    @Test
    fun `serialize and parse round trip keeps cells and order`() {
        val cells = listOf(Cell(7, 0, 0, 4, 3), Cell(3, 0, 3, 2, 1), Cell(12, 2, 3, 2, 2))
        assertEquals(cells, WidgetWall.parse(WidgetWall.serialize(cells)))
    }

    @Test
    fun `parse drops broken entries, cells off the grid and repeated ids`() {
        assertEquals(emptyList<Cell>(), WidgetWall.parse(null))
        assertEquals(emptyList<Cell>(), WidgetWall.parse(""))
        val raw = "4:0:0:2:1,x,5:1:2,6:3:0:2:1,7:0:-1:1:1,8:0:1:0:1,4:2:0:2:1, 9 : 2 : 1 : 2 : 2 "
        assertEquals(listOf(Cell(4, 0, 0, 2, 1), Cell(9, 2, 1, 2, 2)), WidgetWall.parse(raw))
    }

    @Test
    fun `legacy ids parse ignores blanks garbage and duplicates`() {
        assertEquals(emptyList<Int>(), WidgetWall.parseIds(null))
        assertEquals(listOf(4, 9), WidgetWall.parseIds("4,x, 9 ,4,"))
    }

    @Test
    fun `cells overlap only when they share a cell`() {
        val a = Cell(1, 0, 0, 2, 2)
        assertEquals(true, a.overlaps(Cell(2, 1, 1, 2, 2)))
        assertEquals(false, a.overlaps(Cell(2, 2, 0, 2, 2)))
        assertEquals(false, a.overlaps(Cell(2, 0, 2, 4, 1)))
    }

    // Adding

    @Test
    fun `add takes the first free cells from the top`() {
        val cells = listOf(Cell(1, 0, 0, 2, 2), Cell(2, 0, 2, 4, 1))
        assertEquals(Cell(3, 2, 0, 2, 1), WidgetWall.add(cells, 3, 2, 1).last())
        assertEquals(Cell(3, 2, 0, 2, 2), WidgetWall.add(cells, 3, 2, 2).last())
        // 3 high does not fit beside the 2x2 above the 4x1 row
        assertEquals(Cell(3, 0, 3, 2, 3), WidgetWall.add(cells, 3, 2, 3).last())
    }

    @Test
    fun `add on an empty wall starts top left and keeps the width on the grid`() {
        assertEquals(listOf(Cell(1, 0, 0, 4, 3)), WidgetWall.add(emptyList(), 1, 4, 3))
        assertEquals(listOf(Cell(1, 0, 0, 4, 1)), WidgetWall.add(emptyList(), 1, 6, 1))
    }

    @Test
    fun `remove drops only that widget`() {
        val cells = listOf(Cell(1, 0, 0, 2, 2), Cell(2, 2, 0, 2, 2))
        assertEquals(listOf(Cell(2, 2, 0, 2, 2)), WidgetWall.remove(cells, 1))
        assertEquals(cells, WidgetWall.remove(cells, 9))
    }

    // Migration from the list wall (WIDGET_IDS)

    // The phone's wall before the grid: a tall, a wide, a strip and a square widget, one below the other
    private val phoneSpans = mapOf(
        11 to WidgetGrid.Span(4, 3), // Tall
        14 to WidgetGrid.Span(4, 2), // Wide
        17 to WidgetGrid.Span(2, 1), // Strip
        19 to WidgetGrid.Span(2, 2), // Square
    )

    @Test
    fun `migration keeps every id, in reading order, without overlap`() {
        val legacy = WidgetWall.parseIds("11,14,17,19")
        val cells = WidgetWall.migrate(emptyList(), legacy) { phoneSpans.getValue(it) }
        assertEquals(
            listOf(Cell(11, 0, 0, 4, 3), Cell(14, 0, 3, 4, 2), Cell(17, 0, 5, 2, 1), Cell(19, 2, 5, 2, 2)),
            cells,
        )
        assertEquals(legacy, cells.map { it.id })
        assertNoOverlap(cells)
        // Survives a save and a restart
        assertEquals(cells, WidgetWall.parse(WidgetWall.serialize(cells)))
    }

    @Test
    fun `migration never places a widget before the one in front of it`() {
        val spans = mapOf(1 to WidgetGrid.Span(2, 1), 2 to WidgetGrid.Span(4, 2), 3 to WidgetGrid.Span(2, 1))
        val cells = WidgetWall.migrate(emptyList(), listOf(1, 2, 3)) { spans.getValue(it) }
        // Widget 3 would fit beside widget 1 on row 0, but it comes after widget 2
        assertEquals(listOf(Cell(1, 0, 0, 2, 1), Cell(2, 0, 1, 4, 2), Cell(3, 0, 3, 2, 1)), cells)
    }

    @Test
    fun `migration is idempotent and keeps placed widgets where they are`() {
        val placed = listOf(Cell(14, 0, 0, 4, 2))
        val cells = WidgetWall.migrate(placed, listOf(11, 14, 17)) { phoneSpans.getValue(it) }
        assertEquals(Cell(14, 0, 0, 4, 2), cells.first { it.id == 14 })
        assertEquals(setOf(11, 14, 17), cells.map { it.id }.toSet())
        assertNoOverlap(cells)
        assertEquals(cells, WidgetWall.migrate(cells, listOf(11, 14, 17)) { phoneSpans.getValue(it) })
    }

    // Moving and resizing: widgets in the way go down

    @Test
    fun `moving onto a free place changes nothing else`() {
        val cells = listOf(Cell(1, 0, 0, 2, 2), Cell(2, 0, 2, 2, 1))
        // Strip (2) next to the clock (1)
        assertEquals(listOf(Cell(1, 0, 0, 2, 2), Cell(2, 2, 0, 2, 1)), WidgetWall.put(cells, Cell(2, 2, 0, 2, 1)))
    }

    @Test
    fun `widgets in the way are pushed down, and push what they land on`() {
        val cells = listOf(Cell(1, 0, 0, 4, 2), Cell(2, 0, 2, 2, 1), Cell(3, 0, 3, 4, 1), Cell(4, 2, 2, 2, 1))
        // Widget 3 moves to the top: 1 goes below it, 2 and 4 below 1
        val result = WidgetWall.put(cells, Cell(3, 0, 0, 4, 1))
        assertEquals(listOf(Cell(1, 0, 1, 4, 2), Cell(2, 0, 3, 2, 1), Cell(3, 0, 0, 4, 1), Cell(4, 2, 3, 2, 1)), result)
        assertNoOverlap(result)
    }

    @Test
    fun `widgets beside the moved one keep their cells`() {
        val cells = listOf(Cell(1, 0, 0, 2, 2), Cell(2, 2, 0, 2, 2), Cell(3, 0, 2, 2, 1))
        val result = WidgetWall.put(cells, Cell(3, 0, 0, 2, 1))
        assertEquals(listOf(Cell(1, 0, 1, 2, 2), Cell(2, 2, 0, 2, 2), Cell(3, 0, 0, 2, 1)), result)
    }

    @Test
    fun `growing into a neighbour pushes it down`() {
        val cells = listOf(Cell(1, 0, 0, 2, 1), Cell(2, 2, 0, 2, 1))
        val result = WidgetWall.put(cells, Cell(1, 0, 0, 4, 1))
        assertEquals(listOf(Cell(1, 0, 0, 4, 1), Cell(2, 2, 1, 2, 1)), result)
    }

    private val tallLimits = WidgetGrid.Limits(minX = 4, maxX = 4, minY = 2, maxY = 6)
    private val stripLimits = WidgetGrid.Limits(minX = 2, maxX = 4, minY = 1, maxY = 1)

    @Test
    fun `tall shrinks from 4x3 to 4x2 from its bottom edge, not below its minimum`() {
        val tall = Cell(11, 0, 0, 4, 3)
        assertEquals(Cell(11, 0, 0, 4, 2), WidgetWall.resize(tall, Edge.BOTTOM, 2, tallLimits))
        assertEquals(Cell(11, 0, 0, 4, 2), WidgetWall.resize(tall, Edge.BOTTOM, 1, tallLimits))
        assertEquals(Cell(11, 0, 0, 4, 6), WidgetWall.resize(tall, Edge.BOTTOM, 9, tallLimits))
    }

    @Test
    fun `top edge keeps the bottom and stops at the first row`() {
        val tall = Cell(11, 0, 2, 4, 3)
        assertEquals(Cell(11, 0, 3, 4, 2), WidgetWall.resize(tall, Edge.TOP, 3, tallLimits))
        assertEquals(Cell(11, 0, 0, 4, 5), WidgetWall.resize(tall, Edge.TOP, -2, tallLimits))
    }

    @Test
    fun `width stays within the limits and the columns`() {
        val strip = Cell(17, 1, 0, 2, 1)
        assertEquals(Cell(17, 1, 0, 3, 1), WidgetWall.resize(strip, Edge.RIGHT, 5, stripLimits))
        assertEquals(Cell(17, 1, 0, 2, 1), WidgetWall.resize(strip, Edge.RIGHT, 2, stripLimits))
        assertEquals(Cell(17, 0, 0, 3, 1), WidgetWall.resize(strip, Edge.LEFT, -1, stripLimits))
        assertEquals(Cell(17, 1, 0, 2, 1), WidgetWall.resize(strip, Edge.LEFT, 2, stripLimits))
        // Height does not resize
        assertEquals(strip, WidgetWall.resize(strip, Edge.BOTTOM, 3, stripLimits))
    }

    @Test
    fun `a span already outside the limits only moves towards them`() {
        val tooSmall = Cell(11, 0, 0, 4, 1)
        assertEquals(tooSmall, WidgetWall.resize(tooSmall, Edge.BOTTOM, 1, tallLimits))
        assertEquals(Cell(11, 0, 0, 4, 2), WidgetWall.resize(tooSmall, Edge.BOTTOM, 2, tallLimits))
    }
}
