package com.kynrek.spamreporter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.kynrek.spamreporter.Ui.dp
import com.kynrek.spamreporter.Ui.text
import kotlin.math.abs

/**
 * Wraps a row so it can be swiped right (e.g. "Not spam"). A green label is revealed underneath while dragging;
 * past about a third of the width it slides off and [onSwiped] runs. Taps and vertical scrolling pass through.
 */
@SuppressLint("ViewConstructor")
class SwipeRow(ctx: Context, row: View, label: String, private val onSwiped: (SwipeRow) -> Unit) : FrameLayout(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val front = row
    private var x0 = 0f; private var y0 = 0f; private var dragging = false

    init {
        val back = ctx.text("  ✓  $label", 15f, true, 0xFFFFFFFF.toInt()).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(ctx.dp(20), 0, 0, 0)
            background = GradientDrawable().apply { cornerRadius = ctx.dp(12).toFloat(); setColor(Ui.GREEN) }
        }
        val lp = row.layoutParams as? MarginLayoutParams
        addView(back, LayoutParams(-1, -1).apply { bottomMargin = lp?.bottomMargin ?: 0 })
        addView(row)
    }

    private fun startsDrag(e: MotionEvent): Boolean {
        val dx = e.x - x0; val dy = e.y - y0
        if (!dragging && dx > slop && abs(dx) > abs(dy) * 1.5f) { dragging = true; parent?.requestDisallowInterceptTouchEvent(true) }
        return dragging
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; dragging = false }
            MotionEvent.ACTION_MOVE -> return startsDrag(e)
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; dragging = false }
            MotionEvent.ACTION_MOVE -> if (startsDrag(e)) front.translationX = (e.x - x0).coerceAtLeast(0f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging && e.actionMasked == MotionEvent.ACTION_UP && e.x - x0 > width * 0.35f)
                    front.animate().translationX(width.toFloat()).setDuration(150).withEndAction { onSwiped(this) }.start()
                else front.animate().translationX(0f).setDuration(150).start()
                dragging = false
            }
        }
        return true
    }

    /** Put the row back (after Undo). */
    fun reset() { front.translationX = 0f; visibility = View.VISIBLE }
}
