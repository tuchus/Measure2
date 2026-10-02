package com.tuchus.measure

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
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
    private val pendingBefore = ArrayList<Int>()
    private var pending = -1
    private var chain = false
    private var placeRequested = false
    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private var shownHint = ""
    private var shownReadout = ""
    private var shownTotal = ""

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
        unitsButton.text = if (imperial) "Units: in" else "Units: cm"
        shownReadout = ""
        shownTotal = "-"
    }

    private fun setChain(on: Boolean) {
        pairsButton.isSelected = !on
        chainButton.isSelected = on
        surface.queueEvent { chain = on }
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
            publish(null, trackingProblem(camera.trackingFailureReason))
            return
        }
        val hit = findHit(frame)
        if (placeRequested) {
            placeRequested = false
            if (hit != null) place(hit)
        }
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(proj, 0, 0.02f, 60f)
        publish(hit?.hitPose, null)
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

    private fun place(hit: HitResult) {
        val anchor = try { hit.createAnchor() } catch (e: Exception) { return }
        pendingBefore.add(pending)
        anchors.add(anchor)
        val index = anchors.size - 1
        if (pending >= 0) {
            segments.add(intArrayOf(pending, index))
            pending = if (chain) index else -1
        } else {
            pending = index
        }
        runOnUiThread { overlay.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    }

    private fun undo() {
        if (anchors.isEmpty()) return
        val index = anchors.size - 1
        if (segments.isNotEmpty() && segments.last()[1] == index) segments.removeAt(segments.size - 1)
        anchors.removeAt(index).detach()
        pending = pendingBefore.removeAt(pendingBefore.size - 1)
    }

    private fun clearAll() {
        anchors.forEach { it.detach() }
        anchors.clear()
        segments.clear()
        pendingBefore.clear()
        pending = -1
    }

    private fun publish(hitPose: Pose?, problem: String?) {
        val points = anchors.map { val p = it.pose; floatArrayOf(p.tx(), p.ty(), p.tz()) }
        val hitPos = hitPose?.let { floatArrayOf(it.tx(), it.ty(), it.tz()) }
        overlay.snapshot = MeasureOverlay.Snapshot(
            tracking = problem == null,
            view = view.clone(), proj = proj.clone(),
            points = points, segments = segments.map { it.clone() },
            pending = pending, hit = hitPos
        )
        overlay.postInvalidate()

        val hintText = problem ?: when {
            hitPos == null -> "Move the phone slowly to find a surface"
            pending >= 0 -> "Move to the end point and tap +"
            else -> "Aim the circle at the start point and tap +"
        }
        val readoutText = when {
            pending >= 0 && hitPos != null -> Units.format(Units.distance(points[pending], hitPos), imperial)
            segments.isNotEmpty() -> segments.last().let { Units.format(Units.distance(points[it[0]], points[it[1]]), imperial) }
            else -> "—"
        }
        val totalText = if (segments.size >= 2) {
            val sum = segments.sumOf { Units.distance(points[it[0]], points[it[1]]).toDouble() }.toFloat()
            "${segments.size} lines, total ${Units.format(sum, imperial)}"
        } else ""

        if (hintText != shownHint || readoutText != shownReadout || totalText != shownTotal) {
            shownHint = hintText; shownReadout = readoutText; shownTotal = totalText
            runOnUiThread {
                hint.text = hintText
                readout.text = readoutText
                total.text = totalText
            }
        }
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
    }
}
