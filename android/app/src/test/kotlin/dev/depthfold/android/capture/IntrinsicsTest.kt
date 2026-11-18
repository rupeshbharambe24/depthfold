/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class IntrinsicsTest {

    @Test
    fun depthIntrinsicsAreTextureIntrinsicsScaledToDepthSize() {
        val texture = Intrinsics(fx = 1450.0, fy = 1452.0, cx = 960.0, cy = 540.0, width = 1920, height = 1080)
        val d = Intrinsics.forDepth(texture, 160, 90)
        assertEquals(120.833333, d.fx, 1e-5)
        assertEquals(121.0, d.fy, 1e-9)
        assertEquals(80.0, d.cx, 1e-9)
        assertEquals(45.0, d.cy, 1e-9)
        assertEquals(160, d.width)
        assertEquals(90, d.height)
    }

    @Test
    fun scalingCpuImageIntrinsicsWouldBeWrongWhenAspectDiffers() {
        // Typical ARCore case: 640x480 (4:3) CPU image, 1920x1080 (16:9) texture, 160x90 depth.
        // The 16:9 texture is a vertical crop of the 4:3 sensor area, so its fy in depth pixels differs.
        val cpu = Intrinsics(fx = 490.0, fy = 490.0, cx = 320.0, cy = 240.0, width = 640, height = 480)
        val texture = Intrinsics(fx = 1470.0, fy = 1470.0, cx = 960.0, cy = 540.0, width = 1920, height = 1080)
        val right = Intrinsics.forDepth(texture, 160, 90)
        val wrong = cpu.scaledTo(160, 90)
        assertEquals(122.5, right.fx, 1e-9)
        assertEquals(122.5, right.fy, 1e-9) // square pixels preserved
        assertNotEquals(right.fy, wrong.fy, 1.0) // CPU-derived fy would be 91.9, i.e. distorted
        assertEquals(45.0, right.cy, 1e-9)
    }

    @Test
    fun scalingToSameSizeIsIdentity() {
        val k = Intrinsics(100.0, 100.0, 50.0, 40.0, 100, 80)
        assertSame(k, k.scaledTo(100, 80))
    }

    @Test
    fun fromArCoreUsesFocalPrincipalAndDimensions() {
        val k = Intrinsics.fromArCore(floatArrayOf(1450.1f, 1450.2f), floatArrayOf(960.2f, 540.7f), intArrayOf(1920, 1080))
        assertEquals(Intrinsics(1450.1, 1450.2, 960.2, 540.7, 1920, 1080), k)
    }
}
