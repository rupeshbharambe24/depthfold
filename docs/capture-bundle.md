# Capture bundle format (v0.1)

A capture bundle is the folder (or `.zip` of the folder) that the Depthfold Android app records and every
processing tool reads. It holds raw sensor data only, so the same capture can be re-processed later with better
algorithms. Version `0.1` is a draft: fields may still change before `1.0`.

## Layout

```
<capture-id>/
├── bundle.json            # manifest (required)
├── frames.jsonl           # one JSON object per frame (required)
├── rgb/000000.jpg         # colour image per frame (required)
├── depth/000000.png       # 16-bit greyscale PNG, depth in millimetres, 0 = no data (optional per frame)
├── confidence/000000.png  # 8-bit greyscale PNG, 0 (none) … 255 (high), same size as depth (optional)
├── points.ply             # on-device fused point cloud, world frame, metres (optional)
└── model/                 # 3D model built by the app on request (optional, derived data)
    ├── mesh.glb           #   glTF 2.0 binary, world frame, metres, vertex colours
    ├── mesh.ply           #   same mesh, binary PLY, metres
    ├── mesh.stl           #   same mesh, binary STL, millimetres
    └── model.json         #   frames used, parameters and mesh statistics
```

`model/` is derived from the raw data and can be deleted or rebuilt at any time; readers ignore it.

Depth values are the distance along the camera's optical axis (the camera-frame `z` coordinate, as ARCore
returns it), not the Euclidean distance along the pixel ray.

Frame files share one zero-padded six-digit index. A frame may have no depth (depth not ready yet, the device
has no Depth API, or the writer skipped a depth image it had already saved for an earlier frame); its `depth`,
`confidence`, `depth_timestamp_ns` and `depth_intrinsics` fields are then all `null`. In `"smooth"` mode a frame can
have `depth` but `confidence` is always `null`.

## Coordinate conventions

- **World frame**: ARCore world. Right-handed, metres, +Y up (opposite gravity). The origin and yaw are arbitrary
  per session.
- **Camera frame**: OpenCV convention. +X right, +Y down, +Z forward (optical axis), in the *sensor* orientation of
  the CPU image (not the display orientation).
- **`T_world_camera`**: 4×4 matrix, **row-major**, mapping camera-frame points to world-frame points:
  `p_world = T_world_camera · [p_cam, 1]`. ARCore's `Camera.getPose()` uses OpenGL camera axes
  (+Y up, −Z forward); writers convert with `T_world_camera = T_arcore · diag(1, −1, −1, 1)`.
- **`pose_arcore`**: the unconverted `Camera.getPose()` as `{qx, qy, qz, qw, tx, ty, tz}`, kept for debugging.

## Intrinsics

Pixel intrinsics follow the pinhole model, with `(cx, cy)` measured from the top-left pixel corner:
`u = fx · x/z + cx`, `v = fy · y/z + cy`.

- `intrinsics`: for the `rgb` image, from `Camera.getImageIntrinsics()`, rescaled if the JPEG was resized.
- `depth_intrinsics`: for the `depth` image. ARCore depth covers the field of view of the GPU texture
  (`Camera.getTextureIntrinsics()`), which can have a different aspect ratio from the CPU image. Writers must derive
  depth intrinsics from the **texture** intrinsics scaled to the depth resolution, not from the CPU-image intrinsics.

Both images come from the same physical camera, so they share `T_world_camera`.

## `bundle.json`

```json
{
  "format": "depthfold-bundle",
  "version": "0.1",
  "capture_id": "20261118-153012",
  "created_utc": "2026-11-18T15:30:12Z",
  "app": {"name": "depthfold-android", "version": "0.1.0"},
  "device": {"manufacturer": "samsung", "model": "SM-A556E", "android_sdk": 35},
  "arcore": {"sdk_version": "1.x.y", "depth_mode": "raw"},
  "rgb": {"width": 1920, "height": 1080, "format": "jpeg"},
  "depth": {"width": 160, "height": 90, "units": "mm", "source": "raw", "has_confidence": true},
  "frame_count": 120,
  "notes": ""
}
```

- `arcore.depth_mode`: `"raw"` (`acquireRawDepthImage16Bits` + confidence), `"smooth"` (`acquireDepthImage16Bits`,
  interpolated, no confidence) or `"none"`.
- `depth`: `null` when `depth_mode` is `"none"`, and also when no frame in the bundle has depth (the size is then
  unknown).
- `frame_count`: number of lines in `frames.jsonl`. Writers may create a provisional `bundle.json` with
  `frame_count: 0` when recording starts and rewrite it when recording stops; if a recording is interrupted, the
  provisional file remains, so readers should treat `frames.jsonl` as authoritative.

## `frames.jsonl`

One object per line, in capture order:

```json
{"index": 0, "timestamp_ns": 123456789000, "tracking_state": "TRACKING",
 "rgb": "rgb/000000.jpg", "depth": "depth/000000.png", "confidence": "confidence/000000.png",
 "depth_timestamp_ns": 123456789000,
 "intrinsics": {"fx": 1450.1, "fy": 1450.1, "cx": 960.2, "cy": 540.7, "width": 1920, "height": 1080},
 "depth_intrinsics": {"fx": 120.8, "fy": 120.8, "cx": 80.0, "cy": 45.1, "width": 160, "height": 90},
 "T_world_camera": [[1,0,0,0],[0,1,0,0],[0,0,1,0],[0,0,0,1]],
 "pose_arcore": {"qx": 0, "qy": 0, "qz": 0, "qw": 1, "tx": 0, "ty": 0, "tz": 0}}
```

- Only frames with `tracking_state == "TRACKING"` have a trustworthy pose; readers should skip the rest.
- `depth_timestamp_ns` lets readers drop stale depth: ARCore may return the previous depth image for several camera
  frames. The depth image can therefore come from an earlier camera frame (`depth_timestamp_ns < timestamp_ns`),
  while `T_world_camera` is the pose at `timestamp_ns`; readers that need exact depth/pose alignment should use only
  frames where the two timestamps are equal, or weight depth down by the gap.

## Readers

- `processing/` (Python, `depthfold` CLI) validates bundles, fuses depth into meshes and point clouds, takes
  measurements, and exports Nerfstudio `transforms.json` and COLMAP text models.
