/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.model

import dev.depthfold.android.capture.Intrinsics
import kotlin.math.floor
import kotlin.math.min

/** A colour image for voxel colouring: packed `0xRRGGBB` pixels, row-major, and its intrinsics. */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray)

/**
 * Sparse truncated signed distance volume, the same scheme as the Python toolkit's Open3D `VoxelBlockGrid`:
 * voxels are allocated in blocks of 8x8x8 only around observed surfaces, and each depth image is integrated by
 * projecting every voxel of the blocks it touches into the image.
 *
 * Voxel `(i, j, k)` sits at world position `(i, j, k) * voxel`. TSDF values are in `[-1, 1]` (positive in front of
 * the surface, i.e. in observed free space) and weights count the depth images that saw the voxel. Pure JVM code.
 */
class TsdfVolume(
    val voxel: Float,
    val trunc: Float,
    /** Upper bound on allocated blocks (about 5.6 KB each), so a huge scene cannot exhaust memory. */
    val maxBlocks: Int = 12_000,
    /** Weights saturate here so late frames still refine the surface. */
    private val maxWeight: Float = 64f,
) {
    class Block {
        val tsdf = FloatArray(VOXELS) { 1f }
        val weight = FloatArray(VOXELS)
        val rgb = ByteArray(VOXELS * 3)
    }

    private val blocks = HashMap<Long, Block>()

    /** True once [maxBlocks] was reached and some observed surface could not be stored. */
    var overflowed = false
        private set

    val blockCount: Int get() = blocks.size

    /** Allocated block keys in a deterministic order. */
    fun blockKeys(): LongArray = blocks.keys.toLongArray().also { it.sort() }

    fun block(key: Long): Block? = blocks[key]

    /**
     * Integrates one depth image.
     *
     * @param depth z-depth in metres, row-major `depthK.width x depthK.height`; 0 means no measurement.
     * @param tWorldCamera row-major 4x4 camera-to-world transform, OpenCV camera axes (+Z forward).
     * @param colour optional colour image covering (part of) the same view, with intrinsics [colourK].
     */
    fun integrate(
        depth: FloatArray,
        depthK: Intrinsics,
        tWorldCamera: DoubleArray,
        colour: RgbImage? = null,
        colourK: Intrinsics? = null,
    ) {
        val w = depthK.width
        val h = depthK.height
        require(depth.size == w * h) { "depth has ${depth.size} samples, intrinsics say ${w}x$h" }
        val t = tWorldCamera
        val blockSize = voxel * BLOCK

        // 1. Blocks within the truncation band of any measured point.
        val touched = HashSet<Long>()
        val step = blockSize * 0.5f
        for (v in 0 until h) {
            val y0 = ((v + 0.5) - depthK.cy) / depthK.fy
            for (u in 0 until w) {
                val d = depth[v * w + u]
                if (d <= 0f) continue
                val x0 = ((u + 0.5) - depthK.cx) / depthK.fx
                var z = d - trunc
                val zEnd = d + trunc
                while (true) {
                    val zc = min(z, zEnd).toDouble()
                    val px = x0 * zc
                    val py = y0 * zc
                    val wx = t[0] * px + t[1] * py + t[2] * zc + t[3]
                    val wy = t[4] * px + t[5] * py + t[6] * zc + t[7]
                    val wz = t[8] * px + t[9] * py + t[10] * zc + t[11]
                    touched.add(
                        key(
                            floor(wx / blockSize).toInt(),
                            floor(wy / blockSize).toInt(),
                            floor(wz / blockSize).toInt(),
                        ),
                    )
                    if (z >= zEnd) break
                    z += step
                }
            }
        }

        // 2. World-to-camera transform: R^T (p - t).
        val r00 = t[0]; val r01 = t[1]; val r02 = t[2]
        val r10 = t[4]; val r11 = t[5]; val r12 = t[6]
        val r20 = t[8]; val r21 = t[9]; val r22 = t[10]
        val tx = t[3]; val ty = t[7]; val tz = t[11]
        val fx = depthK.fx; val fy = depthK.fy; val cx = depthK.cx; val cy = depthK.cy
        val cfx = colourK?.fx ?: 0.0; val cfy = colourK?.fy ?: 0.0
        val ccx = colourK?.cx ?: 0.0; val ccy = colourK?.cy ?: 0.0
        val cw = colour?.width ?: 0
        val ch = colour?.height ?: 0
        val hasColour = colour != null && colourK != null

        for (k in touched.toLongArray().also { it.sort() }) {
            val block = blocks[k] ?: if (blocks.size < maxBlocks) {
                Block().also { blocks[k] = it }
            } else {
                overflowed = true
                continue
            }
            val bx = unpackX(k) * BLOCK
            val by = unpackY(k) * BLOCK
            val bz = unpackZ(k) * BLOCK
            for (lz in 0 until BLOCK) for (ly in 0 until BLOCK) for (lx in 0 until BLOCK) {
                val gx = (bx + lx) * voxel - tx
                val gy = (by + ly) * voxel - ty
                val gz = (bz + lz) * voxel - tz
                val cz = r02 * gx + r12 * gy + r22 * gz
                if (cz <= 1e-6) continue
                val cxp = r00 * gx + r10 * gy + r20 * gz
                val cyp = r01 * gx + r11 * gy + r21 * gz
                val ui = floor(fx * cxp / cz + cx).toInt()
                val vi = floor(fy * cyp / cz + cy).toInt()
                if (ui < 0 || vi < 0 || ui >= w || vi >= h) continue
                val d = depth[vi * w + ui]
                if (d <= 0f) continue
                val sdf = d - cz
                if (sdf < -trunc) continue
                val value = min(1.0, sdf / trunc).toFloat()
                val i = (lz * BLOCK + ly) * BLOCK + lx
                val wOld = block.weight[i]
                val wNew = wOld + 1f
                block.tsdf[i] = (block.tsdf[i] * wOld + value) / wNew
                block.weight[i] = min(wNew, maxWeight)
                if (hasColour) {
                    val cu = floor(cfx * cxp / cz + ccx).toInt().coerceIn(0, cw - 1)
                    val cv = floor(cfy * cyp / cz + ccy).toInt().coerceIn(0, ch - 1)
                    val p = colour!!.pixels[cv * cw + cu]
                    val o = i * 3
                    block.rgb[o] = blend(block.rgb[o], (p shr 16) and 0xFF, wOld, wNew)
                    block.rgb[o + 1] = blend(block.rgb[o + 1], (p shr 8) and 0xFF, wOld, wNew)
                    block.rgb[o + 2] = blend(block.rgb[o + 2], p and 0xFF, wOld, wNew)
                }
            }
        }
    }

    private fun blend(old: Byte, new: Int, wOld: Float, wNew: Float): Byte =
        (((old.toInt() and 0xFF) * wOld + new) / wNew + 0.5f).toInt().coerceIn(0, 255).toByte()

    /** Random access to voxels by global index, caching the last block looked up. Not thread safe. */
    inner class Accessor {
        private var lastKey = Long.MIN_VALUE
        private var last: Block? = null

        /** Returns the block holding voxel (i, j, k) and stores the voxel's offset in [offset], or null. */
        fun find(i: Int, j: Int, k: Int): Block? {
            val key = key(Math.floorDiv(i, BLOCK), Math.floorDiv(j, BLOCK), Math.floorDiv(k, BLOCK))
            if (key != lastKey) {
                lastKey = key
                last = blocks[key]
            }
            offset = (Math.floorMod(k, BLOCK) * BLOCK + Math.floorMod(j, BLOCK)) * BLOCK + Math.floorMod(i, BLOCK)
            return last
        }

        var offset = 0
            private set
    }

    companion object {
        const val BLOCK = 8
        const val VOXELS = BLOCK * BLOCK * BLOCK
        private const val BITS = 21
        private const val OFFSET = 1 shl (BITS - 1)
        private const val MASK = (1L shl BITS) - 1

        /** Packs three signed coordinates (each within +-2^20) into one key. */
        fun key(x: Int, y: Int, z: Int): Long =
            (((x + OFFSET).toLong() and MASK) shl (2 * BITS)) or
                (((y + OFFSET).toLong() and MASK) shl BITS) or
                ((z + OFFSET).toLong() and MASK)

        fun unpackX(key: Long): Int = ((key shr (2 * BITS)) and MASK).toInt() - OFFSET
        fun unpackY(key: Long): Int = ((key shr BITS) and MASK).toInt() - OFFSET
        fun unpackZ(key: Long): Int = (key and MASK).toInt() - OFFSET
    }
}
