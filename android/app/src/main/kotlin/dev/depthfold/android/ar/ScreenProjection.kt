/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.ar

import kotlin.math.sqrt

/** Pure math for the measurement overlay. Matrices are OpenGL column-major float[16]. */
object ScreenProjection {

    /** Multiplies two column-major 4x4 matrices: returns a * b. */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var s = 0f
                for (k in 0 until 4) s += a[k * 4 + row] * b[col * 4 + k]
                out[col * 4 + row] = s
            }
        }
        return out
    }

    /**
     * Projects world point [p] with a column-major view-projection matrix to view pixels (origin top-left).
     * Returns null when the point is behind the camera.
     */
    fun project(viewProj: FloatArray, p: FloatArray, width: Int, height: Int): ScreenPoint? {
        val x = viewProj[0] * p[0] + viewProj[4] * p[1] + viewProj[8] * p[2] + viewProj[12]
        val y = viewProj[1] * p[0] + viewProj[5] * p[1] + viewProj[9] * p[2] + viewProj[13]
        val w = viewProj[3] * p[0] + viewProj[7] * p[1] + viewProj[11] * p[2] + viewProj[15]
        if (w <= 1e-6f) return null
        val ndcX = x / w
        val ndcY = y / w
        return ScreenPoint((ndcX + 1f) * 0.5f * width, (1f - ndcY) * 0.5f * height)
    }

    fun distance(a: FloatArray, b: FloatArray): Double {
        val dx = (a[0] - b[0]).toDouble()
        val dy = (a[1] - b[1]).toDouble()
        val dz = (a[2] - b[2]).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
