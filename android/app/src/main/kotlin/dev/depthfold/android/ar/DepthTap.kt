/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.ar

import dev.depthfold.android.capture.Intrinsics

/**
 * Pure math for placing a measurement point from the depth image under a tap. ARCore's own hit test only
 * returns depth points in AUTOMATIC depth mode, and the app records in RAW_DEPTH_ONLY when it can, so taps on
 * objects (rather than detected planes) need this.
 */
object DepthTap {

    /** Search radii in depth pixels, tried in order until enough valid pixels are found. Raw depth is sparse. */
    private val RADII = intArrayOf(1, 2, 4, 7)
    private const val MIN_SAMPLES = 3
    const val MAX_DEPTH_MM = 8000

    /**
     * Median depth in metres of the valid pixels nearest to (u, v), in depth-image pixel coordinates with the
     * pixel-corner convention (pixel i covers [i, i + 1)). A pixel is valid when its depth is in
     * (0, [MAX_DEPTH_MM]] and, if [confidenceAt] is given, its confidence is at least [minConfidence].
     * Returns null when too few valid pixels lie within the largest search radius.
     */
    fun medianDepth(
        width: Int,
        height: Int,
        depthMmAt: (x: Int, y: Int) -> Int,
        confidenceAt: ((x: Int, y: Int) -> Int)?,
        u: Double,
        v: Double,
        minConfidence: Int = 64,
    ): Double? {
        val cx = u.toInt()
        val cy = v.toInt()
        if (cx !in 0 until width || cy !in 0 until height) return null
        for (r in RADII) {
            val samples = ArrayList<Int>()
            for (y in maxOf(0, cy - r)..minOf(height - 1, cy + r)) {
                for (x in maxOf(0, cx - r)..minOf(width - 1, cx + r)) {
                    val d = depthMmAt(x, y)
                    if (d <= 0 || d > MAX_DEPTH_MM) continue
                    if (confidenceAt != null && confidenceAt(x, y) < minConfidence) continue
                    samples.add(d)
                }
            }
            if (samples.size >= MIN_SAMPLES) {
                samples.sort()
                return samples[samples.size / 2] / 1000.0
            }
        }
        return null
    }

    /**
     * Back-projects depth-image pixel coordinates (u, v) at z-depth [z] metres into the ARCore camera frame
     * (OpenGL axes: +X right, +Y up, -Z forward), ready for `Camera.getPose().transformPoint`.
     */
    fun cameraPointGl(k: Intrinsics, u: Double, v: Double, z: Double): FloatArray {
        val x = (u - k.cx) / k.fx * z
        val y = (v - k.cy) / k.fy * z
        return floatArrayOf(x.toFloat(), (-y).toFloat(), (-z).toFloat())
    }
}
