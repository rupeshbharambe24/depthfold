/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.capture

/**
 * Pinhole intrinsics in pixels, principal point measured from the top-left pixel corner
 * (`u = fx * x / z + cx`). Matches the `intrinsics` / `depth_intrinsics` objects of the bundle spec.
 */
data class Intrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0) { "invalid intrinsics size ${width}x$height" }
    }

    /**
     * Rescales to an image of [newWidth] x [newHeight] covering the same field of view. Each axis is scaled
     * independently; with the pixel-corner convention no half-pixel offset is needed.
     */
    fun scaledTo(newWidth: Int, newHeight: Int): Intrinsics {
        if (newWidth == width && newHeight == height) return this
        val sx = newWidth.toDouble() / width
        val sy = newHeight.toDouble() / height
        return Intrinsics(fx * sx, fy * sy, cx * sx, cy * sy, newWidth, newHeight)
    }

    companion object {
        /** Builds from ARCore's `CameraIntrinsics` arrays (focal length, principal point, image dimensions). */
        fun fromArCore(focal: FloatArray, principal: FloatArray, dims: IntArray): Intrinsics =
            Intrinsics(
                fx = focal[0].toCleanDouble(),
                fy = focal[1].toCleanDouble(),
                cx = principal[0].toCleanDouble(),
                cy = principal[1].toCleanDouble(),
                width = dims[0],
                height = dims[1],
            )

        /**
         * Depth intrinsics per the bundle spec: ARCore depth covers the GPU texture's field of view, so derive
         * them from the **texture** intrinsics scaled to the depth resolution (never from the CPU image).
         */
        fun forDepth(textureIntrinsics: Intrinsics, depthWidth: Int, depthHeight: Int): Intrinsics =
            textureIntrinsics.scaledTo(depthWidth, depthHeight)
    }
}

/** ARCore pose as stored under `pose_arcore`. */
data class PoseArCore(
    val qx: Double,
    val qy: Double,
    val qz: Double,
    val qw: Double,
    val tx: Double,
    val ty: Double,
    val tz: Double,
)

object PoseMath {

    /** Converts a 16-element column-major matrix (as filled by `Pose.toMatrix`) to a row-major 4x4. */
    fun columnMajorToRowMajor(m: FloatArray): Array<DoubleArray> {
        require(m.size == 16) { "expected 16 elements, got ${m.size}" }
        return Array(4) { r -> DoubleArray(4) { c -> m[c * 4 + r].toCleanDouble() } }
    }

    /**
     * Converts an ARCore camera pose (OpenGL camera axes: +Y up, -Z forward) to the OpenCV camera convention
     * (+Y down, +Z forward): `T_cv = T_gl * diag(1, -1, -1, 1)`, i.e. negate the second and third columns.
     */
    fun openGlToOpenCvCamera(tGl: Array<DoubleArray>): Array<DoubleArray> {
        require(tGl.size == 4 && tGl.all { it.size == 4 }) { "expected a 4x4 matrix" }
        val flip = doubleArrayOf(1.0, -1.0, -1.0, 1.0)
        return Array(4) { r -> DoubleArray(4) { c -> negZeroSafe(tGl[r][c] * flip[c]) } }
    }

    /** `T_world_camera` (row-major, OpenCV camera axes) from ARCore's column-major `Pose.toMatrix` output. */
    fun tWorldCameraFromArCore(columnMajor: FloatArray): Array<DoubleArray> =
        openGlToOpenCvCamera(columnMajorToRowMajor(columnMajor))

    /** Applies a 4x4 row-major rigid transform to a 3D point. */
    fun transformPoint(t: Array<DoubleArray>, p: DoubleArray): DoubleArray =
        DoubleArray(3) { r -> t[r][0] * p[0] + t[r][1] * p[1] + t[r][2] * p[2] + t[r][3] }

    /** Avoids writing `-0.0` into JSON, which some readers render oddly. */
    private fun negZeroSafe(v: Double): Double = if (v == 0.0) 0.0 else v
}

/**
 * Widens a Float to the Double with the same shortest decimal representation (1450.1f -> 1450.1 rather than
 * 1450.0999755859375). No information is lost: the float is exactly recoverable from the result.
 */
fun Float.toCleanDouble(): Double = if (isFinite()) toString().toDouble() else toDouble()
