/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.model

import dev.depthfold.android.capture.PngEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ModelPartsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun decodesOwnPngs() {
        val w = 37
        val h = 11
        val s16 = IntArray(w * h) { (it * 2654435761L % 65536).toInt() }
        val d16 = PngDecoder.decodeGray(PngEncoder.encodeGray16(w, h, s16.map { it.toShort() }.toShortArray()))
        assertEquals(16, d16.bitDepth)
        assertArrayEquals(s16, d16.samples)
        val s8 = ByteArray(w * h) { (it * 31).toByte() }
        val d8 = PngDecoder.decodeGray(PngEncoder.encodeGray8(w, h, s8))
        assertArrayEquals(IntArray(w * h) { s8[it].toInt() and 0xFF }, d8.samples)
    }

    @Test
    fun decodesImageIoPngs() {
        // ImageIO picks its own row filters, as Pillow (the toolkit's synthetic captures) does.
        val w = 64
        val h = 40
        val img = BufferedImage(w, h, BufferedImage.TYPE_USHORT_GRAY)
        val expected = IntArray(w * h) { i -> ((i % w) * 900 + (i / w) * 37 + (i * i) % 113) and 0xFFFF }
        img.raster.setPixels(0, 0, w, h, expected)
        val png = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        assertArrayEquals(expected, PngDecoder.decodeGray(png).samples)
    }

    @Test
    fun decodesEveryRowFilter() {
        val w = 9
        val h = 10
        val bpp = 2
        val samples = IntArray(w * h) { (it * 7919 + 13) % 65536 }
        val rows = Array(h) { y ->
            ByteArray(w * bpp).also { r ->
                for (x in 0 until w) {
                    r[2 * x] = (samples[y * w + x] shr 8).toByte()
                    r[2 * x + 1] = samples[y * w + x].toByte()
                }
            }
        }
        val raw = ByteArrayOutputStream()
        for (y in 0 until h) {
            val filter = y % 5
            raw.write(filter)
            val cur = rows[y]
            val prev = if (y > 0) rows[y - 1] else ByteArray(cur.size)
            for (i in cur.indices) {
                val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                val pred = when (filter) {
                    0 -> 0
                    1 -> a
                    2 -> b
                    3 -> (a + b) / 2
                    else -> {
                        val p = a + b - c
                        val pa = abs(p - a)
                        val pb = abs(p - b)
                        val pc = abs(p - c)
                        if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                    }
                }
                raw.write(((cur[i].toInt() and 0xFF) - pred) and 0xFF)
            }
        }
        val idat = ByteArrayOutputStream().also { DeflaterOutputStream(it).use { d -> d.write(raw.toByteArray()) } }
            .toByteArray()
        val png = ByteArrayOutputStream()
        png.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, data: ByteArray) {
            png.write(ByteBuffer.allocate(4).putInt(data.size).array())
            val body = type.toByteArray(Charsets.US_ASCII) + data
            png.write(body)
            png.write(ByteBuffer.allocate(4).putInt(CRC32().apply { update(body) }.value.toInt()).array())
        }
        chunk("IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put(16).put(0).put(0).put(0).put(0).array())
        // Split the image data over two IDAT chunks.
        chunk("IDAT", idat.copyOfRange(0, idat.size / 2))
        chunk("IDAT", idat.copyOfRange(idat.size / 2, idat.size))
        chunk("IEND", ByteArray(0))
        assertArrayEquals(samples, PngDecoder.decodeGray(png.toByteArray()).samples)
    }

    private fun yaw(angle: Double, tx: Double = 0.0) = doubleArrayOf(
        cos(angle), 0.0, sin(angle), tx,
        0.0, 1.0, 0.0, 0.0,
        -sin(angle), 0.0, cos(angle), 0.0,
        0.0, 0.0, 0.0, 1.0,
    )

    @Test
    fun interpolatesPoses() {
        val mid = Rigid.interpolate(15, 10, yaw(0.0, 1.0), 20, yaw(PI / 2, 3.0))
        val expected = yaw(PI / 4, 2.0)
        for (i in 0 until 16) assertEquals("element $i", expected[i], mid[i], 1e-9)
        val q = Rigid.quaternion(yaw(2.5))
        val back = Rigid.rotation(q)
        val ref = yaw(2.5)
        for (i in intArrayOf(0, 1, 2, 4, 5, 6, 8, 9, 10)) assertEquals(ref[i], back[i], 1e-9)
    }

    private fun tetrahedron(): Mesh = Mesh(
        positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        colours = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120),
        triangles = intArrayOf(0, 2, 1, 0, 1, 3, 0, 3, 2, 1, 2, 3),
    )

    @Test
    fun plyRoundTrips() {
        val mesh = tetrahedron()
        val f = File(tmp.root, "t.ply")
        MeshFiles.writePly(mesh, f, "test")
        val back = MeshFiles.readPly(f)
        assertArrayEquals(mesh.positions, back.positions, 0f)
        assertArrayEquals(mesh.normals, back.normals, 0f)
        assertArrayEquals(mesh.colours, back.colours)
        assertArrayEquals(mesh.triangles, back.triangles)
    }

    @Test
    fun writesValidGlbAndStl() {
        val mesh = tetrahedron()
        val glb = File(tmp.root, "t.glb").also { MeshFiles.writeGlb(mesh, it, "test") }.readBytes()
        val b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, b.getInt(0))
        assertEquals(2, b.getInt(4))
        assertEquals(glb.size, b.getInt(8))
        val jsonLen = b.getInt(12)
        assertEquals(0, jsonLen % 4)
        val json = Json.parseToJsonElement(String(glb, 20, jsonLen, Charsets.UTF_8)).jsonObject
        val accessors = json.getValue("accessors").jsonArray
        assertEquals(4, accessors[0].jsonObject.getValue("count").jsonPrimitive.int)
        assertEquals(12, accessors[3].jsonObject.getValue("count").jsonPrimitive.int)
        val binLen = b.getInt(20 + jsonLen)
        assertEquals(json.getValue("buffers").jsonArray[0].jsonObject.getValue("byteLength").jsonPrimitive.int, binLen)
        assertEquals(glb.size, 20 + jsonLen + 8 + binLen)

        val stl = File(tmp.root, "t.stl").also { MeshFiles.writeStl(mesh, it) }.readBytes()
        assertEquals(84 + 50 * 4, stl.size)
        val s = ByteBuffer.wrap(stl).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(4, s.getInt(80))
        // Face 0 is (v0, v2, v1); its second corner is v2 = (0, 1, 0) m, written in mm.
        assertEquals(1000f, s.getFloat(84 + 12 + 12 + 4), 0f)
        assertEquals(-1f, s.getFloat(84 + 8), 1e-6f) // normal of face 0 points down -Z
    }

    @Test
    fun dropsSmallComponents() {
        val a = tetrahedron()
        val shifted = a.positions.map { it + 5f }.toFloatArray()
        val big = Mesh(
            positions = a.positions + shifted,
            colours = a.colours + a.colours,
            triangles = a.triangles + a.triangles.map { it + 4 }.toIntArray() + intArrayOf(4, 5, 6, 4, 6, 7),
        )
        val cleaned = big.withoutSmallComponents(5)
        assertEquals(4, cleaned.vertexCount)
        assertEquals(6, cleaned.triangleCount)
        assertTrue(cleaned.positions.all { it >= 5f })
    }
}
