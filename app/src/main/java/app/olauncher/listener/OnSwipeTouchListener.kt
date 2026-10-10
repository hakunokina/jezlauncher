package app.olauncher.listener

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewConfiguration
import app.olauncher.data.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot

/*
Swipe, double tap, triple tap and long press touch listener for a view
Source: https://www.tutorialspoint.com/how-to-handle-swipe-gestures-in-kotlin
*/

internal open class OnSwipeTouchListener(c: Context?) : OnTouchListener {
    private var longPressOn = false
    private val gestureDetector: GestureDetector

    // Tap counting. A double tap waits one double-tap timeout for a third tap, but only when
    // isTripleClickEnabled() is true; otherwise it fires right away.
    private val handler = Handler(Looper.getMainLooper())
    private val viewConfiguration = ViewConfiguration.get(requireNotNull(c))
    private val multiTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val pendingDoubleClick = Runnable {
        tapCount = 0
        onDoubleClick()
    }
    private var tapCount = 0
    private var touchIsTap = false
    private var downX = 0f
    private var downY = 0f
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var lastTapUpTime = 0L

    override fun onTouch(view: View, motionEvent: MotionEvent): Boolean {
        if (motionEvent.action == MotionEvent.ACTION_UP)
            longPressOn = false
        trackTaps(motionEvent)
        return gestureDetector.onTouchEvent(motionEvent)
    }

    private fun trackTaps(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchIsTap = true
                downX = e.x
                downY = e.y
                // A third touch may still become a tap; the pending double click is dropped if it does not
                if (tapCount == 2) handler.removeCallbacks(pendingDoubleClick)
            }

            MotionEvent.ACTION_MOVE ->
                if (touchIsTap && distance(downX, downY, e.x, e.y) > viewConfiguration.scaledTouchSlop) touchIsTap = false

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                touchIsTap = false
                resetTaps()
            }

            MotionEvent.ACTION_UP -> {
                if (touchIsTap && e.eventTime - e.downTime < ViewConfiguration.getLongPressTimeout())
                    registerTap(e)
                else
                    resetTaps()
            }
        }
    }

    private fun registerTap(e: MotionEvent) {
        val continuesSequence = tapCount > 0 &&
                e.downTime - lastTapUpTime <= multiTapTimeout &&
                distance(lastTapX, lastTapY, e.x, e.y) <= viewConfiguration.scaledDoubleTapSlop
        tapCount = if (continuesSequence) tapCount + 1 else 1
        lastTapUpTime = e.eventTime
        lastTapX = e.x
        lastTapY = e.y
        when (tapCount) {
            2 -> if (isTripleClickEnabled()) handler.postDelayed(pendingDoubleClick, multiTapTimeout) else {
                tapCount = 0
                onDoubleClick()
            }

            3 -> {
                tapCount = 0
                onTripleClick()
            }
        }
    }

    private fun resetTaps() {
        handler.removeCallbacks(pendingDoubleClick)
        tapCount = 0
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float) = hypot(x2 - x1, y2 - y1)

    private inner class GestureListener : SimpleOnGestureListener() {
        private val SWIPE_THRESHOLD: Int = 100
        private val SWIPE_VELOCITY_THRESHOLD: Int = 100

        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onClick()
            return super.onSingleTapUp(e)
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // Double and triple clicks are decided in trackTaps()
            return super.onDoubleTap(e)
        }

        override fun onLongPress(e: MotionEvent) {
            longPressOn = true
            GlobalScope.launch {
                delay(Constants.LONG_PRESS_DELAY_MS)
                withContext(Dispatchers.Main) {
                    if (isActive && longPressOn)
                        onLongClick()
                }
            }
            super.onLongPress(e)
        }

        override fun onFling(
            event1: MotionEvent?,
            event2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            try {
                val diffY = event2.y - (event1?.y ?: 0F)
                val diffX = event2.x - (event1?.x ?: 0F)
                if (abs(diffX) > abs(diffY)) {
                    if (abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) onSwipeRight() else onSwipeLeft()
                    }
                } else {
                    if (abs(diffY) > SWIPE_THRESHOLD && abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffY < 0) onSwipeUp() else onSwipeDown()
                    }
                }
            } catch (exception: Exception) {
                exception.printStackTrace()
            }
            return false
        }
    }

    open fun onSwipeRight() {}
    open fun onSwipeLeft() {}
    open fun onSwipeUp() {}
    open fun onSwipeDown() {}
    open fun onLongClick() {}
    open fun onDoubleClick() {}
    open fun onTripleClick() {}
    open fun isTripleClickEnabled(): Boolean = false
    open fun onClick() {}

    init {
        gestureDetector = GestureDetector(c, GestureListener())
    }
}