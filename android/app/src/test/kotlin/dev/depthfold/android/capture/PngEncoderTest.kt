/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.Random
import javax.imageio.ImageIO

class PngEncoderTest {

    private fun decode(png: ByteArray): BufferedImage {
        val img: BufferedImage? = ImageIO.read(ByteArrayInputStream(png))
        assertNotNull("ImageIO could not decode the PNG", img)
        return img!!
    }

    private fun ihdr(png: ByteArray): Pair<Int, Int> {
        // signature(8) + length(4) + "IHDR"(4) + width(4) + height(4) + bitDepth(1) + colourType(1)
        assertEquals("IHDR", String(png, 12, 4, Charsets.US_ASCII))
        return (png[24].toInt() and 0xFF) to (png[25].toInt() and 0xFF)
    }

    @Test
    fun gray16RoundTripsThroughImageIo() {
        val w = 160
        val h = 90
        val rnd = Random(42)
        val samples = ShortArray(w * h) { i ->
            when {
                i % 97 == 0 -> 0 // no data
                i % 89 == 0 -> 0xFFFF.toShort() // maximum (tests unsigned handling)
                i % 2 == 0 -> (500 + (i % w) * 10 + (i / w) * 3).toShort() // smooth ramp
                else -> rnd.nextInt(65536).toShort() // noise, exercises every filter
            }
        }
        val png = PngEncoder.encodeGray16(w, h, samples)
        assertEquals(16 to 0, ihdr(png))

        val img = decode(png)
        assertEquals(w, img.width)
        assertEquals(h, img.height)
        assertEquals(BufferedImage.TYPE_USHORT_GRAY, img.type)
        val raster = img.raster
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals("pixel ($x,$y)", samples[y * w + x].toInt() and 0xFFFF, raster.getSample(x, y, 0))
        }
    }

    @Test
    fun gray8RoundTripsThroughImageIo() {
        val w = 37 // odd width
        val h = 11
        val samples = ByteArray(w * h) { i -> ((i * 7) % 256).toByte() }
        val png = PngEncoder.encodeGray8(w, h, samples)
        assertEquals(8 to 0, ihdr(png))
        val img = decode(png)
        assertEquals(BufferedImage.TYPE_BYTE_GRAY, img.type)
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals(samples[y * w + x].toInt() and 0xFF, img.raster.getSample(x, y, 0))
        }
    }

    @Test
    fun singlePixel() {
        val img = decode(PngEncoder.encodeGray16(1, 1, shortArrayOf(1234)))
        assertEquals(1234, img.raster.getSample(0, 0, 0))
    }

    @Test
    fun startsWithPngSignatureAndEndsWithIend() {
        val png = PngEncoder.encodeGray8(2, 2, ByteArray(4))
        assertArrayEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            png.copyOfRange(0, 8),
        )
        val tail = png.copyOfRange(png.size - 12, png.size)
        assertEquals(0, ByteBuffer.wrap(tail, 0, 4).int)
        assertEquals("IEND", String(tail, 4, 4, Charsets.US_ASCII))
        assertEquals(0xAE426082.toInt(), ByteBuffer.wrap(tail, 8, 4).int) // CRC of an empty IEND
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongSampleCount() {
        PngEncoder.encodeGray16(4, 4, ShortArray(15))
    }
}
