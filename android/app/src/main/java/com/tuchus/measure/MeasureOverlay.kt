package com.tuchus.measure

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Draws points, lines, shapes, labels and the aiming circle over the camera image. */
class MeasureOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    class Snapshot(
        val tracking: Boolean,
        val view: FloatArray,
        val proj: FloatArray,
        val points: List<FloatArray>,
        val segments: List<IntArray>,
        val shapes: List<IntArray>,
        val pending: Int,
        val target: FloatArray?,
        val snapIndex: Int,
        val guide: ArMeasureActivity.Guide,
    )

    @Volatile var snapshot: Snapshot? = null
    @Volatile var imperial = false

    private val dp = resources.displayMetrics.density
    private val tape = context.getColor(R.color.tape)
    private val ink = context.getColor(R.color.ink)
    private val guideGreen = 0xFF3DDC84.toInt()

    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 7 * dp; color = 0x66000000; strokeCap = Paint.Cap.ROUND
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3.5f * dp; color = 0xFFFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND
    }
    private val preview = Paint(line).apply {
        color = tape; pathEffect = DashPathEffect(floatArrayOf(10 * dp, 7 * dp), 0f)
    }
    private val shapeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (tape and 0x00FFFFFF) or 0x45000000 }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tape }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * dp; color = 0xFFFFFFFF.toInt()
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = ink
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }
    private val ringShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6 * dp; color = 0x55000000
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * dp; color = 0xFFFFFFFF.toInt()
    }
    private val footerBg = Paint().apply { color = 0xE61C1B18.toInt() }
    private val footerText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); isFakeBoldText = true }
    private val rect = RectF()
    private val poly = Path()
    private val v4 = FloatArray(4)
    private val out4 = FloatArray(4)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawScene(canvas, true)
    }

    /** The same drawing without the aiming circle, for saved photos. */
    fun drawForPhoto(canvas: Canvas) = drawScene(canvas, false)

    /** A dark band along the bottom of a saved photo listing the measurements. */
    fun drawFooter(c: Canvas, w: Int, h: Int, lines: List<String>) {
        if (lines.isEmpty()) return
        val size = w / 26f
        footerText.textSize = size
        val pad = size * 0.7f
        val bandH = pad * 2 + size * 1.35f * lines.size
        c.drawRect(0f, h - bandH, w.toFloat(), h.toFloat(), footerBg)
        lines.forEachIndexed { i, s -> c.drawText(s, pad, h - bandH + pad + size * (1.35f * i + 1f), footerText) }
    }

    private fun drawScene(canvas: Canvas, withReticle: Boolean) {
        val s = snapshot ?: return
        if (s.tracking) {
            val eye = s.points.map { toEye(s, it) }

            for (shape in s.shapes) drawShape(canvas, s, shape, eye)

            for (seg in s.segments) {
                drawSegment(canvas, s, eye[seg[0]], eye[seg[1]], Units.distance(s.points[seg[0]], s.points[seg[1]]), line, tape)
            }
            if (s.pending >= 0 && s.target != null) {
                val guided = s.guide != ArMeasureActivity.Guide.NONE
                preview.color = if (guided) guideGreen else tape
                drawSegment(canvas, s, eye[s.pending], toEye(s, s.target),
                    Units.distance(s.points[s.pending], s.target), preview, if (guided) guideGreen else tape)
            }
            drawAngles(canvas, s, eye)
            for ((i, v) in eye.withIndex()) {
                if (v[2] >= NEAR) continue
                val p = project(s, v)
                val r = if (withReticle && i == s.snapIndex) 11 * dp else 7 * dp
                canvas.drawCircle(p.x, p.y, r, dotFill)
                canvas.drawCircle(p.x, p.y, r, dotRing)
            }
        }
        if (withReticle) {
            val snapped = s.tracking && s.snapIndex >= 0 && s.snapIndex < s.points.size
            val at = if (snapped) toEye(s, s.points[s.snapIndex]).takeIf { it[2] < NEAR }?.let { project(s, it) } else null
            drawReticle(canvas, s.tracking && s.target != null, at)
        }
    }

    private fun drawShape(c: Canvas, s: Snapshot, shape: IntArray, eye: List<FloatArray>) {
        if (shape.any { eye[it][2] >= NEAR }) return
        val pts = shape.map { project(s, eye[it]) }
        poly.reset()
        poly.moveTo(pts[0].x, pts[0].y)
        for (p in pts.drop(1)) poly.lineTo(p.x, p.y)
        poly.close()
        c.drawPath(poly, shapeFill)
        val cx = pts.sumOf { it.x.toDouble() }.toFloat() / pts.size
        val cy = pts.sumOf { it.y.toDouble() }.toFloat() / pts.size
        val area = Units.polygonArea(shape.map { s.points[it] })
        drawLabel(c, cx, cy, Units.formatArea(area, imperial), 0xFFFFFFFF.toInt(), ink, 19f)
    }

    private fun drawAngles(c: Canvas, s: Snapshot, eye: List<FloatArray>) {
        // A corner is a point where exactly two lines meet
        val touching = HashMap<Int, MutableList<Int>>()
        for (seg in s.segments) {
            touching.getOrPut(seg[0]) { ArrayList() }.add(seg[1])
            touching.getOrPut(seg[1]) { ArrayList() }.add(seg[0])
        }
        for ((corner, others) in touching) {
            if (others.size != 2 || eye[corner][2] >= NEAR) continue
            val deg = Units.angle(s.points[others[0]], s.points[corner], s.points[others[1]])
            if (deg < 1f || deg > 179f) continue
            val p = project(s, eye[corner])
            // Put the label inside the corner, along the line halfway between the two arms
            var dx = 0f; var dy = 0f
            for (o in others) {
                if (eye[o][2] >= NEAR) continue
                val q = project(s, eye[o])
                val len = hypot(q.x - p.x, q.y - p.y).coerceAtLeast(1f)
                dx += (q.x - p.x) / len; dy += (q.y - p.y) / len
            }
            val len = hypot(dx, dy)
            if (len > 0.01f) { dx /= len; dy /= len } else { dx = 0f; dy = -1f }
            drawLabel(c, p.x + dx * 40 * dp, p.y + dy * 40 * dp, "${deg.roundToInt()}°", 0xEE1C1B18.toInt(), 0xFFFFFFFF.toInt(), 15f)
        }
    }

    private fun drawSegment(c: Canvas, s: Snapshot, a: FloatArray, b: FloatArray, metres: Float, paint: Paint, labelColor: Int) {
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
        drawLabel(c, (pa.x + pb.x) / 2f, (pa.y + pb.y) / 2f - 24 * dp, Units.format(metres, imperial), labelColor, ink, 19f)
    }

    private fun drawLabel(c: Canvas, x: Float, y: Float, text: String, bg: Int, fg: Int, sizeDp: Float) {
        labelText.textSize = sizeDp * dp
        labelText.color = fg
        labelBg.color = bg
        val w = labelText.measureText(text) + 20 * dp
        val h = labelText.textSize * 1.75f
        rect.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
        c.drawRoundRect(rect, 10 * dp, 10 * dp, labelBg)
        c.drawRoundRect(rect, 10 * dp, 10 * dp, labelEdge)
        val fm = labelText.fontMetrics
        c.drawText(text, x, y - (fm.ascent + fm.descent) / 2, labelText)
    }

    private fun drawReticle(c: Canvas, onSurface: Boolean, snappedAt: PointF?) {
        val cx = snappedAt?.x ?: (width / 2f)
        val cy = snappedAt?.y ?: (height / 2f)
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

    private fun toEye(s: Snapshot, p: FloatArray): FloatArray {
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
