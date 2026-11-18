/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.capture

/**
 * Everything needed to write one bundle frame, copied out of an ARCore `Frame` on the GL thread so that the
 * ARCore images can be closed immediately. Encoding and disk I/O happen later on the writer thread.
 */
class CapturedFrame(
    val timestampNs: Long,
    val trackingState: String,
    val rgb: YuvImageCopy,
    /** `Camera.getImageIntrinsics()`: describes the CPU image. */
    val imageIntrinsics: Intrinsics,
    /** `Camera.getTextureIntrinsics()`: describes the GPU texture, whose field of view the depth image covers. */
    val textureIntrinsics: Intrinsics,
    /** `Pose.toMatrix()` output of `Camera.getPose()`: column-major, OpenGL camera axes. */
    val poseColumnMajor: FloatArray,
    val poseArCore: PoseArCore,
    /** Null when this frame carries no (new) depth. */
    val depth: DepthCopy?,
)

/** A depth image (and optional confidence image) copied out of ARCore. */
class DepthCopy(
    val width: Int,
    val height: Int,
    /** `Image.getTimestamp()` of the depth image. */
    val timestampNs: Long,
    /** 16-bit little-endian plane from `acquire(Raw)DepthImage16Bits`, millimetres. */
    val depth: PlaneCopy,
    /** 8-bit plane from `acquireRawDepthConfidenceImage`, same size as depth; null in smooth mode. */
    val confidence: PlaneCopy?,
)

/**
 * Decides when the GL thread should grab a frame during a burst, and whether a frame's depth is new.
 * Pure logic so it can be unit tested.
 */
class CapturePolicy(fps: Double) {
    private val intervalNs: Long = (1_000_000_000.0 / fps.coerceIn(0.1, 60.0)).toLong()
    private var lastCaptureNs: Long = Long.MIN_VALUE
    private var lastDepthNs: Long = Long.MIN_VALUE

    /** True when at least one capture interval has elapsed since the last grabbed camera frame. */
    fun isDue(frameTimestampNs: Long): Boolean =
        lastCaptureNs == Long.MIN_VALUE || frameTimestampNs - lastCaptureNs >= intervalNs

    /** Record that a capture slot was consumed at this timestamp (whether written or dropped). */
    fun markCaptured(frameTimestampNs: Long) {
        // Advance on the ideal grid so the average rate matches the target even with jittery frame times,
        // but never fall more than one interval behind (e.g. after a stall).
        lastCaptureNs = if (lastCaptureNs == Long.MIN_VALUE || frameTimestampNs - lastCaptureNs >= 2 * intervalNs) {
            frameTimestampNs
        } else {
            lastCaptureNs + intervalNs
        }
    }

    /**
     * ARCore can return the same depth image for several camera frames. Returns true when [depthTimestampNs]
     * differs from the depth last saved, i.e. the depth should be written for this frame.
     */
    fun isNewDepth(depthTimestampNs: Long): Boolean = depthTimestampNs != lastDepthNs

    fun markDepthSaved(depthTimestampNs: Long) {
        lastDepthNs = depthTimestampNs
    }
}
