package com.tuchus.measure

import android.content.Context
import java.util.Locale
import kotlin.math.sqrt

object Units {
    private const val PREFS = "measure"

    fun isImperial(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("imperial", false)

    fun setImperial(c: Context, imperial: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("imperial", imperial).apply()
    }

    fun shortName(imperial: Boolean) = if (imperial) "in" else "cm"

    /** Formats a length given in metres. */
    fun format(metres: Float, imperial: Boolean): String {
        if (!metres.isFinite()) return "—"
        if (imperial) {
            val inches = metres / 0.0254f
            if (inches < 24f) return String.format(Locale.US, if (inches < 10f) "%.2f in" else "%.1f in", inches)
            var feet = (inches / 12f).toInt()
            var rest = inches - feet * 12f
            if (rest >= 11.95f) { feet += 1; rest = 0f }
            return String.format(Locale.US, "%d ft %.1f in", feet, rest)
        }
        return if (metres < 1f) String.format(Locale.US, "%.1f cm", metres * 100f)
        else String.format(Locale.US, "%.2f m", metres)
    }

    /** Formats an area given in square metres. */
    fun formatArea(m2: Float, imperial: Boolean): String {
        if (!m2.isFinite()) return "—"
        if (imperial) {
            val ft2 = m2 / 0.09290304f
            return if (ft2 < 1f) String.format(Locale.US, "%.1f sq in", ft2 * 144f)
            else String.format(Locale.US, "%.2f sq ft", ft2)
        }
        return if (m2 < 0.1f) String.format(Locale.US, "%.0f cm²", m2 * 10000f)
        else String.format(Locale.US, "%.2f m²", m2)
    }

    fun distance(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Area of a flat-ish loop of 3D points (Newell's method). */
    fun polygonArea(pts: List<FloatArray>): Float {
        var nx = 0f; var ny = 0f; var nz = 0f
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            nx += (a[1] - b[1]) * (a[2] + b[2])
            ny += (a[2] - b[2]) * (a[0] + b[0])
            nz += (a[0] - b[0]) * (a[1] + b[1])
        }
        return sqrt(nx * nx + ny * ny + nz * nz) / 2f
    }

    /** Angle at corner b, in degrees, between lines b-a and b-c. */
    fun angle(a: FloatArray, b: FloatArray, c: FloatArray): Float {
        val ux = a[0] - b[0]; val uy = a[1] - b[1]; val uz = a[2] - b[2]
        val vx = c[0] - b[0]; val vy = c[1] - b[1]; val vz = c[2] - b[2]
        val lu = sqrt(ux * ux + uy * uy + uz * uz); val lv = sqrt(vx * vx + vy * vy + vz * vz)
        if (lu == 0f || lv == 0f) return 0f
        val cos = ((ux * vx + uy * vy + uz * vz) / (lu * lv)).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.acos(cos).toDouble()).toFloat()
    }
}
