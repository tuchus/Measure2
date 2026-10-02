package com.tuchus.measure

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

class LevelActivity : Activity(), SensorEventListener {
    private lateinit var sensors: SensorManager
    private lateinit var level: LevelView
    private lateinit var angle: TextView
    private lateinit var mode: TextView
    private lateinit var detail: TextView
    private val g = FloatArray(3)
    private var haveReading = false
    private var wasLevel = false
    private var offsetX = 0f
    private var offsetY = 0f
    private var offsetEdge = 0f
    private var rawX = 0f
    private var rawY = 0f
    private var rawEdge = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_level)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        level = findViewById(R.id.level)
        angle = findViewById(R.id.angle)
        mode = findViewById(R.id.mode)
        detail = findViewById(R.id.detail)

        val prefs = getSharedPreferences("measure", MODE_PRIVATE)
        offsetX = prefs.getFloat("levelX", 0f)
        offsetY = prefs.getFloat("levelY", 0f)
        offsetEdge = prefs.getFloat("levelEdge", 0f)

        findViewById<Button>(R.id.calibrate).setOnClickListener {
            // Whatever the phone reads now counts as level from here on
            if (level.flat) { offsetX = rawX; offsetY = rawY } else offsetEdge = rawEdge
            save()
        }
        findViewById<Button>(R.id.resetCalibration).setOnClickListener {
            offsetX = 0f; offsetY = 0f; offsetEdge = 0f
            save()
        }
        findViewById<Button>(R.id.levelDone).setOnClickListener { finish() }
    }

    private fun save() {
        getSharedPreferences("measure", MODE_PRIVATE).edit()
            .putFloat("levelX", offsetX).putFloat("levelY", offsetY).putFloat("levelEdge", offsetEdge).apply()
        angle.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    override fun onResume() {
        super.onResume()
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            detail.text = "This phone has no tilt sensor."
            return
        }
        sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onPause() {
        super.onPause()
        sensors.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        // Smooth the readings so the bubble settles instead of jittering
        val k = if (haveReading) 0.12f else 1f
        for (i in 0..2) g[i] += (e.values[i] - g[i]) * k
        haveReading = true

        val (x, y, z) = Triple(g[0], g[1], g[2])
        val flat = abs(z) > sqrt(x * x + y * y)
        level.flat = flat
        if (flat) {
            rawX = Math.toDegrees(atan2(x, abs(z)).toDouble()).toFloat()
            rawY = Math.toDegrees(atan2(y, abs(z)).toDouble()).toFloat()
            level.tiltX = rawX - offsetX
            level.tiltY = rawY - offsetY
            val total = sqrt(level.tiltX * level.tiltX + level.tiltY * level.tiltY)
            mode.text = "Lying flat"
            angle.text = String.format(Locale.US, "%.1f°", total)
            detail.text = String.format(Locale.US, "Left to right %.1f°   Top to bottom %.1f°", level.tiltX, level.tiltY)
        } else {
            // Angle of the phone's edge from upright; the nearest quarter turn counts as the goal
            val a = Math.toDegrees(atan2(x, y).toDouble()).toFloat()
            val nearest = (a / 90f).roundToInt() * 90f
            rawEdge = a - nearest
            level.edgeError = rawEdge - offsetEdge
            mode.text = if (nearest.roundToInt() % 180 == 0) "Standing upright: checks plumb" else "On its long side: checks level"
            angle.text = String.format(Locale.US, "%.1f°", abs(level.edgeError))
            detail.text = "Hold an edge of the phone against the surface"
        }
        val nowLevel = level.isLevel()
        if (nowLevel && !wasLevel) angle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        wasLevel = nowLevel
        angle.setTextColor(if (nowLevel) 0xFF3DDC84.toInt() else getColor(R.color.fg))
        level.invalidate()
    }
}
