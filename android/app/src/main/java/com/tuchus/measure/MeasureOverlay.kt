package com.tuchus.measure

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.View

/** Draws points, lines, distance labels and the aiming circle over the camera image. */
class MeasureOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    class Snapshot(
        val tracking: Boolean,
        val view: FloatArray,
        val proj: FloatArray,
        val points: List<FloatArray>,
        val segments: List<IntArray>,
        val pending: Int,
        val hit: FloatArray?,
    )

    @Volatile var snapshot: Snapshot? = null
    @Volatile var imperial = false

    private val dp = resources.displayMetrics.density
    private val tape = context.getColor(R.color.tape)
    private val ink = context.getColor(R.color.ink)

    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 7 * dp; color = 0x66000000; strokeCap = Paint.Cap.ROUND
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3.5f * dp; color = 0xFFFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND
    }
    private val preview = Paint(line).apply {
        color = tape; pathEffect = DashPathEffect(floatArrayOf(10 * dp, 7 * dp), 0f)
    }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tape }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * dp; color = 0xFFFFFFFF.toInt()
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tape }
    private val labelEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = ink
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink; textSize = 19 * dp; isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }
    private val ringShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6 * dp; color = 0x55000000
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * dp; color = 0xFFFFFFFF.toInt()
    }
    private val rect = RectF()
    private val v4 = FloatArray(4)
    private val out4 = FloatArray(4)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = snapshot ?: return
        if (s.tracking) {
            val viewPts = s.points.map { toView(s, it) }
            for (seg in s.segments) {
                drawSegment(canvas, s, viewPts[seg[0]], viewPts[seg[1]],
                    Units.distance(s.points[seg[0]], s.points[seg[1]]), line)
            }
            if (s.pending >= 0 && s.hit != null) {
                drawSegment(canvas, s, viewPts[s.pending], toView(s, s.hit),
                    Units.distance(s.points[s.pending], s.hit), preview)
            }
            for (v in viewPts) {
                if (v[2] >= NEAR) continue
                val p = project(s, v)
                canvas.drawCircle(p.x, p.y, 7 * dp, dotFill)
                canvas.drawCircle(p.x, p.y, 7 * dp, dotRing)
            }
        }
        drawReticle(canvas, s.tracking && s.hit != null)
    }

    private fun drawSegment(c: Canvas, s: Snapshot, a: FloatArray, b: FloatArray, metres: Float, paint: Paint) {
        // Cut the line where it passes behind the camera, so it never flips across the screen
        val aFront = a[2] < NEAR
        val bFront = b[2] < NEAR
        if (!aFront && !bFront) return
        var p = a; var q = b
        if (!aFront || !bFront) {
            val t = (NEAR - a[2]) / (b[2] - a[2])
            val cut = floatArrayOf(a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]), NEAR)
            if (aFront) q = cut else p = cut
        }
        val pa = project(s, p)
        val pb = project(s, q)
        c.drawLine(pa.x, pa.y, pb.x, pb.y, shadow)
        c.drawLine(pa.x, pa.y, pb.x, pb.y, paint)
        drawLabel(c, (pa.x + pb.x) / 2f, (pa.y + pb.y) / 2f - 24 * dp, Units.format(metres, imperial))
    }

    private fun drawLabel(c: Canvas, x: Float, y: Float, text: String) {
        val w = labelText.measureText(text) + 22 * dp
        val h = 34 * dp
        rect.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
        c.drawRoundRect(rect, 10 * dp, 10 * dp, labelBg)
        c.drawRoundRect(rect, 10 * dp, 10 * dp, labelEdge)
        val fm = labelText.fontMetrics
        c.drawText(text, x, y - (fm.ascent + fm.descent) / 2, labelText)
    }

    private fun drawReticle(c: Canvas, onSurface: Boolean) {
        val cx = width / 2f
        val cy = height / 2f
        val r = 26 * dp
        ring.alpha = if (onSurface) 255 else 130
        c.drawCircle(cx, cy, r, ringShadow)
        c.drawCircle(cx, cy, r, ring)
        if (onSurface) {
            c.drawCircle(cx, cy, 5 * dp, dotFill)
        } else {
            val k = 7 * dp
            c.drawLine(cx - k, cy - k, cx + k, cy + k, ring)
            c.drawLine(cx - k, cy + k, cx + k, cy - k, ring)
        }
    }

    private fun toView(s: Snapshot, p: FloatArray): FloatArray {
        v4[0] = p[0]; v4[1] = p[1]; v4[2] = p[2]; v4[3] = 1f
        Matrix.multiplyMV(out4, 0, s.view, 0, v4, 0)
        return floatArrayOf(out4[0], out4[1], out4[2])
    }

    private fun project(s: Snapshot, v: FloatArray): PointF {
        v4[0] = v[0]; v4[1] = v[1]; v4[2] = v[2]; v4[3] = 1f
        Matrix.multiplyMV(out4, 0, s.proj, 0, v4, 0)
        val x = out4[0] / out4[3]
        val y = out4[1] / out4[3]
        return PointF((x + 1f) / 2f * width, (1f - y) / 2f * height)
    }

    companion object {
        /** In camera space, things in front of the camera have z below this. */
        private const val NEAR = -0.03f
    }
}
