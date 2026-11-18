/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenProjectionTest {
    private val identity = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    @Test
    fun ndcMapsToTopLeftPixelOrigin() {
        val centre = ScreenProjection.project(identity, floatArrayOf(0f, 0f, 0f), 200, 100)!!
        assertEquals(100f, centre.x, 1e-4f)
        assertEquals(50f, centre.y, 1e-4f)
        val topLeft = ScreenProjection.project(identity, floatArrayOf(-1f, 1f, 0f), 200, 100)!!
        assertEquals(0f, topLeft.x, 1e-4f)
        assertEquals(0f, topLeft.y, 1e-4f)
    }

    @Test
    fun pointsBehindCameraAreNull() {
        // Perspective-like matrix with w = -z.
        val m = identity.copyOf().also { it[11] = -1f; it[15] = 0f }
        assertNull(ScreenProjection.project(m, floatArrayOf(0f, 0f, 1f), 10, 10))
    }

    @Test
    fun multiplyAppliesRightOperandFirst() {
        val translate = identity.copyOf().also { it[12] = 2f } // x += 2
        val scale = identity.copyOf().also { it[0] = 3f } // x *= 3
        val m = ScreenProjection.multiply(scale, translate) // scale(translate(p))
        val p = ScreenProjection.project(m, floatArrayOf(1f, 0f, 0f), 2, 2)!!
        // x = 3 * (1 + 2) = 9 in NDC -> (9 + 1) / 2 * 2 = 10 px
        assertEquals(10f, p.x, 1e-4f)
    }

    @Test
    fun distanceIsEuclidean() {
        assertEquals(0.5, ScreenProjection.distance(floatArrayOf(0f, 0f, 0f), floatArrayOf(0.3f, 0.4f, 0f)), 1e-6)
    }
}
