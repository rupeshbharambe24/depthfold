# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

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

[Unreleased]: https://github.com/rupeshbharambe24/depthfold/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/rupeshbharambe24/depthfold/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/rupeshbharambe24/depthfold/releases/tag/v0.1.0
