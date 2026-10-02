package com.tuchus.measure

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Maps points in a photo onto a flat surface, given four photo points and where they sit on that surface.
 * This is what lets a photo taken at an angle still measure correctly.
 */
class Homography private constructor(private val h: DoubleArray) {

    fun map(x: Float, y: Float): FloatArray {
        val w = h[6] * x + h[7] * y + 1.0
        return floatArrayOf(((h[0] * x + h[1] * y + h[2]) / w).toFloat(), ((h[3] * x + h[4] * y + h[5]) / w).toFloat())
    }

    fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val a = map(ax, ay); val b = map(bx, by)
        return hypot(b[0] - a[0], b[1] - a[1])
    }

    companion object {
        /** [from] and [to] are four x,y pairs each. Returns null when the points don't make a usable shape. */
        fun solve(from: FloatArray, to: FloatArray): Homography? {
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val x = from[i * 2].toDouble(); val y = from[i * 2 + 1].toDouble()
                val u = to[i * 2].toDouble(); val v = to[i * 2 + 1].toDouble()
                a[i * 2] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            // Gaussian elimination with partial pivoting
            for (col in 0 until 8) {
                var pivot = col
                for (r in col + 1 until 8) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
                if (abs(a[pivot][col]) < 1e-12) return null
                val t = a[col]; a[col] = a[pivot]; a[pivot] = t
                for (r in 0 until 8) {
                    if (r == col) continue
                    val f = a[r][col] / a[col][col]
                    for (k in col until 9) a[r][k] -= f * a[col][k]
                }
            }
            return Homography(DoubleArray(8) { a[it][8] / a[it][it] })
        }
    }
}
