/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.model

import kotlin.math.sqrt

/**
 * An indexed triangle mesh: `positions` and `normals` hold xyz per vertex (metres, ARCore world, +Y up),
 * `colours` hold rgb bytes per vertex and `triangles` three vertex indices per face, counter-clockwise seen from
 * outside. Pure JVM code.
 */
class Mesh(
    val positions: FloatArray,
    val colours: ByteArray,
    val triangles: IntArray,
    val normals: FloatArray = computeNormals(positions, triangles),
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = triangles.size / 3

    /** Axis-aligned bounds `[minX, minY, minZ, maxX, maxY, maxZ]`; zeros for an empty mesh. */
    fun bounds(): FloatArray {
        if (vertexCount == 0) return FloatArray(6)
        val b = floatArrayOf(
            Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
            -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE,
        )
        for (v in 0 until vertexCount) for (a in 0 until 3) {
            val p = positions[3 * v + a]
            if (p < b[a]) b[a] = p
            if (p > b[a + 3]) b[a + 3] = p
        }
        return b
    }

    /**
     * Drops connected pieces with fewer than [minTriangles] triangles (TSDF speckle from depth noise) and the
     * vertices no longer used, like the toolkit's `clean_mesh`.
     */
    fun withoutSmallComponents(minTriangles: Int): Mesh {
        if (minTriangles <= 1 || triangleCount == 0) return this
        val parent = IntArray(vertexCount) { it }
        fun root(x: Int): Int {
            var r = x
            while (parent[r] != r) {
                parent[r] = parent[parent[r]]
                r = parent[r]
            }
            return r
        }
        for (f in 0 until triangleCount) {
            val a = root(triangles[3 * f])
            for (c in 1..2) {
                val b = root(triangles[3 * f + c])
                if (a != b) parent[b] = a
            }
        }
        val faceCount = IntArray(vertexCount)
        for (f in 0 until triangleCount) faceCount[root(triangles[3 * f])]++
        val keepFace = BooleanArray(triangleCount) { faceCount[root(triangles[3 * it])] >= minTriangles }
        return subset(keepFace)
    }

    /** The faces flagged in [keepFace] and the vertices they use, renumbered. */
    fun subset(keepFace: BooleanArray): Mesh {
        val remap = IntArray(vertexCount) { -1 }
        var nv = 0
        var nf = 0
        for (f in 0 until triangleCount) {
            if (!keepFace[f]) continue
            nf++
            for (c in 0..2) {
                val v = triangles[3 * f + c]
                if (remap[v] < 0) remap[v] = nv++
            }
        }
        val pos = FloatArray(nv * 3)
        val nrm = FloatArray(nv * 3)
        val col = ByteArray(nv * 3)
        for (v in 0 until vertexCount) {
            val n = remap[v]
            if (n < 0) continue
            System.arraycopy(positions, 3 * v, pos, 3 * n, 3)
            System.arraycopy(normals, 3 * v, nrm, 3 * n, 3)
            System.arraycopy(colours, 3 * v, col, 3 * n, 3)
        }
        val tri = IntArray(nf * 3)
        var o = 0
        for (f in 0 until triangleCount) {
            if (!keepFace[f]) continue
            for (c in 0..2) tri[o++] = remap[triangles[3 * f + c]]
        }
        return Mesh(pos, col, tri, nrm)
    }

    companion object {
        /** Area-weighted vertex normals. */
        fun computeNormals(positions: FloatArray, triangles: IntArray): FloatArray {
            val n = FloatArray(positions.size)
            for (f in 0 until triangles.size / 3) {
                val a = 3 * triangles[3 * f]
                val b = 3 * triangles[3 * f + 1]
                val c = 3 * triangles[3 * f + 2]
                val ux = positions[b] - positions[a]
                val uy = positions[b + 1] - positions[a + 1]
                val uz = positions[b + 2] - positions[a + 2]
                val vx = positions[c] - positions[a]
                val vy = positions[c + 1] - positions[a + 1]
                val vz = positions[c + 2] - positions[a + 2]
                val nx = uy * vz - uz * vy
                val ny = uz * vx - ux * vz
                val nz = ux * vy - uy * vx
                for (i in intArrayOf(a, b, c)) {
                    n[i] += nx
                    n[i + 1] += ny
                    n[i + 2] += nz
                }
            }
            for (v in 0 until n.size / 3) {
                val l = sqrt(n[3 * v] * n[3 * v] + n[3 * v + 1] * n[3 * v + 1] + n[3 * v + 2] * n[3 * v + 2])
                if (l > 0f) {
                    n[3 * v] /= l
                    n[3 * v + 1] /= l
                    n[3 * v + 2] /= l
                }
            }
            return n
        }
    }
}

/**
 * Extracts the zero level set of a [TsdfVolume] with naive surface nets: one vertex per cell whose corners change
 * sign (the mean of the edge crossings), and one quad per sign-changing voxel edge. Only voxels with weight of at
 * least `minWeight` take part, matching the toolkit's `--min-weight`. The result is an indexed, consistently
 * oriented mesh (normals point into observed free space). Pure JVM code.
 */
object SurfaceNets {
    private const val B = TsdfVolume.BLOCK

    /** TSDF jumps larger than this between neighbours are a truncation artefact, not a surface crossing. */
    private const val MAX_JUMP = 1.0f

    fun extract(volume: TsdfVolume, minWeight: Float): Mesh {
        val acc = volume.Accessor()
        val cellVertex = HashMap<Long, Int>()
        val pos = FloatArrayList()
        val col = ByteArrayList()
        val tsdf = FloatArray(8)
        val rgbSum = IntArray(3)
        val keys = volume.blockKeys()

        // Pass 1: a vertex in every valid cell that the surface crosses.
        for (key in keys) {
            val bx = TsdfVolume.unpackX(key) * B
            val by = TsdfVolume.unpackY(key) * B
            val bz = TsdfVolume.unpackZ(key) * B
            for (lz in 0 until B) for (ly in 0 until B) for (lx in 0 until B) {
                val i = bx + lx
                val j = by + ly
                val k = bz + lz
                var valid = true
                var mask = 0
                rgbSum.fill(0)
                for (c in 0 until 8) {
                    val blk = acc.find(i + (c and 1), j + ((c shr 1) and 1), k + ((c shr 2) and 1))
                    if (blk == null || blk.weight[acc.offset] < minWeight) {
                        valid = false
                        break
                    }
                    val o = acc.offset
                    tsdf[c] = blk.tsdf[o]
                    if (tsdf[c] < 0f) mask = mask or (1 shl c)
                    rgbSum[0] += blk.rgb[3 * o].toInt() and 0xFF
                    rgbSum[1] += blk.rgb[3 * o + 1].toInt() and 0xFF
                    rgbSum[2] += blk.rgb[3 * o + 2].toInt() and 0xFF
                }
                if (!valid || mask == 0 || mask == 0xFF) continue
                var sx = 0f
                var sy = 0f
                var sz = 0f
                var n = 0
                for (a in 0 until 8) for (bit in intArrayOf(1, 2, 4)) {
                    if (a and bit != 0) continue
                    val b = a or bit
                    val ta = tsdf[a]
                    val tb = tsdf[b]
                    if ((ta < 0f) == (tb < 0f) || kotlin.math.abs(ta - tb) > MAX_JUMP) continue
                    val s = ta / (ta - tb)
                    val ax = (a and 1).toFloat()
                    val ay = ((a shr 1) and 1).toFloat()
                    val az = ((a shr 2) and 1).toFloat()
                    sx += ax + if (bit == 1) s else 0f
                    sy += ay + if (bit == 2) s else 0f
                    sz += az + if (bit == 4) s else 0f
                    n++
                }
                if (n == 0) continue
                cellVertex[TsdfVolume.key(i, j, k)] = pos.size / 3
                pos.add((i + sx / n) * volume.voxel)
                pos.add((j + sy / n) * volume.voxel)
                pos.add((k + sz / n) * volume.voxel)
                for (ch in 0..2) col.add((rgbSum[ch] / 8).toByte())
            }
        }

        // Pass 2: a quad around every sign-changing edge whose four cells all have vertices.
        val tris = IntArrayList()
        val quad = IntArray(4)
        for (key in keys) {
            val bx = TsdfVolume.unpackX(key) * B
            val by = TsdfVolume.unpackY(key) * B
            val bz = TsdfVolume.unpackZ(key) * B
            val block = volume.block(key) ?: continue
            for (lz in 0 until B) for (ly in 0 until B) for (lx in 0 until B) {
                val o = (lz * B + ly) * B + lx
                if (block.weight[o] < minWeight) continue
                val t0 = block.tsdf[o]
                val p = intArrayOf(bx + lx, by + ly, bz + lz)
                for (axis in 0 until 3) {
                    val q = p.copyOf()
                    q[axis]++
                    val nb = acc.find(q[0], q[1], q[2]) ?: continue
                    if (nb.weight[acc.offset] < minWeight) continue
                    val t1 = nb.tsdf[acc.offset]
                    if ((t0 < 0f) == (t1 < 0f) || kotlin.math.abs(t0 - t1) > MAX_JUMP) continue
                    // The other two axes in cyclic order, so (axis, u, v) is right-handed.
                    val u = (axis + 1) % 3
                    val v = (axis + 2) % 3
                    var ok = true
                    for ((idx, off) in QUAD_OFFSETS.withIndex()) {
                        val c = p.copyOf()
                        c[u] += off[0]
                        c[v] += off[1]
                        val vi = cellVertex[TsdfVolume.key(c[0], c[1], c[2])]
                        if (vi == null) {
                            ok = false
                            break
                        }
                        quad[idx] = vi
                    }
                    if (!ok) continue
                    // The quad winds counter-clockwise around +axis; the surface faces +axis when the
                    // inside (negative TSDF) is at p.
                    if (t0 < 0f) {
                        tris.add(quad[0]); tris.add(quad[1]); tris.add(quad[2])
                        tris.add(quad[0]); tris.add(quad[2]); tris.add(quad[3])
                    } else {
                        tris.add(quad[0]); tris.add(quad[2]); tris.add(quad[1])
                        tris.add(quad[0]); tris.add(quad[3]); tris.add(quad[2])
                    }
                }
            }
        }
        return Mesh(pos.toArray(), col.toArray(), tris.toArray())
    }

    private val QUAD_OFFSETS = arrayOf(intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(0, 0), intArrayOf(-1, 0))
}

internal class FloatArrayList(capacity: Int = 1024) {
    private var data = FloatArray(capacity)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

internal class IntArrayList(capacity: Int = 1024) {
    private var data = IntArray(capacity)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    fun toArray(): IntArray = data.copyOf(size)
}

internal class ByteArrayList(capacity: Int = 1024) {
    private var data = ByteArray(capacity)
    var size = 0
        private set

    fun add(v: Byte) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    fun toArray(): ByteArray = data.copyOf(size)
}
