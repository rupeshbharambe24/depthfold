/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import dev.depthfold.android.ar.ArRenderer
import dev.depthfold.android.ar.ArSessionFactory
import dev.depthfold.android.capture.CaptureRepository
import dev.depthfold.android.capture.DeviceInfo
import dev.depthfold.android.ui.DepthfoldApp
import dev.depthfold.android.ui.DepthfoldTheme
import java.io.File
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var renderer: ArRenderer
    private lateinit var repository: CaptureRepository

    private var installRequested = false
    private var permissionRequested = false
    private var activityResumed = false
    private var arRunning = false
    private var showingCaptures by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            renderer.updateState { it.copy(cameraPermissionGranted = granted) }
            if (granted) applyArState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        repository = CaptureRepository(capturesRoot())
        renderer = ArRenderer(
            activity = this,
            repository = repository,
            deviceInfo = DeviceInfo(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT),
        )
        surfaceView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        ArCoreApk.getInstance().checkAvailabilityAsync(this) { availability ->
            renderer.updateState { it.copy(arcoreAvailability = availability.name) }
        }
        cleanShareCache()

        setContent {
            DepthfoldTheme {
                DepthfoldApp(
                    renderer = renderer,
                    repository = repository,
                    surfaceView = surfaceView,
                    showingCaptures = showingCaptures,
                    onShowCaptures = { show ->
                        if (show) renderer.stopRecording()
                        showingCaptures = show
                        applyArState()
                    },
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onOpenAppSettings = {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                        )
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        applyArState()
    }

    override fun onPause() {
        super.onPause()
        activityResumed = false
        renderer.stopRecording()
        pauseAr()
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer.setup?.session?.close()
        renderer.setup = null
    }

    /** The AR camera runs only while the activity is resumed and the record screen is visible. */
    private fun applyArState() {
        if (activityResumed && !showingCaptures) resumeAr() else pauseAr()
    }

    private fun resumeAr() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        renderer.updateState { it.copy(cameraPermissionGranted = granted) }
        if (!granted) {
            if (!permissionRequested) {
                permissionRequested = true
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
            return
        }

        if (renderer.setup == null && !createSession()) return
        val setup = renderer.setup ?: return
        if (arRunning) return
        try {
            setup.session.resume()
        } catch (e: CameraNotAvailableException) {
            renderer.updateState { it.copy(sessionError = "Camera not available. Close other camera apps and retry.") }
            return
        }
        surfaceView.onResume()
        renderer.displayRotationHelper.onResume()
        arRunning = true
        renderer.updateState { it.copy(sessionRunning = true, sessionError = null) }
    }

    private fun pauseAr() {
        renderer.displayRotationHelper.onPause()
        // Pause the GL thread first so it is not inside Session.update() when the session pauses.
        surfaceView.onPause()
        renderer.setup?.session?.pause()
        if (arRunning) {
            arRunning = false
            renderer.updateState { it.copy(sessionRunning = false) }
        }
    }

    /** Ensures Google Play Services for AR is installed, then creates the session. */
    private fun createSession(): Boolean {
        val error: String = try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return false // onResume runs again after the install flow
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }
            val setup = ArSessionFactory.create(this)
            renderer.setup = setup
            renderer.updateState {
                it.copy(
                    sessionError = null,
                    depthMode = setup.depthMode,
                    rawDepthSupported = setup.rawDepthSupported,
                    smoothDepthSupported = setup.smoothDepthSupported,
                    cpuImageSize = "${setup.cpuImageSize.width}x${setup.cpuImageSize.height}",
                )
            }
            return true
        } catch (e: UnavailableArcoreNotInstalledException) {
            "Google Play Services for AR is not installed."
        } catch (e: UnavailableUserDeclinedInstallationException) {
            "Google Play Services for AR is required. Install it and reopen the app."
        } catch (e: UnavailableApkTooOldException) {
            "Please update Google Play Services for AR."
        } catch (e: UnavailableSdkTooOldException) {
            "Please update this app."
        } catch (e: UnavailableDeviceNotCompatibleException) {
            "This device does not support ARCore."
        } catch (e: Exception) {
            Log.e(TAG, "session creation failed", e)
            "Could not start AR session: ${e.message}"
        }
        renderer.updateState { it.copy(sessionError = error) }
        return false
    }

    private fun capturesRoot(): File {
        val base = getExternalFilesDir(null) ?: filesDir
        return File(base, "captures").apply { mkdirs() }
    }

    private fun cleanShareCache() {
        val dir = File(cacheDir, SHARE_CACHE_DIR)
        thread(name = "depthfold-cache-clean", isDaemon = true) { dir.deleteRecursively() }
    }

    companion object {
        private const val TAG = "Depthfold"
        const val SHARE_CACHE_DIR = "shared"
    }
}
