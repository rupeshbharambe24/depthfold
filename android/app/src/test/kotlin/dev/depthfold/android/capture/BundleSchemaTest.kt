/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleSchemaTest {

    private val manifest = BundleManifest(
        captureId = "20261118-153012",
        createdUtc = "2026-11-18T15:30:12Z",
        appName = "depthfold-android",
        appVersion = "0.1.0",
        device = DeviceInfo("samsung", "SM-A556E", 35),
        arcoreSdkVersion = "1.57.0",
        depthMode = DepthMode.RAW,
        rgbSize = ImageSize(1920, 1080),
        depthSize = ImageSize(160, 90),
        frameCount = 120,
    )

    private val identityCv = arrayOf(
        doubleArrayOf(1.0, 0.0, 0.0, 0.0),
        doubleArrayOf(0.0, -1.0, 0.0, 0.0),
        doubleArrayOf(0.0, 0.0, -1.0, 0.0),
        doubleArrayOf(0.0, 0.0, 0.0, 1.0),
    )

    @Test
    fun manifestHasSpecKeysAndValues() {
        val j = BundleSchema.manifestJson(manifest)
        assertEquals(
            listOf("format", "version", "capture_id", "created_utc", "app", "device", "arcore", "rgb", "depth", "frame_count", "notes"),
            j.keys.toList(),
        )
        assertEquals("depthfold-bundle", j["format"]!!.jsonPrimitive.content)
        assertEquals("0.1", j["version"]!!.jsonPrimitive.content)
        assertTrue(j["version"]!!.jsonPrimitive.isString)
        assertEquals("depthfold-android", j["app"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(35, j["device"]!!.jsonObject["android_sdk"]!!.jsonPrimitive.int)
        assertEquals("raw", j["arcore"]!!.jsonObject["depth_mode"]!!.jsonPrimitive.content)
        assertEquals("1.57.0", j["arcore"]!!.jsonObject["sdk_version"]!!.jsonPrimitive.content)
        val rgb = j["rgb"]!!.jsonObject
        assertEquals(1920, rgb["width"]!!.jsonPrimitive.int)
        assertEquals("jpeg", rgb["format"]!!.jsonPrimitive.content)
        val depth = j["depth"]!!.jsonObject
        assertEquals(listOf("width", "height", "units", "source", "has_confidence"), depth.keys.toList())
        assertEquals(90, depth["height"]!!.jsonPrimitive.int)
        assertEquals("mm", depth["units"]!!.jsonPrimitive.content)
        assertEquals("raw", depth["source"]!!.jsonPrimitive.content)
        assertTrue(depth["has_confidence"]!!.jsonPrimitive.boolean)
        assertEquals(120, j["frame_count"]!!.jsonPrimitive.int)
        assertEquals("", j["notes"]!!.jsonPrimitive.content)
    }

    @Test
    fun smoothModeHasNoConfidenceAndNoneModeHasNullDepth() {
        val smooth = BundleSchema.manifestJson(manifest.copy(depthMode = DepthMode.SMOOTH))
        assertEquals("smooth", smooth["depth"]!!.jsonObject["source"]!!.jsonPrimitive.content)
        assertFalse(smooth["depth"]!!.jsonObject["has_confidence"]!!.jsonPrimitive.boolean)

        val none = BundleSchema.manifestJson(manifest.copy(depthMode = DepthMode.NONE))
        assertEquals("none", none["arcore"]!!.jsonObject["depth_mode"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, none["depth"])
    }

    @Test
    fun frameWithDepthMatchesSpecShape() {
        val f = FrameRecord(
            index = 0,
            timestampNs = 123456789000L,
            trackingState = "TRACKING",
            rgbPath = BundleSchema.rgbPath(0),
            depthPath = BundleSchema.depthPath(0),
            confidencePath = BundleSchema.confidencePath(0),
            depthTimestampNs = 123456700000L,
            intrinsics = Intrinsics(1450.1, 1450.1, 960.2, 540.7, 1920, 1080),
            depthIntrinsics = Intrinsics(120.8, 120.8, 80.0, 45.1, 160, 90),
            tWorldCamera = identityCv,
            poseArCore = PoseArCore(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0),
        )
        val line = BundleSchema.frameJson(f).toString()
        assertFalse("single line", line.contains('\n'))
        assertFalse("slashes are not escaped", line.contains("\\/"))
        val j = Json.parseToJsonElement(line).jsonObject
        assertEquals(
            listOf(
                "index", "timestamp_ns", "tracking_state", "rgb", "depth", "confidence", "depth_timestamp_ns",
                "intrinsics", "depth_intrinsics", "T_world_camera", "pose_arcore",
            ),
            j.keys.toList(),
        )
        assertEquals(0, j["index"]!!.jsonPrimitive.int)
        assertEquals(123456789000L, j["timestamp_ns"]!!.jsonPrimitive.long)
        assertEquals("rgb/000000.jpg", j["rgb"]!!.jsonPrimitive.content)
        assertEquals("depth/000000.png", j["depth"]!!.jsonPrimitive.content)
        assertEquals("confidence/000000.png", j["confidence"]!!.jsonPrimitive.content)
        assertEquals(123456700000L, j["depth_timestamp_ns"]!!.jsonPrimitive.long)
        val k = j["intrinsics"]!!.jsonObject
        assertEquals(listOf("fx", "fy", "cx", "cy", "width", "height"), k.keys.toList())
        assertEquals(1450.1, k["fx"]!!.jsonPrimitive.double, 0.0)
        assertEquals(1080, k["height"]!!.jsonPrimitive.int)
        assertEquals(160, j["depth_intrinsics"]!!.jsonObject["width"]!!.jsonPrimitive.int)
        val t = j["T_world_camera"]!!.jsonArray
        assertEquals(4, t.size)
        t.forEach { assertEquals(4, (it as JsonArray).size) }
        assertEquals(-1.0, t[1].jsonArray[1].jsonPrimitive.double, 0.0) // row-major: [row][col]
        assertEquals(
            listOf("qx", "qy", "qz", "qw", "tx", "ty", "tz"),
            (j["pose_arcore"] as JsonObject).keys.toList(),
        )
    }

    @Test
    fun frameWithoutDepthHasNullDepthFields() {
        val f = FrameRecord(
            index = 7, timestampNs = 1L, trackingState = "PAUSED", rgbPath = BundleSchema.rgbPath(7),
            depthPath = null, confidencePath = null, depthTimestampNs = null,
            intrinsics = Intrinsics(1.0, 1.0, 0.5, 0.5, 1, 1), depthIntrinsics = null,
            tWorldCamera = identityCv, poseArCore = PoseArCore(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0),
        )
        val j = Json.parseToJsonElement(BundleSchema.frameJson(f).toString()).jsonObject
        assertEquals("rgb/000007.jpg", j["rgb"]!!.jsonPrimitive.content)
        for (key in listOf("depth", "confidence", "depth_timestamp_ns", "depth_intrinsics")) {
            assertTrue("$key present", j.containsKey(key))
            assertEquals(key, JsonNull, j[key])
        }
    }

    @Test
    fun frameNamesAreSixDigits() {
        assertEquals("000000", BundleSchema.frameName(0))
        assertEquals("001234", BundleSchema.frameName(1234))
        assertEquals("depth/999999.png", BundleSchema.depthPath(999999))
    }
}
