/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.depthfold.android.BuildConfig
import dev.depthfold.android.MainActivity
import dev.depthfold.android.capture.CaptureSummary
import dev.depthfold.android.model.Mesh
import dev.depthfold.android.model.MeshFiles
import dev.depthfold.android.model.ModelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Shows a capture's on-device model in an orbit viewer, with buttons to share the mesh files. */
@Composable
fun ModelScreen(capture: CaptureSummary, onBack: () -> Unit, onRebuild: () -> Unit) {
    val context = LocalContext.current
    val modelDir = File(capture.dir, ModelBuilder.MODEL_DIR)
    var mesh by remember(capture.id) { mutableStateOf<Mesh?>(null) }
    var stats by remember(capture.id) { mutableStateOf<JsonObject?>(null) }
    var error by remember(capture.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(capture.id) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val m = MeshFiles.readPly(File(modelDir, ModelBuilder.PLY))
                val report = runCatching {
                    Json.parseToJsonElement(File(modelDir, ModelBuilder.REPORT).readText()).jsonObject
                }.getOrNull()
                m to report
            }
        }
        loaded.onSuccess { (m, report) ->
            mesh = m
            stats = report
        }.onFailure { error = "Could not open the model: ${it.message}" }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("3D model", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
        }
        Text(
            capture.id + (stats?.let { summary(it) }?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            val m = mesh
            if (m == null) {
                if (error == null) CircularProgressIndicator()
            } else {
                MeshViewer(m, Modifier.fillMaxSize())
                Text(
                    "Drag to rotate, pinch to zoom",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp),
                )
            }
        }

        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { shareFile(context, capture, ModelBuilder.GLB, "model/gltf-binary") }) {
                    Text("Share GLB")
                }
                OutlinedButton(onClick = { shareFile(context, capture, ModelBuilder.STL, "model/stl") }) {
                    Text("STL (mm)")
                }
                OutlinedButton(onClick = { shareFile(context, capture, ModelBuilder.PLY, "application/octet-stream") }) {
                    Text("PLY")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "GLB opens in most 3D viewers and Blender; STL is in millimetres for 3D printing and CAD.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRebuild) { Text("Rebuild") }
            }
        }
    }
}

@Composable
private fun MeshViewer(mesh: Mesh, modifier: Modifier) {
    val context = LocalContext.current
    val view = remember { MeshView(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, view) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            view.onPause()
        }
    }
    AndroidView(factory = { view }, modifier = modifier, update = { it.setMesh(mesh) })
}

private fun summary(report: JsonObject): String? {
    val s = report["stats"] as? JsonObject ?: return null
    val tris = s["triangles"]?.jsonPrimitive?.intOrNull ?: return null
    val frames = (report["frames"] as? JsonObject)?.get("depth_integrated")?.jsonPrimitive?.intOrNull
    return "%,d triangles".format(tris) + (frames?.let { " from $it depth frames" } ?: "")
}

/** Copies a model file into the share cache (the only folder the FileProvider exposes) and opens the share sheet. */
private fun shareFile(context: Context, capture: CaptureSummary, name: String, mime: String) {
    val captureId = capture.id
    val source = File(File(capture.dir, ModelBuilder.MODEL_DIR), name)
    val shared = File(File(context.cacheDir, MainActivity.SHARE_CACHE_DIR), "$captureId-$name")
    try {
        shared.parentFile?.mkdirs()
        source.copyTo(shared, overwrite = true)
    } catch (_: Exception) {
        return
    }
    val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", shared)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Depthfold model $captureId")
        clipData = ClipData.newRawUri(shared.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, "Share 3D model"))
    } catch (_: ActivityNotFoundException) {
        // No app can receive the file; nothing to do.
    }
}
