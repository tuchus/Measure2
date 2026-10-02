package com.tuchus.measure

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * Lying flat: a round bubble that shows tilt in both directions.
 * Standing on an edge: a tube bubble that shows how far the edge is from level or plumb.
 */
class LevelView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var flat = true
    var tiltX = 0f      // degrees, flat mode
    var tiltY = 0f      // degrees, flat mode
    var edgeError = 0f  // degrees, edge mode

    private val dp = resources.displayMetrics.density
    private val tape = context.getColor(R.color.tape)
    private val ink = context.getColor(R.color.ink)
    private val green = 0xFF3DDC84.toInt()

    private val vial = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.surface) }
    private val vialEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * dp; color = ink
    }
    private val marks = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = context.getColor(R.color.muted)
    }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = ink
    }
    private val rect = RectF()

    fun isLevel() = if (flat) abs(tiltX) < LEVEL && abs(tiltY) < LEVEL else abs(edgeError) < LEVEL

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        bubble.color = if (isLevel()) green else tape
        val cx = width / 2f
        val cy = height / 2f
        if (flat) {
            val r = min(width, height) / 2f - 8 * dp
            val br = r * 0.18f
            c.drawCircle(cx, cy, r, vial)
            c.drawCircle(cx, cy, r, vialEdge)
            c.drawCircle(cx, cy, br * 1.25f, marks)
            c.drawCircle(cx, cy, r * 0.55f, marks)
            c.drawLine(cx - r, cy, cx + r, cy, marks)
            c.drawLine(cx, cy - r, cx, cy + r, marks)
            // The bubble floats to the high side; 10 degrees reaches the rim
            val travel = r - br - 4 * dp
            var bx = -tiltX / 10f * travel
            var by = tiltY / 10f * travel
            val d = kotlin.math.hypot(bx, by)
            if (d > travel) { bx *= travel / d; by *= travel / d }
            c.drawCircle(cx + bx, cy + by, br, bubble)
            c.drawCircle(cx + bx, cy + by, br, bubbleEdge)
        } else {
            val w = width - 24 * dp
            val h = 70 * dp
            rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            c.drawRoundRect(rect, h / 2, h / 2, vial)
            c.drawRoundRect(rect, h / 2, h / 2, vialEdge)
            val bw = h * 1.1f
            c.drawLine(cx - bw / 2 - 4 * dp, rect.top, cx - bw / 2 - 4 * dp, rect.bottom, marks)
            c.drawLine(cx + bw / 2 + 4 * dp, rect.top, cx + bw / 2 + 4 * dp, rect.bottom, marks)
            val travel = w / 2 - bw / 2 - 8 * dp
            val bx = (edgeError / 10f * travel).coerceIn(-travel, travel)
            rect.set(cx + bx - bw / 2, cy - h / 2 + 10 * dp, cx + bx + bw / 2, cy + h / 2 - 10 * dp)
            c.drawRoundRect(rect, h, h, bubble)
            c.drawRoundRect(rect, h, h, bubbleEdge)
        }
    }

    companion object {
        const val LEVEL = 0.3f
    }
}
