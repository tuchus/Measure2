package com.tuchus.measure

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot

class FloorPlanActivity : Activity() {
    private lateinit var plan: FloorPlanView
    private lateinit var walls: TextView
    private lateinit var unitsButton: Button
    private var imperial = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_floor_plan)
        plan = findViewById(R.id.plan)
        walls = findViewById(R.id.walls)
        unitsButton = findViewById(R.id.planUnits)
        imperial = Units.isImperial(this)

        val xz = intent.getFloatArrayExtra(EXTRA_XZ) ?: FloatArray(0)
        plan.corners = (0 until xz.size / 2).map { floatArrayOf(xz[it * 2], xz[it * 2 + 1]) }
        plan.imperial = imperial

        findViewById<EditText>(R.id.roomName).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { plan.title = s?.toString() ?: "" }
        })
        unitsButton.setOnClickListener {
            imperial = !imperial
            Units.setImperial(this, imperial)
            plan.imperial = imperial
            refresh()
        }
        findViewById<Button>(R.id.planSave).setOnClickListener { save()?.let { toast("Saved to Pictures/Measure") } }
        findViewById<Button>(R.id.planShare).setOnClickListener { save()?.let { Gallery.share(this, it) } }
        findViewById<Button>(R.id.planDone).setOnClickListener { finish() }
        refresh()
    }

    private fun refresh() {
        unitsButton.text = "Units: ${Units.shortName(imperial)}"
        val c = plan.corners
        walls.text = c.indices.joinToString("   ") { i ->
            val a = c[i]; val b = c[(i + 1) % c.size]
            "Wall ${i + 1}: ${Units.format(hypot(b[0] - a[0], b[1] - a[1]), imperial)}"
        }
    }

    /** Draws the plan large and sharp, then saves it. */
    private fun save(): Uri? {
        val size = 2000
        val image = Bitmap.createBitmap(size, (size * 1.25f).toInt(), Bitmap.Config.ARGB_8888)
        plan.drawPlan(Canvas(image), image.width, image.height, size / 400f, dark = false)
        val uri = Gallery.save(this, image)
        if (uri == null) toast("Couldn't save the plan.")
        return uri
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_XZ = "xz"
    }
}
