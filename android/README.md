# Depthfold Android recorder

Records ARCore capture bundles (RGB, raw depth, depth confidence, camera pose and intrinsics per frame) for the
Depthfold processing toolkit. The bundle format is defined in [`../docs/capture-bundle.md`](../docs/capture-bundle.md);
this app is the reference writer.

Status: v0.1. Builds, lint-clean, with JVM unit tests for the pure logic. It has **not yet been run on a physical
device** (see [Testing status](#testing-status)).

## Requirements

- An [ARCore-supported device](https://developers.google.com/ar/devices) running Android 7.0 (API 24) or later,
  with Google Play Services for AR. The app is *AR Required*: Google Play only offers it on supported devices and
  installs Google Play Services for AR with it. When sideloaded, the app asks to install it on first launch.
- Depth needs a device that supports the ARCore Depth API (marked on the list above). Without it the app still
  records RGB, pose and intrinsics, and the bundle reports `depth_mode: "none"`.

## Build

Requires JDK 17 and an Android SDK with platform 37 and build-tools 37.0.0.

```sh
# Point Gradle at the SDK (local.properties is gitignored), or set ANDROID_HOME instead.
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # JVM unit tests, no device needed
./gradlew lint

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: Gradle 9.9.1 (wrapper), Android Gradle Plugin 9.5.1 with built-in Kotlin, Kotlin 2.4.21,
Compose BOM 2026.09.00, ARCore SDK `com.google.ar:core:1.57.0`, compileSdk/targetSdk 37, minSdk 24.

## Using the app

1. Grant the camera permission. The status panel shows tracking state (and the failure reason), the depth mode,
   the camera image size and ARCore availability.
2. Move the phone slowly until tracking reads `TRACKING`.
3. Choose a capture rate (1, 2, 4 or 8 fps; default 4) and press **Rec**. Move slowly around the object, keeping it
   0.3 to 2 m away. Press **Stop** to finalise the bundle.
4. **Captures** lists recorded bundles with frame count, depth mode and size. **Share zip** sends a zip of the
   bundle through the Android share sheet; **Delete** removes it.
5. **Measure** (optional): tap two points on a surface to see the distance between them in centimetres. A third tap
   starts a new measurement.

## What gets recorded

Each recording is one bundle directory, named by UTC start time (`20261118-153012`), under app-specific storage:

```
/sdcard/Android/data/dev.depthfold.android/files/captures/<capture-id>/
├── bundle.json
├── frames.jsonl
├── rgb/000000.jpg
├── depth/000000.png        # 16-bit greyscale, millimetres, 0 = no data
└── confidence/000000.png   # 8-bit greyscale, raw mode only
```

Pull bundles over USB with `adb pull /sdcard/Android/data/dev.depthfold.android/files/captures/`, or use Share zip.

How the app fills the format:

- **Camera image**: the largest back-camera CPU image ARCore offers at 30 fps, up to 2560x1600 (ARCore's default
  is often 640x480). It is saved as JPEG (quality 92) in sensor orientation with no rotation or EXIF orientation.
  The YUV_420_888 to NV21 conversion honours each plane's row and pixel stride.
- **Depth mode**: `RAW_DEPTH_ONLY` if the device supports it (`"raw"`: `acquireRawDepthImage16Bits` plus
  `acquireRawDepthConfidenceImage`), otherwise `AUTOMATIC` (`"smooth"`: `acquireDepthImage16Bits`, no confidence),
  otherwise none. Depth PNGs come from a small built-in 16-bit PNG encoder, because Android's `Bitmap` cannot write
  16-bit PNGs.
- **Repeated depth**: ARCore can return the same depth image for several camera frames. A depth image is saved only
  if its timestamp differs from the last saved one. Otherwise the frame's `depth`, `confidence`,
  `depth_timestamp_ns` and `depth_intrinsics` are `null`.
- **Intrinsics**: `intrinsics` comes from `Camera.getImageIntrinsics()`. `depth_intrinsics` is
  `Camera.getTextureIntrinsics()` scaled to the depth resolution, because depth covers the texture's field of view.
- **Pose**: `T_world_camera` is `Camera.getPose().toMatrix()` (column-major) transposed to row-major and
  right-multiplied by `diag(1, -1, -1, 1)` to give OpenCV camera axes. The unconverted pose is kept in `pose_arcore`.
  Every frame records its `tracking_state`. Frames that are not `TRACKING` are still written, and readers should
  skip them.
- **Threading**: on the GL thread, the app copies the image planes out of the ARCore frame and closes the images
  straight away. Encoding and disk writes run on one background writer thread. If the writer falls more than six
  frames behind, new frames are dropped (the drop count is shown) instead of using up memory.
- `bundle.json` is first written with `frame_count: 0` when recording starts, then rewritten when it stops. If the
  app is killed mid-recording, `frames.jsonl` is still valid up to the last complete line.

## Code layout

```
app/src/main/kotlin/dev/depthfold/android/
├── MainActivity.kt          permission flow, ArCoreApk install check, session lifecycle
├── ar/                      ARCore session setup, GL renderer, frame grabbing, measurement
│   ├── ArSessionFactory.kt  camera-config and depth-mode selection
│   ├── ArRenderer.kt        GLSurfaceView.Renderer: update, draw, capture, hit tests
│   ├── BackgroundRenderer.kt, DisplayRotationHelper.kt   adapted from ARCore hello_ar_java (Apache-2.0)
│   └── FrameGrabber.kt      copies Image planes out of the ARCore frame
├── capture/                 pure JVM: BundleWriter, BundleSchema, PngEncoder, ImagePlanes, Geometry (pose and
│                            intrinsics math), CaptureRepository (list/zip/delete)
└── ui/                      Jetpack Compose screens
```

Everything in `capture/` except `AndroidJpegEncoder`, plus the camera-config chooser and screen projection in `ar/`,
is plain Kotlin and covered by JVM unit tests in `app/src/test/`. The tests cover pose conversion, intrinsics
scaling, the PNG encoder (decoded back with `javax.imageio`), stride handling, the `bundle.json` and `frames.jsonl`
schema, and an end-to-end bundle write.

## Testing status

Verified on the build machine: `assembleDebug`, `testDebugUnitTest` (40 tests) and `lint` (no issues).

Not yet verified, because no device or emulator was available (ARCore needs real camera hardware):

- Session creation, camera-config selection and depth-mode selection on real hardware
- Camera background rendering and display rotation
- Real `Image` plane layouts from ARCore (stride handling is unit-tested against synthetic padded layouts only)
- The JPEG output from `YuvImage`, and whether RGB, depth and confidence line up with each other in recorded bundles
- Capture rate and drop behaviour under real disk speeds, and memory use during long recordings
- Tap-to-measure accuracy, sharing via FileProvider, permission and install flows

Before relying on the data, record a short bundle on a device and run it through the `processing/` validator.

## Known limitations

- Tap-to-measure prefers depth hits, but in `RAW_DEPTH_ONLY` mode ARCore produces no depth hit results, so
  measurements on raw-depth devices use plane and feature-point hits only. Measurements need a detected plane or
  a textured surface.
- The depth image can lag the camera frame (`depth_timestamp_ns < timestamp_ns`), while `T_world_camera` is the
  pose at `timestamp_ns`. Readers that need exact alignment should use frames where the two timestamps match.
- No on-device point cloud (`points.ply`) yet.
- Autofocus is on, which helps close-range objects but means focus, and so the true intrinsics, can drift a little
  during a recording. ARCore reports intrinsics per frame and they are recorded per frame, but ARCore may not
  model focus changes.
- Recording stops when the app goes to the background or the captures screen opens.
- No release signing configuration is included.

## Licence

Apache-2.0 (see `../LICENSE`). `BackgroundRenderer.kt` and `DisplayRotationHelper.kt` are adapted from Google's
ARCore SDK samples (Copyright 2017 Google LLC, Apache-2.0); their original headers are kept. The ARCore SDK is
distributed under the [ARCore Additional Terms of Service](https://developers.google.com/ar/develop/terms).
