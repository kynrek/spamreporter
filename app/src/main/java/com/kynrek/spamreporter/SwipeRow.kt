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
 * Wraps a row so it can be swiped right (green, e.g. "Not spam") and/or left (red, e.g. "Spam"). The matching label
 * shows underneath while dragging; past about a third of the width the row slides off and the callback runs.
 * Pass null for a direction to disable it. Taps and vertical scrolling pass through.
 */
@SuppressLint("ViewConstructor")
class SwipeRow(ctx: Context, row: View, rightLabel: String?, leftLabel: String?,
               private val onRight: ((SwipeRow) -> Unit)?, private val onLeft: ((SwipeRow) -> Unit)?) : FrameLayout(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val front = row
    private val rightBack = rightLabel?.let { label(ctx, "  ✓  $it", Ui.GREEN, Gravity.START or Gravity.CENTER_VERTICAL) }
    private val leftBack = leftLabel?.let { label(ctx, "$it  ✕  ", Ui.RED, Gravity.END or Gravity.CENTER_VERTICAL) }
    private var x0 = 0f; private var y0 = 0f; private var dragging = false

    private fun label(ctx: Context, s: String, color: Int, g: Int) = ctx.text(s, 15f, true, 0xFFFFFFFF.toInt()).apply {
        gravity = g; setPadding(ctx.dp(20), 0, ctx.dp(20), 0); visibility = View.INVISIBLE
        background = GradientDrawable().apply { cornerRadius = ctx.dp(12).toFloat(); setColor(color) }
    }

    init {
        val bottom = (row.layoutParams as? MarginLayoutParams)?.bottomMargin ?: 0
        for (b in listOfNotNull(rightBack, leftBack)) addView(b, LayoutParams(-1, -1).apply { bottomMargin = bottom })
        addView(row)
    }

    private fun startsDrag(e: MotionEvent): Boolean {
        val dx = e.x - x0; val dy = e.y - y0
        val ok = (dx > 0 && onRight != null) || (dx < 0 && onLeft != null)
        if (!dragging && ok && abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) { dragging = true; parent?.requestDisallowInterceptTouchEvent(true) }
        return dragging
    }

    private fun show(dx: Float) {
        val x = when { dx > 0 && onRight != null -> dx; dx < 0 && onLeft != null -> dx; else -> 0f }
        front.translationX = x
        rightBack?.visibility = if (x > 0) View.VISIBLE else View.INVISIBLE
        leftBack?.visibility = if (x < 0) View.VISIBLE else View.INVISIBLE
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
            MotionEvent.ACTION_MOVE -> if (startsDrag(e)) show(e.x - x0)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = front.translationX
                val done = dragging && e.actionMasked == MotionEvent.ACTION_UP && abs(dx) > width * 0.35f
                if (done && dx > 0) front.animate().translationX(width.toFloat()).setDuration(150).withEndAction { onRight?.invoke(this) }.start()
                else if (done && dx < 0) front.animate().translationX(-width.toFloat()).setDuration(150).withEndAction { onLeft?.invoke(this) }.start()
                else front.animate().translationX(0f).setDuration(150).withEndAction { show(0f) }.start()
                dragging = false
            }
        }
        return true
    }

    /** Put the row back (after Undo, or when the row stays in place). */
    fun reset() { show(0f); visibility = View.VISIBLE }
}
