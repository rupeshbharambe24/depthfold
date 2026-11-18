/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.ui

import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.depthfold.android.ar.ArRenderer
import dev.depthfold.android.capture.CaptureRepository

/**
 * Root composable. The record screen (which hosts the GLSurfaceView) stays in the composition; the captures
 * screen is drawn on top of it, and the activity pauses the AR session while it is shown. This avoids tearing
 * down and recreating the GL surface on every navigation.
 */
@Composable
fun DepthfoldApp(
    renderer: ArRenderer,
    repository: CaptureRepository,
    surfaceView: GLSurfaceView,
    showingCaptures: Boolean,
    onShowCaptures: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        RecordScreen(
            renderer = renderer,
            surfaceView = surfaceView,
            onOpenCaptures = { onShowCaptures(true) },
            onRequestPermission = onRequestPermission,
            onOpenAppSettings = onOpenAppSettings,
        )
        if (showingCaptures) {
            BackHandler { onShowCaptures(false) }
            CapturesScreen(repository = repository, onBack = { onShowCaptures(false) })
        }
    }
}
