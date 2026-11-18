/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.capture

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Writes a bundle of a known scene (a floor plane at world y = -12.3 mm seen by a tilted, orbiting camera) using ARCore's
 * own conventions as input: OpenGL camera axes and column-major pose matrices. The depth is ray-traced from those
 * inputs without going through [PoseMath], so the Python toolkit reading the bundle back is an independent check of
 * the axis and intrinsics conventions. `scripts/interop-check.sh` sets `DEPTHFOLD_INTEROP_DIR` to keep the output.
 */
class InteropBundleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val rgbW = 64
    private val rgbH = 48
    private val texW = 64
    private val texH = 36 // 16:9 texture vs 4:3 CPU image, as on many phones
    private val depthW = 160
    private val depthH = 90
    private val cameraHeight = 1.2

    /** World height of the floor; kept off the voxel grid so fusion is not tested on a degenerate alignment. */
    private val floorY = -0.0123

    /** Grey JPEG from the NV21 luma plane, so the bundle holds real, decodable images. */
    private val jpeg = JpegEncoder { nv21, w, h, _, out ->
        val img = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        img.raster.setDataElements(0, 0, w, h, nv21.copyOfRange(0, w * h))
        ImageIO.write(img, "jpg", out)
    }

    /** Row-major 3x3 rotation of the OpenGL camera: yaw about world +Y, then pitch down about camera +X. */
    private fun rotationGl(yaw: Double, pitch: Double): Array<DoubleArray> {
        val ry = arrayOf(
            doubleArrayOf(cos(yaw), 0.0, sin(yaw)),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(-sin(yaw), 0.0, cos(yaw)),
        )
        val rx = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, cos(pitch), -sin(pitch)),
            doubleArrayOf(0.0, sin(pitch), cos(pitch)),
        )
        return Array(3) { r -> DoubleArray(3) { c -> (0..2).sumOf { k -> ry[r][k] * rx[k][c] } } }
    }

    /** Unit quaternion (x, y, z, w) of a rotation matrix with positive trace, enough for these poses. */
    private fun quaternion(r: Array<DoubleArray>): DoubleArray {
        val w = sqrt(1.0 + r[0][0] + r[1][1] + r[2][2]) / 2
        return doubleArrayOf((r[2][1] - r[1][2]) / (4 * w), (r[0][2] - r[2][0]) / (4 * w), (r[1][0] - r[0][1]) / (4 * w), w)
    }

    private fun frame(i: Int): CapturedFrame {
        val yaw = i * 0.08
        val r = rotationGl(yaw, pitch = -0.6) // looking ~34 degrees below the horizon
        val t = doubleArrayOf(0.3 * sin(yaw), cameraHeight, 0.3 * cos(yaw))
        val pose = FloatArray(16)
        for (row in 0..2) for (col in 0..2) pose[col * 4 + row] = r[row][col].toFloat()
        for (row in 0..2) pose[12 + row] = t[row].toFloat()
        pose[15] = 1f

        val tex = Intrinsics(52.0, 52.0, 32.0, 18.0, texW, texH)
        val dk = tex.scaledTo(depthW, depthH)
        val depth = ByteArray(depthW * depthH * 2)
        for (v in 0 until depthH) for (u in 0 until depthW) {
            // Ray through the pixel centre in OpenGL camera axes (+Y up, looking down -Z).
            val dirCam = doubleArrayOf((u + 0.5 - dk.cx) / dk.fx, -(v + 0.5 - dk.cy) / dk.fy, -1.0)
            val dirWorldY = (0..2).sumOf { k -> r[1][k] * dirCam[k] }
            val mm = if (dirWorldY < -1e-6) ((cameraHeight - floorY) / -dirWorldY * 1000).roundToInt() else 0 // z-depth
            val idx = 2 * (v * depthW + u)
            depth[idx] = mm.toByte()
            depth[idx + 1] = (mm ushr 8).toByte()
        }
        val conf = PlaneCopy(ByteArray(depthW * depthH) { 255.toByte() }, depthW, 1)
        val y = PlaneCopy(ByteArray(rgbW * rgbH) { (it * 7 % 251).toByte() }, rgbW, 1)
        val c = PlaneCopy(ByteArray(rgbW * rgbH / 4) { 128.toByte() }, rgbW / 2, 1)
        val ts = 1_000_000_000L + i * 250_000_000L
        return CapturedFrame(
            timestampNs = ts,
            trackingState = "TRACKING",
            rgb = YuvImageCopy(rgbW, rgbH, y, c, c),
            imageIntrinsics = Intrinsics(55.0, 55.0, 32.0, 24.0, rgbW, rgbH),
            textureIntrinsics = tex,
            poseColumnMajor = pose,
            poseArCore = quaternion(r).let { q -> PoseArCore(q[0], q[1], q[2], q[3], t[0], t[1], t[2]) },
            depth = DepthCopy(depthW, depthH, ts, PlaneCopy(depth, depthW * 2, 2), conf),
        )
    }

    @Test
    fun writesFloorSceneBundle() {
        val root = System.getenv("DEPTHFOLD_INTEROP_DIR")?.let(::File) ?: tmp.root
        val dir = File(root, "interop-floor").apply { deleteRecursively() }
        val manifest = BundleManifest(
            captureId = "interop-floor",
            createdUtc = "2026-11-18T00:00:00Z",
            appName = "depthfold-android",
            appVersion = "test",
            device = DeviceInfo("test", "jvm", 0),
            arcoreSdkVersion = "test",
            depthMode = DepthMode.RAW,
            rgbSize = ImageSize(rgbW, rgbH),
            depthSize = null,
            frameCount = 0,
        )
        val writer = BundleWriter(dir, manifest, jpeg, maxPending = 100)
        writer.start()
        repeat(8) { writer.submit(frame(it)) }
        assertEquals(8, writer.finish().get(10, TimeUnit.SECONDS).frameCount)
    }
}
