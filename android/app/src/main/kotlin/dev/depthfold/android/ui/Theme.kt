/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val RecordRed = Color(0xFFE5484D)
internal val MeasureYellow = Color(0xFFFFC53D)
internal val OverlayScrim = Color(0xB3000000)

private val Colors = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    onPrimary = Color(0xFF00283A),
    secondary = Color(0xFFA5B4FC),
    error = RecordRed,
    background = Color(0xFF0B0D10),
    surface = Color(0xFF15181D),
    surfaceVariant = Color(0xFF22262C),
)

/** The app always uses a dark scheme: it sits on top of a live camera feed. */
@Composable
fun DepthfoldTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
