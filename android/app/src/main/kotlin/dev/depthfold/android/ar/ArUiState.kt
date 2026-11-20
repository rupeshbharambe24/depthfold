/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.ar

import dev.depthfold.android.capture.DepthMode

/** Snapshot of AR session and recording status shown by the UI. */
data class ArUiState(
    /** ArCoreApk availability, e.g. "SUPPORTED_INSTALLED", or "CHECKING". */
    val arcoreAvailability: String = "CHECKING",
    val cameraPermissionGranted: Boolean = false,
    /** Human-readable reason the session could not start, if any. */
    val sessionError: String? = null,
    val sessionRunning: Boolean = false,
    val depthMode: DepthMode? = null,
    val rawDepthSupported: Boolean? = null,
    val smoothDepthSupported: Boolean? = null,
    /** e.g. "1920x1080". */
    val cpuImageSize: String? = null,
    val trackingState: String = "-",
    val trackingFailureReason: String = "NONE",
    val recording: Boolean = false,
    val captureId: String? = null,
    val captureFps: Double = DEFAULT_CAPTURE_FPS,
    val framesWritten: Int = 0,
    val depthFramesWritten: Int = 0,
    val framesDropped: Int = 0,
    val writerError: String? = null,
    /** Set when a recording has been finalised. */
    val lastFinishedCapture: String? = null,
) {
    companion object {
        const val DEFAULT_CAPTURE_FPS = 4.0
    }
}

/** Screen-space point (pixels in the AR view) of a measurement marker; null when behind the camera. */
data class ScreenPoint(val x: Float, val y: Float)

data class MeasureState(
    val enabled: Boolean = false,
    val points: List<ScreenPoint?> = emptyList(),
    val distanceCm: Double? = null,
    /** What kind of surface each point was placed on, e.g. "depth", "plane", "point". */
    val hitKinds: List<String> = emptyList(),
    val message: String? = null,
    /** Distance from the phone to the surface under the centre crosshair, or null if nothing is there. */
    val aimDistanceCm: Double? = null,
    /** With one point placed: distance from it to the surface under the crosshair. */
    val previewCm: Double? = null,
)
