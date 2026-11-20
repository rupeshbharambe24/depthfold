/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.ar

import android.app.Activity
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import dev.depthfold.android.BuildConfig
import dev.depthfold.android.capture.AndroidJpegEncoder
import dev.depthfold.android.capture.BundleManifest
import dev.depthfold.android.capture.BundleWriter
import dev.depthfold.android.capture.CapturePolicy
import dev.depthfold.android.capture.CaptureRepository
import dev.depthfold.android.capture.DepthMode
import dev.depthfold.android.capture.DeviceInfo
import dev.depthfold.android.capture.Intrinsics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteOrder
import java.util.Date
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.thread

/**
 * GL-thread side of the app: updates the ARCore session, draws the camera background, grabs frames for an active
 * recording and resolves measurement taps. UI-thread methods ([startRecording], [stopRecording], [onTap], ...)
 * only post requests; all ARCore frame access happens in [onDrawFrame].
 */
class ArRenderer(
    activity: Activity,
    private val repository: CaptureRepository,
    private val deviceInfo: DeviceInfo,
) : GLSurfaceView.Renderer {

    private val _state = MutableStateFlow(ArUiState())
    val state: StateFlow<ArUiState> = _state.asStateFlow()

    private val _measure = MutableStateFlow(MeasureState())
    val measure: StateFlow<MeasureState> = _measure.asStateFlow()

    val displayRotationHelper = DisplayRotationHelper(activity)
    private val background = BackgroundRenderer()

    /** Set by the activity (UI thread) once the session is created; read on the GL thread. */
    @Volatile
    var setup: SessionSetup? = null

    private class Recording(val writer: BundleWriter, val policy: CapturePolicy) {
        val dropped = AtomicInteger(0)
    }

    @Volatile
    private var recording: Recording? = null

    // Measurement: taps are queued from the UI thread and resolved on the GL thread.
    private val pendingTaps = ConcurrentLinkedQueue<FloatArray>()
    private val resetMeasureRequested = AtomicBoolean(false)
    private val anchors = ArrayList<Pair<Anchor, String>>() // GL thread only
    private var viewWidth = 0
    private var viewHeight = 0
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)

    fun updateState(block: (ArUiState) -> ArUiState) = _state.update(block)

    // ---------------------------------------------------------------- UI-thread API

    fun setCaptureFps(fps: Double) {
        if (recording == null) _state.update { it.copy(captureFps = fps) }
    }

    /** Starts a burst recording into a new bundle. Returns false if no session or already recording. */
    fun startRecording(): Boolean {
        val s = setup ?: return false
        if (recording != null) return false
        val now = Date()
        // Created here (a single mkdir) so a second recording in the same second gets a distinct name.
        val dir = repository.newCaptureDir(now).apply { mkdirs() }
        val manifest = BundleManifest(
            captureId = dir.name,
            createdUtc = CaptureRepository.isoUtc(now),
            appName = APP_NAME,
            appVersion = BuildConfig.VERSION_NAME,
            device = deviceInfo,
            arcoreSdkVersion = BuildConfig.ARCORE_SDK_VERSION,
            depthMode = s.depthMode,
            rgbSize = s.cpuImageSize,
            depthSize = null,
            frameCount = 0,
        )
        val writer = BundleWriter(
            dir = dir,
            baseManifest = manifest,
            jpegEncoder = AndroidJpegEncoder,
            listener = object : BundleWriter.Listener {
                override fun onFrameWritten(framesWritten: Int, depthFramesWritten: Int) {
                    _state.update { it.copy(framesWritten = framesWritten, depthFramesWritten = depthFramesWritten) }
                }

                override fun onError(message: String, error: Throwable?) {
                    Log.e(TAG, message, error)
                    _state.update { it.copy(writerError = message) }
                }
            },
        )
        writer.start()
        val fps = _state.value.captureFps
        recording = Recording(writer, CapturePolicy(fps))
        _state.update {
            it.copy(
                recording = true,
                captureId = dir.name,
                framesWritten = 0,
                depthFramesWritten = 0,
                framesDropped = 0,
                writerError = null,
                lastFinishedCapture = null,
            )
        }
        return true
    }

    /** Stops the recording; the bundle is finalised on the writer thread. Safe to call when not recording. */
    fun stopRecording() {
        val rec = recording ?: return
        recording = null
        val future = rec.writer.finish()
        _state.update { it.copy(recording = false) }
        thread(name = "depthfold-finalise", isDaemon = true) {
            try {
                val manifest = future.get(60, TimeUnit.SECONDS)
                _state.update { it.copy(lastFinishedCapture = "${manifest.captureId}: ${manifest.frameCount} frames") }
            } catch (e: Exception) {
                Log.e(TAG, "finalising bundle failed", e)
                _state.update { it.copy(writerError = "Finalising bundle failed: ${e.message}") }
            }
        }
    }

    fun setMeasureEnabled(enabled: Boolean) {
        if (!enabled) resetMeasureRequested.set(true)
        _measure.update { MeasureState(enabled = enabled) }
    }

    fun resetMeasurement() {
        resetMeasureRequested.set(true)
    }

    /** View-pixel coordinates of a tap on the AR view. */
    fun onTap(x: Float, y: Float) {
        if (_measure.value.enabled && pendingTaps.size < 4) pendingTaps.add(floatArrayOf(x, y))
    }

    // ---------------------------------------------------------------- GL thread

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.05f, 0.05f, 1f)
        try {
            background.createOnGlThread()
        } catch (e: RuntimeException) {
            Log.e(TAG, "background renderer init failed", e)
            _state.update { it.copy(sessionError = "OpenGL init failed: ${e.message}") }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height
        displayRotationHelper.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = setup ?: return
        if (background.textureId < 0) return
        val session = s.session

        val frame: Frame = try {
            displayRotationHelper.updateSessionIfNeeded(session)
            session.setCameraTextureName(background.textureId)
            session.update()
        } catch (_: SessionPausedException) {
            return
        } catch (e: CameraNotAvailableException) {
            _state.update { it.copy(sessionError = "Camera not available: ${e.message}") }
            return
        } catch (e: RuntimeException) {
            // Typically a session that was paused/closed on the UI thread between frames.
            Log.w(TAG, "session.update failed", e)
            return
        }

        background.draw(frame)
        val camera = frame.camera
        val tracking = camera.trackingState
        val reason = camera.trackingFailureReason
        _state.update {
            if (it.trackingState == tracking.name && it.trackingFailureReason == reason.name) {
                it
            } else {
                it.copy(trackingState = tracking.name, trackingFailureReason = reason.name)
            }
        }

        try {
            recording?.let { captureIfDue(frame, s, it) }
        } catch (e: RuntimeException) {
            Log.e(TAG, "frame capture failed", e)
            _state.update { it.copy(writerError = "Frame capture failed: ${e.message}") }
        }

        try {
            updateMeasurement(frame, s, tracking)
        } catch (e: RuntimeException) {
            Log.w(TAG, "measurement failed", e)
        }
    }

    private fun captureIfDue(frame: Frame, s: SessionSetup, rec: Recording) {
        val ts = frame.timestamp
        if (ts == 0L || !rec.policy.isDue(ts)) return
        rec.policy.markCaptured(ts)
        if (!rec.writer.hasCapacity()) {
            onDropped(rec)
            return
        }
        val captured = FrameGrabber.grab(frame, s.depthMode, rec.policy) ?: return
        if (rec.writer.submit(captured)) {
            captured.depth?.let { rec.policy.markDepthSaved(it.timestampNs) }
        } else {
            onDropped(rec)
        }
    }

    private fun onDropped(rec: Recording) {
        val n = rec.dropped.incrementAndGet()
        _state.update { it.copy(framesDropped = n) }
    }

    private fun updateMeasurement(frame: Frame, s: SessionSetup, tracking: TrackingState) {
        if (resetMeasureRequested.getAndSet(false)) {
            anchors.forEach { it.first.detach() }
            anchors.clear()
            pendingTaps.clear()
            _measure.update { it.copy(points = emptyList(), distanceCm = null, hitKinds = emptyList(), message = null) }
        }
        if (!_measure.value.enabled) return

        var message: String? = _measure.value.message
        while (true) {
            val tap = pendingTaps.poll() ?: break
            if (tracking != TrackingState.TRACKING) {
                message = "Not tracking yet. Move the phone slowly."
                continue
            }
            val hits = frame.hitTest(tap[0], tap[1])
            val placed = pickSurfaceHit(hits)?.let { it.createAnchor() to if (it.trackable is Plane) "plane" else "depth" }
                ?: depthHit(frame, s, tap[0], tap[1])?.let { s.session.createAnchor(it) to "depth" }
                ?: pickPointHit(hits)?.let { it.createAnchor() to "point" }
            if (placed == null) {
                message = if (s.depthMode == DepthMode.NONE) {
                    "No surface found there. Point at a well-lit, textured surface and move the phone side to " +
                        "side until it is detected, then tap again."
                } else {
                    "No depth there yet. Keep it 0.3 to 2 m away, move the phone a little, and tap again."
                }
                continue
            }
            if (anchors.size >= 2) {
                anchors.forEach { it.first.detach() }
                anchors.clear()
            }
            anchors.add(placed)
            message = if (anchors.size == 1) "Tap a second point." else null
        }

        anchors.removeAll { (anchor, _) ->
            (anchor.trackingState == TrackingState.STOPPED).also { if (it) anchor.detach() }
        }

        val points: List<ScreenPoint?>
        var distanceCm: Double? = null
        if (anchors.isEmpty() || viewWidth == 0) {
            points = emptyList()
        } else {
            frame.camera.getViewMatrix(viewMatrix, 0)
            frame.camera.getProjectionMatrix(projMatrix, 0, 0.05f, 100f)
            val viewProj = ScreenProjection.multiply(projMatrix, viewMatrix)
            val positions = anchors.map { it.first.pose.translation }
            points = positions.map { ScreenProjection.project(viewProj, it, viewWidth, viewHeight) }
            if (positions.size == 2) distanceCm = ScreenProjection.distance(positions[0], positions[1]) * 100.0
        }
        val kinds = anchors.map { it.second }
        _measure.update { it.copy(points = points, distanceCm = distanceCm, hitKinds = kinds, message = message) }
    }

    /** ARCore depth points and planes (inside their polygon). Depth points only appear in AUTOMATIC depth mode. */
    private fun pickSurfaceHit(hits: List<HitResult>): HitResult? = hits.firstOrNull { hit ->
        when (val t = hit.trackable) {
            is DepthPoint -> t.trackingState == TrackingState.TRACKING
            is Plane -> t.trackingState == TrackingState.TRACKING && t.isPoseInPolygon(hit.hitPose)
            else -> false
        }
    }

    /** Feature points with an estimated normal: the least reliable placement, used last. */
    private fun pickPointHit(hits: List<HitResult>): HitResult? = hits.firstOrNull { hit ->
        val t = hit.trackable
        t is Point && t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
    }

    /**
     * World pose of the depth under view pixel ([x], [y]), read from the frame's own depth image (raw or
     * smooth, whichever the session records). Null when the session has no depth or none is valid there.
     */
    private fun depthHit(frame: Frame, s: SessionSetup, x: Float, y: Float): Pose? {
        if (s.depthMode == DepthMode.NONE) return null
        val raw = s.depthMode == DepthMode.RAW
        val image = try {
            if (raw) frame.acquireRawDepthImage16Bits() else frame.acquireDepthImage16Bits()
        } catch (_: NotYetAvailableException) {
            return null
        }
        return image.use { depth ->
            val confidence = if (raw) {
                try {
                    frame.acquireRawDepthConfidenceImage()
                } catch (_: NotYetAvailableException) {
                    null
                }
            } else {
                null
            }
            confidence.use { conf ->
                // Depth covers the GPU texture's field of view, so go view -> normalised texture coordinates.
                val tex = FloatArray(2)
                frame.transformCoordinates2d(Coordinates2d.VIEW, floatArrayOf(x, y), Coordinates2d.TEXTURE_NORMALIZED, tex)
                val w = depth.width
                val h = depth.height
                val u = tex[0].toDouble() * w
                val v = tex[1].toDouble() * h
                val dPlane = depth.planes[0]
                val dBuf = dPlane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                val cPlane = conf?.takeIf { it.width == w && it.height == h }?.planes?.get(0)
                val cBuf = cPlane?.buffer?.duplicate()
                val z = DepthTap.medianDepth(
                    width = w,
                    height = h,
                    depthMmAt = { px, py -> dBuf.getShort(py * dPlane.rowStride + px * dPlane.pixelStride).toInt() and 0xFFFF },
                    confidenceAt = cBuf?.let { b ->
                        { px, py -> b.get(py * cPlane.rowStride + px * cPlane.pixelStride).toInt() and 0xFF }
                    },
                    u = u,
                    v = v,
                ) ?: return@use null
                val ti = frame.camera.textureIntrinsics
                val k = Intrinsics.forDepth(Intrinsics.fromArCore(ti.focalLength, ti.principalPoint, ti.imageDimensions), w, h)
                val world = frame.camera.pose.transformPoint(DepthTap.cameraPointGl(k, u, v, z))
                Pose.makeTranslation(world)
            }
        }
    }

    companion object {
        private const val TAG = "DepthfoldRenderer"
        const val APP_NAME = "depthfold-android"
    }
}
