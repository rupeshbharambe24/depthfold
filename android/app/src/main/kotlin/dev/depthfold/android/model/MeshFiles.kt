/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mesh file formats written on the phone. Coordinates stay in the bundle's world frame (ARCore world, metres,
 * +Y up), which is also glTF's convention; STL is written in millimetres, as slicers and CAD tools assume, like
 * the toolkit's `mesh.stl`. Pure JVM code.
 */
object MeshFiles {

    /** Binary little-endian PLY with float positions and normals and uchar colours. */
    fun writePly(mesh: Mesh, file: File, comment: String = "") {
        out(file).use { os ->
            val header = buildString {
                append("ply\nformat binary_little_endian 1.0\n")
                if (comment.isNotEmpty()) append("comment ").append(comment.replace('\n', ' ')).append('\n')
                append("element vertex ${mesh.vertexCount}\n")
                append("property float x\nproperty float y\nproperty float z\n")
                append("property float nx\nproperty float ny\nproperty float nz\n")
                append("property uchar red\nproperty uchar green\nproperty uchar blue\n")
                append("element face ${mesh.triangleCount}\n")
                append("property list uchar int vertex_indices\nend_header\n")
            }
            os.write(header.toByteArray(Charsets.US_ASCII))
            val vb = le(27)
            for (v in 0 until mesh.vertexCount) {
                vb.clear()
                for (a in 0..2) vb.putFloat(mesh.positions[3 * v + a])
                for (a in 0..2) vb.putFloat(mesh.normals[3 * v + a])
                vb.put(mesh.colours, 3 * v, 3)
                os.write(vb.array())
            }
            val fb = le(13)
            for (f in 0 until mesh.triangleCount) {
                fb.clear()
                fb.put(3)
                for (c in 0..2) fb.putInt(mesh.triangles[3 * f + c])
                os.write(fb.array())
            }
        }
    }

    /** Reads a PLY written by [writePly] (not a general PLY reader). */
    fun readPly(file: File): Mesh {
        DataInputStream(BufferedInputStream(file.inputStream(), 256 * 1024)).use { input ->
            var vertices = -1
            var faces = -1
            while (true) {
                val line = readLine(input) ?: throw IOException("PLY header not terminated")
                if (line == "end_header") break
                val parts = line.split(' ')
                if (parts.size == 3 && parts[0] == "element") {
                    when (parts[1]) {
                        "vertex" -> vertices = parts[2].toInt()
                        "face" -> faces = parts[2].toInt()
                    }
                }
            }
            if (vertices < 0 || faces < 0) throw IOException("PLY header has no vertex/face counts")
            val pos = FloatArray(vertices * 3)
            val nrm = FloatArray(vertices * 3)
            val col = ByteArray(vertices * 3)
            val vb = le(27)
            for (v in 0 until vertices) {
                input.readFully(vb.array())
                vb.clear()
                for (a in 0..2) pos[3 * v + a] = vb.float
                for (a in 0..2) nrm[3 * v + a] = vb.float
                vb.get(col, 3 * v, 3)
            }
            val tri = IntArray(faces * 3)
            val fb = le(13)
            for (f in 0 until faces) {
                input.readFully(fb.array())
                fb.clear()
                if (fb.get().toInt() != 3) throw IOException("PLY face $f is not a triangle")
                for (c in 0..2) tri[3 * f + c] = fb.int
            }
            return Mesh(pos, col, tri, nrm)
        }
    }

    /** Binary STL, scaled by [scale] (1000 = millimetres). */
    fun writeStl(mesh: Mesh, file: File, scale: Float = 1000f) {
        out(file).use { os ->
            val header = ByteArray(80)
            "Depthfold mesh, units: ${if (scale == 1000f) "mm" else "x$scale m"}".toByteArray(Charsets.US_ASCII)
                .let { System.arraycopy(it, 0, header, 0, minOf(it.size, 80)) }
            os.write(header)
            os.write(le(4).putInt(mesh.triangleCount).array())
            val b = le(50)
            val p = mesh.positions
            for (f in 0 until mesh.triangleCount) {
                b.clear()
                val i0 = 3 * mesh.triangles[3 * f]
                val i1 = 3 * mesh.triangles[3 * f + 1]
                val i2 = 3 * mesh.triangles[3 * f + 2]
                val ux = p[i1] - p[i0]; val uy = p[i1 + 1] - p[i0 + 1]; val uz = p[i1 + 2] - p[i0 + 2]
                val vx = p[i2] - p[i0]; val vy = p[i2 + 1] - p[i0 + 1]; val vz = p[i2 + 2] - p[i0 + 2]
                val nx = uy * vz - uz * vy
                val ny = uz * vx - ux * vz
                val nz = ux * vy - uy * vx
                val l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 0f } ?: 1f
                b.putFloat(nx / l).putFloat(ny / l).putFloat(nz / l)
                for (i in intArrayOf(i0, i1, i2)) for (a in 0..2) b.putFloat(p[i + a] * scale)
                b.putShort(0)
                os.write(b.array())
            }
        }
    }

    /**
     * Binary glTF 2.0 (GLB) with positions, normals, vertex colours and 32-bit indices, one mesh in one node.
     * Opens in Blender, Windows 3D Viewer, three.js and most web viewers.
     */
    fun writeGlb(mesh: Mesh, file: File, generator: String) {
        val nv = mesh.vertexCount
        val posBytes = nv * 12
        val nrmBytes = nv * 12
        val colBytes = nv * 4
        val idxBytes = mesh.triangles.size * 4
        val bin = le(posBytes + nrmBytes + colBytes + idxBytes)
        for (x in mesh.positions) bin.putFloat(x)
        for (x in mesh.normals) bin.putFloat(x)
        for (v in 0 until nv) {
            bin.put(mesh.colours, 3 * v, 3)
            bin.put(0xFF.toByte())
        }
        for (i in mesh.triangles) bin.putInt(i)

        val b = mesh.bounds()
        val json = buildJsonObject {
            putJsonObject("asset") {
                put("version", "2.0")
                put("generator", generator)
            }
            put("scene", 0)
            putJsonArray("scenes") { add(buildJsonObject { putJsonArray("nodes") { add(JsonPrimitive(0)) } }) }
            putJsonArray("nodes") { add(buildJsonObject { put("mesh", 0) }) }
            putJsonArray("materials") {
                add(
                    buildJsonObject {
                        putJsonObject("pbrMetallicRoughness") {
                            put("metallicFactor", 0.0)
                            put("roughnessFactor", 1.0)
                        }
                    },
                )
            }
            putJsonArray("meshes") {
                add(
                    buildJsonObject {
                        putJsonArray("primitives") {
                            add(
                                buildJsonObject {
                                    putJsonObject("attributes") {
                                        put("POSITION", 0)
                                        put("NORMAL", 1)
                                        put("COLOR_0", 2)
                                    }
                                    put("indices", 3)
                                    put("material", 0)
                                    put("mode", 4)
                                },
                            )
                        }
                    },
                )
            }
            putJsonArray("buffers") { add(buildJsonObject { put("byteLength", bin.capacity()) }) }
            putJsonArray("bufferViews") {
                add(view(0, posBytes, ARRAY_BUFFER))
                add(view(posBytes, nrmBytes, ARRAY_BUFFER))
                add(view(posBytes + nrmBytes, colBytes, ARRAY_BUFFER))
                add(view(posBytes + nrmBytes + colBytes, idxBytes, ELEMENT_ARRAY_BUFFER))
            }
            putJsonArray("accessors") {
                add(
                    buildJsonObject {
                        put("bufferView", 0)
                        put("componentType", FLOAT)
                        put("count", nv)
                        put("type", "VEC3")
                        put("min", buildJsonArray { for (a in 0..2) add(JsonPrimitive(b[a])) })
                        put("max", buildJsonArray { for (a in 3..5) add(JsonPrimitive(b[a])) })
                    },
                )
                add(accessor(1, FLOAT, nv, "VEC3"))
                add(accessor(2, UNSIGNED_BYTE, nv, "VEC4", normalized = true))
                add(accessor(3, UNSIGNED_INT, mesh.triangles.size, "SCALAR"))
            }
        }
        val jsonBytes = pad(json.toString().toByteArray(Charsets.UTF_8), ' '.code.toByte())
        val binBytes = pad(bin.array(), 0)
        out(file).use { os ->
            os.write(le(12).putInt(GLB_MAGIC).putInt(2).putInt(12 + 8 + jsonBytes.size + 8 + binBytes.size).array())
            os.write(le(8).putInt(jsonBytes.size).putInt(CHUNK_JSON).array())
            os.write(jsonBytes)
            os.write(le(8).putInt(binBytes.size).putInt(CHUNK_BIN).array())
            os.write(binBytes)
        }
    }

    private fun view(offset: Int, length: Int, target: Int) = buildJsonObject {
        put("buffer", 0)
        put("byteOffset", offset)
        put("byteLength", length)
        put("target", target)
    }

    private fun accessor(view: Int, componentType: Int, count: Int, type: String, normalized: Boolean = false) =
        buildJsonObject {
            put("bufferView", view)
            put("componentType", componentType)
            put("count", count)
            put("type", type)
            if (normalized) put("normalized", true)
        }

    private fun pad(bytes: ByteArray, fill: Byte): ByteArray {
        val padded = (bytes.size + 3) / 4 * 4
        return if (padded == bytes.size) bytes else bytes.copyOf(padded).also { it.fill(fill, bytes.size, padded) }
    }

    private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun out(file: File): OutputStream = BufferedOutputStream(FileOutputStream(file), 256 * 1024)

    private fun readLine(input: DataInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            sb.append(c.toChar())
        }
    }

    private const val GLB_MAGIC = 0x46546C67 // "glTF"
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942
    private const val FLOAT = 5126
    private const val UNSIGNED_BYTE = 5121
    private const val UNSIGNED_INT = 5125
    private const val ARRAY_BUFFER = 34962
    private const val ELEMENT_ARRAY_BUFFER = 34963
}
