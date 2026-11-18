/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.ui

import dev.depthfold.android.ar.ArUiState

/** One-line guidance for the user, derived from tracking and recording state. Pure logic. */
internal fun guidanceHint(state: ArUiState): String = when {
    !state.cameraPermissionGranted -> "Camera permission is needed to record."
    state.sessionError != null -> state.sessionError
    !state.sessionRunning -> "Starting AR session..."
    state.trackingState != "TRACKING" -> when (state.trackingFailureReason) {
        "INSUFFICIENT_LIGHT" -> "Too dark. Add light to the scene."
        "EXCESSIVE_MOTION" -> "Moving too fast. Move more slowly."
        "INSUFFICIENT_FEATURES" -> "Point at a surface with more texture or detail."
        "CAMERA_UNAVAILABLE" -> "Camera is in use by another app."
        "BAD_STATE" -> "AR tracking hit an error. Restart the app."
        else -> "Move the phone slowly to start tracking."
    }
    state.recording -> "Move slowly around the object, keeping it in view and 0.3 to 2 m away."
    else -> "Move slowly around the object, then press Record."
}

internal fun yesNo(v: Boolean?): String = when (v) {
    null -> "?"
    true -> "yes"
    false -> "no"
}
