package com.tuchus.measure

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import com.google.ar.core.ArCoreApk

class MainActivity : Activity() {
    private lateinit var metric: Button
    private lateinit var imperial: Button
    private lateinit var room: Button
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        metric = findViewById(R.id.unitsMetric)
        imperial = findViewById(R.id.unitsImperial)
        room = findViewById(R.id.roomButton)
        status = findViewById(R.id.arStatus)

        metric.setOnClickListener { Units.setImperial(this, false); showUnits() }
        imperial.setOnClickListener { Units.setImperial(this, true); showUnits() }
        room.setOnClickListener { startActivity(Intent(this, ArMeasureActivity::class.java)) }
        findViewById<Button>(R.id.photoButton).setOnClickListener {
            startActivity(Intent(this, PhotoActivity::class.java))
        }
        findViewById<Button>(R.id.levelButton).setOnClickListener {
            startActivity(Intent(this, LevelActivity::class.java))
        }
        findViewById<Button>(R.id.historyButton).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        showUnits()
        checkAr()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    private fun showUnits() {
        val imp = Units.isImperial(this)
        metric.isSelected = !imp
        imperial.isSelected = imp
    }

    private fun checkAr() {
        val a = ArCoreApk.getInstance().checkAvailability(this)
        if (a.isTransient) {
            handler.postDelayed({ checkAr() }, 250)
            return
        }
        room.isEnabled = a != ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE
        status.text = when (a) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> "Your phone is ready for room measuring."
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD ->
                "The first time you start, your phone will ask to install or update Google Play Services for AR."
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE ->
                "This phone can't measure in the room. Photo measuring still works."
            else -> "Couldn't check for AR support. You can still try."
        }
    }
}
