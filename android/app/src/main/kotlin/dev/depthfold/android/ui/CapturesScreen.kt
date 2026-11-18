/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.depthfold.android.BuildConfig
import dev.depthfold.android.MainActivity
import dev.depthfold.android.capture.CaptureRepository
import dev.depthfold.android.capture.CaptureSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun CapturesScreen(repository: CaptureRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var captures by remember { mutableStateOf<List<CaptureSummary>?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<CaptureSummary?>(null) }

    LaunchedEffect(refresh) {
        captures = withContext(Dispatchers.IO) { repository.list() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Captures", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
        }
        Text(
            "Stored in ${repository.root.absolutePath}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(8.dp))

        val list = captures
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> Text("No captures yet.", style = MaterialTheme.typography.bodyLarge)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { capture ->
                    CaptureCard(
                        capture = capture,
                        busy = busyId == capture.id,
                        enabled = busyId == null,
                        onShare = {
                            busyId = capture.id
                            error = null
                            scope.launch {
                                val result = runCatching {
                                    withContext(Dispatchers.IO) {
                                        val out = File(File(context.cacheDir, MainActivity.SHARE_CACHE_DIR), "${capture.id}.zip")
                                        repository.zip(capture.id, out)
                                    }
                                }
                                busyId = null
                                result
                                    .onSuccess { shareZip(context, it, capture.id) }
                                    .onFailure { error = "Could not create zip: ${it.message}" }
                            }
                        },
                        onDelete = { pendingDelete = capture },
                    )
                }
            }
        }
    }

    pendingDelete?.let { capture ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete capture?") },
            text = { Text("${capture.id} will be permanently deleted from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { repository.delete(capture.id) }
                        if (!ok) error = "Could not delete ${capture.id}"
                        refresh++
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CaptureCard(
    capture: CaptureSummary,
    busy: Boolean,
    enabled: Boolean,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(capture.id, style = MaterialTheme.typography.titleMedium)
            val frames = capture.frameCount?.let { "$it frames" } ?: "incomplete"
            val depth = capture.depthMode?.let { "depth $it" } ?: "depth ?"
            Text(
                "$frames, $depth, ${Formatter.formatShortFileSize(context, capture.sizeBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onShare, enabled = enabled) { Text("Share zip") }
                OutlinedButton(onClick = onDelete, enabled = enabled) { Text("Delete") }
                if (busy) CircularProgressIndicator(Modifier.padding(start = 4.dp))
            }
        }
    }
}

private fun shareZip(context: Context, zip: File, captureId: String) {
    val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", zip)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Depthfold capture $captureId")
        clipData = ClipData.newRawUri(captureId, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, "Share capture"))
    } catch (_: ActivityNotFoundException) {
        // No app can receive the zip; nothing to do.
    }
}
