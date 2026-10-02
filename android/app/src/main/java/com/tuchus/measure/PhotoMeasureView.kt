package com.tuchus.measure

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** Shows the photo and lets you place and drag points on it. Points are kept in photo pixels. */
class PhotoMeasureView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var onChange: (() -> Unit)? = null
    var imperial = false
        set(v) { field = v; invalidate() }
    var refMm = 85.6f
        set(v) { field = v; invalidate() }

    val ref = ArrayList<PointF>()
    val pts = ArrayList<PointF>()
    val hasImage get() = bitmap != null

    private var bitmap: Bitmap? = null
    private val toScreen = Matrix()
    private val toPhoto = Matrix()
    private var dragList: ArrayList<PointF>? = null
    private var dragIndex = -1
    private var fingerX = 0f

    private val dp = resources.displayMetrics.density
    private val tape = context.getColor(R.color.tape)
    private val mark = context.getColor(R.color.mark)
    private val ink = context.getColor(R.color.ink)

    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1 * dp; color = context.getColor(R.color.line)
    }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.muted); textSize = 17 * dp; textAlign = Paint.Align.CENTER
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6 * dp; color = 0x66000000; strokeCap = Paint.Cap.ROUND
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * dp; strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = 0xFFFFFFFF.toInt()
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; color = ink
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16 * dp; isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }
    private val loupeEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * dp; color = 0xFFFFFFFF.toInt()
    }
    private val loupeOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6 * dp; color = 0x88000000.toInt()
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp
    }
    private val rect = RectF()
    private val loupeMatrix = Matrix()
    private val loupePath = Path()
    private val tmp = FloatArray(2)

    fun setImage(b: Bitmap) {
        bitmap = b
        ref.clear(); pts.clear()
        fit()
        invalidate()
        onChange?.invoke()
    }

    fun undo() {
        if (pts.isNotEmpty()) pts.removeAt(pts.size - 1) else if (ref.isNotEmpty()) ref.removeAt(ref.size - 1)
        changed()
    }

    fun clearRef() { ref.clear(); changed() }
    fun clearLines() { pts.clear(); changed() }

    /** Millimetres per photo pixel, once the known object is marked. */
    private fun mmPerPixel(): Float? {
        if (ref.size < 2 || refMm <= 0f) return null
        val d = hypot(ref[1].x - ref[0].x, ref[1].y - ref[0].y)
        return if (d > 0f) refMm / d else null
    }

    fun lengthsMetres(): List<Float> {
        val scale = mmPerPixel() ?: return emptyList()
        return (0 until pts.size / 2).map { i ->
            val a = pts[i * 2]; val b = pts[i * 2 + 1]
            hypot(b.x - a.x, b.y - a.y) * scale / 1000f
        }
    }

    /** The photo as shown on screen with its marks, cropped to the photo. */
    fun render(): Bitmap? {
        val b = bitmap ?: return null
        val shown = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        toScreen.mapRect(shown)
        val out = Bitmap.createBitmap(shown.width().toInt().coerceAtLeast(1), shown.height().toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.translate(-shown.left, -shown.top)
        c.drawBitmap(b, toScreen, photoPaint)
        drawMarks(c)
        return out
    }

    private fun changed() { invalidate(); onChange?.invoke() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { fit() }

    private fun fit() {
        val b = bitmap ?: return
        toScreen.setRectToRect(
            RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()),
            RectF(0f, 0f, width.toFloat(), height.toFloat()), Matrix.ScaleToFit.CENTER
        )
        toScreen.invert(toPhoto)
    }

    private fun screen(p: PointF): PointF {
        tmp[0] = p.x; tmp[1] = p.y; toScreen.mapPoints(tmp); return PointF(tmp[0], tmp[1])
    }

    private fun photo(x: Float, y: Float): PointF {
        val b = bitmap!!
        tmp[0] = x; tmp[1] = y; toPhoto.mapPoints(tmp)
        return PointF(tmp[0].coerceIn(0f, b.width - 1f), tmp[1].coerceIn(0f, b.height - 1f))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                var best = 36 * dp
                dragList = null
                for (list in listOf(ref, pts)) for ((i, p) in list.withIndex()) {
                    val s = screen(p)
                    val d = hypot(s.x - e.x, s.y - e.y)
                    if (d < best) { best = d; dragList = list; dragIndex = i }
                }
                if (dragList == null) {
                    val list = if (ref.size < 2) ref else pts
                    list.add(photo(e.x, e.y))
                    dragList = list; dragIndex = list.size - 1
                }
                fingerX = e.x
                changed()
            }
            MotionEvent.ACTION_MOVE -> {
                dragList?.let { it[dragIndex] = photo(e.x, e.y) }
                fingerX = e.x
                changed()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragList = null
                changed()
                if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap
        if (b == null) {
            rect.set(1f, 1f, width - 1f, height - 1f)
            canvas.drawRoundRect(rect, 12 * dp, 12 * dp, frame)
            canvas.drawText("Your photo appears here", width / 2f, height / 2f, empty)
            return
        }
        canvas.drawBitmap(b, toScreen, photoPaint)
        drawMarks(canvas)
        dragList?.let { drawLoupe(canvas, b, it[dragIndex]) }
    }

    private fun drawMarks(c: Canvas) {
        if (ref.size == 2) drawLine(c, screen(ref[0]), screen(ref[1]), mark, "known")
        val scale = mmPerPixel()
        for (i in 0 until pts.size / 2) {
            val a = pts[i * 2]; val b = pts[i * 2 + 1]
            val text = scale?.let { Units.format(hypot(b.x - a.x, b.y - a.y) * it / 1000f, imperial) } ?: "mark the known object first"
            drawLine(c, screen(a), screen(b), tape, text)
        }
        for (p in ref) drawDot(c, screen(p), mark)
        for (p in pts) drawDot(c, screen(p), tape)
    }

    private fun drawLine(c: Canvas, a: PointF, b: PointF, color: Int, text: String) {
        stroke.color = color
        c.drawLine(a.x, a.y, b.x, b.y, shadow)
        c.drawLine(a.x, a.y, b.x, b.y, stroke)
        val x = (a.x + b.x) / 2f
        val y = (a.y + b.y) / 2f - 22 * dp
        labelBg.color = color
        labelText.color = if (color == mark) 0xFFFFFFFF.toInt() else ink
        val w = labelText.measureText(text) + 18 * dp
        val h = 28 * dp
        rect.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
        c.drawRoundRect(rect, 8 * dp, 8 * dp, labelBg)
        c.drawRoundRect(rect, 8 * dp, 8 * dp, labelEdge)
        val fm = labelText.fontMetrics
        c.drawText(text, x, y - (fm.ascent + fm.descent) / 2, labelText)
    }

    private fun drawDot(c: Canvas, p: PointF, color: Int) {
        fill.color = color
        c.drawCircle(p.x, p.y, 7 * dp, fill)
        c.drawCircle(p.x, p.y, 7 * dp, white)
    }

    /** A magnified view of the point under your finger, in the corner away from it. */
    private fun drawLoupe(c: Canvas, b: Bitmap, target: PointF) {
        val r = 62 * dp
        val cx = if (fingerX < width / 2f) width - r - 12 * dp else r + 12 * dp
        val cy = r + 12 * dp
        val s = screen(target)
        loupeMatrix.set(toScreen)
        loupeMatrix.postScale(3f, 3f, s.x, s.y)
        loupeMatrix.postTranslate(cx - s.x, cy - s.y)
        c.save()
        loupePath.reset()
        loupePath.addCircle(cx, cy, r, Path.Direction.CW)
        c.clipPath(loupePath)
        c.drawColor(0xFF000000.toInt())
        c.drawBitmap(b, loupeMatrix, photoPaint)
        cross.color = if (dragList === ref) mark else tape
        c.drawLine(cx - r, cy, cx - 6 * dp, cy, cross)
        c.drawLine(cx + 6 * dp, cy, cx + r, cy, cross)
        c.drawLine(cx, cy - r, cx, cy - 6 * dp, cross)
        c.drawLine(cx, cy + 6 * dp, cx, cy + r, cross)
        c.restore()
        c.drawCircle(cx, cy, r, loupeOuter)
        c.drawCircle(cx, cy, r, loupeEdge)
    }
}
