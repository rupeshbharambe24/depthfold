/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.model

import dev.depthfold.android.capture.BundleManifest
import dev.depthfold.android.capture.BundleWriter
import dev.depthfold.android.capture.CapturedFrame
import dev.depthfold.android.capture.DepthCopy
import dev.depthfold.android.capture.DepthMode
import dev.depthfold.android.capture.DeviceInfo
import dev.depthfold.android.capture.ImageSize
import dev.depthfold.android.capture.Intrinsics
import dev.depthfold.android.capture.JpegEncoder
import dev.depthfold.android.capture.PlaneCopy
import dev.depthfold.android.capture.PoseArCore
import dev.depthfold.android.capture.YuvImageCopy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * End to end on the JVM: writes a bundle of a ray-traced scene (a 100 mm radius ball resting on a floor, circled by
 * the camera) with the app's own [BundleWriter], builds the model from it and checks the geometry against the
 * known scene.
 */
class ModelBuilderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val depthW = 160
    private val depthH = 90
    private val floorY = -0.0123 // off the voxel grid
    private val radius = 0.1
    private val centre = doubleArrayOf(0.0213, floorY + radius, -0.0071)

    private val jpeg = JpegEncoder { _, _, _, _, out -> out.write(byteArrayOf(0)) } // never decoded here

    /** Uniform orange so voxel colours can be checked. */
    private val rgbDecoder = RgbDecoder { _, _ -> RgbImage(32, 18, IntArray(32 * 18) { 0xE07020 }) }

    private fun sub(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { a[it] - b[it] }
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun unit(a: DoubleArray) = sqrt(dot(a, a)).let { l -> DoubleArray(3) { a[it] / l } }

    /** Ray parameter of the first hit of `eye + t * dir` with the ball or floor, or null. */
    private fun trace(eye: DoubleArray, dir: DoubleArray): Double? {
        var best: Double? = null
        val oc = sub(eye, centre)
        val a = dot(dir, dir)
        val b = 2 * dot(oc, dir)
        val c = dot(oc, oc) - radius * radius
        val disc = b * b - 4 * a * c
        if (disc >= 0) {
            val t = (-b - sqrt(disc)) / (2 * a)
            if (t > 0) best = t
        }
        if (dir[1] < -1e-9) {
            val t = (floorY - eye[1]) / dir[1]
            if (t > 0 && (best == null || t < best)) best = t
        }
        return best
    }

    private fun frame(i: Int, n: Int): CapturedFrame {
        val yaw = 2 * Math.PI * i / n
        val eye = doubleArrayOf(centre[0] + 0.55 * sin(yaw), 0.42, centre[2] + 0.55 * cos(yaw))
        val forward = unit(sub(centre, eye))
        val right = unit(cross(forward, doubleArrayOf(0.0, 1.0, 0.0)))
        val up = cross(right, forward)
        // OpenGL camera axes: columns are right, up and -forward.
        val r = Array(3) { row -> doubleArrayOf(right[row], up[row], -forward[row]) }
        val pose = FloatArray(16)
        for (row in 0..2) for (col in 0..2) pose[col * 4 + row] = r[row][col].toFloat()
        for (row in 0..2) pose[12 + row] = eye[row].toFloat()
        pose[15] = 1f

        val tex = Intrinsics(62.5, 62.5, 40.0, 22.5, 80, 45)
        val dk = tex.scaledTo(depthW, depthH)
        val depth = ByteArray(depthW * depthH * 2)
        for (v in 0 until depthH) for (u in 0 until depthW) {
            val dc = doubleArrayOf((u + 0.5 - dk.cx) / dk.fx, -(v + 0.5 - dk.cy) / dk.fy, -1.0)
            val dw = DoubleArray(3) { row -> (0..2).sumOf { k -> r[row][k] * dc[k] } }
            val mm = trace(eye, dw)?.let { (it * 1000).roundToInt() } ?: 0 // z-depth, since dc.z = -1
            val idx = 2 * (v * depthW + u)
            depth[idx] = mm.toByte()
            depth[idx + 1] = (mm ushr 8).toByte()
        }
        val conf = PlaneCopy(ByteArray(depthW * depthH) { 255.toByte() }, depthW, 1)
        val y = PlaneCopy(ByteArray(16 * 12), 16, 1)
        val c = PlaneCopy(ByteArray(8 * 6) { 128.toByte() }, 8, 1)
        val ts = 1_000_000_000L + i * 250_000_000L
        return CapturedFrame(
            timestampNs = ts,
            trackingState = if (i == 3) "PAUSED" else "TRACKING",
            rgb = YuvImageCopy(16, 12, y, c, c),
            imageIntrinsics = Intrinsics(14.0, 14.0, 8.0, 6.0, 16, 12),
            textureIntrinsics = tex,
            poseColumnMajor = pose,
            poseArCore = PoseArCore(0.0, 0.0, 0.0, 1.0, eye[0], eye[1], eye[2]),
            depth = DepthCopy(depthW, depthH, ts, PlaneCopy(depth, depthW * 2, 2), conf),
        )
    }

    private fun writeBundle(name: String, frames: Int = 36): File {
        val dir = File(tmp.root, name)
        val manifest = BundleManifest(
            captureId = name,
            createdUtc = "2026-11-19T00:00:00Z",
            appName = "depthfold-android",
            appVersion = "test",
            device = DeviceInfo("test", "jvm", 0),
            arcoreSdkVersion = "test",
            depthMode = DepthMode.RAW,
            rgbSize = ImageSize(16, 12),
            depthSize = null,
            frameCount = 0,
        )
        val writer = BundleWriter(dir, manifest, jpeg, maxPending = 1000)
        writer.start()
        repeat(frames) { assertTrue(writer.submit(frame(it, frames))) }
        writer.finish().get(30, TimeUnit.SECONDS)
        return dir
    }

    @Test
    fun buildsBallOnFloor() {
        val dir = writeBundle("ball")
        val messages = ArrayList<String>()
        val result = ModelBuilder(rgbDecoder, "test").build(dir, progress = { _, m -> messages.add(m) })
        val mesh = result.mesh
        assertTrue("mesh has ${mesh.triangleCount} triangles", mesh.triangleCount > 2000)
        assertEquals("Done", messages.last())

        // Ball: surface points well above the floor should be `radius` from the centre.
        val ballErr = ArrayList<Double>()
        val outward = ArrayList<Double>()
        val floorErr = ArrayList<Double>()
        for (v in 0 until mesh.vertexCount) {
            val p = DoubleArray(3) { mesh.positions[3 * v + it].toDouble() }
            val d = sub(p, centre)
            val dist = sqrt(dot(d, d))
            if (p[1] > floorY + 0.03 && dist < radius + 0.03) {
                ballErr.add(dist - radius)
                val n = DoubleArray(3) { mesh.normals[3 * v + it].toDouble() }
                outward.add(dot(n, d) / dist)
            } else if (dist > radius + 0.03) {
                floorErr.add(p[1] - floorY)
            }
        }
        assertTrue("ball vertices: ${ballErr.size}", ballErr.size > 500)
        val ballMedian = ballErr.sorted()[ballErr.size / 2]
        val ballP95 = ballErr.map { kotlin.math.abs(it) }.sorted()[(ballErr.size * 0.95).toInt()]
        assertTrue("ball radius bias ${ballMedian * 1000} mm", kotlin.math.abs(ballMedian) < 0.0015)
        assertTrue("ball 95th percentile error ${ballP95 * 1000} mm", ballP95 < 0.004)
        assertTrue("normals point out of the ball", outward.count { it > 0.5 } > 0.95 * outward.size)
        val floorMedian = floorErr.sorted()[floorErr.size / 2]
        assertTrue("floor height error ${floorMedian * 1000} mm", kotlin.math.abs(floorMedian) < 0.0015)

        // Colours come from the decoder.
        assertEquals(0xE0, mesh.colours[0].toInt() and 0xFF, 2)

        // Outputs on disk and a report that adds up.
        for (name in listOf(ModelBuilder.GLB, ModelBuilder.PLY, ModelBuilder.STL, ModelBuilder.REPORT)) {
            assertTrue(name, File(result.dir, name).isFile)
        }
        assertTrue(ModelBuilder.hasModel(dir))
        assertFalse(File(dir, "model.tmp").exists())
        val report = Json.parseToJsonElement(File(result.dir, ModelBuilder.REPORT).readText()).jsonObject
        val frames = report.getValue("frames").jsonObject
        assertEquals(36, frames.getValue("total").jsonPrimitive.int)
        assertEquals(1, frames.getValue("skipped_not_tracking").jsonPrimitive.int)
        assertEquals(35, frames.getValue("depth_integrated").jsonPrimitive.int)
        assertEquals(mesh.triangleCount, report.getValue("stats").jsonObject.getValue("triangles").jsonPrimitive.int)
        val reread = MeshFiles.readPly(File(result.dir, ModelBuilder.PLY))
        assertEquals(mesh.vertexCount, reread.vertexCount)
        assertEquals(mesh.triangleCount, reread.triangleCount)

        System.getenv("DEPTHFOLD_MODEL_TEST_DIR")?.let { out ->
            File(out).mkdirs()
            result.dir.copyRecursively(File(out, "ball-model"), overwrite = true)
        }
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
        assertTrue("expected $expected +- $tolerance, got $actual", kotlin.math.abs(expected - actual) <= tolerance)

    @Test
    fun cancellingKeepsThePreviousModel() {
        val dir = writeBundle("cancel", frames = 12)
        ModelBuilder(null, "test").build(dir)
        val before = File(dir, "model/${ModelBuilder.PLY}").readBytes()
        var calls = 0
        try {
            ModelBuilder(null, "test").build(dir, isCancelled = { ++calls > 3 })
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertTrue(File(dir, "model/${ModelBuilder.PLY}").readBytes().contentEquals(before))
    }

    @Test
    fun captureWithoutDepthIsRejected() {
        val dir = File(tmp.root, "nodepth").apply { mkdirs() }
        File(dir, "bundle.json").writeText(
            """{"format": "depthfold-bundle", "version": "0.1", "arcore": {"depth_mode": "none"}, "frame_count": 0}""",
        )
        try {
            ModelBuilder(null, "test").build(dir)
            throw AssertionError("expected ModelError")
        } catch (e: ModelError) {
            assertTrue(e.message!!.contains("no depth"))
        }
    }

    /** Builds a model from any bundle given in `DEPTHFOLD_FUSE_BUNDLE` (used by `scripts/interop-check.sh`). */
    @Test
    fun buildsExternalBundle() {
        val path = System.getenv("DEPTHFOLD_FUSE_BUNDLE") ?: return
        val decoder = RgbDecoder { file, _ ->
            val img = javax.imageio.ImageIO.read(file)
            RgbImage(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
        }
        val result = ModelBuilder(decoder, "interop").build(File(path))
        println("built ${result.mesh.triangleCount} triangles in ${result.dir}")
    }
}
