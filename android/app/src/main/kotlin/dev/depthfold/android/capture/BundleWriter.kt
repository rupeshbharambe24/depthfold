/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/** Encodes an NV21 buffer to JPEG. The app uses `android.graphics.YuvImage`; tests use a fake. */
fun interface JpegEncoder {
    fun encodeNv21(nv21: ByteArray, width: Int, height: Int, quality: Int, out: OutputStream)
}

/**
 * Writes one capture bundle (see `docs/capture-bundle.md`) on a dedicated single background thread, so that
 * encoding and disk I/O never run on the GL thread. Frames are written in submission order; indices are
 * contiguous (a frame whose files fail to write is skipped and does not consume an index).
 *
 * Lifecycle: [start] once, [submit] any number of frames, then [finish] once.
 *
 * Pure JVM code apart from the injected [JpegEncoder].
 */
class BundleWriter(
    val dir: File,
    private val baseManifest: BundleManifest,
    private val jpegEncoder: JpegEncoder,
    private val maxPending: Int = 6,
    private val jpegQuality: Int = 92,
    private val listener: Listener? = null,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "depthfold-bundle-writer").apply { isDaemon = true }
    },
) {
    interface Listener {
        fun onFrameWritten(framesWritten: Int, depthFramesWritten: Int) {}
        fun onError(message: String, error: Throwable?) {}
    }

    private val lock = Any()
    private var started = false
    private var finished = false
    private val pending = AtomicInteger(0)

    // Writer-thread state.
    private var framesOut: BufferedWriter? = null
    private var nextIndex = 0
    private var depthFrames = 0
    private var rgbSize: ImageSize = baseManifest.rgbSize
    private var depthSize: ImageSize? = baseManifest.depthSize

    /** Number of submitted frames not yet written. */
    val pendingCount: Int get() = pending.get()

    /** True when a new frame would be accepted by [submit] (used to skip copying when the writer lags). */
    fun hasCapacity(): Boolean = synchronized(lock) { started && !finished } && pending.get() < maxPending

    /** Creates the bundle directory and a provisional `bundle.json` (frame_count 0). */
    fun start() {
        synchronized(lock) {
            check(!started) { "already started" }
            started = true
        }
        executor.execute {
            try {
                for (sub in listOf(BundleSchema.RGB_DIR, BundleSchema.DEPTH_DIR, BundleSchema.CONFIDENCE_DIR)) {
                    val d = File(dir, sub)
                    if (!d.isDirectory && !d.mkdirs()) throw IOException("cannot create $d")
                }
                writeManifest(currentManifest())
                framesOut = BufferedWriter(
                    OutputStreamWriter(FileOutputStream(File(dir, BundleSchema.FRAMES_JSONL)), Charsets.UTF_8),
                )
            } catch (t: Throwable) {
                listener?.onError("Cannot create bundle: ${t.message}", t)
            }
        }
    }

    /**
     * Queues a frame. Returns false (and drops the frame) when the writer is finished or more than
     * [maxPending] frames are already queued, so a slow disk cannot exhaust memory.
     */
    fun submit(frame: CapturedFrame): Boolean {
        synchronized(lock) {
            if (!started || finished) return false
            if (pending.get() >= maxPending) return false
            pending.incrementAndGet()
        }
        executor.execute {
            try {
                writeFrame(frame)
            } catch (t: Throwable) {
                listener?.onError("Frame write failed: ${t.message}", t)
            } finally {
                pending.decrementAndGet()
            }
        }
        return true
    }

    /**
     * Stops accepting frames, waits for queued frames, closes `frames.jsonl` and rewrites `bundle.json` with the
     * final frame count and image sizes. The returned future completes on the writer thread.
     */
    fun finish(notes: String = baseManifest.notes): Future<BundleManifest> {
        synchronized(lock) {
            check(started) { "not started" }
            check(!finished) { "already finished" }
            finished = true
        }
        val result = executor.submit(Callable {
            try {
                framesOut?.close()
            } finally {
                framesOut = null
            }
            val manifest = currentManifest().copy(notes = notes)
            writeManifest(manifest)
            manifest
        })
        executor.shutdown()
        return result
    }

    private fun currentManifest(): BundleManifest = baseManifest.copy(
        rgbSize = rgbSize,
        depthSize = if (baseManifest.depthMode == DepthMode.NONE) null else depthSize,
        frameCount = nextIndex,
    )

    private fun writeFrame(frame: CapturedFrame) {
        val out = framesOut ?: throw IOException("bundle not initialised")
        val index = nextIndex
        val rgb = frame.rgb

        val rgbRel = BundleSchema.rgbPath(index)
        val nv21 = ImagePlanes.yuv420ToNv21(rgb)
        writeFile(File(dir, rgbRel)) { jpegEncoder.encodeNv21(nv21, rgb.width, rgb.height, jpegQuality, it) }

        var depthRel: String? = null
        var confRel: String? = null
        var depthTs: Long? = null
        var depthK: Intrinsics? = null
        val depth = frame.depth
        if (depth != null && baseManifest.depthMode != DepthMode.NONE) {
            val samples = ImagePlanes.packDepth16LittleEndian(depth.depth, depth.width, depth.height)
            val png = PngEncoder.encodeGray16(depth.width, depth.height, samples)
            depthRel = BundleSchema.depthPath(index)
            writeFile(File(dir, depthRel)) { it.write(png) }
            val conf = depth.confidence
            if (conf != null && baseManifest.depthMode == DepthMode.RAW) {
                val c = ImagePlanes.packGray8(conf, depth.width, depth.height)
                confRel = BundleSchema.confidencePath(index)
                writeFile(File(dir, confRel)) { it.write(PngEncoder.encodeGray8(depth.width, depth.height, c)) }
            }
            depthTs = depth.timestampNs
            depthK = Intrinsics.forDepth(frame.textureIntrinsics, depth.width, depth.height)
        }

        val record = FrameRecord(
            index = index,
            timestampNs = frame.timestampNs,
            trackingState = frame.trackingState,
            rgbPath = rgbRel,
            depthPath = depthRel,
            confidencePath = confRel,
            depthTimestampNs = depthTs,
            // Rescale in case the CPU image differs from the intrinsics' reference size (it normally matches).
            intrinsics = frame.imageIntrinsics.scaledTo(rgb.width, rgb.height),
            depthIntrinsics = depthK,
            tWorldCamera = PoseMath.tWorldCameraFromArCore(frame.poseColumnMajor),
            poseArCore = frame.poseArCore,
        )
        out.write(BundleSchema.frameJson(record).toString())
        out.write("\n")
        out.flush()

        nextIndex = index + 1
        rgbSize = ImageSize(rgb.width, rgb.height)
        if (depth != null && depthRel != null) {
            depthFrames++
            depthSize = ImageSize(depth.width, depth.height)
        }
        listener?.onFrameWritten(nextIndex, depthFrames)
    }

    private fun writeManifest(m: BundleManifest) {
        val text = PRETTY_JSON.encodeToString(JsonElement.serializer(), BundleSchema.manifestJson(m)) + "\n"
        val tmp = File(dir, BundleSchema.BUNDLE_JSON + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        val target = File(dir, BundleSchema.BUNDLE_JSON)
        if (!tmp.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private inline fun writeFile(file: File, block: (OutputStream) -> Unit) {
        BufferedOutputStream(FileOutputStream(file), 64 * 1024).use(block)
    }

    companion object {
        private val PRETTY_JSON = Json { prettyPrint = true }
    }
}
