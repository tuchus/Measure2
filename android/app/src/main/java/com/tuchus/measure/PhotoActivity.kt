package com.tuchus.measure

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.max
import kotlin.math.min

class PhotoActivity : Activity() {
    private lateinit var measure: PhotoMeasureView
    private lateinit var refType: Spinner
    private lateinit var customMm: EditText
    private lateinit var step: TextView
    private lateinit var results: TextView
    private lateinit var unitsButton: Button
    private var shotUri: Uri? = null
    private var imperial = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo)
        measure = findViewById(R.id.measure)
        refType = findViewById(R.id.refType)
        customMm = findViewById(R.id.customMm)
        step = findViewById(R.id.step)
        results = findViewById(R.id.results)
        unitsButton = findViewById(R.id.photoUnits)
        shotUri = savedInstanceState?.getString("shot")?.let(Uri::parse)

        imperial = Units.isImperial(this)
        measure.imperial = imperial

        refType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Refs.all.map { it.label })
        refType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                customMm.visibility = if (Refs.all[position].mm <= 0f) View.VISIBLE else View.GONE
                applyRef()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        customMm.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = applyRef()
        })

        findViewById<Button>(R.id.takePhoto).setOnClickListener { takePhoto() }
        findViewById<Button>(R.id.choosePhoto).setOnClickListener {
            val pick = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
            try { startActivityForResult(pick, REQUEST_PICK) } catch (e: ActivityNotFoundException) { toast("No gallery app found.") }
        }
        findViewById<Button>(R.id.photoUndo).setOnClickListener { measure.undo() }
        findViewById<Button>(R.id.remark).setOnClickListener { measure.clearRef() }
        findViewById<Button>(R.id.photoClear).setOnClickListener { measure.clearLines() }
        unitsButton.setOnClickListener {
            imperial = !imperial
            Units.setImperial(this, imperial)
            measure.imperial = imperial
            refresh()
        }
        measure.onChange = { refresh() }
        applyRef()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        shotUri?.let { outState.putString("shot", it.toString()) }
    }

    private fun takePhoto() {
        // The app holds the camera permission for room measuring, so Android requires it here too
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }
        val dir = File(cacheDir, "photos").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "shot-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        shotUri = uri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivityForResult(intent, REQUEST_SHOT) } catch (e: ActivityNotFoundException) { toast("No camera app found.") }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) takePhoto()
        else toast("Taking a photo needs the camera. You can still choose a photo from the gallery.")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_SHOT -> shotUri?.let { load(it) }
            REQUEST_PICK -> data?.data?.let { load(it) }
        }
    }

    private fun load(uri: Uri) {
        try {
            // ImageDecoder turns the photo the right way up by itself
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = min(1f, MAX_SIDE.toFloat() / max(w, h))
                decoder.setTargetSize(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            measure.setImage(bitmap)
        } catch (e: Exception) {
            toast("Couldn't open that photo.")
        }
    }

    private fun applyRef() {
        val ref = Refs.all[refType.selectedItemPosition.coerceAtLeast(0)]
        measure.refMm = if (ref.mm > 0f) ref.mm else customMm.text.toString().toFloatOrNull() ?: 0f
        refresh()
    }

    private fun refresh() {
        unitsButton.text = "Units: ${Units.shortName(imperial)}"
        step.text = when {
            !measure.hasImage -> "Take or choose a photo to start."
            measure.ref.size < 2 -> "Step 1 of 2: tap both ends of the known object (red)."
            else -> "Step 2 of 2: tap two points to measure (yellow). Drag any point to adjust it."
        }
        val lengths = measure.lengthsMetres()
        results.text = if (lengths.isEmpty()) "" else
            lengths.mapIndexed { i, m -> "Line ${i + 1}:  ${Units.format(m, imperial)}" }.joinToString("\n")
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQUEST_SHOT = 1
        private const val REQUEST_PICK = 2
        private const val REQUEST_CAMERA = 3
        private const val MAX_SIDE = 2400
    }
}
