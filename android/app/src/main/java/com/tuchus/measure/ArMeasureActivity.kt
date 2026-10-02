package com.tuchus.measure

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class ArMeasureActivity : Activity(), GLSurfaceView.Renderer {

    enum class Mode { MEASURE, FLOOR, FIT }
    enum class Guide { NONE, VERTICAL, LEVEL, SQUARE }

    private lateinit var surface: GLSurfaceView
    private lateinit var overlay: MeasureOverlay
    private lateinit var readout: TextView
    private lateinit var hint: TextView
    private lateinit var total: TextView
    private lateinit var pairsButton: Button
    private lateinit var chainButton: Button
    private lateinit var unitsButton: Button
    private lateinit var closeButton: Button
    private lateinit var shareButton: Button
    private lateinit var addButton: Button
    private lateinit var undoButton: Button
    private lateinit var clearButton: Button
    private lateinit var modeButtons: Map<Mode, Button>
    private lateinit var measurePanel: View
    private lateinit var fitPanel: View
    private lateinit var fitPreset: Spinner
    private lateinit var fitW: EditText
    private lateinit var fitD: EditText
    private lateinit var fitH: EditText
    private lateinit var fitUnits: TextView

    private var session: Session? = null
    private var installRequested = false
    private val background = BackgroundRenderer()
    @Volatile private var textureSet = false
    private var viewWidth = 0
    private var viewHeight = 0
    private var geometryChanged = false
    @Volatile private var imperial = false
    @Volatile private var mode = Mode.MEASURE
    private var lastSaved: Uri? = null
    private var fillingFields = false

    // The box for "Will it fit?", in metres; written on the UI thread, read on the GL thread
    @Volatile private var boxW = 2.1f
    @Volatile private var boxD = 0.9f
    @Volatile private var boxH = 0.85f

    // Only touched on the GL thread
    private val anchors = ArrayList<Anchor>()
    private val segments = ArrayList<IntArray>()
    private val shapes = ArrayList<IntArray>()
    private val path = ArrayList<Int>()          // points of the joined path being drawn
    private val history = ArrayList<Step>()
    private var pending = -1
    private var chain = false
    private var placeRequested = false
    private var closeRequested = false
    private var captureRequested = false
    private var boxAnchor: Anchor? = null
    private var boxPos: FloatArray? = null
    private var boxYaw = 0f
    private var dragAt: FloatArray? = null
    private var dragEnded = false
    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val camPos = FloatArray(3)
    private var shown = ""

    private val dp by lazy { resources.displayMetrics.density }

    /** What one tap of + did, so Undo can take it back exactly. */
    private class Step(
        val newAnchor: Boolean, val newSegment: Boolean, val newShape: Boolean,
        val pendingBefore: Int, val pathBefore: List<Int>,
    )

    /** Where + would put a point right now. */
    private class Target(val pos: FloatArray, val hit: HitResult?, val snapIndex: Int, val guide: Guide)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surface = findViewById(R.id.surface)
        overlay = findViewById(R.id.overlay)
        readout = findViewById(R.id.readout)
        hint = findViewById(R.id.hint)
        total = findViewById(R.id.total)
        pairsButton = findViewById(R.id.modePairs)
        chainButton = findViewById(R.id.modeChain)
        unitsButton = findViewById(R.id.units)
        closeButton = findViewById(R.id.closeShape)
        shareButton = findViewById(R.id.share)
        addButton = findViewById(R.id.add)
        undoButton = findViewById(R.id.undo)
        clearButton = findViewById(R.id.clear)
        measurePanel = findViewById(R.id.measurePanel)
        fitPanel = findViewById(R.id.fitPanel)
        fitPreset = findViewById(R.id.fitPreset)
        fitW = findViewById(R.id.fitW)
        fitD = findViewById(R.id.fitD)
        fitH = findViewById(R.id.fitH)
        fitUnits = findViewById(R.id.fitUnits)
        modeButtons = mapOf(
            Mode.MEASURE to findViewById(R.id.tabMeasure),
            Mode.FLOOR to findViewById(R.id.tabFloor),
            Mode.FIT to findViewById(R.id.tabFit),
        )

        imperial = Units.isImperial(this)
        overlay.imperial = imperial

        surface.preserveEGLContextOnPause = true
        surface.setEGLContextClientVersion(2)
        surface.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surface.setRenderer(this)
        surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // Tapping anywhere on the camera view does the same as the + button.
        // In "Will it fit?" a drag moves the box instead.
        overlay.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f; var downY = 0f; var dragging = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false }
                    MotionEvent.ACTION_MOVE -> if (mode == Mode.FIT) {
                        if (!dragging && hypot(e.x - downX, e.y - downY) > 12 * dp) dragging = true
                        if (dragging) {
                            val p = floatArrayOf(e.x * viewWidth / v.width, e.y * viewHeight / v.height)
                            surface.queueEvent { dragAt = p }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragging) surface.queueEvent { dragEnded = true } else { v.performClick(); requestPoint() }
                    }
                }
                return true
            }
        })
        addButton.setOnClickListener { requestPoint() }
        undoButton.setOnClickListener { surface.queueEvent { undo() } }
        clearButton.setOnClickListener { surface.queueEvent { clearAll() } }
        findViewById<Button>(R.id.done).setOnClickListener { finish() }
        closeButton.setOnClickListener { surface.queueEvent { closeRequested = true } }
        findViewById<Button>(R.id.save).setOnClickListener { surface.queueEvent { captureRequested = true } }
        shareButton.setOnClickListener { lastSaved?.let { Gallery.share(this, it) } }
        pairsButton.setOnClickListener { setChain(false) }
        chainButton.setOnClickListener { setChain(true) }
        unitsButton.setOnClickListener {
            imperial = !imperial
            overlay.imperial = imperial
            Units.setImperial(this, imperial)
            showUnits()
        }
        for ((m, b) in modeButtons) b.setOnClickListener { setMode(m) }
        setupFit()
        setChain(false)
        setMode(intent.getStringExtra(EXTRA_MODE)?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.MEASURE)
        showUnits()
    }

    private fun setupFit() {
        fitPreset.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, FitPresets.all.map { it.label })
        fitPreset.setSelection(1)
        fitPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val p = FitPresets.all[position]
                if (p.w <= 0f) return
                boxW = p.w / 100f; boxD = p.d / 100f; boxH = p.h / 100f
                fillFitFields()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (fillingFields) return
                Units.fromField(fitW.text.toString(), imperial)?.let { boxW = it }
                Units.fromField(fitD.text.toString(), imperial)?.let { boxD = it }
                Units.fromField(fitH.text.toString(), imperial)?.let { boxH = it }
                if (fitPreset.selectedItemPosition != 0) {
                    val p = FitPresets.all[fitPreset.selectedItemPosition]
                    if (abs(p.w / 100f - boxW) > 0.004f || abs(p.d / 100f - boxD) > 0.004f || abs(p.h / 100f - boxH) > 0.004f) {
                        fitPreset.setSelection(0)
                    }
                }
            }
        }
        fitW.addTextChangedListener(watcher)
        fitD.addTextChangedListener(watcher)
        fitH.addTextChangedListener(watcher)
        findViewById<Button>(R.id.turnLeft).setOnClickListener { surface.queueEvent { boxYaw += TURN } }
        findViewById<Button>(R.id.turnRight).setOnClickListener { surface.queueEvent { boxYaw -= TURN } }
        findViewById<Button>(R.id.removeBox).setOnClickListener { surface.queueEvent { removeBox() } }
        fillFitFields()
    }

    private fun fillFitFields() {
        fillingFields = true
        fitW.setText(Units.toField(boxW, imperial))
        fitD.setText(Units.toField(boxD, imperial))
        fitH.setText(Units.toField(boxH, imperial))
        fitUnits.text = if (imperial) "Sizes in inches" else "Sizes in centimetres"
        fillingFields = false
    }

    private fun setMode(m: Mode) {
        mode = m
        for ((k, b) in modeButtons) b.isSelected = k == m
        measurePanel.visibility = if (m == Mode.MEASURE) View.VISIBLE else View.GONE
        fitPanel.visibility = if (m == Mode.FIT) View.VISIBLE else View.GONE
        undoButton.visibility = if (m == Mode.FIT) View.INVISIBLE else View.VISIBLE
        clearButton.visibility = if (m == Mode.FIT) View.INVISIBLE else View.VISIBLE
        addButton.contentDescription = if (m == Mode.FIT) "Put the box at the circle" else "Drop a point"
        surface.queueEvent {
            // Each mode starts with a clean slate so floor corners and loose lines don't mix
            clearAll()
            chain = m == Mode.FLOOR || chainButton.isSelected
        }
        shown = ""
    }

    override fun onResume() {
        super.onResume()
        if (session == null) {
            val problem: String? = try {
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) ==
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED
                ) {
                    installRequested = true
                    return
                }
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
                    return
                }
                session = Session(this).also { configure(it) }
                textureSet = false
                null
            } catch (e: UnavailableUserDeclinedInstallationException) {
                "Room measuring needs Google Play Services for AR. Install it from the Play Store, then try again."
            } catch (e: UnavailableDeviceNotCompatibleException) {
                "This phone can't measure in the room. Photo measuring still works."
            } catch (e: UnavailableApkTooOldException) {
                "Update Google Play Services for AR from the Play Store, then try again."
            } catch (e: UnavailableSdkTooOldException) {
                "This version of Measure is too old for your phone's AR services. Install the latest APK."
            } catch (e: UnavailableException) {
                "Room measuring isn't available: ${e.message}"
            } catch (e: Exception) {
                "Couldn't start room measuring: ${e.message}"
            }
            if (problem != null) {
                fail(problem)
                return
            }
        }
        try {
            session!!.resume()
        } catch (e: CameraNotAvailableException) {
            session = null
            fail("Another app is using the camera. Close it and try again.")
            return
        }
        surface.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            surface.onPause()
            session!!.pause()
        }
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA &&
            (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED)
        ) {
            fail("Room measuring needs the camera. Allow it in Settings, Apps, Measure, Permissions.")
        }
    }

    private fun configure(s: Session) {
        val config = Config(s)
        config.focusMode = Config.FocusMode.AUTO
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        config.lightEstimationMode = Config.LightEstimationMode.DISABLED
        // Depth lets points land on surfaces that aren't flat planes
        if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) config.depthMode = Config.DepthMode.AUTOMATIC
        s.configure(config)
    }

    private fun fail(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showUnits() {
        unitsButton.text = Units.shortName(imperial)
        fillFitFields()
        shown = ""
    }

    private fun setChain(on: Boolean) {
        pairsButton.isSelected = !on
        chainButton.isSelected = on
        surface.queueEvent {
            if (mode == Mode.FLOOR) return@queueEvent
            chain = on
            path.clear()
            if (pending >= 0) path.add(pending)
        }
    }

    private fun requestPoint() {
        surface.queueEvent { placeRequested = true }
    }

    private fun openFloorPlan(points: List<FloatArray>) {
        val xz = FloatArray(points.size * 2)
        points.forEachIndexed { i, p -> xz[i * 2] = p[0]; xz[i * 2 + 1] = p[2] }
        startActivity(Intent(this, FloorPlanActivity::class.java).putExtra(FloorPlanActivity.EXTRA_XZ, xz))
    }

    // ---------- GL thread ----------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.create()
        textureSet = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height
        geometryChanged = true
    }

    /** Where the magnifier sits, in view pixels. */
    private fun loupeRect(): RectF {
        val size = 128 * dp
        val left = 12 * dp
        val top = viewHeight * 0.30f
        return RectF(left, top, left + size, top + size)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (!textureSet) {
            s.setCameraTextureName(background.textureId)
            textureSet = true
        }
        if (geometryChanged) {
            s.setDisplayGeometry(Surface.ROTATION_0, viewWidth, viewHeight)
            geometryChanged = false
        }
        val frame = try { s.update() } catch (e: Throwable) { return }
        background.draw(frame)
        if (captureRequested) {
            captureRequested = false
            capture()
        }

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            publish(null, trackingProblem(camera.trackingFailureReason))
            return
        }
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(proj, 0, 0.02f, 60f)
        val pose = camera.pose
        camPos[0] = pose.tx(); camPos[1] = pose.ty(); camPos[2] = pose.tz()

        if (mode == Mode.FIT) {
            updateBox(s, frame)
            publish(null, null)
        } else {
            var target = findTarget(frame)
            if (placeRequested) {
                placeRequested = false
                if (target != null) {
                    place(s, target)
                    target = findTarget(frame)
                }
            }
            if (closeRequested) {
                closeRequested = false
                closeShape()
            }
            publish(target, null)
        }

        // The magnifier shows the camera image around the circle, three times bigger
        val loupe = loupeRect()
        background.drawZoomed(
            loupe.left.toInt(), (viewHeight - loupe.bottom).toInt(), loupe.width().toInt(),
            3f, viewWidth, viewHeight
        )
    }

    /** Works out where a point would go: onto an existing point, along a guide, or onto the surface. */
    private fun findTarget(frame: Frame): Target? {
        val cx = viewWidth / 2f
        val cy = viewHeight / 2f
        val points = positions()

        // An existing point near the circle wins, so lines can share ends and shapes can close
        var best = 30 * dp
        var snap = -1
        for ((i, p) in points.withIndex()) {
            val sp = screenOf(p) ?: continue
            val d = hypot(sp[0] - cx, sp[1] - cy)
            if (d < best && i != pending) { best = d; snap = i }
        }
        if (snap >= 0) return Target(points[snap], null, snap, Guide.NONE)

        val hit = findHit(frame, cx, cy) ?: return null
        val pos = floatArrayOf(hit.hitPose.tx(), hit.hitPose.ty(), hit.hitPose.tz())

        if (mode == Mode.FLOOR) {
            // Floor corners all sit at the height of the first corner
            if (path.isNotEmpty()) pos[1] = points[path[0]][1]
            if (path.size >= 2) {
                // Square the corner up when it is within a few degrees of a right angle
                val a = points[path[path.size - 2]]; val b = points[path[path.size - 1]]
                val ux = b[0] - a[0]; val uz = b[2] - a[2]
                val vx = pos[0] - b[0]; val vz = pos[2] - b[2]
                val lu = hypot(ux, uz); val lv = hypot(vx, vz)
                if (lu > 0.05f && lv > 0.05f && abs((ux * vx + uz * vz) / (lu * lv)) < SIN_SQUARE) {
                    // Perpendicular to the last wall, on the side the phone is pointing
                    var px = -uz / lu; var pz = ux / lu
                    if (px * vx + pz * vz < 0) { px = -px; pz = -pz }
                    val along = px * vx + pz * vz
                    return Target(floatArrayOf(b[0] + px * along, pos[1], b[2] + pz * along), hit, -1, Guide.SQUARE)
                }
            }
            return Target(pos, hit, -1, Guide.NONE)
        }

        if (pending < 0) return Target(pos, hit, -1, Guide.NONE)

        // Snap the line to straight up-and-down or to level when it is very nearly there
        val start = points[pending]
        val dx = pos[0] - start[0]; val dy = pos[1] - start[1]; val dz = pos[2] - start[2]
        val across = sqrt(dx * dx + dz * dz)
        if (abs(dy) > 0.03f && across < abs(dy) * TAN_VERTICAL) {
            return Target(floatArrayOf(start[0], pos[1], start[2]), hit, -1, Guide.VERTICAL)
        }
        if (across > 0.03f && abs(dy) < across * TAN_LEVEL) {
            return Target(floatArrayOf(pos[0], start[1], pos[2]), hit, -1, Guide.LEVEL)
        }
        return Target(pos, hit, -1, Guide.NONE)
    }

    private fun screenOf(p: FloatArray): FloatArray? {
        val v = floatArrayOf(p[0], p[1], p[2], 1f)
        val eye = FloatArray(4)
        android.opengl.Matrix.multiplyMV(eye, 0, view, 0, v, 0)
        if (eye[2] > -0.03f) return null
        val clip = FloatArray(4)
        android.opengl.Matrix.multiplyMV(clip, 0, proj, 0, eye, 0)
        return floatArrayOf((clip[0] / clip[3] + 1f) / 2f * viewWidth, (1f - clip[1] / clip[3]) / 2f * viewHeight)
    }

    /** The surface at a screen position, nearest first. */
    private fun findHit(frame: Frame, x: Float, y: Float, floorOnly: Boolean = false): HitResult? {
        for (h in frame.hitTest(x, y)) {
            when (val t = h.trackable) {
                is Plane -> if (t.trackingState == TrackingState.TRACKING && t.subsumedBy == null &&
                    t.isPoseInPolygon(h.hitPose) &&
                    (!floorOnly || t.type == Plane.Type.HORIZONTAL_UPWARD_FACING)
                ) return h
                is DepthPoint -> if (!floorOnly) return h
                is Point -> if (!floorOnly && t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) return h
            }
        }
        return null
    }

    private fun positions(): List<FloatArray> = anchors.map { val p = it.pose; floatArrayOf(p.tx(), p.ty(), p.tz()) }

    private fun place(s: Session, t: Target) {
        val pendingBefore = pending
        val pathBefore = ArrayList(path)
        var newAnchor = false
        val index = if (t.snapIndex >= 0) t.snapIndex else {
            val exact = t.guide == Guide.NONE && (mode != Mode.FLOOR || path.isEmpty())
            val fromHit = if (exact && t.hit != null) {
                try { t.hit.createAnchor() } catch (e: Exception) { null }
            } else null
            val anchor = fromHit ?: try {
                s.createAnchor(Pose.makeTranslation(t.pos[0], t.pos[1], t.pos[2]))
            } catch (e: Exception) { return }
            anchors.add(anchor)
            newAnchor = true
            anchors.size - 1
        }
        var newSegment = false
        var newShape = false
        if (pending >= 0) {
            segments.add(intArrayOf(pending, index))
            newSegment = true
            if (chain) {
                if (path.size >= 3 && index == path[0]) {
                    shapes.add(path.toIntArray())
                    newShape = true
                    path.clear()
                    pending = -1
                } else {
                    path.add(index)
                    pending = index
                }
            } else {
                path.clear()
                pending = -1
            }
        } else {
            pending = index
            path.clear()
            path.add(index)
        }
        history.add(Step(newAnchor, newSegment, newShape, pendingBefore, pathBefore))
        runOnUiThread { overlay.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        if (newShape && mode == Mode.FLOOR) {
            val pts = positions()
            val room = shapes.last().map { pts[it] }
            runOnUiThread { openFloorPlan(room) }
        }
    }

    private fun closeShape() {
        if (path.size >= 3 && pending >= 0) {
            history.add(Step(false, true, true, pending, ArrayList(path)))
            segments.add(intArrayOf(pending, path[0]))
            shapes.add(path.toIntArray())
            path.clear()
            pending = -1
            runOnUiThread { overlay.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        }
        if (mode == Mode.FLOOR && shapes.isNotEmpty()) {
            val pts = positions()
            val room = shapes.last().map { pts[it] }
            runOnUiThread { openFloorPlan(room) }
        }
    }

    private fun undo() {
        if (history.isEmpty()) return
        val step = history.removeAt(history.size - 1)
        if (step.newShape) shapes.removeAt(shapes.size - 1)
        if (step.newSegment) segments.removeAt(segments.size - 1)
        if (step.newAnchor) anchors.removeAt(anchors.size - 1).detach()
        pending = step.pendingBefore
        path.clear()
        path.addAll(step.pathBefore)
    }

    private fun clearAll() {
        anchors.forEach { it.detach() }
        anchors.clear()
        segments.clear()
        shapes.clear()
        path.clear()
        history.clear()
        pending = -1
        removeBox()
    }

    // ----- "Will it fit?" -----

    private fun removeBox() {
        boxAnchor?.detach()
        boxAnchor = null
        boxPos = null
    }

    private fun updateBox(s: Session, frame: Frame) {
        boxAnchor?.let { if (it.trackingState == TrackingState.TRACKING) boxPos = it.pose.let { p -> floatArrayOf(p.tx(), p.ty(), p.tz()) } }

        if (placeRequested) {
            placeRequested = false
            val hit = findHit(frame, viewWidth / 2f, viewHeight / 2f, floorOnly = true)
                ?: findHit(frame, viewWidth / 2f, viewHeight / 2f)
            if (hit != null) {
                val first = boxPos == null
                setBoxAt(s, hit.hitPose.tx(), hit.hitPose.ty(), hit.hitPose.tz())
                if (first) faceCamera()
                runOnUiThread { overlay.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
            }
        }
        dragAt?.let { at ->
            dragAt = null
            val hit = findHit(frame, at[0], at[1], floorOnly = true) ?: findHit(frame, at[0], at[1])
            if (hit != null && boxPos != null) {
                // Follow the finger without making a new anchor every frame
                boxAnchor?.detach(); boxAnchor = null
                boxPos = floatArrayOf(hit.hitPose.tx(), hit.hitPose.ty(), hit.hitPose.tz())
            }
        }
        if (dragEnded) {
            dragEnded = false
            boxPos?.let { setBoxAt(s, it[0], it[1], it[2]) }
        }
    }

    private fun setBoxAt(s: Session, x: Float, y: Float, z: Float) {
        boxAnchor?.detach()
        boxAnchor = try { s.createAnchor(Pose.makeTranslation(x, y, z)) } catch (e: Exception) { null }
        boxPos = floatArrayOf(x, y, z)
    }

    private fun faceCamera() {
        val p = boxPos ?: return
        boxYaw = atan2(camPos[0] - p[0], camPos[2] - p[2])
    }

    /** The eight corners: bit 0 picks the width side, bit 1 the depth side, bit 2 the top. */
    private fun boxCorners(): List<FloatArray>? {
        val p = boxPos ?: return null
        val c = cos(boxYaw); val sn = sin(boxYaw)
        return (0 until 8).map { i ->
            val x = (if (i and 1 == 0) -0.5f else 0.5f) * boxW
            val z = (if (i and 2 == 0) -0.5f else 0.5f) * boxD
            val y = if (i and 4 == 0) 0f else boxH
            floatArrayOf(p[0] + x * c + z * sn, p[1] + y, p[2] - x * sn + z * c)
        }
    }

    // ----- Sharing what's measured with the screen -----

    private fun publish(target: Target?, problem: String?) {
        val points = positions()
        val corners = if (mode == Mode.FIT) boxCorners() else null
        overlay.snapshot = MeasureOverlay.Snapshot(
            tracking = problem == null,
            view = view.clone(), proj = proj.clone(),
            points = points, segments = segments.map { it.clone() }, shapes = shapes.map { it.clone() },
            pending = pending, target = target?.pos, snapIndex = target?.snapIndex ?: -1,
            guide = target?.guide ?: Guide.NONE,
            box = corners, boxSize = floatArrayOf(boxW, boxD, boxH),
            loupe = loupeRect(),
        )
        overlay.postInvalidate()

        val canClose = chain && path.size >= 3
        var hintText: String
        val readoutText: String
        val lines = ArrayList<String>()

        if (mode == Mode.FIT) {
            hintText = problem ?: if (corners == null) "Aim the circle at the floor and tap + to put the box there"
            else "Drag the box to move it. Tap + to move it to the circle."
            readoutText = "${Units.format(boxW, imperial)} × ${Units.format(boxD, imperial)} × ${Units.format(boxH, imperial)}"
            lines.add("Volume ${Units.formatVolume(boxW * boxD * boxH, imperial)}")
        } else {
            hintText = problem ?: when {
                target == null -> if (mode == Mode.FLOOR) "Aim at the floor where it meets a wall" else "Move the phone slowly to find a surface"
                canClose && target.snapIndex == path[0] -> if (mode == Mode.FLOOR) "Tap + to finish the room" else "Tap + to close the shape"
                target.snapIndex >= 0 -> "On an existing point. Tap + to use it"
                target.guide == Guide.VERTICAL -> "Straight up and down"
                target.guide == Guide.LEVEL -> "Level"
                target.guide == Guide.SQUARE -> "Square corner"
                mode == Mode.FLOOR && pending < 0 -> "Aim at a corner where the walls meet the floor, tap +"
                mode == Mode.FLOOR -> "Go to the next corner and tap +"
                pending >= 0 -> "Move to the end point and tap +"
                else -> "Aim the circle at the start point and tap +"
            }
            if (problem == null && target != null) {
                hintText += "\nThe circle is " + Units.format(Units.distance(camPos, target.pos), imperial) + " from the phone"
            }
            readoutText = when {
                pending >= 0 && target != null -> Units.format(Units.distance(points[pending], target.pos), imperial)
                segments.isNotEmpty() -> segments.last().let { Units.format(Units.distance(points[it[0]], points[it[1]]), imperial) }
                else -> "—"
            }
            if (segments.size >= 2) {
                val sum = segments.sumOf { Units.distance(points[it[0]], points[it[1]]).toDouble() }.toFloat()
                lines.add("${segments.size} ${if (mode == Mode.FLOOR) "walls" else "lines"}, total ${Units.format(sum, imperial)}")
            }
            shapes.forEachIndexed { i, shape ->
                val pts = shape.map { points[it] }
                val perimeter = pts.indices.sumOf { Units.distance(pts[it], pts[(it + 1) % pts.size]).toDouble() }.toFloat()
                val name = if (mode == Mode.FLOOR) "Room" else "Shape"
                lines.add("$name ${i + 1}: area ${Units.formatArea(Units.polygonArea(pts), imperial)}, around ${Units.format(perimeter, imperial)}")
            }
        }
        val totalText = lines.takeLast(3).joinToString("\n")
        val closeText = when {
            mode == Mode.FLOOR && canClose -> "Finish the room and see the floor plan"
            mode == Mode.FLOOR && shapes.isNotEmpty() -> "See the floor plan"
            mode == Mode.MEASURE && canClose -> "Close the shape and show its area"
            else -> ""
        }

        val key = listOf(hintText, readoutText, totalText, closeText).joinToString("|")
        if (key != shown) {
            shown = key
            runOnUiThread {
                hint.text = hintText
                readout.text = readoutText
                total.text = totalText
                total.visibility = if (totalText.isEmpty()) View.GONE else View.VISIBLE
                closeButton.text = closeText
                closeButton.visibility = if (closeText.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    /** Grabs the camera image and hands it to the UI thread to add the measurements and save. */
    private fun capture() {
        val w = viewWidth
        val h = viewHeight
        val buf = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val upsideDown = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buf.rewind()
        upsideDown.copyPixelsFromBuffer(buf)
        val flip = android.graphics.Matrix().apply { preScale(1f, -1f) }
        val image = Bitmap.createBitmap(upsideDown, 0, 0, w, h, flip, false)
        upsideDown.recycle()
        val summary = shown.split("|").let { parts ->
            (listOf(parts.getOrElse(1) { "" }) + parts.getOrElse(2) { "" }.split("\n"))
        }.filter { it.isNotBlank() && it != "—" }
        runOnUiThread { saveCapture(image, summary) }
    }

    private fun saveCapture(image: Bitmap, summary: List<String>) {
        val canvas = Canvas(image)
        canvas.scale(image.width.toFloat() / overlay.width, image.height.toFloat() / overlay.height)
        overlay.drawForPhoto(canvas)
        canvas.setMatrix(null)
        overlay.drawFooter(canvas, image.width, image.height, summary)
        Thread {
            val uri = Gallery.save(this, image)
            runOnUiThread {
                if (uri == null) {
                    Toast.makeText(this, "Couldn't save the photo.", Toast.LENGTH_LONG).show()
                } else {
                    lastSaved = uri
                    shareButton.visibility = View.VISIBLE
                    Toast.makeText(this, "Saved to Pictures/Measure", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun trackingProblem(reason: TrackingFailureReason): String = when (reason) {
        TrackingFailureReason.NONE -> "Starting the camera… move the phone slowly"
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark. Turn on a light."
        TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast. Slow down."
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point at something with more detail or texture"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "The camera is busy. Close other camera apps."
        TrackingFailureReason.BAD_STATE -> "Tracking stopped. Tap Done and start again."
        else -> "Hold steady…"
    }

    companion object {
        const val EXTRA_MODE = "mode"
        private const val REQUEST_CAMERA = 1
        private val TAN_VERTICAL = kotlin.math.tan(Math.toRadians(4.0)).toFloat()
        private val TAN_LEVEL = kotlin.math.tan(Math.toRadians(2.5)).toFloat()
        private val SIN_SQUARE = kotlin.math.sin(Math.toRadians(4.0)).toFloat()
        private val TURN = Math.toRadians(15.0).toFloat()
    }
}
