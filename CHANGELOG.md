# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

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

[Unreleased]: https://github.com/rupeshbharambe24/depthfold/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/rupeshbharambe24/depthfold/releases/tag/v0.1.0
