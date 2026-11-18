"""Export camera poses and images for third-party reconstruction tools.

* **Nerfstudio** (``transforms.json``): camera-to-world matrices in OpenGL camera axes (+X right, +Y up,
  +Z backward), i.e. ``T_world_camera @ diag(1, -1, -1, 1)``, with ``OPENCV`` intrinsics and zero distortion.
* **COLMAP** text model (``sparse/0/cameras.txt``, ``images.txt``, ``points3D.txt``): world-to-camera rotation as a
  Hamilton quaternion ``(QW, QX, QY, QZ)`` plus translation, OpenCV camera axes, ``PINHOLE`` cameras.
  ``points3D.txt`` is empty; run COLMAP's triangulator to fill it if needed.

Both formats use the same pixel convention as the bundle (pixel centres at ``i + 0.5``), so intrinsics are
copied unchanged. The world frame is the bundle's ARCore world (metres, +Y up). Only ``TRACKING`` frames are
exported. Images are copied (or symlinked with ``link=True`` for folder bundles) into ``images/``.
"""

from __future__ import annotations

import json
from collections.abc import Iterable
from pathlib import Path, PurePosixPath
from typing import Any, Literal

import numpy as np

from . import __version__
from .bundle import Bundle, Frame
from .geometry import Intrinsics, invert_rigid, opencv_to_opengl_c2w, quat_from_matrix

ExportFormat = Literal["nerfstudio", "colmap"]


def _frames(bundle: Bundle, stride: int) -> list[Frame]:
    frames = list(bundle.iter_tracking_frames())[:: max(1, stride)]
    if not frames:
        raise ValueError("bundle has no TRACKING frames to export")
    return frames


def _copy_images(bundle: Bundle, frames: Iterable[Frame], images_dir: Path, link: bool) -> dict[int, str]:
    names: dict[int, str] = {}
    for f in frames:
        name = PurePosixPath(f.rgb).name
        bundle.source.copy_to(f.rgb, images_dir / name, link=link)
        names[f.index] = name
    return names


def export_nerfstudio(bundle: Bundle, out_dir: Path | str, stride: int = 1, link: bool = False) -> Path:
    """Write ``transforms.json`` and ``images/`` for Nerfstudio; returns the path of ``transforms.json``.

    Intrinsics shared by all frames are written at the top level, otherwise per frame. If the bundle contains an
    on-device ``points.ply`` it is copied and referenced as ``ply_file_path`` (used by Splatfacto for
    initialisation). Bundle depth is not exported: its field of view differs from the RGB image, and Nerfstudio
    expects depth maps registered to the RGB pixels.
    """
    out = Path(out_dir)
    frames = _frames(bundle, stride)
    names = _copy_images(bundle, frames, out / "images", link)
    intr_set = {f.intrinsics for f in frames}
    shared = len(intr_set) == 1

    def intr_fields(k: Intrinsics) -> dict[str, Any]:
        return {"fl_x": k.fx, "fl_y": k.fy, "cx": k.cx, "cy": k.cy, "w": k.width, "h": k.height}

    data: dict[str, Any] = {"camera_model": "OPENCV", "k1": 0.0, "k2": 0.0, "p1": 0.0, "p2": 0.0}
    if shared:
        data.update(intr_fields(frames[0].intrinsics))
    out_frames = []
    for f in frames:
        entry: dict[str, Any] = {
            "file_path": f"images/{names[f.index]}",
            "transform_matrix": opencv_to_opengl_c2w(f.T_world_camera).tolist(),
            "depthfold_frame_index": f.index,
            "timestamp_ns": f.timestamp_ns,
        }
        if not shared:
            entry.update(intr_fields(f.intrinsics))
        out_frames.append(entry)
    data["frames"] = out_frames
    if bundle.source.exists("points.ply"):
        bundle.source.copy_to("points.ply", out / "points.ply", link=link)
        data["ply_file_path"] = "points.ply"
    data["depthfold"] = {
        "version": __version__,
        "capture_id": bundle.capture_id,
        "world_frame": "ARCore world: metres, +Y up",
        "camera_axes": "OpenGL (+X right, +Y up, +Z backward)",
    }
    path = out / "transforms.json"
    path.write_text(json.dumps(data, indent=2) + "\n")
    return path


def export_colmap(bundle: Bundle, out_dir: Path | str, stride: int = 1, link: bool = False) -> Path:
    """Write a COLMAP text model to ``out_dir/sparse/0`` and images to ``out_dir/images``; returns the model dir."""
    out = Path(out_dir)
    frames = _frames(bundle, stride)
    names = _copy_images(bundle, frames, out / "images", link)
    model = out / "sparse" / "0"
    model.mkdir(parents=True, exist_ok=True)

    cam_ids: dict[Intrinsics, int] = {}
    for f in frames:
        cam_ids.setdefault(f.intrinsics, len(cam_ids) + 1)
    lines = [
        "# Camera list with one line of data per camera:",
        "#   CAMERA_ID, MODEL, WIDTH, HEIGHT, PARAMS[]",
        f"# Number of cameras: {len(cam_ids)}",
    ]
    for k, cid in cam_ids.items():
        lines.append(f"{cid} PINHOLE {k.width} {k.height} {k.fx!r} {k.fy!r} {k.cx!r} {k.cy!r}")
    (model / "cameras.txt").write_text("\n".join(lines) + "\n")

    lines = [
        "# Image list with two lines of data per image:",
        "#   IMAGE_ID, QW, QX, QY, QZ, TX, TY, TZ, CAMERA_ID, NAME",
        "#   POINTS2D[] as (X, Y, POINT3D_ID)",
        f"# Number of images: {len(frames)}, mean observations per image: 0",
    ]
    for image_id, f in enumerate(frames, start=1):
        T_cw = invert_rigid(f.T_world_camera)  # noqa: N806
        qx, qy, qz, qw = quat_from_matrix(T_cw[:3, :3])
        tx, ty, tz = (float(v) for v in T_cw[:3, 3])
        lines.append(
            f"{image_id} {qw!r} {qx!r} {qy!r} {qz!r} {tx!r} {ty!r} {tz!r} {cam_ids[f.intrinsics]} {names[f.index]}"
        )
        lines.append("")
    (model / "images.txt").write_text("\n".join(lines) + "\n")
    (model / "points3D.txt").write_text(
        "# 3D point list with one line of data per point:\n"
        "#   POINT3D_ID, X, Y, Z, R, G, B, ERROR, TRACK[] as (IMAGE_ID, POINT2D_IDX)\n"
        "# Number of points: 0, mean track length: 0\n"
    )
    return model


def export_bundle(bundle: Bundle, fmt: ExportFormat, out_dir: Path | str, stride: int = 1, link: bool = False) -> Path:
    """Dispatch to :func:`export_nerfstudio` or :func:`export_colmap`."""
    if fmt == "nerfstudio":
        return export_nerfstudio(bundle, out_dir, stride, link)
    if fmt == "colmap":
        return export_colmap(bundle, out_dir, stride, link)
    raise ValueError(f"unknown export format {fmt!r}")


def project_world_point(T_world_camera: np.ndarray, intr: Intrinsics, p_world: np.ndarray) -> np.ndarray:  # noqa: N803
    """Pixel coordinates of a world point in a bundle camera (reference for export consistency checks)."""
    p_cam = invert_rigid(T_world_camera)[:3, :3] @ p_world + invert_rigid(T_world_camera)[:3, 3]
    uv, _ = intr.project(p_cam[None])
    return uv[0]
