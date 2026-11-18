/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraConfigChooserTest {
    private fun opt(w: Int, h: Int, depthSensor: Boolean = false) = CameraConfigOption(w, h, 1920, 1080, depthSensor)

    @Test
    fun picksLargestCpuImageUnderCap() {
        val options = listOf(opt(640, 480), opt(1280, 720), opt(1920, 1080), opt(4000, 3000))
        assertEquals(2, CameraConfigChooser.choose(options))
    }

    @Test
    fun prefersDepthSensorOnTie() {
        val options = listOf(opt(1920, 1080), opt(1920, 1080, depthSensor = true), opt(640, 480))
        assertEquals(1, CameraConfigChooser.choose(options))
    }

    @Test
    fun fallsBackToSmallestWhenAllExceedCap() {
        assertEquals(1, CameraConfigChooser.choose(listOf(opt(4000, 3000), opt(3000, 2000)), maxPixels = 1000))
    }

    @Test
    fun emptyListGivesNull() {
        assertNull(CameraConfigChooser.choose(emptyList()))
    }
}
