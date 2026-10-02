package com.tuchus.measure

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** A room seen from above: walls with their lengths, the area, corner angles, a grid and a scale bar. */
class FloorPlanView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    /** Corners in metres on the floor, in the order they were tapped. */
    var corners: List<FloatArray> = emptyList()
        set(v) { field = level(v); invalidate() }
    var imperial = false
        set(v) { field = v; invalidate() }
    var title = ""
        set(v) { field = v; invalidate() }

    private val wall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.MITER
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    /** Turns the plan so its longest wall runs left to right. */
    private fun level(pts: List<FloatArray>): List<FloatArray> {
        if (pts.size < 2) return pts
        var best = 0; var bestLen = 0f
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            val len = hypot(b[0] - a[0], b[1] - a[1])
            if (len > bestLen) { bestLen = len; best = i }
        }
        val a = pts[best]; val b = pts[(best + 1) % pts.size]
        val angle = -atan2(b[1] - a[1], b[0] - a[0])
        val c = cos(angle); val s = sin(angle)
        return pts.map { floatArrayOf(it[0] * c - it[1] * s, it[0] * s + it[1] * c) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawPlan(canvas, width, height, resources.displayMetrics.density, dark = false)
    }

    fun drawPlan(c: Canvas, w: Int, h: Int, unit: Float, dark: Boolean) {
        val bg = context.getColor(R.color.surface)
        val fg = context.getColor(R.color.fg)
        val muted = context.getColor(R.color.muted)
        val tape = context.getColor(R.color.tape)
        val ink = context.getColor(R.color.ink)
        c.drawColor(bg)
        if (corners.size < 3) {
            small.color = muted; small.textSize = 16 * unit
            c.drawText("Mark at least three corners", w / 2f, h / 2f, small)
            return
        }

        // Fit the room into the space, leaving room for the wall labels
        val margin = 56 * unit
        val top = if (title.isNotBlank()) 40 * unit else 0f
        val minX = corners.minOf { it[0] }; val maxX = corners.maxOf { it[0] }
        val minY = corners.minOf { it[1] }; val maxY = corners.maxOf { it[1] }
        val spanX = (maxX - minX).coerceAtLeast(0.1f); val spanY = (maxY - minY).coerceAtLeast(0.1f)
        val scale = minOf((w - 2 * margin) / spanX, (h - top - 2 * margin - 40 * unit) / spanY)
        val ox = (w - spanX * scale) / 2f - minX * scale
        val oy = top + margin + (h - top - 2 * margin - 40 * unit - spanY * scale) / 2f - minY * scale
        fun sx(x: Float) = ox + x * scale
        fun sy(y: Float) = oy + y * scale

        // Grid: one line per metre, or per foot
        val step = if (imperial) 0.3048f else 1f
        grid.color = (muted and 0x00FFFFFF) or 0x30000000; grid.strokeWidth = 1 * unit
        var gx = floor((-ox / scale) / step) * step
        while (sx(gx) < w) { c.drawLine(sx(gx), top, sx(gx), h.toFloat(), grid); gx += step }
        var gy = floor(((top - oy) / scale) / step) * step
        while (sy(gy) < h) { if (sy(gy) >= top) c.drawLine(0f, sy(gy), w.toFloat(), sy(gy), grid); gy += step }

        // Room
        path.reset()
        path.moveTo(sx(corners[0][0]), sy(corners[0][1]))
        for (p in corners.drop(1)) path.lineTo(sx(p[0]), sy(p[1]))
        path.close()
        fill.color = (tape and 0x00FFFFFF) or 0x40000000
        c.drawPath(path, fill)
        wall.color = fg; wall.strokeWidth = 6 * unit
        c.drawPath(path, wall)

        // Which way is outside: depends on whether the corners go clockwise
        var signed = 0f
        for (i in corners.indices) {
            val a = corners[i]; val b = corners[(i + 1) % corners.size]
            signed += a[0] * b[1] - b[0] * a[1]
        }
        val outward = if (signed > 0) -1f else 1f

        // Wall lengths outside each wall
        text.textSize = 15 * unit
        for (i in corners.indices) {
            val a = corners[i]; val b = corners[(i + 1) % corners.size]
            val len = hypot(b[0] - a[0], b[1] - a[1])
            if (len < 0.01f) continue
            val nx = -(b[1] - a[1]) / len * outward; val ny = (b[0] - a[0]) / len * outward
            val mx = sx((a[0] + b[0]) / 2) + nx * 22 * unit
            val my = sy((a[1] + b[1]) / 2) + ny * 22 * unit
            label(c, mx, my, Units.format(len, imperial), tape, ink, text, unit)
        }

        // Corner angles just inside each corner
        small.textSize = 12 * unit; small.color = muted
        for (i in corners.indices) {
            val prev = corners[(i - 1 + corners.size) % corners.size]
            val cur = corners[i]
            val next = corners[(i + 1) % corners.size]
            val deg = Units.angle(floatArrayOf(prev[0], 0f, prev[1]), floatArrayOf(cur[0], 0f, cur[1]), floatArrayOf(next[0], 0f, next[1]))
            var dx = (prev[0] - cur[0]) / hypot(prev[0] - cur[0], prev[1] - cur[1]) + (next[0] - cur[0]) / hypot(next[0] - cur[0], next[1] - cur[1])
            var dy = (prev[1] - cur[1]) / hypot(prev[0] - cur[0], prev[1] - cur[1]) + (next[1] - cur[1]) / hypot(next[0] - cur[0], next[1] - cur[1])
            val l = hypot(dx, dy)
            if (l < 0.01f) continue
            dx /= l; dy /= l
            dot.color = fg
            c.drawCircle(sx(cur[0]), sy(cur[1]), 4.5f * unit, dot)
            c.drawText("${deg.roundToInt()}°", sx(cur[0]) + dx * 26 * unit, sy(cur[1]) + dy * 26 * unit + 4 * unit, small)
        }

        // Area and the distance round, in the middle of the room
        val area = Units.polygonArea(corners.map { floatArrayOf(it[0], 0f, it[1]) })
        val around = corners.indices.sumOf { i ->
            val a = corners[i]; val b = corners[(i + 1) % corners.size]
            hypot(b[0] - a[0], b[1] - a[1]).toDouble()
        }.toFloat()
        val cx = sx(corners.sumOf { it[0].toDouble() }.toFloat() / corners.size)
        val cy = sy(corners.sumOf { it[1].toDouble() }.toFloat() / corners.size)
        text.textSize = 20 * unit
        label(c, cx, cy, Units.formatArea(area, imperial), bg, fg, text, unit)
        small.textSize = 13 * unit; small.color = fg
        c.drawText("${Units.format(around, imperial)} round the walls", cx, cy + 30 * unit, small)

        if (title.isNotBlank()) {
            text.textSize = 22 * unit; text.color = fg
            c.drawText(title, w / 2f, 28 * unit, text)
        }

        // Scale bar along the bottom
        val barLen = step * scale * (if (imperial) 3 else 1)
        val bx = 20 * unit; val by = h - 20 * unit
        wall.strokeWidth = 3 * unit
        c.drawLine(bx, by, bx + barLen, by, wall)
        c.drawLine(bx, by - 6 * unit, bx, by + 2 * unit, wall)
        c.drawLine(bx + barLen, by - 6 * unit, bx + barLen, by + 2 * unit, wall)
        small.textAlign = Paint.Align.LEFT; small.color = fg; small.textSize = 12 * unit
        c.drawText(if (imperial) "3 ft" else "1 m", bx + barLen + 8 * unit, by + 4 * unit, small)
        small.textAlign = Paint.Align.CENTER
    }

    private fun label(c: Canvas, x: Float, y: Float, s: String, bg: Int, fg: Int, p: Paint, unit: Float) {
        p.color = fg
        val w = p.measureText(s) + 14 * unit
        val h = p.textSize * 1.6f
        rect.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
        labelBg.color = bg
        c.drawRoundRect(rect, 7 * unit, 7 * unit, labelBg)
        val fm = p.fontMetrics
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2, p)
    }
}
