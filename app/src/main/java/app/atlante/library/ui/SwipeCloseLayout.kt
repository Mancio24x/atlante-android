package app.atlante.library.ui

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Overlay of the side menu: the sheet ([sheet], at the start edge) follows the finger when you drag it towards the
 * edge, and the scrim fades with it; let go past a third of its width, or with a quick fling, and it closes
 * ([onClose]); otherwise it springs back. Vertical drags stay with the menu's scroll.
 */
class SwipeCloseLayout(context: Context) : FrameLayout(context) {
    var sheet: View? = null
    var scrim: View? = null
    var onClose: Runnable? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private var downX = 0f; private var downY = 0f; private var dragging = false
    private var velocity: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; dragging = false; track(ev, true) }
            MotionEvent.ACTION_MOVE -> {
                track(ev, false)
                val dx = ev.x - downX; val dy = ev.y - downY
                if (sheet != null && -dx > slop && abs(dx) > abs(dy) * 1.5f) { dragging = true; parent?.requestDisallowInterceptTouchEvent(true) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> recycle()
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val s = sheet ?: return super.onTouchEvent(ev)
        if (!dragging) {
            // Taps on the scrim reach its click listener.
            return super.onTouchEvent(ev)
        }
        track(ev, false)
        val width = s.width.coerceAtLeast(1)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val offset = (ev.x - downX).coerceIn(-width.toFloat(), 0f)
                s.translationX = offset; scrim?.alpha = 1f + offset / width
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val v = velocity?.let { it.computeCurrentVelocity(1000); it.xVelocity } ?: 0f
                val close = ev.actionMasked == MotionEvent.ACTION_UP && (-s.translationX > width / 3f || v < -minFling)
                dragging = false; recycle()
                if (close) onClose?.run()
                else { s.animate().translationX(0f).setDuration(180).setInterpolator(EMPHASIZED).start(); scrim?.animate()?.alpha(1f)?.setDuration(180)?.start() }
            }
        }
        return true
    }

    private fun track(ev: MotionEvent, reset: Boolean) {
        if (reset) { velocity?.recycle(); velocity = VelocityTracker.obtain() }
        velocity?.addMovement(ev)
    }
    private fun recycle() { velocity?.recycle(); velocity = null }

    companion object {
        /** Material 3 "emphasized decelerate": quick start, soft landing. */
        @JvmField val EMPHASIZED = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        /** Material 3 "emphasized accelerate", for things leaving the screen. */
        @JvmField val EMPHASIZED_EXIT = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    }
}
