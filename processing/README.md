# Depthfold processing toolkit

Python library and `depthfold` command-line tool that turn a Depthfold capture bundle (RGB + ARCore depth +
confidence + camera pose + intrinsics per frame) into 3D meshes, 2D profiles, real-world measurements and
camera exports for Nerfstudio and COLMAP.

The bundle format is specified in [`../docs/capture-bundle.md`](../docs/capture-bundle.md). This package is
its reference reader.

## Install

Requires [uv](https://docs.astral.sh/uv/) and Python 3.10 or newer (the project pins 3.12 in `.python-version`).
Open3D needs a prebuilt wheel for your platform and Python version; 0.20 ships CPython 3.10-3.14 wheels for
macOS arm64.

```sh
cd processing
uv sync              # creates processing/.venv with runtime and dev dependencies
uv run depthfold --help
uv run pytest        # about 25 s; prints a table of measured errors against synthetic ground truth
```

Runtime dependencies: numpy, scipy, Open3D, trimesh, ezdxf and Pillow, all under permissive licences
(BSD, MIT, Apache-2.0 or HPND). Open3D pulls in a fairly large set of transitive packages (Dash, Flask and others);
none of them are GPL.

## Quick start

```sh
uv run depthfold synth -o demo/bottle                  # synthetic 70 x 240 mm cylinder on a table
uv run depthfold validate demo/bottle
uv run depthfold fuse demo/bottle -o demo/fused
uv run depthfold measure demo/bottle -o demo/measure
uv run depthfold export demo/bottle --format nerfstudio -o demo/nerfstudio
uv run depthfold export demo/bottle --format colmap -o demo/colmap
```

Every command accepts a bundle folder or a `.zip` of it (with `bundle.json` at the archive root or inside a
single top-level folder).

## Commands

### `validate <bundle> [--json] [--quick]`

Checks the bundle against the spec and prints frame, tracking and depth statistics. Problems are grouped by
type with the affected frame indices. Errors (non-compliance) give exit code 1; warnings do not.

Checked: manifest fields and depth mode, `frames.jsonl` parsing, index and timestamp order, file presence,
image sizes and pixel formats (16-bit depth, 8-bit confidence, JPEG RGB), intrinsics, rigidity of
`T_world_camera`, consistency of `T_world_camera` with `pose_arcore`, the null-depth rules, provisional
`frame_count: 0`, and how many depth images come from an earlier camera frame than their pose. `--quick` skips
decoding images.

### `fuse <bundle> -o out/ [--voxel 0.005] [--min-confidence 128] [--max-depth 2.0] ...`

1. Selects usable depth: skips frames that are not `TRACKING`, frames without depth, and repeated depth
   (same `depth_timestamp_ns` as an earlier frame). Depth older than its frame by more than
   `--max-depth-age-ms` (default 1 ms) is paired with the pose at `depth_timestamp_ns`, interpolated between the
   neighbouring tracking frames; `--no-pose-repair` drops it instead.
2. Removes depth below `--min-confidence` (raw mode only) and outside `[--min-depth, --max-depth]`.
3. Integrates into a sparse TSDF (Open3D `VoxelBlockGrid`, truncation 3 voxels by default) using
   `depth_intrinsics` and `T_world_camera`. Voxel colours are sampled from the RGB image with the RGB
   intrinsics, so differing depth/RGB resolution, field of view and aspect ratio are handled correctly.
4. Extracts the mesh with marching cubes (voxels seen by fewer than `--min-weight` frames are left out) and removes
   small disconnected pieces.

| Output       | Content                                                                 |
|--------------|-------------------------------------------------------------------------|
| `mesh.ply`   | Coloured triangle mesh with normals, world frame, metres                |
| `mesh.obj`   | Same mesh (vertex colours as `v x y z r g b`), metres                    |
| `mesh.glb`   | Same mesh as binary glTF with `COLOR_0`, metres                          |
| `mesh.stl`   | Same geometry in **millimetres** (`--stl-units m` to keep metres)        |
| `points.ply` | Coloured surface points from the TSDF, metres                           |
| `fuse.json`  | Provenance, frames used and skipped (with reasons), parameters, stats  |

The world frame is the ARCore world of the session: right-handed, +Y up, arbitrary origin and yaw.

### `measure <bundle-or-mesh> -o out/ [--shape auto|box|cylinder]`

Measures the object standing on the dominant horizontal surface. Input is a bundle (fused on the fly with the
same options as `fuse`) or a mesh/point cloud (`.ply`, `.obj`, `.glb`, `.stl`, `.off`; `.stl` is read as mm,
others as metres unless `--units` says otherwise; up is +Y unless `--up` says otherwise). If a `fuse.json`
sits next to the mesh, its provenance is carried into the report.

Method:

1. **Support plane**: RANSAC restricted to normals within 20 degrees of up, refined by least squares.
2. **Object**: points more than about 5 mm above the plane, largest connected cluster.
3. **Height**: median height of the up-facing points of a flat top, or a high quantile of all heights if the
   top is not flat.
4. **Oriented bounding box** (gravity-aligned): minimum-area footprint rectangle; length and width from opposite
   face points for boxes, robust extents of mid-height side points otherwise.
5. **Cylinder / bottle** (`cylinder`, or `auto` when a circle fits the sides better than a rectangle): axis
   perpendicular to the plane, axis position fitted with one free radius per 5 mm height slice; diameter
   (median over 10-90 % of the height), maximum diameter and the radius-versus-height profile. The tilt of
   per-slice centres is reported as a diagnostic.
6. **Volume**: watertight mesh volume if the object's mesh component is closed; otherwise the integral of
   pi r(h)^2 over the profile (cylinder) or length x width x height (box). The method used is stated.

| Output              | Content                                                                         |
|---------------------|---------------------------------------------------------------------------------|
| `measurements.json` | Measurement report (below)                                                      |
| `profile.svg`       | Side view (half profile and silhouette for cylinders) and top view, in mm      |
| `profile.dxf`       | Same views for CAD (R2010, mm). Layer `PROFILE` holds a closed, revolve-ready half profile for cylinders; also `SILHOUETTE`, `AXIS`, `BASE`, `FOOTPRINT`, `TEXT` |
| `object.ply`        | The isolated object (mesh faces or points) that was measured                    |

`measurements.json` lists every value with its unit, an approximate 1-sigma uncertainty, the uncertainty
components (surface resolution, fit residual, plane noise, scale) and the method that produced it. It also
records the support plane, the bounding box axes in world coordinates, the radius profile, shape-selection
residuals, warnings, all parameters, and provenance: bundle path and capture id, device, ARCore depth mode,
frames used and skipped, fusion settings and the tool version.

The uncertainty includes an assumed 1 % relative scale uncertainty of ARCore tracking
(`--scale-uncertainty`). It does not cover systematic depth errors on difficult surfaces (see Limitations).

### `export <bundle> --format nerfstudio|colmap -o out/ [--stride N] [--link]`

Exports `TRACKING` frames and their RGB images (copied, or symlinked with `--link` for folder bundles).

- **Nerfstudio**: `transforms.json` with `OPENCV` intrinsics (zero distortion) and camera-to-world
  `transform_matrix` converted to OpenGL camera axes (`T_world_camera @ diag(1, -1, -1, 1)`). The on-device
  `points.ply`, if present, is referenced as `ply_file_path`. Depth is not exported because it is not registered
  to the RGB pixels.
- **COLMAP**: text model in `sparse/0/` with `PINHOLE` cameras, world-to-camera quaternions
  (`QW QX QY QZ`) and translations, and an empty `points3D.txt` (run `colmap point_triangulator` to fill it).

Bundle, COLMAP and Nerfstudio all put pixel centres at `i + 0.5`, so intrinsics are copied unchanged. The test
suite projects world points through both exports with independent parsers and checks they agree with the
bundle to 1e-6 px.

### `synth -o bundle/ [--shape cylinder|box] [--depth-mode raw|smooth] ...`

Writes a spec-compliant synthetic bundle of a known object on a 1.4 m table, seen from a camera orbiting on two
rings, plus `ground_truth.json` (not part of the format). The sensor model is deliberately ARCore-like:

- RGB 640x480 (4:3) and depth 160x90 (16:9) with depth intrinsics derived from a 16:9 "GPU texture", so the two
  images differ in resolution, field of view and aspect ratio; phone held in portrait (sensor x axis vertical).
- Depth noise growing with distance squared and with grazing incidence, 2 % holes, sparse outliers, and mixed
  ("flying") pixels at depth edges, all reflected in the confidence image. `smooth` mode has no confidence.
- Frames without fresh depth (null depth fields), depth taken from an earlier camera frame than the pose, a
  two-frame tracking loss with a bogus pose, and a first frame without depth.
- Optional pose noise (`--pose-noise-mm`, `--pose-noise-deg`).

## Accuracy on synthetic data

`uv run pytest` prints the errors measured on the synthetic captures (default settings, 5 mm voxels):

| Case                                   | Quantity       | Truth     | Error    |
|----------------------------------------|----------------|-----------|----------|
| Cylinder, raw depth                    | diameter       | 70 mm     | -0.6 mm  |
|                                        | height         | 240 mm    | +0.4 mm  |
|                                        | volume         | 923.6 mL  | -1.4 %   |
| Box 180 x 110 x 75 mm, raw depth       | length / width / height | | +0.1 / -0.1 / +0.2 mm |
|                                        | volume         | 1485 mL   | +0.3 %   |
| Cylinder, smooth depth (no confidence) | diameter / height | | -1.8 / +0.4 mm |
| Cylinder, 1 mm / 0.2 deg pose noise    | diameter / height | | -1.0 / +0.3 mm |

The tests require lengths within 3 mm and volumes within 5 %. Synthetic data has perfect calibration and
Lambertian surfaces, so expect larger errors on real captures.

## Library use

```python
from depthfold.bundle import Bundle
from depthfold.fuse import FuseConfig, fuse_bundle, write_fuse_outputs
from depthfold.measure import MeasureConfig, run_measure

with Bundle.open("capture.zip") as bundle:
    result = fuse_bundle(bundle, FuseConfig(voxel=0.004))
write_fuse_outputs(result, "out/")

report = run_measure("capture.zip", "out/measure", MeasureConfig(shape="cylinder"))
print(report["measurements"]["diameter"])
```

Modules: `bundle` (reading, PNG IO, frame selection), `geometry` (intrinsics, poses, axis conventions),
`validate`, `fuse`, `measure`, `drawing` (SVG/DXF), `export`, `synth`, `cli`.

## Limitations

- **Difficult surfaces.** ARCore depth (time-of-flight or depth-from-motion) is unreliable on transparent,
  reflective, very dark or textureless surfaces. Glass bottles, chrome and glossy plastic produce holes or depth
  from whatever is behind or reflected. Matte objects, or a light coat of removable matte spray, work best.
- **Depth noise and resolution.** Raw depth is low resolution (typically 160x90 or 160x120) and noisy, with noise
  growing roughly with distance squared. Keep the object within about 0.3-0.8 m and walk all the way around it,
  including views from above. Features smaller than about two voxels (10 mm by default) are not resolved.
- **TSDF artefacts.** Fusion rounds sharp convex edges and can leave a small outward lip, up to roughly the
  truncation distance (15 mm by default), just below a flat top. The measurement methods avoid these regions,
  but they are visible in the mesh and in the profile drawing near the top.
- **Tracking.** Measurements inherit ARCore's metric scale and drift. Loop closures and relocalisation jumps
  during a capture blur or double the surface; validate the bundle and re-capture if tracking was lost often.
- **Object assumptions.** One object standing on a roughly horizontal surface. The cylinder model assumes the
  axis is perpendicular to that surface (the tilt diagnostic warns above 3 degrees). Objects touching other
  objects merge into one cluster.
- **Volume.** Scans are open at the support plane, so volume normally comes from the shape model, not the mesh.
  It is the external volume, not the capacity of a container.
- **Uncertainties** are approximate 1-sigma estimates from a simple error model, not calibrated confidence
  intervals.
- **Implementation note.** Open3D's legacy `ScalableTSDFVolume` returns empty meshes with Open3D 0.20 on
  macOS arm64, so fusion uses the tensor `VoxelBlockGrid` API.

## License

Apache-2.0, see [`../LICENSE`](../LICENSE).
