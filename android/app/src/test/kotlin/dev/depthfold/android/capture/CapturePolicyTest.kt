/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePolicyTest {

    @Test
    fun fourFpsFromThirtyFpsCameraAveragesFourFramesPerSecond() {
        val policy = CapturePolicy(4.0)
        var captured = 0
        val frameNs = 33_333_333L
        for (i in 0 until 300) { // 10 s of 30 fps frames
            val ts = 5_000_000_000L + i * frameNs
            if (policy.isDue(ts)) {
                policy.markCaptured(ts)
                captured++
            }
        }
        assertEquals(40.0, captured.toDouble(), 1.0)
    }

    @Test
    fun firstFrameIsAlwaysDue() {
        assertTrue(CapturePolicy(1.0).isDue(0L))
    }

    @Test
    fun staleDepthIsDetectedByTimestamp() {
        val policy = CapturePolicy(4.0)
        assertTrue(policy.isNewDepth(100))
        policy.markDepthSaved(100)
        assertFalse(policy.isNewDepth(100))
        assertTrue(policy.isNewDepth(133))
    }
}
