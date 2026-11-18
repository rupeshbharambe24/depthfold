/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.ui

import android.opengl.GLSurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.depthfold.android.ar.ArRenderer
import dev.depthfold.android.ar.ArUiState
import dev.depthfold.android.ar.MeasureState
import java.util.Locale

private val FPS_CHOICES = listOf(1.0, 2.0, 4.0, 8.0)

@Composable
fun RecordScreen(
    renderer: ArRenderer,
    surfaceView: GLSurfaceView,
    onOpenCaptures: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val state by renderer.state.collectAsStateWithLifecycle()
    val measure by renderer.measure.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { surfaceView }, modifier = Modifier.fillMaxSize())

        // Tap layer + measurement markers. Same bounds as the GLSurfaceView, so tap pixels match hitTest pixels.
        MeasureOverlay(
            measure = measure,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(measure.enabled) {
                    if (measure.enabled) detectTapGestures { renderer.onTap(it.x, it.y) }
                },
        )

        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(12.dp),
        ) {
            StatusPanel(state)
            Spacer(Modifier.height(8.dp))
            HintBanner(guidanceHint(state))
            if (!state.cameraPermissionGranted) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRequestPermission) { Text("Grant camera permission") }
                    OutlinedButton(onClick = onOpenAppSettings) { Text("App settings") }
                }
            }
            Spacer(Modifier.weight(1f))
            if (measure.enabled) MeasureReadout(measure, onReset = renderer::resetMeasurement)
            Spacer(Modifier.height(8.dp))
            Controls(
                state = state,
                measureEnabled = measure.enabled,
                onRecord = { if (state.recording) renderer.stopRecording() else renderer.startRecording() },
                onFps = renderer::setCaptureFps,
                onToggleMeasure = { renderer.setMeasureEnabled(!measure.enabled) },
                onOpenCaptures = onOpenCaptures,
            )
        }
    }
}

@Composable
private fun StatusPanel(state: ArUiState) {
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(OverlayScrim, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val tracking = if (state.trackingState == "TRACKING" || state.trackingFailureReason == "NONE") {
            state.trackingState
        } else {
            "${state.trackingState} (${state.trackingFailureReason})"
        }
        val depth = state.depthMode?.wireName ?: "-"
        Text("Tracking  $tracking", style = mono, color = if (state.trackingState == "TRACKING") Color.White else MeasureYellow)
        Text(
            "Depth     $depth  (raw ${yesNo(state.rawDepthSupported)}, smooth ${yesNo(state.smoothDepthSupported)})",
            style = mono,
            color = Color.White,
        )
        Text("Camera    ${state.cpuImageSize ?: "-"}  ARCore ${state.arcoreAvailability}", style = mono, color = Color.White)
        if (state.recording || state.framesWritten > 0) {
            val prefix = if (state.recording) "REC" else "Saved"
            Text(
                "$prefix  ${state.framesWritten} frames, ${state.depthFramesWritten} with depth" +
                    if (state.framesDropped > 0) ", ${state.framesDropped} dropped" else "",
                style = mono,
                color = if (state.recording) RecordRed else Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
        state.lastFinishedCapture?.let { Text("Last      $it", style = mono, color = Color.White) }
        state.writerError?.let { Text(it, style = mono, color = RecordRed) }
    }
}

@Composable
private fun HintBanner(text: String) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .background(OverlayScrim, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun MeasureReadout(measure: MeasureState, onReset: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(OverlayScrim, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val d = measure.distanceCm
            Text(
                if (d != null) String.format(Locale.ROOT, "%.1f cm", d) else "Tap two points to measure",
                color = MeasureYellow,
                style = MaterialTheme.typography.titleMedium,
            )
            val detail = measure.message ?: measure.hitKinds.takeIf { it.isNotEmpty() }?.joinToString(" to ")
            if (detail != null) Text(detail, color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onReset) { Text("Reset") }
    }
}

@Composable
private fun Controls(
    state: ArUiState,
    measureEnabled: Boolean,
    onRecord: () -> Unit,
    onFps: (Double) -> Unit,
    onToggleMeasure: () -> Unit,
    onOpenCaptures: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(OverlayScrim, RoundedCornerShape(16.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Rate", color = Color.White, style = MaterialTheme.typography.labelMedium)
            for (fps in FPS_CHOICES) {
                FilterChip(
                    selected = state.captureFps == fps,
                    onClick = { onFps(fps) },
                    enabled = !state.recording,
                    label = { Text("${fps.toInt()} fps") },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onOpenCaptures, enabled = !state.recording) { Text("Captures") }
            Button(
                onClick = onRecord,
                enabled = state.sessionRunning,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = RecordRed, contentColor = Color.White),
                modifier = Modifier
                    .size(76.dp)
                    .semantics { contentDescription = if (state.recording) "Stop recording" else "Start recording" },
            ) {
                Text(if (state.recording) "Stop" else "Rec", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onToggleMeasure) { Text(if (measureEnabled) "Done" else "Measure") }
        }
    }
}

@Composable
private fun MeasureOverlay(measure: MeasureState, modifier: Modifier) {
    Canvas(modifier) {
        val pts = measure.points
        val a = pts.getOrNull(0)
        val b = pts.getOrNull(1)
        if (a != null && b != null) {
            drawLine(MeasureYellow, Offset(a.x, a.y), Offset(b.x, b.y), strokeWidth = 4.dp.toPx())
        }
        for (p in pts) {
            if (p == null) continue
            drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(p.x, p.y))
            drawCircle(MeasureYellow, radius = 9.dp.toPx(), center = Offset(p.x, p.y), style = Stroke(3.dp.toPx()))
        }
    }
}
