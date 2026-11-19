/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.model

import java.io.IOException
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** A greyscale image as decoded from PNG: one sample per pixel, row-major, 0..255 or 0..65535. */
class GrayImage(val width: Int, val height: Int, val bitDepth: Int, val samples: IntArray)

/**
 * Minimal PNG decoder for the greyscale images of a capture bundle (8-bit confidence, 16-bit depth). Android's
 * `BitmapFactory` cannot return 16-bit samples, so depth PNGs are decoded here. Supports colour type 0 at bit
 * depth 8 or 16, all five row filters and any IDAT split; interlaced images are rejected. Pure JVM code.
 */
object PngDecoder {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun decodeGray(png: ByteArray): GrayImage {
        if (png.size < 8 || !png.copyOfRange(0, 8).contentEquals(SIGNATURE)) throw IOException("not a PNG")
        var pos = 8
        var width = 0
        var height = 0
        var bitDepth = 0
        val idat = java.io.ByteArrayOutputStream()
        var sawHeader = false
        while (pos + 8 <= png.size) {
            val length = readInt(png, pos)
            val type = String(png, pos + 4, 4, Charsets.US_ASCII)
            val data = pos + 8
            if (length < 0 || data + length + 4 > png.size) throw IOException("truncated PNG chunk $type")
            when (type) {
                "IHDR" -> {
                    width = readInt(png, data)
                    height = readInt(png, data + 4)
                    bitDepth = png[data + 8].toInt() and 0xFF
                    val colourType = png[data + 9].toInt() and 0xFF
                    val interlace = png[data + 12].toInt() and 0xFF
                    if (colourType != 0) throw IOException("expected a greyscale PNG, got colour type $colourType")
                    if (bitDepth != 8 && bitDepth != 16) throw IOException("unsupported bit depth $bitDepth")
                    if (interlace != 0) throw IOException("interlaced PNG not supported")
                    if (width <= 0 || height <= 0) throw IOException("invalid PNG size ${width}x$height")
                    sawHeader = true
                }
                "IDAT" -> idat.write(png, data, length)
                "IEND" -> break
            }
            pos = data + length + 4 // skip CRC
        }
        if (!sawHeader) throw IOException("PNG has no IHDR")

        val bpp = bitDepth / 8
        val stride = width * bpp
        val raw = inflate(idat.toByteArray(), height * (stride + 1))
        val samples = IntArray(width * height)
        var prev = ByteArray(stride)
        var cur = ByteArray(stride)
        for (y in 0 until height) {
            val rowStart = y * (stride + 1)
            val filter = raw[rowStart].toInt() and 0xFF
            System.arraycopy(raw, rowStart + 1, cur, 0, stride)
            unfilter(filter, cur, prev, bpp)
            val base = y * width
            if (bpp == 1) {
                for (x in 0 until width) samples[base + x] = cur[x].toInt() and 0xFF
            } else {
                for (x in 0 until width) {
                    samples[base + x] = ((cur[2 * x].toInt() and 0xFF) shl 8) or (cur[2 * x + 1].toInt() and 0xFF)
                }
            }
            val t = prev
            prev = cur
            cur = t
        }
        return GrayImage(width, height, bitDepth, samples)
    }

    private fun unfilter(filter: Int, cur: ByteArray, prev: ByteArray, bpp: Int) {
        when (filter) {
            0 -> Unit
            1 -> for (i in bpp until cur.size) cur[i] = (cur[i] + cur[i - bpp]).toByte()
            2 -> for (i in cur.indices) cur[i] = (cur[i] + prev[i]).toByte()
            3 -> for (i in cur.indices) {
                val left = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                val up = prev[i].toInt() and 0xFF
                cur[i] = (cur[i] + ((left + up) ushr 1)).toByte()
            }
            4 -> for (i in cur.indices) {
                val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                cur[i] = (cur[i] + paeth(a, b, c)).toByte()
            }
            else -> throw IOException("invalid PNG filter type $filter")
        }
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun inflate(data: ByteArray, expected: Int): ByteArray {
        val out = ByteArray(expected)
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            var n = 0
            while (n < expected && !inflater.finished()) {
                val got = inflater.inflate(out, n, expected - n)
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                n += got
            }
            if (n < expected) throw IOException("PNG image data is truncated ($n of $expected bytes)")
        } catch (e: DataFormatException) {
            throw IOException("corrupt PNG image data", e)
        } finally {
            inflater.end()
        }
        return out
    }

    private fun readInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
}
