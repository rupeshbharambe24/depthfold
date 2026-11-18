/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.ar

/** The parts of an ARCore `CameraConfig` that matter for choosing one. Pure data for unit testing. */
data class CameraConfigOption(
    val imageWidth: Int,
    val imageHeight: Int,
    val textureWidth: Int,
    val textureHeight: Int,
    val usesDepthSensor: Boolean,
) {
    val imagePixels: Long get() = imageWidth.toLong() * imageHeight
}

object CameraConfigChooser {
    /**
     * Upper bound on the CPU image size. ARCore's default config is often 640x480; larger images give much better
     * reconstructions, but beyond ~4 MP the per-frame YUV copy and JPEG encode at several fps get expensive.
     */
    const val DEFAULT_MAX_PIXELS: Long = 2560L * 1600L

    /**
     * Returns the index of the preferred option: the largest CPU image not exceeding [maxPixels]
     * (ties broken by depth-sensor use, then larger texture), or the smallest image if all exceed the cap.
     * Returns null for an empty list.
     */
    fun choose(options: List<CameraConfigOption>, maxPixels: Long = DEFAULT_MAX_PIXELS): Int? {
        if (options.isEmpty()) return null
        val indexed = options.withIndex()
        val withinCap = indexed.filter { it.value.imagePixels <= maxPixels }
        val best = if (withinCap.isNotEmpty()) {
            withinCap.maxWithOrNull(
                compareBy<IndexedValue<CameraConfigOption>> { it.value.imagePixels }
                    .thenBy { if (it.value.usesDepthSensor) 1 else 0 }
                    .thenBy { it.value.textureWidth.toLong() * it.value.textureHeight },
            )
        } else {
            indexed.minByOrNull { it.value.imagePixels }
        }
        return best?.index
    }
}
