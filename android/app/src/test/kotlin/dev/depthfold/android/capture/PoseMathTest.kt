/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PoseMathTest {

    /** Column-major float[16] as OpenGL / ARCore `Pose.toMatrix` fill it, from a row-major 4x4. */
    private fun columnMajor(rowMajor: Array<DoubleArray>): FloatArray =
        FloatArray(16) { i -> rowMajor[i % 4][i / 4].toFloat() }

    private fun assertMatrix(expected: Array<DoubleArray>, actual: Array<DoubleArray>, eps: Double = 1e-6) {
        for (r in 0 until 4) assertArrayEquals("row $r", expected[r], actual[r], eps)
    }

    @Test
    fun columnMajorToRowMajor_putsTranslationInLastColumn() {
        // Pose.toMatrix stores translation at indices 12, 13, 14.
        val m = FloatArray(16)
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
        m[12] = 0.5f; m[13] = -2f; m[14] = 3.25f
        m[4] = 7f // row 0, column 1
        val r = PoseMath.columnMajorToRowMajor(m)
        assertArrayEquals(doubleArrayOf(1.0, 7.0, 0.0, 0.5), r[0], 0.0)
        assertArrayEquals(doubleArrayOf(0.0, 1.0, 0.0, -2.0), r[1], 0.0)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 1.0, 3.25), r[2], 0.0)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0, 1.0), r[3], 0.0)
    }

    @Test
    fun roundTripOfArbitraryMatrix() {
        val rowMajor = Array(4) { r -> DoubleArray(4) { c -> (r * 4 + c).toDouble() } }
        assertMatrix(rowMajor, PoseMath.columnMajorToRowMajor(columnMajor(rowMajor)), 0.0)
    }

    @Test
    fun identityPose_flipsYAndZAxes() {
        val identity = Array(4) { r -> DoubleArray(4) { c -> if (r == c) 1.0 else 0.0 } }
        val t = PoseMath.tWorldCameraFromArCore(columnMajor(identity))
        val expected = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0, 0.0),
            doubleArrayOf(0.0, -1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, -1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, 1.0),
        )
        assertMatrix(expected, t, 0.0)
        // OpenCV forward (+Z) is ARCore camera -Z, i.e. world -Z for an identity pose; OpenCV down (+Y) is world -Y.
        assertArrayEquals(doubleArrayOf(0.0, 0.0, -1.0), PoseMath.transformPoint(t, doubleArrayOf(0.0, 0.0, 1.0)), 0.0)
        assertArrayEquals(doubleArrayOf(0.0, -1.0, 0.0), PoseMath.transformPoint(t, doubleArrayOf(0.0, 1.0, 0.0)), 0.0)
        // No negative zeros leak into the JSON.
        assertEquals("0.0", t[0][1].toString())
    }

    @Test
    fun rotatedTranslatedPose_mapsOpticalAxisAndKeepsTranslation() {
        // Camera at (1, 1.5, -2) yawed +90 degrees about world +Y: it looks along world -X.
        val a = Math.toRadians(90.0)
        val gl = arrayOf(
            doubleArrayOf(cos(a), 0.0, sin(a), 1.0),
            doubleArrayOf(0.0, 1.0, 0.0, 1.5),
            doubleArrayOf(-sin(a), 0.0, cos(a), -2.0),
            doubleArrayOf(0.0, 0.0, 0.0, 1.0),
        )
        val t = PoseMath.tWorldCameraFromArCore(columnMajor(gl))
        // Translation unchanged by the axis flip.
        assertEquals(1.0, t[0][3], 1e-6)
        assertEquals(1.5, t[1][3], 1e-6)
        assertEquals(-2.0, t[2][3], 1e-6)
        // A point 2 m ahead on the optical axis lands 2 m along world -X from the camera centre.
        val ahead = PoseMath.transformPoint(t, doubleArrayOf(0.0, 0.0, 2.0))
        assertArrayEquals(doubleArrayOf(-1.0, 1.5, -2.0), ahead, 1e-6)
        // Image "down" is world down.
        val below = PoseMath.transformPoint(t, doubleArrayOf(0.0, 1.0, 0.0))
        assertArrayEquals(doubleArrayOf(1.0, 0.5, -2.0), below, 1e-6)
        // Rotation part stays orthonormal with determinant +1 (right-handed).
        val r = Array(3) { i -> DoubleArray(3) { j -> t[i][j] } }
        val det = r[0][0] * (r[1][1] * r[2][2] - r[1][2] * r[2][1]) -
            r[0][1] * (r[1][0] * r[2][2] - r[1][2] * r[2][0]) +
            r[0][2] * (r[1][0] * r[2][1] - r[1][1] * r[2][0])
        assertEquals(1.0, det, 1e-6)
    }

    @Test
    fun cleanDoubleKeepsShortestDecimal() {
        assertEquals(1450.1, 1450.1f.toCleanDouble(), 0.0)
        assertEquals(1450.1f, 1450.1f.toCleanDouble().toFloat(), 0f)
    }
}
