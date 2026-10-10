package app.olauncher.ui

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.util.SizeF
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import app.olauncher.data.WidgetGrid
import app.olauncher.data.WidgetWall
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Reports a long press on the widget while passing every touch on, so the widget stays interactive. */
class LongPressFrameLayout(context: Context) : FrameLayout(context) {
    var onLongPress: (() -> Unit)? = null

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            onLongPress?.invoke()
        }
    })

    // onInterceptTouchEvent misses the rest of a gesture that starts where no child takes the touch (the empty
    // sides of a narrow widget), so a scroll there turned into a long press. dispatchTouchEvent sees every event;
    // keeping an unclaimed DOWN keeps the stream, including the CANCEL when the wall starts scrolling
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev) || ev.actionMasked == MotionEvent.ACTION_DOWN
    }
}

/** ScrollView that shows every touch to [onTouch] first, also the ones a child takes or it scrolls with. */
class WatchingScrollView(context: Context, attrs: AttributeSet?) : ScrollView(context, attrs) {
    var onTouch: ((MotionEvent) -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        onTouch?.invoke(ev)
        return super.dispatchTouchEvent(ev)
    }
}

/** The provider's sizes for [WidgetGrid] in dp; the Android 12 fields stay unset below it. */
fun AppWidgetProviderInfo.gridProvider(density: Float): WidgetGrid.Provider {
    val sdk31 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    return WidgetGrid.Provider(
        minWidth = minWidth / density,
        minHeight = minHeight / density,
        minResizeWidth = minResizeWidth / density,
        minResizeHeight = minResizeHeight / density,
        maxResizeWidth = if (sdk31) maxResizeWidth / density else 0f,
        maxResizeHeight = if (sdk31) maxResizeHeight / density else 0f,
        resizeHorizontal = resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0,
        resizeVertical = resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0,
        targetCellWidth = if (sdk31) targetCellWidth else 0,
        targetCellHeight = if (sdk31) targetCellHeight else 0,
    )
}

/**
 * The wall's grid: each widget (its AppWidgetHostView in a [LongPressFrameLayout]) sits on its [WidgetWall.Cell].
 * A long press starts edit mode: widgets stop taking touches, dragging one moves it cell by cell, the selected one
 * shows handles on the edges it resizes along. Widgets in the way of a move or resize go down ([WidgetWall.put]).
 * Scrolled vertically by its [WatchingScrollView] parent.
 */
class WidgetWallView(context: Context, attrs: AttributeSet?) : ViewGroup(context, attrs) {

    /** Host view of a widget id; null when its provider is gone. */
    var hostView: (Int) -> AppWidgetHostView? = { null }

    /** A move or resize was dropped; the layout to save. */
    var onLayoutChanged: (List<WidgetWall.Cell>) -> Unit = {}

    var onEditChanged: (Boolean) -> Unit = {}

    var cells: List<WidgetWall.Cell> = emptyList()
        private set

    var editing = false
        private set

    var selectedId = AppWidgetManager.INVALID_APPWIDGET_ID
        private set

    // Layout while a move or resize is under the finger; cells keep the one before it
    private var preview: List<WidgetWall.Cell>? = null
    private val shown get() = preview ?: cells

    private val containers = mutableMapOf<Int, LongPressFrameLayout>()
    private val reportedSizes = mutableMapOf<Int, Pair<Int, Int>>()

    private val density = resources.displayMetrics.density
    private val gap = WidgetGrid.GAP_DP * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val handleRadius = 6 * density
    private val handleReach = 24 * density
    private var grid = metrics()
    private val pitchX get() = (grid.cellWidth + WidgetGrid.GAP_DP) * density
    private val pitchY get() = (grid.cellHeight + WidgetGrid.GAP_DP) * density

    /** A touch on a widget in edit mode: a move when [edge] is null, else a resize from that edge. */
    private class Gesture(val start: List<WidgetWall.Cell>, val cell: WidgetWall.Cell, val edge: WidgetWall.Edge?, val downX: Float, val downY: Float) {
        var dragging = false
        var grabX = 0f
        var grabY = 0f
    }

    private var gesture: Gesture? = null
    private var tapOnEmpty = false
    private var downX = 0f
    private var downY = 0f
    private var rawX = 0f
    private var rawY = 0f
    private var autoScrolling = false

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = Color.WHITE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    /** Grid for the current width, or for the screen's before the first layout. */
    fun metrics(): WidgetGrid.Metrics {
        val w = if (width > 0) width else resources.displayMetrics.widthPixels
        return WidgetGrid.metrics((w - paddingLeft - paddingRight) / density)
    }

    fun submit(cells: List<WidgetWall.Cell>) {
        this.cells = cells
        val ids = cells.map { it.id }.toSet()
        (containers.keys - ids).forEach { id ->
            removeView(containers.remove(id))
            reportedSizes.remove(id)
        }
        cells.filter { it.id !in containers }.forEach { addContainer(it.id) }
        if (selectedId !in ids) selectedId = AppWidgetManager.INVALID_APPWIDGET_ID
        requestLayout()
        invalidate()
    }

    /** Top of a widget's cell, for scrolling to it. */
    fun topOf(id: Int): Int? = cells.firstOrNull { it.id == id }?.let { paddingTop + (it.y * pitchY).roundToInt() }

    fun stopEditing() {
        if (!editing) return
        gesture?.let { end(it, commit = false) }
        editing = false
        selectedId = AppWidgetManager.INVALID_APPWIDGET_ID
        onEditChanged(false)
        requestLayout()
        invalidate()
    }

    private fun addContainer(id: Int) {
        val host = hostView(id) ?: return
        (host.parent as? ViewGroup)?.removeView(host)
        val container = LongPressFrameLayout(context).apply {
            addView(host, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            onLongPress = { startEdit(id) }
        }
        containers[id] = container
        addView(container)
    }

    // The finger is still down after the long press: moving it now drags the widget
    private fun startEdit(id: Int) {
        val cell = cells.firstOrNull { it.id == id } ?: return
        containers[id]?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (!editing) {
            editing = true
            onEditChanged(true)
            requestLayout()
        }
        selectedId = id
        gesture = Gesture(cells, cell, null, downX, downY)
        parent?.requestDisallowInterceptTouchEvent(true)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        grid = WidgetGrid.metrics((width - paddingLeft - paddingRight) / density)
        shown.forEach { cell ->
            containers[cell.id]?.measure(
                MeasureSpec.makeMeasureSpec((grid.width(cell.spanX) * density).roundToInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((grid.height(cell.spanY) * density).roundToInt(), MeasureSpec.EXACTLY),
            )
        }
        // Edit mode shows a free row below the last widget to drop onto
        val rows = (shown.maxOfOrNull { it.bottom } ?: 0) + if (editing) 1 else 0
        val content = paddingTop + paddingBottom + if (rows > 0) (grid.height(rows) * density).roundToInt() else 0
        setMeasuredDimension(width, resolveSize(max(content, suggestedMinimumHeight), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val dragged = gesture?.takeIf { it.dragging && it.edge == null }
        shown.forEach { cell ->
            val c = containers[cell.id] ?: return@forEach
            val x = paddingLeft + (cell.x * pitchX).roundToInt()
            val y = paddingTop + (cell.y * pitchY).roundToInt()
            val oldX = c.left + c.translationX
            val oldY = c.top + c.translationY
            val wasLaidOut = c.isLaidOut
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            // Widgets pushed aside and a dropped widget glide to their cells
            if (cell.id != dragged?.cell?.id && wasLaidOut && (oldX != x.toFloat() || oldY != y.toFloat())) {
                c.translationX = oldX - x
                c.translationY = oldY - y
                c.animate().translationX(0f).translationY(0f).setDuration(150).start()
            }
        }
        dragged?.let { follow(it) }
        if (gesture == null) post { reportSizes() }
        updateGestureExclusion()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = ev.x
            downY = ev.y
        }
        rawX = ev.rawX
        rawY = ev.rawY
        return super.dispatchTouchEvent(ev)
    }

    // While editing the wall takes every touch, so widgets get no clicks; also the rest of the long press that
    // started editing. An intercepted DOWN also reaches onTouchEvent, later events do not
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!editing) return false
        if (ev.actionMasked != MotionEvent.ACTION_DOWN) onEditTouch(ev)
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!editing) return false
        onEditTouch(ev)
        return true
    }

    private fun onEditTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val selected = shown.firstOrNull { it.id == selectedId }
                val edge = selected?.let { edgeAt(it, ev.x, ev.y) }
                val cell = if (edge != null) selected else cellAt(ev.x, ev.y)
                gesture = cell?.let { Gesture(cells, it, edge, ev.x, ev.y) }
                tapOnEmpty = cell == null
                // Touching a widget drags it; touching empty space still scrolls the wall
                if (cell != null) parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                val moved = hypot(ev.x - downX, ev.y - downY) > touchSlop
                if (moved) tapOnEmpty = false
                val g = gesture ?: return
                if (!g.dragging) {
                    if (hypot(ev.x - g.downX, ev.y - g.downY) <= touchSlop) return
                    beginDrag(g)
                }
                drag(g)
            }

            MotionEvent.ACTION_UP -> {
                val g = gesture
                when {
                    g == null -> if (tapOnEmpty) stopEditing()
                    g.dragging -> end(g, commit = true)
                    else -> {
                        gesture = null
                        selectedId = g.cell.id
                        updateGestureExclusion()
                        invalidate()
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> gesture?.let { end(it, commit = false) }
        }
    }

    private fun beginDrag(g: Gesture) {
        g.dragging = true
        if (g.edge != null) return
        selectedId = g.cell.id
        val c = containers[g.cell.id] ?: return
        g.grabX = g.downX - c.left
        g.grabY = g.downY - c.top
        // Drawn above the widgets it passes over
        c.translationZ = 8 * density
    }

    // Also called while auto scrolling, when the finger stays and the wall moves under it
    private fun drag(g: Gesture) {
        val (x, y) = fingerInGrid()
        val target = if (g.edge == null) movedCell(g, x, y) else resizedCell(g, g.edge, x, y) ?: g.cell
        val next = if (target == g.cell) g.start else WidgetWall.put(g.start, target)
        if (next != shown) {
            preview = next
            requestLayout()
        }
        if (g.edge == null) follow(g)
        invalidate()
        if (!autoScrolling && edgeScrollStep() != 0) {
            autoScrolling = true
            postOnAnimation(autoScroll)
        }
    }

    private val autoScroll = Runnable {
        autoScrolling = false
        val g = gesture?.takeIf { it.dragging } ?: return@Runnable
        val step = edgeScrollStep()
        if (step == 0) return@Runnable
        (parent as? View)?.scrollBy(0, step)
        drag(g)
    }

    private fun end(g: Gesture, commit: Boolean) {
        gesture = null
        val next = preview
        preview = null
        if (commit && next != null && next != cells) {
            cells = next
            onLayoutChanged(next)
        }
        containers[g.cell.id]?.let { c ->
            c.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(150).withEndAction { c.translationZ = 0f }.start()
        }
        requestLayout()
        invalidate()
    }

    // The dragged widget's top left follows the finger; it snaps to the nearest cell, below the others at most
    private fun movedCell(g: Gesture, x: Float, y: Float): WidgetWall.Cell {
        val c = g.cell
        val bottomOfOthers = g.start.filter { it.id != c.id }.maxOfOrNull { it.bottom } ?: 0
        return c.copy(
            x = ((x - g.grabX - paddingLeft) / pitchX).roundToInt().coerceIn(0, WidgetGrid.COLUMNS - c.spanX),
            y = ((y - g.grabY - paddingTop) / pitchY).roundToInt().coerceIn(0, bottomOfOthers),
        )
    }

    // The dragged edge snaps to the nearest grid line, the middle of the gap between two cells
    private fun resizedCell(g: Gesture, edge: WidgetWall.Edge, x: Float, y: Float): WidgetWall.Cell? {
        val limits = limits(g.cell.id) ?: return null
        val line = when (edge) {
            WidgetWall.Edge.LEFT, WidgetWall.Edge.RIGHT -> (x - paddingLeft + gap / 2) / pitchX
            WidgetWall.Edge.TOP, WidgetWall.Edge.BOTTOM -> (y - paddingTop + gap / 2) / pitchY
        }
        return WidgetWall.resize(g.cell, edge, line.roundToInt(), limits)
    }

    private fun follow(g: Gesture) {
        val c = containers[g.cell.id] ?: return
        val (x, y) = fingerInGrid()
        c.animate().cancel()
        c.translationX = x - g.grabX - c.left
        c.translationY = y - g.grabY - c.top
        c.scaleX = 1.04f
        c.scaleY = 1.04f
        c.alpha = 0.85f
    }

    private fun fingerInGrid(): Pair<Float, Float> {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return (rawX - location[0]) to (rawY - location[1])
    }

    // Near the top or bottom of the visible wall a drag scrolls it
    private fun edgeScrollStep(): Int {
        val scroll = parent as? View ?: return 0
        val location = IntArray(2)
        scroll.getLocationOnScreen(location)
        val y = rawY - location[1]
        val edge = 64 * density
        return when {
            y < edge && scroll.canScrollVertically(-1) -> -(10 * density).roundToInt()
            y > scroll.height - edge && scroll.canScrollVertically(1) -> (10 * density).roundToInt()
            else -> 0
        }
    }

    private fun cellAt(x: Float, y: Float) = shown.firstOrNull { rectOf(it).contains(x, y) }

    private fun rectOf(cell: WidgetWall.Cell): RectF {
        val left = paddingLeft + cell.x * pitchX
        val top = paddingTop + cell.y * pitchY
        return RectF(left, top, left + grid.width(cell.spanX) * density, top + grid.height(cell.spanY) * density)
    }

    private fun limits(id: Int): WidgetGrid.Limits? {
        val info = (containers[id]?.getChildAt(0) as? AppWidgetHostView)?.appWidgetInfo ?: return null
        val viewport = (parent as? View)?.height?.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val maxRows = max(1, ((viewport - paddingTop - paddingBottom + gap) / pitchY).toInt())
        return WidgetGrid.limits(grid, info.gridProvider(density), maxRows)
    }

    // Handles sit in the middle of the edges a widget resizes along; none when it does not resize
    private fun handles(cell: WidgetWall.Cell): List<Pair<WidgetWall.Edge, PointF>> {
        val limits = limits(cell.id) ?: return emptyList()
        val r = rectOf(cell)
        return buildList {
            if (limits.resizesX) {
                add(WidgetWall.Edge.LEFT to PointF(r.left, r.centerY()))
                add(WidgetWall.Edge.RIGHT to PointF(r.right, r.centerY()))
            }
            if (limits.resizesY) {
                add(WidgetWall.Edge.TOP to PointF(r.centerX(), r.top))
                add(WidgetWall.Edge.BOTTOM to PointF(r.centerX(), r.bottom))
            }
        }
    }

    private fun edgeAt(cell: WidgetWall.Cell, x: Float, y: Float) =
        handles(cell).filter { (_, p) -> hypot(p.x - x, p.y - y) <= handleReach }
            .minByOrNull { (_, p) -> hypot(p.x - x, p.y - y) }?.first

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!editing) return
        // The frame marks the selected widget's cells; while it moves, the cells it would land on
        val cell = shown.firstOrNull { it.id == selectedId } ?: return
        val radius = 12 * density
        canvas.drawRoundRect(rectOf(cell), radius, radius, framePaint)
        if (gesture?.let { it.dragging && it.edge == null } == true) return
        handles(cell).forEach { (_, p) -> canvas.drawCircle(p.x, p.y, handleRadius, handlePaint) }
    }

    // Handles near the screen's sides must not start the system back gesture
    private fun updateGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val cell = shown.firstOrNull { it.id == selectedId }
        systemGestureExclusionRects = if (!editing || cell == null) emptyList() else handles(cell).map { (_, p) ->
            Rect((p.x - handleReach).toInt(), (p.y - handleReach).toInt(), (p.x + handleReach).toInt(), (p.y + handleReach).toInt())
        }
    }

    // Providers lay out for the size they are told; tell them when a widget's cells change, not while dragging
    private fun reportSizes() {
        containers.forEach { (id, c) ->
            val size = c.width to c.height
            if (c.width <= 0 || c.height <= 0 || reportedSizes[id] == size) return@forEach
            reportedSizes[id] = size
            (c.getChildAt(0) as? AppWidgetHostView)?.let { updateSize(it) }
        }
    }

    // Reports the size the host view really got, so the provider lays out for exactly that box
    private fun updateSize(hostView: AppWidgetHostView) {
        val density = hostView.resources.displayMetrics.density
        // updateAppWidgetSize takes the default widget padding off the sizes it sends, although the wall
        // removes that padding from the view (WidgetsFragment); add it back so the provider gets the real box
        val pad = AppWidgetHostView.getDefaultPaddingForWidget(hostView.context, hostView.appWidgetInfo?.provider, null)
        val w = (hostView.width - hostView.paddingLeft - hostView.paddingRight + pad.left + pad.right) / density
        val h = (hostView.height - hostView.paddingTop - hostView.paddingBottom + pad.top + pad.bottom) / density
        if (hostView.width <= 0 || hostView.height <= 0) return
        // Android 12+ providers (sized RemoteViews, Glance) read OPTION_APPWIDGET_SIZES, which the old call leaves empty
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hostView.updateAppWidgetSize(Bundle(), listOf(SizeF(w, h)))
        } else {
            @Suppress("DEPRECATION")
            hostView.updateAppWidgetSize(null, w.toInt(), h.toInt(), w.toInt(), h.toInt())
        }
    }
}
