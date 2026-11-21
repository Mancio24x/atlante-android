package app.atlante.library.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * The side drawer's frame: child 0 is the scrim, child 1 the panel. A drag to the left anywhere on the drawer moves
 * the panel with the finger (the scrim fades with it); let go past a third of its width, or with a quick flick, and
 * [onClose] runs; otherwise the panel slides back. Vertical drags stay with the menu's own scrolling.
 */
class SwipeCloseFrame(context: Context, private val onClose: Runnable) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4f
    private var downX = 0f; private var downY = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null

    private val panel: View? get() = if (childCount > 1) getChildAt(1) else null
    private val scrim: View? get() = if (childCount > 0) getChildAt(0) else null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; dragging = false; track(ev, true) }
            MotionEvent.ACTION_MOVE -> {
                track(ev, false)
                val dx = ev.x - downX; val dy = ev.y - downY
                if (dx < -slop && abs(dx) > abs(dy) * 1.5f) { dragging = true; parent?.requestDisallowInterceptTouchEvent(true) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!dragging) recycle()
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(ev)
        val p = panel ?: return false
        track(ev, false)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.x - downX).coerceAtMost(0f)
                p.translationX = dx
                scrim?.alpha = 1f - (-dx / p.width.coerceAtLeast(1)).coerceIn(0f, 1f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                dragging = false; recycle()
                if (ev.actionMasked == MotionEvent.ACTION_UP && (-p.translationX > p.width / 3f || vx < -minFling)) onClose.run()
                else {
                    p.animate().translationX(0f).setDuration(180).setInterpolator(DecelerateInterpolator(1.6f)).start()
                    scrim?.animate()?.alpha(1f)?.setDuration(180)?.start()
                }
            }
        }
        return true
    }

    private fun track(ev: MotionEvent, reset: Boolean) {
        if (reset) recycle()
        val v = velocity ?: VelocityTracker.obtain().also { velocity = it }
        v.addMovement(ev)
    }
    private fun recycle() { velocity?.recycle(); velocity = null }
}
