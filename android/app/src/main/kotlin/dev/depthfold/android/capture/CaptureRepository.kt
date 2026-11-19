/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.capture

import dev.depthfold.android.model.ModelBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Summary of a capture bundle on disk, for the captures list. */
data class CaptureSummary(
    val id: String,
    val dir: File,
    val createdUtc: String?,
    val frameCount: Int?,
    val depthMode: String?,
    val sizeBytes: Long,
    /** True when the app has built a 3D model into the bundle's `model/` folder. */
    val hasModel: Boolean = false,
)

/** Lists, deletes and zips capture bundles under [root]. Blocking; call from a background thread. */
class CaptureRepository(val root: File) {

    fun list(): List<CaptureSummary> {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.map { summarize(it) }.sortedByDescending { it.id }
    }

    fun summarize(dir: File): CaptureSummary {
        val manifest = readManifest(dir)
        return CaptureSummary(
            id = dir.name,
            dir = dir,
            createdUtc = manifest?.get("created_utc")?.jsonPrimitive?.content,
            frameCount = manifest?.get("frame_count")?.jsonPrimitive?.intOrNull,
            depthMode = (manifest?.get("arcore") as? JsonObject)?.get("depth_mode")?.jsonPrimitive?.content,
            sizeBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            hasModel = ModelBuilder.hasModel(dir),
        )
    }

    fun delete(id: String): Boolean {
        val dir = resolve(id) ?: return false
        return dir.deleteRecursively()
    }

    /**
     * Zips bundle [id] into [outFile] with the bundle folder as the single top-level entry, so unzipping yields
     * `<capture-id>/bundle.json` etc. JPEG/PNG payloads are already compressed, so the fastest deflate level is used.
     */
    fun zip(id: String, outFile: File): File {
        val dir = resolve(id) ?: throw IllegalArgumentException("no capture $id")
        outFile.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile), 256 * 1024)).use { zos ->
            zos.setLevel(Deflater.BEST_SPEED)
            dir.walkTopDown().onEnter { !it.name.endsWith(".tmp") }.filter { it.isFile && !it.name.endsWith(".tmp") }.sortedBy { it.path }.forEach { f ->
                val rel = f.relativeTo(dir).invariantSeparatorsPath
                zos.putNextEntry(ZipEntry("${dir.name}/$rel").apply { time = f.lastModified() })
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return outFile
    }

    /** Returns a fresh, not-yet-existing capture directory named after [now] in UTC. */
    fun newCaptureDir(now: Date = Date()): File {
        val base = captureIdFor(now)
        var candidate = File(root, base)
        var n = 1
        while (candidate.exists()) candidate = File(root, "$base-${n++}")
        return candidate
    }

    private fun resolve(id: String): File? {
        if (id.isEmpty() || id.contains('/') || id.contains('\\') || id == "." || id == "..") return null
        return File(root, id).takeIf { it.isDirectory }
    }

    private fun readManifest(dir: File): JsonObject? = try {
        val f = File(dir, BundleSchema.BUNDLE_JSON)
        if (f.isFile) Json.parseToJsonElement(f.readText()).jsonObject else null
    } catch (_: Exception) {
        null
    }

    companion object {
        fun captureIdFor(date: Date): String = utcFormat("yyyyMMdd-HHmmss").format(date)
        fun isoUtc(date: Date): String = utcFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(date)

        private fun utcFormat(pattern: String) =
            SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }
}
