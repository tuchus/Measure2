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

    fun distance(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
