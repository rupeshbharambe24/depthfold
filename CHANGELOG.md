# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.2.2] - 2026-11-20

### Fixed

- Measuring an object standing on a table could put a point on the table behind it: the ray over the object's top
  meets the table plane, and plane hits were always preferred. A plane hit is now overridden when the frame's
  depth shows a surface clearly in front of it.
- A tap at an object's edge took the median of object and background depth; the nearer surface now wins.

### Added

- Measure mode has a centre crosshair with the live distance from the phone, an **Add** button that places a point
  under the crosshair (so the finger does not hide the target), and a dashed preview line with its live length
  from the first point. The result notes that it is the distance between the two points and stays fixed as you move.

## [0.2.1] - 2026-11-20

### Fixed

- Tap-to-measure said "No surface found" on objects: ARCore only returns depth hits in AUTOMATIC depth mode, and
  the app records in RAW_DEPTH_ONLY. Taps now fall back to the frame's own depth under the finger (median of the
  nearest confident pixels), so points can be placed on objects, not only on detected planes.
- Clearer messages when a tap finds no surface or no depth.

### Changed

- Debug APKs are signed with a shared key checked into the repo (`android/app/debug.keystore`), so new versions
  install over old ones and keep captures. Installing 0.2.1 over 0.2.0 or 0.1.0 still needs one uninstall.

## [0.2.0] - 2026-11-19

### Added

- On-phone 3D model: **Build 3D model** in the captures list fuses the capture's depth into a sparse TSDF volume
  (same scheme and defaults as `depthfold fuse`, but 1.5 m maximum depth), extracts the surface with surface nets,
  removes small pieces and writes `model/mesh.glb`, `mesh.ply`, `mesh.stl` (mm) and `model.json` into the bundle.
- 3D viewer in the app (drag to rotate, pinch to zoom) and sharing of the GLB, STL and PLY files.
- The interoperability check also measures, with the toolkit, a mesh built by the app's model builder from a
  synthetic bottle (69.3 mm / 240.4 mm against a true 70 / 240 mm).
- Capture bundle spec: optional `model/` folder for app-built models.

### Changed

- Dependabot groups GitHub Actions updates into one pull request; CI actions updated to current major versions.
- Development is focused on the core pipeline for now; CONTRIBUTING.md asks for an issue before any pull request.

## [0.1.0] - 2026-11-18

First public alpha.

### Added

- Capture bundle format v0.1 (`docs/capture-bundle.md`): RGB, raw ARCore depth with confidence, camera pose and
  intrinsics per frame, with explicit coordinate conventions.
- Android recorder (Kotlin, Jetpack Compose, ARCore 1.57): burst capture at 1–8 fps, raw or smooth depth,
  depth intrinsics derived from the camera texture, tap-to-measure, and sharing captures as zip files.
- `depthfold` Python toolkit: `validate`, `fuse` (TSDF fusion to PLY/OBJ/GLB/STL), `measure` (box and cylinder
  measurements with uncertainties, SVG/DXF profiles), `export` (Nerfstudio, COLMAP) and `synth` (synthetic captures
  for testing).
- Interoperability check between the Android bundle writer and the toolkit, and CI for both.

### Known limitations

- Not yet tested on physical devices.

[Unreleased]: https://github.com/rupeshbharambe24/depthfold/compare/v0.2.2...HEAD
[0.2.2]: https://github.com/rupeshbharambe24/depthfold/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/rupeshbharambe24/depthfold/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/rupeshbharambe24/depthfold/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/rupeshbharambe24/depthfold/releases/tag/v0.1.0
