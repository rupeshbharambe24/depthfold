/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.ar

import android.graphics.ImageFormat
import android.media.Image
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import dev.depthfold.android.capture.CapturePolicy
import dev.depthfold.android.capture.CapturedFrame
import dev.depthfold.android.capture.DepthCopy
import dev.depthfold.android.capture.DepthMode
import dev.depthfold.android.capture.Intrinsics
import dev.depthfold.android.capture.PlaneCopy
import dev.depthfold.android.capture.PoseArCore
import dev.depthfold.android.capture.YuvImageCopy
import dev.depthfold.android.capture.toCleanDouble

/**
 * Copies one ARCore frame into a [CapturedFrame] on the GL thread. Only memcpy-level work happens here: plane
 * buffers are copied verbatim (with their strides) and every `Image` is closed before returning. Stride handling,
 * JPEG and PNG encoding happen later on the writer thread.
 */
internal object FrameGrabber {

    /** Returns null when the camera image is not available for this frame. */
    fun grab(frame: Frame, depthMode: DepthMode, policy: CapturePolicy): CapturedFrame? {
        val camera = frame.camera
        val rgb = try {
            frame.acquireCameraImage()
        } catch (_: NotYetAvailableException) {
            return null
        }.use { img ->
            if (img.format != ImageFormat.YUV_420_888 || img.planes.size < 3) return null
            YuvImageCopy(img.width, img.height, copy(img.planes[0]), copy(img.planes[1]), copy(img.planes[2]))
        }

        val pose = camera.pose
        val matrix = FloatArray(16)
        pose.toMatrix(matrix, 0)

        return CapturedFrame(
            timestampNs = frame.timestamp,
            trackingState = camera.trackingState.name,
            rgb = rgb,
            imageIntrinsics = camera.imageIntrinsics.toIntrinsics(),
            textureIntrinsics = camera.textureIntrinsics.toIntrinsics(),
            poseColumnMajor = matrix,
            poseArCore = PoseArCore(
                qx = pose.qx().toCleanDouble(),
                qy = pose.qy().toCleanDouble(),
                qz = pose.qz().toCleanDouble(),
                qw = pose.qw().toCleanDouble(),
                tx = pose.tx().toCleanDouble(),
                ty = pose.ty().toCleanDouble(),
                tz = pose.tz().toCleanDouble(),
            ),
            depth = when (depthMode) {
                DepthMode.RAW -> grabDepth(frame, raw = true, policy)
                DepthMode.SMOOTH -> grabDepth(frame, raw = false, policy)
                DepthMode.NONE -> null
            },
        )
    }

    /** Returns null when depth is not available yet or is the same image as the last saved one. */
    private fun grabDepth(frame: Frame, raw: Boolean, policy: CapturePolicy): DepthCopy? {
        val image = try {
            if (raw) frame.acquireRawDepthImage16Bits() else frame.acquireDepthImage16Bits()
        } catch (_: NotYetAvailableException) {
            return null
        }
        return image.use { depth ->
            if (!policy.isNewDepth(depth.timestamp)) return null
            val plane = depth.planes[0]
            if (plane.pixelStride < 2) return null
            val confidence = if (raw) {
                try {
                    frame.acquireRawDepthConfidenceImage().use { conf ->
                        if (conf.width == depth.width && conf.height == depth.height) copy(conf.planes[0]) else null
                    }
                } catch (_: NotYetAvailableException) {
                    null
                }
            } else {
                null
            }
            DepthCopy(depth.width, depth.height, depth.timestamp, copy(plane), confidence)
        }
    }

    private fun copy(plane: Image.Plane): PlaneCopy {
        val buf = plane.buffer.duplicate()
        buf.rewind()
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        return PlaneCopy(bytes, plane.rowStride, plane.pixelStride)
    }

    private fun CameraIntrinsics.toIntrinsics(): Intrinsics =
        Intrinsics.fromArCore(focalLength, principalPoint, imageDimensions)
}
