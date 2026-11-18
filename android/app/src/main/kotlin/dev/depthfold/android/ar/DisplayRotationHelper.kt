/*
 * Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modifications Copyright 2026 The Depthfold Authors:
 * adapted from the ARCore SDK hello_ar_java sample (common/helpers/DisplayRotationHelper.java);
 * ported to Kotlin, uses Context.getDisplay() on API 30+.
 */
package dev.depthfold.android.ar

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import com.google.ar.core.Session

/**
 * Tracks display rotation and viewport size and forwards them to [Session.setDisplayGeometry] on the GL thread,
 * so the camera background and hit tests stay aligned with the screen.
 */
class DisplayRotationHelper(private val activity: Activity) : DisplayManager.DisplayListener {
    @Volatile
    private var viewportChanged = false
    @Volatile
    private var viewportWidth = 0
    @Volatile
    private var viewportHeight = 0

    private val displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val display: Display?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            (activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        }

    /** Call from Activity.onResume. */
    fun onResume() {
        displayManager.registerDisplayListener(this, null)
    }

    /** Call from Activity.onPause. */
    fun onPause() {
        displayManager.unregisterDisplayListener(this)
    }

    /** Call from GLSurfaceView.Renderer.onSurfaceChanged. */
    fun onSurfaceChanged(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    /** Call on the GL thread before Session.update(). */
    fun updateSessionIfNeeded(session: Session) {
        if (viewportChanged) {
            val rotation = display?.rotation ?: 0
            session.setDisplayGeometry(rotation, viewportWidth, viewportHeight)
            viewportChanged = false
        }
    }

    override fun onDisplayAdded(displayId: Int) = Unit

    override fun onDisplayRemoved(displayId: Int) = Unit

    override fun onDisplayChanged(displayId: Int) {
        viewportChanged = true
    }
}
