"""End-to-end: synthetic bundle -> fuse -> measure, and exports read back by independent parsers.

Tolerances (stated in the README): lengths within +/-3 mm, volume within +/-5 %.
"""

from __future__ import annotations

import json
from pathlib import Path

import ezdxf
import numpy as np
import pytest
import trimesh
from scipy.spatial.transform import Rotation

from depthfold.bundle import Bundle
from depthfold.export import export_colmap, export_nerfstudio, project_world_point
from depthfold.fuse import FuseConfig, fuse_bundle, write_fuse_outputs
from depthfold.measure import MeasureConfig, measure_points, run_measure

LENGTH_TOL_MM = 3.0
VOLUME_TOL = 0.05


def _gt(bundle: Path) -> dict:
    return json.loads((bundle / "ground_truth.json").read_text())


def test_fuse_writes_all_outputs(cylinder_bundle: Path, tmp_path: Path) -> None:
    with Bundle.open(cylinder_bundle) as b:
        result = fuse_bundle(b, FuseConfig())
    paths = write_fuse_outputs(result, tmp_path)
    for key in ("ply", "obj", "glb", "stl", "points", "report"):
        assert paths[key].is_file() and paths[key].stat().st_size > 0
    ply = trimesh.load(paths["ply"], process=False)
    glb = trimesh.load(paths["glb"], force="mesh", process=False)
    stl = trimesh.load(paths["stl"], process=False)
    obj = trimesh.load(paths["obj"], process=False)
    assert len(ply.faces) == len(glb.faces) == len(stl.faces) == len(obj.faces) > 1000
    assert np.allclose(stl.bounds, ply.bounds * 1000, atol=1e-2)  # STL in millimetres
    assert np.allclose(glb.bounds, ply.bounds, atol=1e-5)
    report = json.loads(paths["report"].read_text())
    assert report["frames"]["depth_frames_used"] == len(report["frames_used"]) > 40
    gt = _gt(cylinder_bundle)
    # The fused floor lies on the true support plane.
    n, d = np.array(gt["support_plane_world"]["normal"]), gt["support_plane_world"]["d"]
    v = np.asarray(result.mesh.vertices)
    near_floor = np.abs(v @ n + d) < 0.01
    assert near_floor.mean() > 0.5
    assert abs(np.median(v[near_floor] @ n + d)) < 0.001


def test_cylinder_measurement(cylinder_bundle: Path, tmp_path: Path, error_log) -> None:  # noqa: ANN001
    report = run_measure(cylinder_bundle, tmp_path, MeasureConfig(shape="auto"))
    gt = _gt(cylinder_bundle)
    m = report["measurements"]
    assert report["shape"] == "cylinder"
    assert abs(error_log("cylinder (raw)", "diameter_mm", m["diameter"]["value"], gt["diameter_mm"])) <= LENGTH_TOL_MM
    assert abs(error_log("cylinder (raw)", "height_mm", m["height"]["value"], gt["height_mm"])) <= LENGTH_TOL_MM
    err = error_log("cylinder (raw)", "volume_ml", m["volume"]["value"], gt["volume_ml"])
    assert abs(err) <= VOLUME_TOL * gt["volume_ml"]
    for key in ("length", "width"):
        assert abs(m[key]["value"] - gt["diameter_mm"]) <= LENGTH_TOL_MM
    for v in m.values():
        assert v["unit"] in ("mm", "mL") and v["uncertainty"] > 0 and v["method"]
    assert "profile" in m["volume"]["method"]
    prov = report["provenance"]
    assert prov["capture_id"] == "synth-cylinder-0" and prov["device"]["model"] == "synthetic"
    assert prov["depth_mode"] == "raw" and len(prov["frames_used"]) > 40
    assert report["tool"]["version"]
    profile = report["profile"]
    radii = np.array([p["radius_mm"] for p in profile if 30 < p["height_mm"] < 210])
    assert np.all(np.abs(radii - 35.0) < 2.0)

    # Drawings: real units, closed revolve profile.
    svg = (tmp_path / "profile.svg").read_text()
    assert 'width="' in svg and "mm" in svg.split("\n")[1]
    doc = ezdxf.readfile(tmp_path / "profile.dxf")
    assert doc.units == ezdxf.units.MM
    prof = [e for e in doc.modelspace().query("LWPOLYLINE") if e.dxf.layer == "PROFILE"][0]
    pts = np.array(prof.get_points("xy"))
    assert prof.closed and np.isclose(pts[:, 1].max(), m["height"]["value"], atol=0.01)
    assert abs(np.median(pts[:, 0][pts[:, 0] > 1]) - gt["diameter_mm"] / 2) < 2.0
    assert (tmp_path / "object.ply").is_file()


def test_box_measurement(box_bundle: Path, tmp_path: Path, error_log) -> None:  # noqa: ANN001
    report = run_measure(box_bundle, tmp_path, MeasureConfig(shape="auto"))
    gt = _gt(box_bundle)
    m = report["measurements"]
    assert report["shape"] == "box"
    for key in ("length", "width", "height"):
        err = error_log("box (raw)", f"{key}_mm", m[key]["value"], gt[f"{key}_mm"])
        assert abs(err) <= LENGTH_TOL_MM, key
    err = error_log("box (raw)", "volume_ml", m["volume"]["value"], gt["volume_ml"])
    assert abs(err) <= VOLUME_TOL * gt["volume_ml"]
    # The box length axis is recovered (up to sign) in the world frame.
    yaw = np.radians(gt["config"]["world_yaw_deg"] + gt["yaw_deg_in_scene"])
    true_axis = Rotation.from_euler("y", yaw).apply([1.0, 0.0, 0.0])
    axis = np.array(report["bounding_box"]["axes_world"]["length"])
    assert abs(axis @ true_axis) > np.cos(np.radians(2))


def test_smooth_mode_measurement(smooth_bundle: Path, tmp_path: Path, error_log) -> None:  # noqa: ANN001
    report = run_measure(smooth_bundle, tmp_path, MeasureConfig(shape="cylinder"))
    gt = _gt(smooth_bundle)
    m = report["measurements"]
    err = error_log("cylinder (smooth)", "diameter_mm", m["diameter"]["value"], gt["diameter_mm"])
    assert abs(err) <= LENGTH_TOL_MM
    assert abs(error_log("cylinder (smooth)", "height_mm", m["height"]["value"], gt["height_mm"])) <= LENGTH_TOL_MM


def test_pose_noise_robustness(noisy_pose_bundle: Path, tmp_path: Path, error_log) -> None:  # noqa: ANN001
    report = run_measure(noisy_pose_bundle, tmp_path, MeasureConfig(shape="cylinder"))
    gt = _gt(noisy_pose_bundle)
    m = report["measurements"]
    case = "cylinder (pose noise)"
    assert abs(error_log(case, "diameter_mm", m["diameter"]["value"], gt["diameter_mm"])) <= LENGTH_TOL_MM
    assert abs(error_log(case, "height_mm", m["height"]["value"], gt["height_mm"])) <= LENGTH_TOL_MM


def test_measure_watertight_mesh(tmp_path: Path) -> None:
    mesh = trimesh.creation.cylinder(radius=0.035, height=0.24, sections=128)
    mesh.apply_transform(trimesh.transformations.rotation_matrix(-np.pi / 2, [1, 0, 0]))  # axis along +Y
    mesh.apply_translation([0.2, 0.12 - 1.1, 0.4])
    mesh = mesh.subdivide_to_size(0.004)
    path = tmp_path / "bottle.stl"
    (mesh.copy().apply_scale(1000)).export(path)  # STL in millimetres
    report = run_measure(path, tmp_path / "out", MeasureConfig())
    m = report["measurements"]
    assert "watertight" in m["volume"]["method"]
    assert abs(m["volume"]["value"] - np.pi * 3.5**2 * 24) < 0.01 * np.pi * 3.5**2 * 24
    assert abs(m["height"]["value"] - 240.0) < 1.0
    assert abs(m["diameter"]["value"] - 70.0) < 1.0
    # A lone object mesh has no floor: its own flat base acts as the support plane.
    assert report["support_plane"]["tilt_from_up_deg"] < 0.1


def test_measure_points_requires_object() -> None:
    rng = np.random.default_rng(0)
    floor = np.column_stack([rng.uniform(-1, 1, 5000), np.zeros(5000), rng.uniform(-1, 1, 5000)])
    with pytest.raises(Exception, match="above the support plane"):
        measure_points(floor, np.tile([0.0, 1.0, 0.0], (5000, 1)))


# --------------------------------------------------------------------------------------------------------------
# Exports: parse with independent minimal readers and reproject world points.
# --------------------------------------------------------------------------------------------------------------


def _qvec2rotmat(qw: float, qx: float, qy: float, qz: float) -> np.ndarray:
    """COLMAP's own quaternion convention (Hamilton, w first), written out explicitly."""
    return np.array(
        [
            [1 - 2 * qy**2 - 2 * qz**2, 2 * qx * qy - 2 * qw * qz, 2 * qz * qx + 2 * qw * qy],
            [2 * qx * qy + 2 * qw * qz, 1 - 2 * qx**2 - 2 * qz**2, 2 * qy * qz - 2 * qw * qx],
            [2 * qz * qx - 2 * qw * qy, 2 * qy * qz + 2 * qw * qx, 1 - 2 * qx**2 - 2 * qy**2],
        ]
    )


def _read_colmap(model: Path) -> tuple[dict[int, tuple], dict[str, tuple]]:
    cams = {}
    for line in (model / "cameras.txt").read_text().splitlines():
        if line and not line.startswith("#"):
            t = line.split()
            assert t[1] == "PINHOLE"
            cams[int(t[0])] = (int(t[2]), int(t[3]), *map(float, t[4:8]))
    images = {}
    lines = [ln for ln in (model / "images.txt").read_text().splitlines() if not ln.startswith("#")]
    for header in lines[0::2]:
        t = header.split()
        q = list(map(float, t[1:5]))
        tvec = np.array(list(map(float, t[5:8])))
        images[t[9]] = (_qvec2rotmat(*q), tvec, int(t[8]))
    return cams, images


def _project_colmap(cams: dict, img: tuple, X: np.ndarray) -> np.ndarray:
    R, t, cid = img
    _, _, fx, fy, cx, cy = cams[cid]
    p = R @ X + t
    return np.array([fx * p[0] / p[2] + cx, fy * p[1] / p[2] + cy])


def _project_nerfstudio(data: dict, frame: dict, X: np.ndarray) -> np.ndarray:
    c2w = np.array(frame["transform_matrix"])
    w2c = np.linalg.inv(c2w)
    p = w2c[:3, :3] @ X + w2c[:3, 3]  # OpenGL camera: looks along -Z, +Y up
    fx, fy, cx, cy = (frame.get(k, data.get(k)) for k in ("fl_x", "fl_y", "cx", "cy"))
    return np.array([fx * p[0] / -p[2] + cx, -fy * p[1] / -p[2] + cy])


def test_exports_reproject_consistently(cylinder_bundle: Path, tmp_path: Path) -> None:
    with Bundle.open(cylinder_bundle) as b:
        ns = export_nerfstudio(b, tmp_path / "ns")
        model = export_colmap(b, tmp_path / "colmap")
        frames = list(b.iter_tracking_frames())
    data = json.loads(ns.read_text())
    cams, images = _read_colmap(model)
    assert len(data["frames"]) == len(images) == len(frames) == 70  # 2 non-TRACKING frames skipped
    assert (model / "points3D.txt").is_file()
    gt = _gt(cylinder_bundle)
    X = np.array(gt["object_base_center_world"]) + np.array([0.0, 0.12, 0.0])  # middle of the cylinder axis
    worst = 0.0
    for f, fr in zip(frames, data["frames"], strict=True):
        name = Path(f.rgb).name
        assert fr["file_path"] == f"images/{name}" and (tmp_path / "ns" / fr["file_path"]).is_file()
        assert (tmp_path / "colmap" / "images" / name).is_file()
        ref = project_world_point(f.T_world_camera, f.intrinsics, X)
        uv_ns = _project_nerfstudio(data, fr, X)
        uv_cm = _project_colmap(cams, images[name], X)
        worst = max(worst, np.abs(uv_ns - ref).max(), np.abs(uv_cm - ref).max())
        assert 0 < ref[0] < f.intrinsics.width and 0 < ref[1] < f.intrinsics.height  # object is in view
    assert worst < 1e-6
