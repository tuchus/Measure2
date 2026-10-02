package com.tuchus.measure

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
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

class ArMeasureActivity : Activity(), GLSurfaceView.Renderer {

    private lateinit var surface: GLSurfaceView
    private lateinit var overlay: MeasureOverlay
    private lateinit var readout: TextView
    private lateinit var hint: TextView
    private lateinit var total: TextView
    private lateinit var pairsButton: Button
    private lateinit var chainButton: Button
    private lateinit var unitsButton: Button

    private var session: Session? = null
    private var installRequested = false
    private val background = BackgroundRenderer()
    @Volatile private var textureSet = false
    private var viewWidth = 0
    private var viewHeight = 0
    private var geometryChanged = false
    @Volatile private var imperial = false

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
    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val camPos = FloatArray(3)
    private var shownHint = ""
    private var shownReadout = ""
    private var shownTotal = ""
    private var shownCanClose = false
    private val snapRadius by lazy { 30 * resources.displayMetrics.density }

    /** What one tap of + did, so Undo can take it back exactly. */
    private class Step(
        val newAnchor: Boolean, val newSegment: Boolean, val newShape: Boolean,
        val pendingBefore: Int, val pathBefore: List<Int>,
    )

    enum class Guide { NONE, VERTICAL, LEVEL }

    /** Where + would put a point right now. */
    private class Target(val pos: FloatArray, val hit: HitResult?, val snapIndex: Int, val guide: Guide)

    private var lastSaved: android.net.Uri? = null
    private lateinit var closeButton: Button
    private lateinit var shareButton: Button

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

        imperial = Units.isImperial(this)
        overlay.imperial = imperial
        showUnits()

        surface.preserveEGLContextOnPause = true
        surface.setEGLContextClientVersion(2)
        surface.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surface.setRenderer(this)
        surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // Tapping anywhere on the camera view drops a point, same as the + button
        overlay.setOnClickListener { requestPoint() }
        findViewById<Button>(R.id.add).setOnClickListener { requestPoint() }
        findViewById<Button>(R.id.undo).setOnClickListener { surface.queueEvent { undo() } }
        findViewById<Button>(R.id.clear).setOnClickListener { surface.queueEvent { clearAll() } }
        findViewById<Button>(R.id.done).setOnClickListener { finish() }
        closeButton = findViewById(R.id.closeShape)
        shareButton = findViewById(R.id.share)
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
        setChain(false)
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
        shownReadout = ""
        shownTotal = "-"
    }

    private fun setChain(on: Boolean) {
        pairsButton.isSelected = !on
        chainButton.isSelected = on
        surface.queueEvent {
            chain = on
            path.clear()
            if (pending >= 0) path.add(pending)
        }
    }

    private fun requestPoint() {
        surface.queueEvent { placeRequested = true }
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

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            captureRequested = false
            publish(null, trackingProblem(camera.trackingFailureReason))
            return
        }
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(proj, 0, 0.02f, 60f)
        val pose = camera.pose
        camPos[0] = pose.tx(); camPos[1] = pose.ty(); camPos[2] = pose.tz()

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
        if (captureRequested) {
            captureRequested = false
            capture()
        }
    }

    /** Works out where a point would go: onto an existing point, along a guide, or onto the surface. */
    private fun findTarget(frame: Frame): Target? {
        val cx = viewWidth / 2f
        val cy = viewHeight / 2f
        val points = positions()

        // An existing point near the circle wins, so lines can share ends and shapes can close
        var best = snapRadius
        var snap = -1
        for ((i, p) in points.withIndex()) {
            val sp = screenOf(p) ?: continue
            val d = hypot(sp[0] - cx, sp[1] - cy)
            if (d < best && i != pending) { best = d; snap = i }
        }
        if (snap >= 0) return Target(points[snap], null, snap, Guide.NONE)

        val hit = findHit(frame) ?: return null
        val pos = floatArrayOf(hit.hitPose.tx(), hit.hitPose.ty(), hit.hitPose.tz())
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

    /** The surface under the circle in the middle of the screen, nearest first. */
    private fun findHit(frame: Frame): HitResult? {
        for (h in frame.hitTest(viewWidth / 2f, viewHeight / 2f)) {
            when (val t = h.trackable) {
                is Plane -> if (t.trackingState == TrackingState.TRACKING && t.subsumedBy == null &&
                    t.isPoseInPolygon(h.hitPose)
                ) return h
                is DepthPoint -> return h
                is Point -> if (t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) return h
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
            val fromHit = if (t.guide == Guide.NONE && t.hit != null) {
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
    }

    private fun closeShape() {
        if (path.size < 3 || pending < 0) return
        history.add(Step(false, true, true, pending, ArrayList(path)))
        segments.add(intArrayOf(pending, path[0]))
        shapes.add(path.toIntArray())
        path.clear()
        pending = -1
        runOnUiThread { overlay.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
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
    }

    private fun publish(target: Target?, problem: String?) {
        val points = positions()
        val snapshot = MeasureOverlay.Snapshot(
            tracking = problem == null,
            view = view.clone(), proj = proj.clone(),
            points = points, segments = segments.map { it.clone() }, shapes = shapes.map { it.clone() },
            pending = pending, target = target?.pos, snapIndex = target?.snapIndex ?: -1,
            guide = target?.guide ?: Guide.NONE,
        )
        overlay.snapshot = snapshot
        overlay.postInvalidate()

        val canClose = chain && path.size >= 3
        var hintText = problem ?: when {
            target == null -> "Move the phone slowly to find a surface"
            canClose && target.snapIndex == path[0] -> "Tap + to close the shape"
            target.snapIndex >= 0 -> "On an existing point. Tap + to use it"
            target.guide == Guide.VERTICAL -> "Straight up and down"
            target.guide == Guide.LEVEL -> "Level"
            pending >= 0 -> "Move to the end point and tap +"
            else -> "Aim the circle at the start point and tap +"
        }
        if (problem == null && target != null) {
            hintText += "\nThe circle is " + Units.format(Units.distance(camPos, target.pos), imperial) + " from the phone"
        }
        val readoutText = when {
            pending >= 0 && target != null -> Units.format(Units.distance(points[pending], target.pos), imperial)
            segments.isNotEmpty() -> segments.last().let { Units.format(Units.distance(points[it[0]], points[it[1]]), imperial) }
            else -> "—"
        }
        val lines = ArrayList<String>()
        if (segments.size >= 2) {
            val sum = segments.sumOf { Units.distance(points[it[0]], points[it[1]]).toDouble() }.toFloat()
            lines.add("${segments.size} lines, total ${Units.format(sum, imperial)}")
        }
        shapes.forEachIndexed { i, shape ->
            val pts = shape.map { points[it] }
            val perimeter = pts.indices.sumOf { Units.distance(pts[it], pts[(it + 1) % pts.size]).toDouble() }.toFloat()
            lines.add("Shape ${i + 1}: area ${Units.formatArea(Units.polygonArea(pts), imperial)}, around ${Units.format(perimeter, imperial)}")
        }
        val totalText = lines.takeLast(3).joinToString("\n")

        if (hintText != shownHint || readoutText != shownReadout || totalText != shownTotal || canClose != shownCanClose) {
            shownHint = hintText; shownReadout = readoutText; shownTotal = totalText; shownCanClose = canClose
            runOnUiThread {
                hint.text = hintText
                readout.text = readoutText
                total.text = totalText
                total.visibility = if (totalText.isEmpty()) View.GONE else View.VISIBLE
                closeButton.visibility = if (canClose) View.VISIBLE else View.GONE
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
        val summary = (listOf(shownReadout) + shownTotal.split("\n")).filter { it.isNotBlank() && it != "—" }
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
        private const val REQUEST_CAMERA = 1
        private val TAN_VERTICAL = kotlin.math.tan(Math.toRadians(4.0)).toFloat()
        private val TAN_LEVEL = kotlin.math.tan(Math.toRadians(2.5)).toFloat()
    }
}
