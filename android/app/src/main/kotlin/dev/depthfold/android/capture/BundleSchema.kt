/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.capture

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `arcore.depth_mode` / `depth.source` values of the bundle spec. */
enum class DepthMode(val wireName: String) {
    /** `acquireRawDepthImage16Bits` + `acquireRawDepthConfidenceImage`. */
    RAW("raw"),

    /** `acquireDepthImage16Bits`: interpolated, no confidence. */
    SMOOTH("smooth"),

    /** No Depth API on this device. */
    NONE("none"),
}

/** One line of `frames.jsonl`. */
data class FrameRecord(
    val index: Int,
    val timestampNs: Long,
    val trackingState: String,
    val rgbPath: String,
    val depthPath: String?,
    val confidencePath: String?,
    val depthTimestampNs: Long?,
    val intrinsics: Intrinsics,
    val depthIntrinsics: Intrinsics?,
    val tWorldCamera: Array<DoubleArray>,
    val poseArCore: PoseArCore,
) {
    override fun equals(other: Any?): Boolean =
        other is FrameRecord && index == other.index && timestampNs == other.timestampNs &&
            trackingState == other.trackingState && rgbPath == other.rgbPath && depthPath == other.depthPath &&
            confidencePath == other.confidencePath && depthTimestampNs == other.depthTimestampNs &&
            intrinsics == other.intrinsics && depthIntrinsics == other.depthIntrinsics &&
            tWorldCamera.contentDeepEquals(other.tWorldCamera) && poseArCore == other.poseArCore

    override fun hashCode(): Int = 31 * index + timestampNs.hashCode()
}

data class DeviceInfo(val manufacturer: String, val model: String, val androidSdk: Int)

data class ImageSize(val width: Int, val height: Int)

/** Contents of `bundle.json`. */
data class BundleManifest(
    val captureId: String,
    val createdUtc: String,
    val appName: String,
    val appVersion: String,
    val device: DeviceInfo,
    val arcoreSdkVersion: String,
    val depthMode: DepthMode,
    val rgbSize: ImageSize,
    /** Null when depth mode is none, or when no depth frame was recorded (size is then unknown). */
    val depthSize: ImageSize?,
    val frameCount: Int,
    val notes: String = "",
)

/** Builds the JSON documents defined in `docs/capture-bundle.md`. Pure JVM code. */
object BundleSchema {
    const val FORMAT = "depthfold-bundle"
    const val VERSION = "0.1"

    const val BUNDLE_JSON = "bundle.json"
    const val FRAMES_JSONL = "frames.jsonl"
    const val RGB_DIR = "rgb"
    const val DEPTH_DIR = "depth"
    const val CONFIDENCE_DIR = "confidence"

    fun frameName(index: Int): String = "%06d".format(java.util.Locale.ROOT, index)
    fun rgbPath(index: Int) = "$RGB_DIR/${frameName(index)}.jpg"
    fun depthPath(index: Int) = "$DEPTH_DIR/${frameName(index)}.png"
    fun confidencePath(index: Int) = "$CONFIDENCE_DIR/${frameName(index)}.png"

    fun manifestJson(m: BundleManifest): JsonObject = buildJsonObject {
        put("format", FORMAT)
        put("version", VERSION)
        put("capture_id", m.captureId)
        put("created_utc", m.createdUtc)
        put("app", buildJsonObject {
            put("name", m.appName)
            put("version", m.appVersion)
        })
        put("device", buildJsonObject {
            put("manufacturer", m.device.manufacturer)
            put("model", m.device.model)
            put("android_sdk", m.device.androidSdk)
        })
        put("arcore", buildJsonObject {
            put("sdk_version", m.arcoreSdkVersion)
            put("depth_mode", m.depthMode.wireName)
        })
        put("rgb", buildJsonObject {
            put("width", m.rgbSize.width)
            put("height", m.rgbSize.height)
            put("format", "jpeg")
        })
        val depth = m.depthSize
        if (m.depthMode == DepthMode.NONE || depth == null) {
            put("depth", JsonNull)
        } else {
            put("depth", buildJsonObject {
                put("width", depth.width)
                put("height", depth.height)
                put("units", "mm")
                put("source", m.depthMode.wireName)
                put("has_confidence", m.depthMode == DepthMode.RAW)
            })
        }
        put("frame_count", m.frameCount)
        put("notes", m.notes)
    }

    fun frameJson(f: FrameRecord): JsonObject = buildJsonObject {
        put("index", f.index)
        put("timestamp_ns", f.timestampNs)
        put("tracking_state", f.trackingState)
        put("rgb", f.rgbPath)
        put("depth", f.depthPath.orNull())
        put("confidence", f.confidencePath.orNull())
        put("depth_timestamp_ns", f.depthTimestampNs?.let { JsonPrimitive(it) } ?: JsonNull)
        put("intrinsics", intrinsicsJson(f.intrinsics))
        put("depth_intrinsics", f.depthIntrinsics?.let { intrinsicsJson(it) } ?: JsonNull)
        put("T_world_camera", matrixJson(f.tWorldCamera))
        put("pose_arcore", buildJsonObject {
            put("qx", f.poseArCore.qx)
            put("qy", f.poseArCore.qy)
            put("qz", f.poseArCore.qz)
            put("qw", f.poseArCore.qw)
            put("tx", f.poseArCore.tx)
            put("ty", f.poseArCore.ty)
            put("tz", f.poseArCore.tz)
        })
    }

    fun intrinsicsJson(k: Intrinsics): JsonObject = buildJsonObject {
        put("fx", k.fx)
        put("fy", k.fy)
        put("cx", k.cx)
        put("cy", k.cy)
        put("width", k.width)
        put("height", k.height)
    }

    fun matrixJson(m: Array<DoubleArray>): JsonArray = buildJsonArray {
        for (row in m) add(buildJsonArray { for (v in row) add(JsonPrimitive(v)) })
    }

    private fun String?.orNull(): JsonElement = this?.let { JsonPrimitive(it) } ?: JsonNull
}
