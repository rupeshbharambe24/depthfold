/*
 * Copyright 2026 The Depthfold Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.depthfold.android.ar

import android.content.Context
import android.util.Log
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import dev.depthfold.android.capture.DepthMode
import dev.depthfold.android.capture.ImageSize
import java.util.EnumSet

/** A configured (still paused) ARCore session plus what was chosen for it. */
class SessionSetup(
    val session: Session,
    val depthMode: DepthMode,
    val rawDepthSupported: Boolean,
    val smoothDepthSupported: Boolean,
    val cpuImageSize: ImageSize,
    val textureSize: ImageSize,
)

object ArSessionFactory {
    private const val TAG = "DepthfoldSession"

    /**
     * Creates and configures a session:
     *  - back camera, 30 fps, the largest CPU image up to [CameraConfigChooser.DEFAULT_MAX_PIXELS]
     *    (ARCore's default is often only 640x480);
     *  - depth: RAW_DEPTH_ONLY if supported (bundle mode "raw"), else AUTOMATIC ("smooth"), else disabled;
     *  - autofocus and horizontal + vertical plane finding (for tap-to-measure).
     *
     * Throws the ARCore `Unavailable*Exception`s from the Session constructor; the caller reports them.
     */
    fun create(context: Context): SessionSetup {
        val session = Session(context)
        try {
            val defaultConfig = session.cameraConfig
            val filter = CameraConfigFilter(session)
                .setFacingDirection(CameraConfig.FacingDirection.BACK)
                .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
            val configs = session.getSupportedCameraConfigs(filter)
            val options = configs.map {
                CameraConfigOption(
                    imageWidth = it.imageSize.width,
                    imageHeight = it.imageSize.height,
                    textureWidth = it.textureSize.width,
                    textureHeight = it.textureSize.height,
                    usesDepthSensor = it.depthSensorUsage == CameraConfig.DepthSensorUsage.REQUIRE_AND_USE,
                )
            }
            val chosen = CameraConfigChooser.choose(options)?.let { configs[it] }
            if (chosen != null) session.cameraConfig = chosen

            // Depth support can depend on the camera config; fall back to the default config if the chosen
            // one has no depth but the default does.
            var raw = session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
            var smooth = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            if (!raw && !smooth && chosen != null) {
                session.cameraConfig = defaultConfig
                raw = session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
                smooth = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                if (!raw && !smooth) session.cameraConfig = chosen // no depth either way: keep the larger image
            }
            val depthMode = when {
                raw -> DepthMode.RAW
                smooth -> DepthMode.SMOOTH
                else -> DepthMode.NONE
            }

            val config = Config(session).apply {
                this.depthMode = when (depthMode) {
                    DepthMode.RAW -> Config.DepthMode.RAW_DEPTH_ONLY
                    DepthMode.SMOOTH -> Config.DepthMode.AUTOMATIC
                    DepthMode.NONE -> Config.DepthMode.DISABLED
                }
                focusMode = Config.FocusMode.AUTO
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                updateMode = Config.UpdateMode.BLOCKING
            }
            session.configure(config)

            val cam = session.cameraConfig
            Log.i(TAG, "camera image ${cam.imageSize}, texture ${cam.textureSize}, depth $depthMode")
            return SessionSetup(
                session = session,
                depthMode = depthMode,
                rawDepthSupported = raw,
                smoothDepthSupported = smooth,
                cpuImageSize = ImageSize(cam.imageSize.width, cam.imageSize.height),
                textureSize = ImageSize(cam.textureSize.width, cam.textureSize.height),
            )
        } catch (t: Throwable) {
            session.close()
            throw t
        }
    }
}
