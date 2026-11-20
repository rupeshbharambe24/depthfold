/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.ar

import dev.depthfold.android.capture.Intrinsics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class DepthTapTest {
    private val w = 160
    private val h = 120
    private val k = Intrinsics(120.0, 120.0, 80.0, 60.0, w, h)

    /** Sparse raw-depth-like wall at 1 m: only every third pixel is valid, the rest are 0. */
    private fun sparseWall(x: Int, y: Int) = if ((x + y) % 3 == 0) 1000 else 0

    @Test
    fun twoTapsOnAWallGiveTheTrueDistance() {
        val a = point(40.5, 60.5)
        val b = point(120.5, 60.5)
        val d = sqrt((0..2).sumOf { ((a[it] - b[it]) * (a[it] - b[it])).toDouble() })
        assertEquals(80.0 / 120.0, d, 1e-4)
        // ARCore camera axes: in front of the camera is -Z.
        assertEquals(-1f, a[2], 1e-6f)
    }

    @Test
    fun imageRowsGoDownwardsSoUpperPixelsArePositiveY() {
        assertEquals(0.5f, point(80.0, 0.0)[1], 1e-6f)
    }

    @Test
    fun lowConfidencePixelsAreIgnored() {
        // Near pixels say 0.5 m with low confidence; confident pixels further out say 1 m.
        val depth = { x: Int, y: Int -> if (x in 79..81 && y in 59..61) 500 else 1000 }
        val conf = { x: Int, y: Int -> if (x in 79..81 && y in 59..61) 10 else 255 }
        assertEquals(1.0, DepthTap.medianDepth(w, h, depth, conf, 80.0, 60.0)!!, 1e-9)
        assertEquals(0.5, DepthTap.medianDepth(w, h, depth, null, 80.0, 60.0)!!, 1e-9)
    }

    @Test
    fun nearestValidPixelsWin() {
        // Object at 0.4 m covering a small patch around the tap, background at 2 m beyond it.
        val depth = { x: Int, y: Int -> if (x in 75..85 && y in 55..65) 400 else 2000 }
        assertEquals(0.4, DepthTap.medianDepth(w, h, depth, null, 80.5, 60.5)!!, 1e-9)
    }

    @Test
    fun aTapOnAnObjectEdgeLandsOnTheObjectNotTheWallBehind() {
        // Bottle top edge at row 60: bottle (0.5 m) below it, wall (1.5 m) above. Sparse, so the window has to grow
        // and straddles both surfaces.
        val depth = { x: Int, y: Int -> if ((x + y) % 3 != 0) 0 else if (y >= 60) 500 else 1500 }
        assertEquals(0.5, DepthTap.medianDepth(w, h, depth, null, 80.5, 59.5)!!, 1e-9)
    }

    @Test
    fun foregroundIgnoresASingleNoisyNearPixel() {
        // One stray 0.2 m reading among a surface at 1.00-1.02 m does not pull the result forward.
        val samples = listOf(200) + List(20) { 1000 + it }
        assertEquals(1009, DepthTap.foregroundMedian(samples)) // median of all 21, stray included, not 200
    }

    @Test
    fun depthWinsOnlyWhenClearlyInFrontOfThePlane() {
        // Ray over a bottle top meets the table 0.9 m away; the bottle is at 0.5 m.
        assertTrue(DepthTap.depthIsInFrontOfPlane(planeDistance = 0.9f, depthDistance = 0.5f))
        // Tapping the table itself: depth and plane agree within noise, so the (more precise) plane is kept.
        assertFalse(DepthTap.depthIsInFrontOfPlane(planeDistance = 0.9f, depthDistance = 0.88f))
        assertFalse(DepthTap.depthIsInFrontOfPlane(planeDistance = 0.4f, depthDistance = 0.38f))
    }

    @Test
    fun noValidDepthOrOutsideTheImageGivesNull() {
        assertNull(DepthTap.medianDepth(w, h, { _, _ -> 0 }, null, 80.0, 60.0))
        assertNull(DepthTap.medianDepth(w, h, { _, _ -> 9000 }, null, 80.0, 60.0))
        assertNull(DepthTap.medianDepth(w, h, ::sparseWall, null, -1.0, 60.0))
        assertNull(DepthTap.medianDepth(w, h, ::sparseWall, null, 80.0, 120.0))
    }

    private fun point(u: Double, v: Double): FloatArray {
        val z = DepthTap.medianDepth(w, h, ::sparseWall, null, u, v)!!
        return DepthTap.cameraPointGl(k, u, v, z)
    }
}
