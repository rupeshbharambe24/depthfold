/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.capture

/**
 * A copy of one `android.media.Image` plane: the raw buffer bytes plus the strides needed to address it.
 *
 * Row and pixel strides are device-specific: rows are often padded (rowStride > width * bytesPerPixel) and the
 * chroma planes of YUV_420_888 are often interleaved (pixelStride == 2). Pixel (x, y) starts at
 * `y * rowStride + x * pixelStride`. The last row may be shorter than rowStride, so the buffer length is not
 * necessarily `rowStride * height`.
 */
class PlaneCopy(val bytes: ByteArray, val rowStride: Int, val pixelStride: Int) {
    init {
        require(rowStride > 0 && pixelStride > 0) { "invalid strides row=$rowStride pixel=$pixelStride" }
    }
}

/** A YUV_420_888 camera image copied out of ARCore so the `Image` can be released immediately. */
class YuvImageCopy(val width: Int, val height: Int, val y: PlaneCopy, val u: PlaneCopy, val v: PlaneCopy)

/** Pure-JVM helpers that turn strided plane copies into tightly packed arrays. */
object ImagePlanes {

    /**
     * Converts YUV_420_888 to NV21 (full Y plane followed by interleaved V/U at half resolution), honouring each
     * plane's row and pixel stride. This is what `android.graphics.YuvImage` expects.
     */
    fun yuv420ToNv21(img: YuvImageCopy): ByteArray {
        val w = img.width
        val h = img.height
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val out = ByteArray(w * h + 2 * cw * ch)

        val y = img.y
        if (y.pixelStride == 1) {
            for (row in 0 until h) {
                System.arraycopy(y.bytes, row * y.rowStride, out, row * w, w)
            }
        } else {
            var o = 0
            for (row in 0 until h) {
                val base = row * y.rowStride
                for (col in 0 until w) out[o++] = y.bytes[base + col * y.pixelStride]
            }
        }

        var o = w * h
        val u = img.u
        val v = img.v
        for (row in 0 until ch) {
            val uBase = row * u.rowStride
            val vBase = row * v.rowStride
            for (col in 0 until cw) {
                out[o++] = v.bytes[vBase + col * v.pixelStride]
                out[o++] = u.bytes[uBase + col * u.pixelStride]
            }
        }
        return out
    }

    /** Extracts an 8-bit single-channel image (e.g. ARCore raw-depth confidence) into a packed array. */
    fun packGray8(plane: PlaneCopy, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height)
        if (plane.pixelStride == 1) {
            for (row in 0 until height) System.arraycopy(plane.bytes, row * plane.rowStride, out, row * width, width)
        } else {
            var o = 0
            for (row in 0 until height) {
                val base = row * plane.rowStride
                for (col in 0 until width) out[o++] = plane.bytes[base + col * plane.pixelStride]
            }
        }
        return out
    }

    /**
     * Extracts a 16-bit little-endian single-channel image (ARCore `acquire*DepthImage16Bits`, millimetres)
     * into a packed array of unsigned samples stored in [Short]s.
     */
    fun packDepth16LittleEndian(plane: PlaneCopy, width: Int, height: Int): ShortArray {
        val out = ShortArray(width * height)
        var o = 0
        for (row in 0 until height) {
            val base = row * plane.rowStride
            for (col in 0 until width) {
                val i = base + col * plane.pixelStride
                val lo = plane.bytes[i].toInt() and 0xFF
                val hi = plane.bytes[i + 1].toInt() and 0xFF
                out[o++] = ((hi shl 8) or lo).toShort()
            }
        }
        return out
    }
}
