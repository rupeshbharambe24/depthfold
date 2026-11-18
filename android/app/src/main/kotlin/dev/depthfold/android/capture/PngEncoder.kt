/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.capture

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs

/**
 * Minimal greyscale PNG encoder (colour type 0) for 8-bit and 16-bit samples.
 *
 * Android's Bitmap cannot encode 16-bit PNGs, and raw depth must be stored losslessly in millimetres, so this
 * encoder writes the file directly: signature, IHDR, one IDAT (zlib via [Deflater]) and IEND, each chunk with
 * its CRC-32. Rows use the PNG adaptive-filter heuristic (minimum sum of absolute differences), which compresses
 * smooth depth maps noticeably better than filter type 0.
 *
 * Pure JVM code; no Android dependencies.
 */
object PngEncoder {

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        0x0D, 0x0A, 0x1A, 0x0A,
    )

    /**
     * Encodes 16-bit unsigned greyscale samples (row-major, [width] * [height] entries).
     * Each [Short] is interpreted as unsigned (0..65535). PNG stores 16-bit samples big-endian.
     */
    fun encodeGray16(width: Int, height: Int, samples: ShortArray, compressionLevel: Int = 6): ByteArray {
        require(width > 0 && height > 0) { "invalid size ${width}x$height" }
        require(samples.size == width * height) { "expected ${width * height} samples, got ${samples.size}" }
        val stride = width * 2
        val raw = ByteArray(stride * height)
        for (i in samples.indices) {
            val v = samples[i].toInt() and 0xFFFF
            raw[2 * i] = (v ushr 8).toByte()
            raw[2 * i + 1] = v.toByte()
        }
        return encode(width, height, bitDepth = 16, bytesPerPixel = 2, raw = raw, compressionLevel = compressionLevel)
    }

    /** Encodes 8-bit greyscale samples (row-major, [width] * [height] entries). */
    fun encodeGray8(width: Int, height: Int, samples: ByteArray, compressionLevel: Int = 6): ByteArray {
        require(width > 0 && height > 0) { "invalid size ${width}x$height" }
        require(samples.size == width * height) { "expected ${width * height} samples, got ${samples.size}" }
        return encode(width, height, bitDepth = 8, bytesPerPixel = 1, raw = samples, compressionLevel = compressionLevel)
    }

    private fun encode(
        width: Int,
        height: Int,
        bitDepth: Int,
        bytesPerPixel: Int,
        raw: ByteArray,
        compressionLevel: Int,
    ): ByteArray {
        val stride = width * bytesPerPixel
        val filtered = filterRows(raw, stride, height, bytesPerPixel)

        val out = ByteArrayOutputStream(filtered.size / 2 + 128)
        out.write(SIGNATURE)

        val ihdr = ByteArrayOutputStream(13)
        DataOutputStream(ihdr).apply {
            writeInt(width)
            writeInt(height)
            writeByte(bitDepth)
            writeByte(0) // colour type 0: greyscale
            writeByte(0) // compression method 0: deflate
            writeByte(0) // filter method 0: adaptive
            writeByte(0) // interlace method 0: none
            flush()
        }
        writeChunk(out, "IHDR", ihdr.toByteArray())
        writeChunk(out, "IDAT", deflate(filtered, compressionLevel))
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** Applies a per-row PNG filter; output rows are prefixed with their filter-type byte. */
    private fun filterRows(raw: ByteArray, stride: Int, height: Int, bpp: Int): ByteArray {
        val out = ByteArray((stride + 1) * height)
        val candidate = Array(5) { ByteArray(stride) }
        for (y in 0 until height) {
            val rowOff = y * stride
            val prevOff = rowOff - stride // only valid when y > 0
            var bestType = 0
            var bestScore = Long.MAX_VALUE
            for (type in 0..4) {
                val buf = candidate[type]
                var score = 0L
                for (x in 0 until stride) {
                    val cur = raw[rowOff + x].toInt() and 0xFF
                    val left = if (x >= bpp) raw[rowOff + x - bpp].toInt() and 0xFF else 0
                    val up = if (y > 0) raw[prevOff + x].toInt() and 0xFF else 0
                    val upLeft = if (y > 0 && x >= bpp) raw[prevOff + x - bpp].toInt() and 0xFF else 0
                    val predictor = when (type) {
                        0 -> 0
                        1 -> left
                        2 -> up
                        3 -> (left + up) ushr 1
                        else -> paeth(left, up, upLeft)
                    }
                    val f = (cur - predictor).toByte()
                    buf[x] = f
                    score += abs(f.toInt())
                }
                if (score < bestScore) {
                    bestScore = score
                    bestType = type
                }
            }
            val outOff = y * (stride + 1)
            out[outOff] = bestType.toByte()
            System.arraycopy(candidate[bestType], 0, out, outOff + 1, stride)
        }
        return out
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }

    private fun deflate(data: ByteArray, level: Int): ByteArray {
        val deflater = Deflater(level)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            val buf = ByteArray(16 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val dos = DataOutputStream(out)
        dos.writeInt(data.size)
        dos.write(typeBytes)
        dos.write(data)
        dos.writeInt(crc.value.toInt())
        dos.flush()
    }
}
