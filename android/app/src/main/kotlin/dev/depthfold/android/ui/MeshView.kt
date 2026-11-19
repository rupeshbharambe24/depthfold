/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.ui

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import dev.depthfold.android.model.Mesh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Orbit viewer for a [Mesh]: drag to rotate around the model, pinch to zoom. Draws vertex colours with a
 * headlight, both faces lit. Renders only when something changes.
 */
@SuppressLint("ViewConstructor")
class MeshView(context: Context) : GLSurfaceView(context) {

    private val renderer = MeshRenderer()

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                renderer.zoom(detector.scaleFactor)
                requestRender()
                return true
            }
        },
    )
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var shown: Mesh? = null

    init {
        // Keep this surface above the (paused) AR camera surface, which stays alive underneath.
        setZOrderMediaOverlay(true)
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setMesh(mesh: Mesh) {
        if (mesh === shown) return
        shown = mesh
        renderer.setMesh(mesh)
        requestRender()
    }

    @SuppressLint("ClickableViewAccessibility") // dragging the model has no click equivalent
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> dragging = false
            MotionEvent.ACTION_MOVE -> if (dragging && event.pointerCount == 1 && !scaleDetector.isInProgress) {
                renderer.rotate((event.x - lastX) / width * 4f, (event.y - lastY) / height * 3f)
                lastX = event.x
                lastY = event.y
                requestRender()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}

private class MeshRenderer : GLSurfaceView.Renderer {

    /** Triangle soup built from the mesh on the caller's thread, uploaded on the GL thread. */
    private class Geometry(val vertices: Int, val data: ByteBuffer, val centre: FloatArray, val radius: Float)

    private val pending = AtomicReference<Geometry?>()
    private var geometry: Geometry? = null
    private var uploaded = false
    private var program = 0
    private var vbo = 0
    private var aspect = 1f

    @Volatile private var yaw = 0.6f
    @Volatile private var pitch = 0.5f
    @Volatile private var distanceScale = 1f

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    fun setMesh(mesh: Mesh) {
        val n = mesh.triangles.size
        val data = ByteBuffer.allocateDirect(n * STRIDE).order(ByteOrder.nativeOrder())
        val grey = mesh.colours.all { it.toInt() == 0 }
        for (corner in mesh.triangles) {
            for (a in 0..2) data.putFloat(mesh.positions[3 * corner + a])
            for (a in 0..2) data.putFloat(mesh.normals[3 * corner + a])
            for (a in 0..2) data.put(if (grey) 0xB4.toByte() else mesh.colours[3 * corner + a])
            data.put(0xFF.toByte())
        }
        data.flip()
        val b = mesh.bounds()
        val centre = FloatArray(3) { (b[it] + b[it + 3]) / 2 }
        val dx = b[3] - b[0]
        val dy = b[4] - b[1]
        val dz = b[5] - b[2]
        pending.set(Geometry(n, data, centre, sqrt(dx * dx + dy * dy + dz * dz) / 2))
    }

    fun rotate(dYaw: Float, dPitch: Float) {
        yaw -= dYaw
        pitch = (pitch + dPitch).coerceIn(-1.45f, 1.45f)
    }

    fun zoom(factor: Float) {
        distanceScale = (distanceScale / factor).coerceIn(0.05f, 5f)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.07f, 0.08f, 0.09f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        program = link(VERTEX_SHADER, FRAGMENT_SHADER)
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        uploaded = false // a new context has no buffers
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        pending.getAndSet(null)?.let {
            geometry = it
            uploaded = false
        }
        val g = geometry ?: return
        if (!uploaded) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, g.data.capacity(), g.data, GLES20.GL_STATIC_DRAW)
            uploaded = true
        }

        val dist = g.radius * 2.6f * distanceScale
        val eye = floatArrayOf(
            g.centre[0] + dist * cos(pitch) * sin(yaw),
            g.centre[1] + dist * sin(pitch),
            g.centre[2] + dist * cos(pitch) * cos(yaw),
        )
        val near = (dist - g.radius * 2f).coerceAtLeast(dist * 0.02f)
        Matrix.perspectiveM(proj, 0, 45f, aspect, near, dist + g.radius * 2f)
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], g.centre[0], g.centre[1], g.centre[2], 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(program, "uEye"), 1, eye, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        val aColour = GLES20.glGetAttribLocation(program, "aColour")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glEnableVertexAttribArray(aColour)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE, 0)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, STRIDE, 12)
        GLES20.glVertexAttribPointer(aColour, 4, GLES20.GL_UNSIGNED_BYTE, true, STRIDE, 24)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, g.vertices)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aNormal)
        GLES20.glDisableVertexAttribArray(aColour)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun link(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "mesh shader link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    private fun compile(type: Int, source: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, source)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "mesh shader compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
        return s
    }

    companion object {
        /** Bytes per vertex: position and normal as floats, colour as 4 bytes. */
        private const val STRIDE = 28

        private const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            attribute vec4 aColour;
            varying vec3 vPos;
            varying vec3 vNormal;
            varying vec4 vColour;
            void main() {
                vPos = aPos;
                vNormal = aNormal;
                vColour = aColour;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform vec3 uEye;
            varying vec3 vPos;
            varying vec3 vNormal;
            varying vec4 vColour;
            void main() {
                vec3 n = normalize(vNormal);
                vec3 l = normalize(uEye - vPos);
                float shade = 0.35 + 0.65 * abs(dot(n, l));
                gl_FragColor = vec4(vColour.rgb * shade, 1.0);
            }
        """
    }
}
