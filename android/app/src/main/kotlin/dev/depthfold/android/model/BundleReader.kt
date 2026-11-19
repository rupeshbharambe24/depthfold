/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.model

import dev.depthfold.android.capture.BundleSchema
import dev.depthfold.android.capture.Intrinsics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt

/** One `frames.jsonl` record as read back for fusion. Poses are row-major 4x4 `T_world_camera` (OpenCV axes). */
class BundleFrame(
    val index: Int,
    val timestampNs: Long,
    val trackingState: String,
    val rgbPath: String,
    val depthPath: String?,
    val confidencePath: String?,
    val depthTimestampNs: Long?,
    val intrinsics: Intrinsics,
    val depthIntrinsics: Intrinsics?,
    val tWorldCamera: DoubleArray,
) {
    val isTracking: Boolean get() = trackingState == "TRACKING"
}

/** A parsed bundle folder: `bundle.json` plus every readable line of `frames.jsonl`. */
class BundleData(
    val dir: File,
    val captureId: String,
    val depthMode: String,
    val frames: List<BundleFrame>,
    /** Lines of `frames.jsonl` that could not be parsed (e.g. a line cut short when the app was killed). */
    val badLines: Int,
)

/** A depth image chosen for fusion and the camera pose it was captured at. */
class DepthSample(val frame: BundleFrame, val tWorldCamera: DoubleArray, val poseSource: String)

class DepthSelection(
    val samples: List<DepthSample>,
    val framesTotal: Int,
    val framesTracking: Int,
    val skippedNotTracking: Int,
    val skippedNoDepth: Int,
    val skippedRepeatedDepth: Int,
    val skippedStaleDepth: Int,
    val repairedPoses: Int,
)

/** Reads capture bundles (see `docs/capture-bundle.md`) back for on-device processing. Pure JVM code. */
object BundleReader {

    fun read(dir: File): BundleData {
        val manifestFile = File(dir, BundleSchema.BUNDLE_JSON)
        if (!manifestFile.isFile) throw IOException("${dir.name} has no ${BundleSchema.BUNDLE_JSON}")
        val manifest = Json.parseToJsonElement(manifestFile.readText()).jsonObject
        if (manifest["format"]?.jsonPrimitive?.content != BundleSchema.FORMAT) {
            throw IOException("${dir.name} is not a Depthfold bundle")
        }
        val depthMode = (manifest["arcore"] as? JsonObject)?.get("depth_mode")?.jsonPrimitive?.content ?: "none"
        val frames = ArrayList<BundleFrame>()
        var bad = 0
        val framesFile = File(dir, BundleSchema.FRAMES_JSONL)
        if (framesFile.isFile) {
            framesFile.forEachLine(Charsets.UTF_8) { line ->
                if (line.isBlank()) return@forEachLine
                val frame = try {
                    parseFrame(Json.parseToJsonElement(line).jsonObject)
                } catch (_: Exception) {
                    null
                }
                if (frame == null) bad++ else frames.add(frame)
            }
        }
        return BundleData(
            dir = dir,
            captureId = manifest["capture_id"]?.jsonPrimitive?.content ?: dir.name,
            depthMode = depthMode,
            frames = frames,
            badLines = bad,
        )
    }

    private fun parseFrame(o: JsonObject): BundleFrame {
        val m = o.getValue("T_world_camera").jsonArray
        require(m.size == 4)
        val t = DoubleArray(16)
        for (r in 0 until 4) {
            val row = m[r].jsonArray
            require(row.size == 4)
            for (c in 0 until 4) t[r * 4 + c] = row[c].jsonPrimitive.double
        }
        return BundleFrame(
            index = o.getValue("index").jsonPrimitive.int,
            timestampNs = o.getValue("timestamp_ns").jsonPrimitive.long,
            trackingState = o.getValue("tracking_state").jsonPrimitive.content,
            rgbPath = o.getValue("rgb").jsonPrimitive.content,
            depthPath = o["depth"].stringOrNull(),
            confidencePath = o["confidence"].stringOrNull(),
            depthTimestampNs = o["depth_timestamp_ns"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long,
            intrinsics = intrinsics(o.getValue("intrinsics")),
            depthIntrinsics = o["depth_intrinsics"]?.takeUnless { it is JsonNull }?.let { intrinsics(it) },
            tWorldCamera = t,
        )
    }

    private fun intrinsics(e: JsonElement): Intrinsics {
        val k = e.jsonObject
        return Intrinsics(
            fx = k.getValue("fx").jsonPrimitive.double,
            fy = k.getValue("fy").jsonPrimitive.double,
            cx = k.getValue("cx").jsonPrimitive.double,
            cy = k.getValue("cy").jsonPrimitive.double,
            width = k.getValue("width").jsonPrimitive.int,
            height = k.getValue("height").jsonPrimitive.int,
        )
    }

    private fun JsonElement?.stringOrNull(): String? =
        if (this == null || this is JsonNull || this is JsonArray) null else jsonPrimitive.content

    /**
     * Selects the depth images that can be fused, each with the pose it was captured at. Same rules as the
     * Python toolkit's `Bundle.depth_samples`: skip frames that are not tracking, skip depth already seen on an
     * earlier frame, and give depth older than its frame by more than [maxDepthAgeMs] the pose of the frame
     * recorded at its timestamp (or one interpolated from the two tracking frames around it).
     */
    fun selectDepth(
        bundle: BundleData,
        maxDepthAgeMs: Double = 1.0,
        maxInterpGapMs: Double = 200.0,
        repairStalePose: Boolean = true,
    ): DepthSelection {
        val tracking = bundle.frames.filter { it.isTracking }
        val times = LongArray(tracking.size) { tracking[it].timestampNs }
        val seen = HashSet<Long>()
        val maxAge = (maxDepthAgeMs * 1e6).toLong()
        val maxGap = (maxInterpGapMs * 1e6).toLong()
        val samples = ArrayList<DepthSample>()
        var notTracking = 0
        var noDepth = 0
        var repeated = 0
        var stale = 0
        var repaired = 0
        for (f in bundle.frames) {
            val dts = f.depthTimestampNs
            val isRepeat = dts != null && dts in seen
            if (dts != null && f.depthPath != null) seen.add(dts)
            when {
                !f.isTracking -> notTracking++
                f.depthPath == null || f.depthIntrinsics == null -> noDepth++
                isRepeat -> repeated++
                dts == null || f.timestampNs - dts <= maxAge -> samples.add(DepthSample(f, f.tWorldCamera, "frame"))
                !repairStalePose -> stale++
                else -> {
                    var k = java.util.Arrays.binarySearch(times, dts)
                    if (k < 0) k = -k - 1
                    val near = listOf(k - 1, k).filter { it in times.indices && abs(times[it] - dts) <= maxAge }
                    if (near.isNotEmpty()) {
                        val j = near.minBy { abs(times[it] - dts) }
                        samples.add(DepthSample(f, tracking[j].tWorldCamera, "matched_frame"))
                        repaired++
                    } else if (k > 0 && k < times.size && times[k] - times[k - 1] <= maxGap) {
                        val pose = Rigid.interpolate(
                            dts, times[k - 1], tracking[k - 1].tWorldCamera, times[k], tracking[k].tWorldCamera,
                        )
                        samples.add(DepthSample(f, pose, "interpolated"))
                        repaired++
                    } else {
                        stale++
                    }
                }
            }
        }
        return DepthSelection(
            samples = samples,
            framesTotal = bundle.frames.size,
            framesTracking = tracking.size,
            skippedNotTracking = notTracking,
            skippedNoDepth = noDepth,
            skippedRepeatedDepth = repeated,
            skippedStaleDepth = stale,
            repairedPoses = repaired,
        )
    }
}

/** Row-major 4x4 rigid transforms stored as `DoubleArray(16)`. */
object Rigid {

    /** Pose at [t] between ([t0], [a]) and ([t1], [b]): slerp of the rotations, linear translation. */
    fun interpolate(t: Long, t0: Long, a: DoubleArray, t1: Long, b: DoubleArray): DoubleArray {
        if (t1 == t0) return a.copyOf()
        val s = (t - t0).toDouble() / (t1 - t0)
        val qa = quaternion(a)
        var qb = quaternion(b)
        var dot = qa.indices.sumOf { qa[it] * qb[it] }
        if (dot < 0) {
            qb = DoubleArray(4) { -qb[it] }
            dot = -dot
        }
        val q = if (dot > 0.9995) {
            DoubleArray(4) { qa[it] + s * (qb[it] - qa[it]) }
        } else {
            val theta = acos(dot.coerceAtMost(1.0))
            val wa = sin((1 - s) * theta) / sin(theta)
            val wb = sin(s * theta) / sin(theta)
            DoubleArray(4) { wa * qa[it] + wb * qb[it] }
        }
        val n = sqrt(q.sumOf { it * it })
        for (i in q.indices) q[i] /= n
        val out = rotation(q)
        out[3] = (1 - s) * a[3] + s * b[3]
        out[7] = (1 - s) * a[7] + s * b[7]
        out[11] = (1 - s) * a[11] + s * b[11]
        return out
    }

    /** Unit quaternion (w, x, y, z) of the rotation part of [m]. */
    fun quaternion(m: DoubleArray): DoubleArray {
        val m00 = m[0]
        val m11 = m[5]
        val m22 = m[10]
        val tr = m00 + m11 + m22
        return if (tr > 0) {
            val s = sqrt(tr + 1.0) * 2
            doubleArrayOf(0.25 * s, (m[9] - m[6]) / s, (m[2] - m[8]) / s, (m[4] - m[1]) / s)
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1.0 + m00 - m11 - m22) * 2
            doubleArrayOf((m[9] - m[6]) / s, 0.25 * s, (m[1] + m[4]) / s, (m[2] + m[8]) / s)
        } else if (m11 > m22) {
            val s = sqrt(1.0 + m11 - m00 - m22) * 2
            doubleArrayOf((m[2] - m[8]) / s, (m[1] + m[4]) / s, 0.25 * s, (m[6] + m[9]) / s)
        } else {
            val s = sqrt(1.0 + m22 - m00 - m11) * 2
            doubleArrayOf((m[4] - m[1]) / s, (m[2] + m[8]) / s, (m[6] + m[9]) / s, 0.25 * s)
        }
    }

    /** 4x4 transform with the rotation of unit quaternion [q] (w, x, y, z) and zero translation. */
    fun rotation(q: DoubleArray): DoubleArray {
        val (w, x, y, z) = q
        return doubleArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y), 0.0,
            2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x), 0.0,
            2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y), 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
    }
}
