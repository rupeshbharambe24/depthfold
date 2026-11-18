"""Pose, axis and intrinsics conventions."""

from __future__ import annotations

import numpy as np
import pytest
from scipy.spatial.transform import Rotation

from depthfold.bundle import interpolate_pose
from depthfold.geometry import (
    GL_FROM_CV,
    Intrinsics,
    T_world_camera_from_arcore,
    arcore_from_T_world_camera,
    arcore_pose_to_matrix,
    invert_rigid,
    look_at,
    opencv_to_opengl_c2w,
    plane_basis,
    pose_to_arcore_dict,
    quat_from_matrix,
    rigidity_error,
    transform_points,
)


def random_pose(seed: int) -> np.ndarray:
    rng = np.random.default_rng(seed)
    T = np.eye(4)
    T[:3, :3] = Rotation.random(random_state=seed).as_matrix()
    T[:3, 3] = rng.normal(size=3)
    return T


def test_gl_cv_flip_is_involution() -> None:
    assert np.allclose(GL_FROM_CV @ GL_FROM_CV, np.eye(4))


def test_arcore_conversion_axes() -> None:
    # ARCore camera at the origin looking down world -Z (OpenGL: camera looks along its own -Z).
    T_arcore = np.eye(4)
    T = T_world_camera_from_arcore(T_arcore)
    forward_cv = T[:3, :3] @ np.array([0.0, 0.0, 1.0])  # OpenCV +Z is the optical axis
    down_cv = T[:3, :3] @ np.array([0.0, 1.0, 0.0])  # OpenCV +Y is image down
    assert np.allclose(forward_cv, [0, 0, -1])
    assert np.allclose(down_cv, [0, -1, 0])
    assert rigidity_error(T) < 1e-12


@pytest.mark.parametrize("seed", range(5))
def test_arcore_roundtrip(seed: int) -> None:
    T = random_pose(seed)
    d = pose_to_arcore_dict(T)
    assert np.allclose(T_world_camera_from_arcore(arcore_pose_to_matrix(d)), T, atol=1e-10)
    assert np.allclose(arcore_from_T_world_camera(T_world_camera_from_arcore(T)), T)


@pytest.mark.parametrize("seed", range(5))
def test_quaternion_and_inverse(seed: int) -> None:
    T = random_pose(seed)
    qx, qy, qz, qw = quat_from_matrix(T[:3, :3])
    assert qw >= 0
    assert np.isclose(qx * qx + qy * qy + qz * qz + qw * qw, 1.0)
    assert np.allclose(Rotation.from_quat([qx, qy, qz, qw]).as_matrix(), T[:3, :3])
    assert np.allclose(invert_rigid(T) @ T, np.eye(4))


def test_opengl_c2w_matches_nerf_convention() -> None:
    T = look_at([1.0, 0.5, 2.0], [0.0, 0.0, 0.0])
    c2w = opencv_to_opengl_c2w(T)
    # In OpenGL axes the camera looks along -Z and +Y is up in the image.
    view = c2w[:3, :3] @ np.array([0, 0, -1.0])
    target_dir = -np.array([1.0, 0.5, 2.0]) / np.linalg.norm([1.0, 0.5, 2.0])
    assert np.allclose(view, target_dir)
    assert c2w[:3, :3][1, 1] > 0  # image up has a positive world-Y component


def test_look_at_and_portrait_roll() -> None:
    T = look_at([0, 0, 1.0], [0, 0, 0], roll_deg=0)
    assert np.allclose(T[:3, 2], [0, 0, -1])  # optical axis towards target
    assert np.allclose(T[:3, 0], [1, 0, 0])  # image right = world +X
    assert np.allclose(T[:3, 1], [0, -1, 0])  # image down = world -Y
    Tp = look_at([0, 0, 1.0], [0, 0, 0], roll_deg=-90)
    assert abs(Tp[:3, 0] @ [0, 1, 0]) > 0.99  # sensor x axis is vertical in portrait


def test_intrinsics_corner_convention_and_scaling() -> None:
    k = Intrinsics(100.0, 100.0, 80.0, 45.0, 160, 90)
    # The optical axis hits the corner-convention principal point; pixel (79, 44) has its centre at (79.5, 44.5).
    uv, z = k.project(np.array([[0.0, 0.0, 2.0]]))
    assert np.allclose(uv, [[80.0, 45.0]]) and z[0] == 2.0
    rays = k.pixel_rays()
    assert np.allclose(rays[44, 79], [(79.5 - 80) / 100, (44.5 - 45) / 100, 1])
    k2 = k.scaled(320, 180)
    assert (k2.fx, k2.cx, k2.cy) == (200.0, 160.0, 90.0)
    # A pixel centre maps to the centre of the corresponding 2x2 block after upscaling.
    p = np.array([[0.13, -0.07, 1.3]])
    assert np.allclose(k2.project(p)[0], 2 * k.project(p)[0])
    assert k.to_center_convention() == (100.0, 100.0, 79.5, 44.5)


def test_backproject_project_roundtrip() -> None:
    k = Intrinsics(120.8, 121.0, 80.2, 45.1, 160, 90)
    depth = np.full((90, 160), 1.5)
    depth[0, 0] = 0  # no data
    pts = k.backproject(depth)
    assert np.isnan(pts[0, 0]).all()
    uv, z = k.project(pts.reshape(-1, 3)[1:])
    vv, uu = np.divmod(np.arange(1, 160 * 90), 160)
    assert np.allclose(uv[:, 0], uu + 0.5) and np.allclose(uv[:, 1], vv + 0.5)
    assert np.allclose(z, 1.5)


def test_transform_and_plane_basis() -> None:
    T = random_pose(7)
    p = np.random.default_rng(0).normal(size=(10, 3))
    assert np.allclose(transform_points(invert_rigid(T), transform_points(T, p)), p)
    n = np.array([0.1, 0.9, -0.2])
    e1, e2 = plane_basis(n)
    assert np.allclose(np.cross(e1, e2), n / np.linalg.norm(n))


def test_interpolate_pose_midpoint() -> None:
    T0 = np.eye(4)
    T1 = np.eye(4)
    T1[:3, :3] = Rotation.from_euler("y", 40, degrees=True).as_matrix()
    T1[:3, 3] = [1.0, 0.0, 0.0]
    Tm = interpolate_pose(150, 100, T0, 200, T1)
    assert np.allclose(Tm[:3, 3], [0.5, 0, 0])
    assert np.isclose(Rotation.from_matrix(Tm[:3, :3]).magnitude(), np.radians(20))
