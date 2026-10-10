package app.olauncher.data

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The wall's home screen like grid (One UI on the phone: 4 columns, cells a bit taller than wide, a gap between
 * cells) and how many cells a provider takes: its target cells (Android 12+) or the cells its minimum size needs,
 * never wider than the screen. All values in dp.
 */
object WidgetGrid {

    const val COLUMNS = 4

    /** Space between two cells, across and down. */
    const val GAP_DP = 16f

    /** A provider's sizes (AppWidgetProviderInfo in dp). A max or target of 0 is unset. */
    data class Provider(
        val minWidth: Float,
        val minHeight: Float,
        val minResizeWidth: Float = 0f,
        val minResizeHeight: Float = 0f,
        val maxResizeWidth: Float = 0f,
        val maxResizeHeight: Float = 0f,
        val resizeHorizontal: Boolean = false,
        val resizeVertical: Boolean = false,
        val targetCellWidth: Int = 0,
        val targetCellHeight: Int = 0,
    )

    /**
     * Cell size on a wall [availableWidthDp] wide: the columns and the gaps between them fill the width, a row is a
     * quarter of the width high (a 4x2 widget is 328x180dp like One UI draws it on the phone).
     */
    data class Metrics(val cellWidth: Float, val cellHeight: Float) {
        fun width(spanX: Int) = spanX * cellWidth + (spanX - 1) * GAP_DP
        fun height(spanY: Int) = spanY * cellHeight + (spanY - 1) * GAP_DP
    }

    fun metrics(availableWidthDp: Float) =
        Metrics((availableWidthDp - (COLUMNS - 1) * GAP_DP) / COLUMNS, availableWidthDp / COLUMNS)

    data class Span(val x: Int, val y: Int)

    /** Spans the user may resize a widget to; equal min and max in a direction the provider does not resize in. */
    data class Limits(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int) {
        val resizesX get() = minX < maxX
        val resizesY get() = minY < maxY
    }

    /**
     * Span a new widget gets. A target span smaller than what the minimum (resize) size needs is ignored, like
     * Launcher3 does; the minimum resize size only counts in a direction the provider can be resized in.
     */
    fun span(metrics: Metrics, p: Provider) = Span(
        span(p.minWidth, p.minResizeWidth, p.resizeHorizontal, p.targetCellWidth, metrics.cellWidth).coerceAtMost(COLUMNS),
        span(p.minHeight, p.minResizeHeight, p.resizeVertical, p.targetCellHeight, metrics.cellHeight),
    )

    /**
     * Resize range: down to the minimum resize size, up to the max resize size (Android 12+), the screen width and
     * [maxRows] when unset. The default [span] always lies inside.
     */
    fun limits(metrics: Metrics, p: Provider, maxRows: Int): Limits {
        val span = span(metrics, p)
        val minX = if (p.resizeHorizontal) min(span.x, smallest(p.minWidth, p.minResizeWidth, metrics.cellWidth)) else span.x
        val minY = if (p.resizeVertical) min(span.y, smallest(p.minHeight, p.minResizeHeight, metrics.cellHeight)) else span.y
        val maxX = if (p.resizeHorizontal) largest(p.maxResizeWidth, metrics.cellWidth, COLUMNS) else span.x
        val maxY = if (p.resizeVertical) largest(p.maxResizeHeight, metrics.cellHeight, maxRows) else span.y
        return Limits(minX, max(maxX, span.x).coerceAtMost(COLUMNS), minY, max(maxY, span.y))
    }

    /** Cells needed to show [sizeDp]: n cells plus the n-1 gaps between them must cover it. */
    fun cellsFor(sizeDp: Float, cellDp: Float, gapDp: Float = GAP_DP): Int =
        max(1, ceil((sizeDp + gapDp) / (cellDp + gapDp) - 0.001f).toInt())

    private fun span(minSize: Float, minResize: Float, resizable: Boolean, target: Int, cell: Float): Int {
        val default = cellsFor(minSize, cell)
        val smallest = if (resizable && minResize > 0f) smallest(minSize, minResize, cell) else default
        return if (target >= smallest) target else default
    }

    private fun smallest(minSize: Float, minResize: Float, cell: Float) =
        cellsFor(if (minResize > 0f) min(minResize, minSize) else minSize, cell)

    // The most cells that still fit in maxResize; unset means up to the grid's own bound
    private fun largest(maxResize: Float, cell: Float, bound: Int): Int =
        if (maxResize <= 0f) bound
        else min(bound, max(1, floor((maxResize + GAP_DP) / (cell + GAP_DP) + 0.001f).toInt()))
}
