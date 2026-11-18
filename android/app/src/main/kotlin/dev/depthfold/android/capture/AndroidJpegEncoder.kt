/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.capture

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.IOException
import java.io.OutputStream

/** JPEG encoding of a packed NV21 buffer via the platform encoder. */
object AndroidJpegEncoder : JpegEncoder {
    override fun encodeNv21(nv21: ByteArray, width: Int, height: Int, quality: Int, out: OutputStream) {
        // The buffer is tightly packed (see ImagePlanes.yuv420ToNv21), so default strides are correct.
        val image = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        if (!image.compressToJpeg(Rect(0, 0, width, height), quality, out)) {
            throw IOException("JPEG encoding failed")
        }
    }
}
