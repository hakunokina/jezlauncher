package app.olauncher.data

import kotlin.math.max
import kotlin.math.min

/**
 * Pure layout logic for the widget wall: which AppWidgetHost ids are shown and on which cells of the
 * [WidgetGrid]. The layout is stored in [Prefs] as comma separated "id:x:y:spanX:spanY" entries.
 */
object WidgetWall {

    /** Host id the launcher registers its AppWidgetHost with. */
    const val HOST_ID = 1024

    /** A widget's place on the grid, in cells; rows grow downwards without a bound. */
    data class Cell(val id: Int, val x: Int, val y: Int, val spanX: Int, val spanY: Int) {
        val right get() = x + spanX
        val bottom get() = y + spanY

        fun overlaps(other: Cell) = x < other.right && other.x < right && y < other.bottom && other.y < bottom
    }

    enum class Edge { LEFT, TOP, RIGHT, BOTTOM }

    /** Ids of a wall saved before the grid: comma separated, in wall order. */
    fun parseIds(raw: String?): List<Int> =
        raw.orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() }.distinct()

    fun parse(raw: String?, columns: Int = WidgetGrid.COLUMNS): List<Cell> =
        raw.orEmpty().split(",").mapNotNull { entry ->
            val n = entry.split(":").map { it.trim().toIntOrNull() ?: return@mapNotNull null }
            if (n.size != 5) return@mapNotNull null
            Cell(n[0], n[1], n[2], n[3], n[4]).takeIf { it.x >= 0 && it.y >= 0 && it.spanX > 0 && it.spanY > 0 && it.right <= columns }
        }.distinctBy { it.id }

    fun serialize(cells: List<Cell>): String =
        cells.joinToString(",") { "${it.id}:${it.x}:${it.y}:${it.spanX}:${it.spanY}" }

    /**
     * Adds [id] on the first free cells that fit its span, reading row by row from the top; [from] (y * columns + x)
     * starts the search further down. Rows are unbounded, so there is always room.
     */
    fun add(cells: List<Cell>, id: Int, spanX: Int, spanY: Int, from: Int = 0, columns: Int = WidgetGrid.COLUMNS): List<Cell> {
        val width = spanX.coerceIn(1, columns)
        var index = from
        while (true) {
            val cell = Cell(id, index % columns, index / columns, width, spanY)
            if (cell.right <= columns && cells.none { it.overlaps(cell) }) return cells + cell
            index++
        }
    }

    fun remove(cells: List<Cell>, id: Int): List<Cell> = cells.filterNot { it.id == id }

    /**
     * Moves a wall saved before the grid (only an id order) onto it: each widget takes the first free cells after the
     * one before it, so the reading order stays. Ids already on [cells] keep their place; [span] gives a widget's size.
     */
    fun migrate(cells: List<Cell>, ids: List<Int>, columns: Int = WidgetGrid.COLUMNS, span: (Int) -> WidgetGrid.Span): List<Cell> {
        var result = cells
        var from = 0
        for (id in ids) {
            if (result.any { it.id == id }) continue
            val s = span(id)
            result = add(result, id, s.x, s.y, from, columns)
            val placed = result.last()
            from = placed.y * columns + placed.x
        }
        return result
    }

    /**
     * Puts a moved or resized [cell] in place. Every widget in its way goes down until it is free, top to bottom, so
     * a widget pushed onto another pushes that one too; widgets not in the way keep their cells.
     */
    fun put(cells: List<Cell>, cell: Cell): List<Cell> {
        val placed = mutableListOf(cell)
        cells.filter { it.id != cell.id }.sortedWith(compareBy({ it.y }, { it.x })).forEach { other ->
            var c = other
            while (placed.any { it.overlaps(c) }) c = c.copy(y = c.y + 1)
            placed += c
        }
        val byId = placed.associateBy { it.id }
        return cells.map { byId.getValue(it.id) }
    }

    /**
     * [cell] with its [edge] dragged to grid line [line] (a column line for LEFT/RIGHT, a row line for TOP/BOTTOM), within
     * [limits] and the columns; the opposite edge stays. A span already outside the limits may only move towards them.
     */
    fun resize(cell: Cell, edge: Edge, line: Int, limits: WidgetGrid.Limits, columns: Int = WidgetGrid.COLUMNS): Cell {
        val minX = min(limits.minX, cell.spanX)
        val maxX = max(limits.maxX, cell.spanX)
        val minY = min(limits.minY, cell.spanY)
        val maxY = max(limits.maxY, cell.spanY)
        return when (edge) {
            Edge.LEFT -> line.coerceIn(max(0, cell.right - maxX), cell.right - minX).let { cell.copy(x = it, spanX = cell.right - it) }
            Edge.RIGHT -> cell.copy(spanX = (line - cell.x).coerceIn(minX, max(minX, min(maxX, columns - cell.x))))
            Edge.TOP -> line.coerceIn(max(0, cell.bottom - maxY), cell.bottom - minY).let { cell.copy(y = it, spanY = cell.bottom - it) }
            Edge.BOTTOM -> cell.copy(spanY = (line - cell.y).coerceIn(minY, maxY))
        }
    }
}
