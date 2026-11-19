/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.model

import dev.depthfold.android.capture.Intrinsics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

/** Decodes a bundle RGB JPEG, downscaled to at most about [maxWidth] pixels wide. The app uses `BitmapFactory`. */
fun interface RgbDecoder {
    fun decode(file: File, maxWidth: Int): RgbImage
}

/** On-device fusion parameters; the defaults match the toolkit's `depthfold fuse` except [maxDepth]. */
data class ModelConfig(
    val voxel: Float = 0.005f,
    val truncVoxels: Float = 3f,
    val minConfidence: Int = 128,
    val minDepth: Float = 0.10f,
    /** Nearer than the toolkit's 2 m, to keep the volume within phone memory; scans are close-up. */
    val maxDepth: Float = 1.5f,
    val minWeight: Float = 3f,
    val minComponentTriangles: Int = 200,
    val colourMaxWidth: Int = 640,
    val maxBlocks: Int = 12_000,
)

class ModelResult(val dir: File, val mesh: Mesh, val report: JsonObject)

class ModelError(message: String) : IOException(message)

/**
 * Builds a 3D model from a capture bundle on the phone: selects usable depth frames, integrates them into a
 * [TsdfVolume], extracts a mesh with [SurfaceNets], removes small pieces and writes `model/` inside the bundle
 * (`mesh.glb`, `mesh.ply`, `mesh.stl` in mm, `model.json`). Pure JVM code apart from the injected [RgbDecoder].
 */
class ModelBuilder(
    private val rgbDecoder: RgbDecoder?,
    private val generator: String,
    private val config: ModelConfig = ModelConfig(),
) {
    /**
     * @param progress called with a fraction in [0, 1] and a short status line.
     * @param isCancelled polled between frames; when it returns true the build stops with [CancellationException]
     *   and any earlier model is kept.
     */
    fun build(
        bundleDir: File,
        progress: (Float, String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): ModelResult {
        val start = System.nanoTime()
        progress(0f, "Reading capture")
        val bundle = BundleReader.read(bundleDir)
        if (bundle.depthMode == "none") throw ModelError("This capture has no depth, so no 3D model can be built.")
        val selection = BundleReader.selectDepth(bundle)
        if (selection.samples.isEmpty()) {
            throw ModelError("No usable depth frames: tracking was lost or depth was missing for the whole capture.")
        }

        val cfg = config
        val volume = TsdfVolume(cfg.voxel, cfg.voxel * cfg.truncVoxels, cfg.maxBlocks)
        var integrated = 0
        var validFractionSum = 0.0
        for ((n, sample) in selection.samples.withIndex()) {
            if (isCancelled()) throw CancellationException("cancelled")
            progress(0.85f * n / selection.samples.size, "Fusing depth ${n + 1} of ${selection.samples.size}")
            val f = sample.frame
            val depthK = f.depthIntrinsics ?: continue
            val depth = readDepth(bundleDir, f, depthK)
            val valid = depth.count { it > 0f }
            validFractionSum += valid.toDouble() / depth.size
            if (valid == 0) continue
            var colour: RgbImage? = null
            var colourK: Intrinsics? = null
            if (rgbDecoder != null) {
                try {
                    colour = rgbDecoder.decode(File(bundleDir, f.rgbPath), cfg.colourMaxWidth)
                    colourK = f.intrinsics.scaledTo(colour.width, colour.height)
                } catch (_: Exception) {
                    colour = null // an unreadable photo only costs colour, not geometry
                }
            }
            volume.integrate(depth, depthK, sample.tWorldCamera, colour, colourK)
            integrated++
        }
        if (integrated == 0) {
            throw ModelError("Every depth pixel was below the confidence or outside the ${cfg.maxDepth} m range.")
        }

        if (isCancelled()) throw CancellationException("cancelled")
        progress(0.88f, "Extracting surface")
        val raw = SurfaceNets.extract(volume, cfg.minWeight)
        progress(0.94f, "Cleaning mesh")
        val mesh = raw.withoutSmallComponents(cfg.minComponentTriangles)
        if (mesh.triangleCount == 0) {
            throw ModelError("The scan produced no surface. Move slowly around the object and keep it within 1.5 m.")
        }

        if (isCancelled()) throw CancellationException("cancelled")
        progress(0.97f, "Saving model")
        val b = mesh.bounds()
        val seconds = (System.nanoTime() - start) / 1e9
        val report = buildJsonObject {
            putJsonObject("tool") {
                put("name", "depthfold-android")
                put("version", generator)
                put("command", "build-model")
            }
            put("capture_id", bundle.captureId)
            putJsonObject("frames") {
                put("total", selection.framesTotal)
                put("tracking", selection.framesTracking)
                put("depth_samples", selection.samples.size)
                put("depth_integrated", integrated)
                put("skipped_not_tracking", selection.skippedNotTracking)
                put("skipped_no_depth", selection.skippedNoDepth)
                put("skipped_repeated_depth", selection.skippedRepeatedDepth)
                put("skipped_stale_depth", selection.skippedStaleDepth)
                put("repaired_poses", selection.repairedPoses)
                put("unreadable_lines", bundle.badLines)
            }
            putJsonObject("config") {
                put("voxel_m", cfg.voxel)
                put("sdf_trunc_m", cfg.voxel * cfg.truncVoxels)
                put("min_confidence", cfg.minConfidence)
                put("min_depth_m", cfg.minDepth)
                put("max_depth_m", cfg.maxDepth)
                put("min_weight", cfg.minWeight)
                put("min_component_triangles", cfg.minComponentTriangles)
                put("mesher", "surface nets")
            }
            putJsonObject("stats") {
                put("vertices", mesh.vertexCount)
                put("triangles", mesh.triangleCount)
                put("triangles_before_cleaning", raw.triangleCount)
                put("voxel_blocks", volume.blockCount)
                put("volume_full", volume.overflowed)
                put("mean_valid_depth_fraction", round4(validFractionSum / selection.samples.size))
                put("bbox_min_m", buildJsonArray { for (a in 0..2) add(JsonPrimitive(round4(b[a].toDouble()))) })
                put("bbox_max_m", buildJsonArray { for (a in 3..5) add(JsonPrimitive(round4(b[a].toDouble()))) })
                put("seconds", (seconds * 100).roundToInt() / 100.0)
            }
            putJsonObject("units") {
                put("mesh.glb, mesh.ply", "m")
                put("mesh.stl", "mm")
            }
            put("frame", "ARCore world (right-handed, +Y up)")
        }

        val tmp = File(bundleDir, "$MODEL_DIR.tmp")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) throw IOException("cannot create $tmp")
        MeshFiles.writeGlb(mesh, File(tmp, GLB), generator)
        MeshFiles.writePly(mesh, File(tmp, PLY), "Depthfold ${bundle.captureId}, metres, ARCore world +Y up")
        MeshFiles.writeStl(mesh, File(tmp, STL))
        File(tmp, REPORT).writeText(PRETTY.encodeToString(JsonElement.serializer(), report) + "\n")
        val target = File(bundleDir, MODEL_DIR)
        target.deleteRecursively()
        if (!tmp.renameTo(target)) throw IOException("cannot move model into place")
        progress(1f, "Done")
        return ModelResult(target, mesh, report)
    }

    /** Depth in metres with low-confidence and out-of-range pixels set to 0 (the toolkit's `filter_depth`). */
    private fun readDepth(dir: File, f: BundleFrame, k: Intrinsics): FloatArray {
        val depth = PngDecoder.decodeGray(File(dir, f.depthPath!!).readBytes())
        if (depth.width != k.width || depth.height != k.height) {
            throw ModelError("Frame ${f.index}: depth is ${depth.width}x${depth.height}, intrinsics say ${k.width}x${k.height}")
        }
        val conf = f.confidencePath?.let { p ->
            PngDecoder.decodeGray(File(dir, p).readBytes()).takeIf { it.width == depth.width && it.height == depth.height }
        }
        val cfg = config
        return FloatArray(depth.samples.size) { i ->
            val m = depth.samples[i] / 1000f
            when {
                m < cfg.minDepth || m > cfg.maxDepth -> 0f
                conf != null && conf.samples[i] < cfg.minConfidence -> 0f
                else -> m
            }
        }
    }

    companion object {
        const val MODEL_DIR = "model"
        const val GLB = "mesh.glb"
        const val PLY = "mesh.ply"
        const val STL = "mesh.stl"
        const val REPORT = "model.json"
        private val PRETTY = Json { prettyPrint = true }

        private fun round4(v: Double) = (v * 10_000).roundToInt() / 10_000.0

        /** True when [bundleDir] holds a finished model. */
        fun hasModel(bundleDir: File): Boolean = File(File(bundleDir, MODEL_DIR), PLY).isFile
    }
}
