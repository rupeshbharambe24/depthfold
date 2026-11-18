/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ImagePlanesTest {

    private fun yAt(x: Int, y: Int) = (x + 10 * y).toByte()
    private fun uAt(x: Int, y: Int) = (100 + x + 5 * y).toByte()
    private fun vAt(x: Int, y: Int) = (200 + x + 3 * y).toByte()

    /** Builds a YUV_420_888 image the way many devices lay it out: padded rows, interleaved chroma. */
    private fun paddedSemiPlanar(w: Int, h: Int, rowPad: Int): YuvImageCopy {
        val yStride = w + rowPad
        val yBytes = ByteArray(yStride * (h - 1) + w) // last row unpadded, as in real Image buffers
        for (y in 0 until h) for (x in 0 until w) yBytes[y * yStride + x] = yAt(x, y)

        val cw = w / 2
        val ch = h / 2
        val cStride = w + rowPad
        // Interleaved buffer VUVU...; the U plane view starts one byte after V (NV21-like memory).
        val vu = ByteArray(cStride * (ch - 1) + 2 * cw)
        for (y in 0 until ch) for (x in 0 until cw) {
            vu[y * cStride + 2 * x] = vAt(x, y)
            vu[y * cStride + 2 * x + 1] = uAt(x, y)
        }
        val vBytes = vu.copyOfRange(0, vu.size - 1)
        val uBytes = vu.copyOfRange(1, vu.size)
        return YuvImageCopy(
            w, h,
            y = PlaneCopy(yBytes, yStride, 1),
            u = PlaneCopy(uBytes, cStride, 2),
            v = PlaneCopy(vBytes, cStride, 2),
        )
    }

    private fun expectedNv21(w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 3 / 2)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = yAt(x, y)
        var o = w * h
        for (y in 0 until h / 2) for (x in 0 until w / 2) {
            out[o++] = vAt(x, y)
            out[o++] = uAt(x, y)
        }
        return out
    }

    @Test
    fun nv21FromPaddedInterleavedPlanes() {
        val img = paddedSemiPlanar(w = 8, h = 6, rowPad = 24)
        assertArrayEquals(expectedNv21(8, 6), ImagePlanes.yuv420ToNv21(img))
    }

    @Test
    fun nv21FromFullyPlanarI420() {
        val w = 6
        val h = 4
        val yBytes = ByteArray(w * h) { yAt(it % w, it / w) }
        val uBytes = ByteArray(w / 2 * h / 2) { uAt(it % (w / 2), it / (w / 2)) }
        val vBytes = ByteArray(w / 2 * h / 2) { vAt(it % (w / 2), it / (w / 2)) }
        val img = YuvImageCopy(w, h, PlaneCopy(yBytes, w, 1), PlaneCopy(uBytes, w / 2, 1), PlaneCopy(vBytes, w / 2, 1))
        assertArrayEquals(expectedNv21(w, h), ImagePlanes.yuv420ToNv21(img))
    }

    @Test
    fun depth16LittleEndianWithRowPadding() {
        val w = 3
        val h = 2
        val rowStride = 10 // 6 bytes of data + 4 padding
        val values = intArrayOf(0, 1000, 65535, 258, 4095, 32768)
        val bytes = ByteArray(rowStride * (h - 1) + w * 2)
        for (y in 0 until h) for (x in 0 until w) {
            val v = values[y * w + x]
            bytes[y * rowStride + 2 * x] = v.toByte()
            bytes[y * rowStride + 2 * x + 1] = (v ushr 8).toByte()
        }
        val out = ImagePlanes.packDepth16LittleEndian(PlaneCopy(bytes, rowStride, 2), w, h)
        assertEquals(values.toList(), out.map { it.toInt() and 0xFFFF })
    }

    @Test
    fun gray8WithRowPadding() {
        val bytes = byteArrayOf(1, 2, 3, 0, 0, 4, 5, 6)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), ImagePlanes.packGray8(PlaneCopy(bytes, 5, 1), 3, 2))
    }
}
