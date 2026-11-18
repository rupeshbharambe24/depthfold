/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.imageio.ImageIO

class BundleWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Writes the NV21 length as text, so tests can check the JPEG step received a full packed buffer. */
    private val fakeJpeg = JpegEncoder { nv21, w, h, _, out -> out.write("NV21 $w $h ${nv21.size}".toByteArray()) }

    private val rgbW = 8
    private val rgbH = 6
    private val depthW = 4
    private val depthH = 2

    private fun manifest(id: String, mode: DepthMode) = BundleManifest(
        captureId = id,
        createdUtc = "2026-11-18T15:30:12Z",
        appName = "depthfold-android",
        appVersion = "0.1.0",
        device = DeviceInfo("google", "Pixel", 36),
        arcoreSdkVersion = "1.57.0",
        depthMode = mode,
        rgbSize = ImageSize(rgbW, rgbH),
        depthSize = null,
        frameCount = 0,
    )

    private fun yuv(): YuvImageCopy {
        val y = PlaneCopy(ByteArray(rgbW * rgbH) { it.toByte() }, rgbW, 1)
        val c = PlaneCopy(ByteArray(rgbW * rgbH / 4) { 1 }, rgbW / 2, 1)
        return YuvImageCopy(rgbW, rgbH, y, c, c)
    }

    private fun depth(ts: Long, withConfidence: Boolean): DepthCopy {
        val bytes = ByteArray(depthW * depthH * 2)
        for (i in 0 until depthW * depthH) {
            val mm = 1000 + i
            bytes[2 * i] = mm.toByte()
            bytes[2 * i + 1] = (mm ushr 8).toByte()
        }
        val conf = if (withConfidence) PlaneCopy(ByteArray(depthW * depthH) { (it * 30).toByte() }, depthW, 1) else null
        return DepthCopy(depthW, depthH, ts, PlaneCopy(bytes, depthW * 2, 2), conf)
    }

    private fun frame(ts: Long, depth: DepthCopy?): CapturedFrame {
        val pose = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f; it[12] = 0.25f }
        return CapturedFrame(
            timestampNs = ts,
            trackingState = "TRACKING",
            rgb = yuv(),
            imageIntrinsics = Intrinsics(10.0, 10.0, 4.0, 3.0, rgbW, rgbH),
            textureIntrinsics = Intrinsics(40.0, 40.0, 16.0, 9.0, 32, 18),
            poseColumnMajor = pose,
            poseArCore = PoseArCore(0.0, 0.0, 0.0, 1.0, 0.25, 0.0, 0.0),
            depth = depth,
        )
    }

    private fun writeBundle(dir: File, mode: DepthMode, frames: List<CapturedFrame>): BundleManifest {
        val writer = BundleWriter(dir, manifest(dir.name, mode), fakeJpeg, maxPending = 100)
        writer.start()
        frames.forEach { assertTrue(writer.submit(it)) }
        val result = writer.finish().get(10, TimeUnit.SECONDS)
        assertFalse("no submissions after finish", writer.submit(frames.first()))
        return result
    }

    @Test
    fun writesSpecCompliantRawBundle() {
        val dir = File(tmp.root, "20261118-153012")
        val frames = listOf(
            frame(100, depth(90, withConfidence = true)),
            frame(200, null), // depth unchanged since previous frame: not written
            frame(300, depth(290, withConfidence = true)),
        )
        val result = writeBundle(dir, DepthMode.RAW, frames)
        assertEquals(3, result.frameCount)

        val bundle = Json.parseToJsonElement(File(dir, "bundle.json").readText()).jsonObject
        assertEquals("depthfold-bundle", bundle["format"]!!.jsonPrimitive.content)
        assertEquals("20261118-153012", bundle["capture_id"]!!.jsonPrimitive.content)
        assertEquals(3, bundle["frame_count"]!!.jsonPrimitive.int)
        assertEquals(rgbW, bundle["rgb"]!!.jsonObject["width"]!!.jsonPrimitive.int)
        val depth = bundle["depth"]!!.jsonObject
        assertEquals(depthW, depth["width"]!!.jsonPrimitive.int)
        assertEquals(depthH, depth["height"]!!.jsonPrimitive.int)
        assertFalse(File(dir, "bundle.json.tmp").exists())

        val lines = File(dir, "frames.jsonl").readLines()
        assertEquals(3, lines.size)
        val objs = lines.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf(0, 1, 2), objs.map { it["index"]!!.jsonPrimitive.int })
        assertEquals(listOf(100L, 200L, 300L), objs.map { it["timestamp_ns"]!!.jsonPrimitive.long })

        val f0 = objs[0]
        assertEquals("depth/000000.png", f0["depth"]!!.jsonPrimitive.content)
        assertEquals("confidence/000000.png", f0["confidence"]!!.jsonPrimitive.content)
        assertEquals(90L, f0["depth_timestamp_ns"]!!.jsonPrimitive.long)
        // Depth intrinsics come from the texture intrinsics (32x18) scaled to 4x2.
        val dk = f0["depth_intrinsics"]!!.jsonObject
        assertEquals(5.0, dk["fx"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(40.0 * 2 / 18, dk["fy"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(2.0, dk["cx"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(1.0, dk["cy"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(10.0, f0["intrinsics"]!!.jsonObject["fx"]!!.jsonPrimitive.double, 0.0)
        // Row-major OpenCV pose: translation in column 3, Y/Z axes flipped.
        val t = f0["T_world_camera"]!!.jsonArray
        assertEquals(0.25, t[0].jsonArray[3].jsonPrimitive.double, 1e-9)
        assertEquals(-1.0, t[1].jsonArray[1].jsonPrimitive.double, 0.0)
        assertEquals(-1.0, t[2].jsonArray[2].jsonPrimitive.double, 0.0)

        val f1 = objs[1]
        assertEquals(JsonNull, f1["depth"])
        assertEquals(JsonNull, f1["confidence"])
        assertEquals(JsonNull, f1["depth_timestamp_ns"])
        assertEquals(JsonNull, f1["depth_intrinsics"])
        assertFalse(File(dir, "depth/000001.png").exists())

        // Every referenced file exists; JPEG step got a packed NV21 buffer (w*h*3/2 bytes).
        for (o in objs) {
            for (key in listOf("rgb", "depth", "confidence")) {
                val rel = (o[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: continue
                assertTrue("$rel exists", File(dir, rel).isFile)
            }
        }
        assertEquals("NV21 8 6 72", File(dir, "rgb/000000.jpg").readText())

        val depthPng = ImageIO.read(File(dir, "depth/000000.png"))
        assertEquals(depthW, depthPng.width)
        assertEquals(1000, depthPng.raster.getSample(0, 0, 0))
        assertEquals(1007, depthPng.raster.getSample(3, 1, 0))
        val confPng = ImageIO.read(File(dir, "confidence/000002.png"))
        assertEquals(depthW, confPng.width)
        assertEquals(30, confPng.raster.getSample(1, 0, 0))
    }

    @Test
    fun smoothBundleWritesDepthWithoutConfidence() {
        val dir = File(tmp.root, "smooth")
        writeBundle(dir, DepthMode.SMOOTH, listOf(frame(1, depth(1, withConfidence = true))))
        val f = Json.parseToJsonElement(File(dir, "frames.jsonl").readLines().single()).jsonObject
        assertEquals("depth/000000.png", f["depth"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, f["confidence"])
        val depth = Json.parseToJsonElement(File(dir, "bundle.json").readText()).jsonObject["depth"] as JsonObject
        assertEquals("smooth", depth["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun noDepthBundleHasNullDepth() {
        val dir = File(tmp.root, "nodepth")
        writeBundle(dir, DepthMode.NONE, listOf(frame(1, null), frame(2, null)))
        val bundle = Json.parseToJsonElement(File(dir, "bundle.json").readText()).jsonObject
        assertEquals(JsonNull, bundle["depth"])
        assertEquals("none", bundle["arcore"]!!.jsonObject["depth_mode"]!!.jsonPrimitive.content)
        assertEquals(2, bundle["frame_count"]!!.jsonPrimitive.int)
    }

    @Test
    fun dropsFramesWhenBacklogIsFull() {
        val dir = File(tmp.root, "backlog")
        val gate = java.util.concurrent.CountDownLatch(1)
        val slowJpeg = JpegEncoder { _, _, _, _, out -> gate.await(); out.write(1) }
        val writer = BundleWriter(dir, manifest("backlog", DepthMode.NONE), slowJpeg, maxPending = 2)
        writer.start()
        assertTrue(writer.submit(frame(1, null)))
        assertTrue(writer.submit(frame(2, null)))
        assertFalse(writer.hasCapacity())
        assertFalse(writer.submit(frame(3, null)))
        gate.countDown()
        assertEquals(2, writer.finish().get(10, TimeUnit.SECONDS).frameCount)
    }

    @Test
    fun repositoryListsZipsAndDeletes() {
        val repo = CaptureRepository(tmp.root)
        val dir = File(tmp.root, "20261118-153012")
        writeBundle(dir, DepthMode.RAW, listOf(frame(1, depth(1, true))))

        val summary = repo.list().single()
        assertEquals("20261118-153012", summary.id)
        assertEquals(1, summary.frameCount)
        assertEquals("raw", summary.depthMode)
        assertTrue(summary.sizeBytes > 0)

        val zip = repo.zip(summary.id, File(tmp.newFolder("share"), "x.zip"))
        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }.toSet()
            assertTrue(names.containsAll(listOf(
                "20261118-153012/bundle.json",
                "20261118-153012/frames.jsonl",
                "20261118-153012/rgb/000000.jpg",
                "20261118-153012/depth/000000.png",
                "20261118-153012/confidence/000000.png",
            )))
        }

        assertFalse(repo.delete("../etc"))
        assertTrue(repo.delete(summary.id))
        assertTrue(repo.list().none { it.id == summary.id })
    }

    @Test
    fun newCaptureDirIsUniqueUtcTimestamp() {
        val repo = CaptureRepository(tmp.root)
        val date = java.util.Date(1795015812000L) // 2026-11-18T15:30:12Z
        assertEquals("20261118-153012", CaptureRepository.captureIdFor(date))
        assertEquals("2026-11-18T15:30:12Z", CaptureRepository.isoUtc(date))
        val first = repo.newCaptureDir(date)
        first.mkdirs()
        assertEquals("20261118-153012-1", repo.newCaptureDir(date).name)
    }
}
