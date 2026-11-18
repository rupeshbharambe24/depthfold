"""Camera models, rigid transforms and axis conventions.

Conventions used throughout Depthfold (see ``docs/capture-bundle.md``):

* **World**: ARCore world frame, right-handed, metres, +Y up.
* **Camera**: OpenCV axes, +X right, +Y down, +Z forward, in the sensor orientation of the image.
* **Pixels**: ``(cx, cy)`` are measured from the top-left *corner* of the top-left pixel, so the centre of the
  pixel at integer index ``(i, j)`` is at continuous coordinate ``(i + 0.5, j + 0.5)``. COLMAP and Nerfstudio use
  the same convention, and Open3D's tensor TSDF kernels (which look up pixel ``floor(u)``) behave the same way.
  OpenCV puts pixel centres at integer coordinates (see :meth:`Intrinsics.to_center_convention`).
* ``T_a_b`` maps points expressed in frame ``b`` into frame ``a``: ``p_a = T_a_b @ [p_b, 1]``.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import asdict, dataclass
from typing import Any

import numpy as np
from numpy.typing import ArrayLike, NDArray
from scipy.spatial.transform import Rotation

FloatArray = NDArray[np.float64]

#: Change of basis between OpenCV and OpenGL camera axes (flip Y and Z). It is its own inverse, so
#: ``T_world_cv = T_world_gl @ GL_FROM_CV`` and ``T_world_gl = T_world_cv @ GL_FROM_CV``.
GL_FROM_CV: FloatArray = np.diag([1.0, -1.0, -1.0, 1.0])


@dataclass(frozen=True)
class Intrinsics:
    """Pinhole intrinsics for one image, in pixels, with the corner pixel convention.

    Projection of a camera-frame point ``(x, y, z)`` is ``u = fx * x / z + cx``, ``v = fy * y / z + cy``.
    """

    fx: float
    fy: float
    cx: float
    cy: float
    width: int
    height: int

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> Intrinsics:
        """Build from a bundle ``intrinsics`` / ``depth_intrinsics`` object."""
        return cls(
            fx=float(d["fx"]),
            fy=float(d["fy"]),
            cx=float(d["cx"]),
            cy=float(d["cy"]),
            width=int(d["width"]),
            height=int(d["height"]),
        )

    def to_dict(self) -> dict[str, float | int]:
        """Serialise in the bundle format."""
        return asdict(self)

    @property
    def K(self) -> FloatArray:  # noqa: N802 - conventional name
        """3x3 camera matrix (corner convention)."""
        return np.array([[self.fx, 0.0, self.cx], [0.0, self.fy, self.cy], [0.0, 0.0, 1.0]])

    def scaled(self, width: int, height: int) -> Intrinsics:
        """Return intrinsics for the same camera resampled to ``width`` x ``height`` pixels.

        With the corner convention, resizing is a pure per-axis scale of ``fx, cx`` and ``fy, cy``.
        """
        sx = width / self.width
        sy = height / self.height
        return Intrinsics(self.fx * sx, self.fy * sy, self.cx * sx, self.cy * sy, int(width), int(height))

    def to_center_convention(self) -> tuple[float, float, float, float]:
        """Return ``(fx, fy, cx, cy)`` for libraries that put pixel centres at integer coordinates."""
        return self.fx, self.fy, self.cx - 0.5, self.cy - 0.5

    def project(self, points_cam: ArrayLike) -> tuple[FloatArray, FloatArray]:
        """Project camera-frame points ``(N, 3)`` to continuous pixel coordinates.

        Returns ``(uv, z)`` where ``uv`` is ``(N, 2)``; points with ``z <= 0`` get ``nan`` coordinates.
        """
        p = np.asarray(points_cam, dtype=np.float64).reshape(-1, 3)
        z = p[:, 2]
        with np.errstate(divide="ignore", invalid="ignore"):
            u = self.fx * p[:, 0] / z + self.cx
            v = self.fy * p[:, 1] / z + self.cy
        bad = z <= 0
        u[bad] = np.nan
        v[bad] = np.nan
        return np.stack([u, v], axis=1), z

    def pixel_rays(self) -> FloatArray:
        """Camera-frame rays ``(H, W, 3)`` with ``z = 1`` through every pixel centre."""
        u = np.arange(self.width, dtype=np.float64) + 0.5
        v = np.arange(self.height, dtype=np.float64) + 0.5
        uu, vv = np.meshgrid(u, v)
        x = (uu - self.cx) / self.fx
        y = (vv - self.cy) / self.fy
        return np.stack([x, y, np.ones_like(x)], axis=-1)

    def backproject(self, depth_m: ArrayLike) -> FloatArray:
        """Back-project a z-depth image ``(H, W)`` in metres to camera-frame points ``(H, W, 3)``.

        Pixels with depth ``<= 0`` or non-finite depth produce ``nan`` points.
        """
        d = np.asarray(depth_m, dtype=np.float64)
        if d.shape != (self.height, self.width):
            raise ValueError(f"depth shape {d.shape} does not match intrinsics {self.height}x{self.width}")
        pts = self.pixel_rays() * d[..., None]
        pts[~(np.isfinite(d) & (d > 0))] = np.nan
        return pts


def as_matrix4(m: ArrayLike) -> FloatArray:
    """Convert a row-major nested list (as stored in ``frames.jsonl``) to a 4x4 float array."""
    a = np.asarray(m, dtype=np.float64)
    if a.shape != (4, 4):
        raise ValueError(f"expected a 4x4 matrix, got shape {a.shape}")
    return a


def invert_rigid(T: ArrayLike) -> FloatArray:  # noqa: N803
    """Invert a rigid 4x4 transform without a general matrix inverse."""
    T = as_matrix4(T)
    R = T[:3, :3]
    t = T[:3, 3]
    out = np.eye(4)
    out[:3, :3] = R.T
    out[:3, 3] = -R.T @ t
    return out


def rigidity_error(T: ArrayLike) -> float:
    """Return how far ``T`` is from a proper rigid transform (0 for a perfect one).

    The value is the max of ``|R^T R - I|``, ``|det R - 1|`` and the deviation of the bottom row from
    ``[0, 0, 0, 1]``.
    """
    T = as_matrix4(T)
    R = T[:3, :3]
    return float(
        max(
            np.abs(R.T @ R - np.eye(3)).max(),
            abs(np.linalg.det(R) - 1.0),
            np.abs(T[3] - np.array([0.0, 0.0, 0.0, 1.0])).max(),
        )
    )


def transform_points(T: ArrayLike, points: ArrayLike) -> FloatArray:  # noqa: N803
    """Apply a 4x4 transform to points of shape ``(..., 3)``."""
    T = as_matrix4(T)
    p = np.asarray(points, dtype=np.float64)
    return p @ T[:3, :3].T + T[:3, 3]


def pose_from_quat_trans(qx: float, qy: float, qz: float, qw: float, t: ArrayLike) -> FloatArray:
    """Build a 4x4 transform from a Hamilton unit quaternion ``(qx, qy, qz, qw)`` and a translation."""
    T = np.eye(4)
    T[:3, :3] = Rotation.from_quat([qx, qy, qz, qw]).as_matrix()
    T[:3, 3] = np.asarray(t, dtype=np.float64).reshape(3)
    return T


def quat_from_matrix(R: ArrayLike) -> tuple[float, float, float, float]:  # noqa: N803
    """Return the unit quaternion ``(qx, qy, qz, qw)`` of a rotation matrix, with ``qw >= 0``."""
    q = Rotation.from_matrix(np.asarray(R, dtype=np.float64)).as_quat()
    if q[3] < 0:
        q = -q
    return float(q[0]), float(q[1]), float(q[2]), float(q[3])


def arcore_pose_to_matrix(pose: Mapping[str, float]) -> FloatArray:
    """Convert a ``pose_arcore`` object to the 4x4 ARCore camera pose (OpenGL camera axes)."""
    return pose_from_quat_trans(pose["qx"], pose["qy"], pose["qz"], pose["qw"], [pose["tx"], pose["ty"], pose["tz"]])


def T_world_camera_from_arcore(T_arcore: ArrayLike) -> FloatArray:  # noqa: N802, N803
    """Convert ARCore ``Camera.getPose()`` (OpenGL camera axes) to ``T_world_camera`` (OpenCV axes)."""
    return as_matrix4(T_arcore) @ GL_FROM_CV


def arcore_from_T_world_camera(T_world_camera: ArrayLike) -> FloatArray:  # noqa: N802, N803
    """Inverse of :func:`T_world_camera_from_arcore`."""
    return as_matrix4(T_world_camera) @ GL_FROM_CV


def opencv_to_opengl_c2w(T_world_camera: ArrayLike) -> FloatArray:  # noqa: N803
    """Camera-to-world matrix with OpenGL camera axes (+Y up, -Z forward), as used by Nerfstudio/NeRF."""
    return as_matrix4(T_world_camera) @ GL_FROM_CV


def pose_to_arcore_dict(T_world_camera: ArrayLike) -> dict[str, float]:  # noqa: N803
    """Return the ``pose_arcore`` object that corresponds to a ``T_world_camera`` matrix."""
    T_gl = arcore_from_T_world_camera(T_world_camera)
    qx, qy, qz, qw = quat_from_matrix(T_gl[:3, :3])
    tx, ty, tz = (float(v) for v in T_gl[:3, 3])
    return {"qx": qx, "qy": qy, "qz": qz, "qw": qw, "tx": tx, "ty": ty, "tz": tz}


def look_at(eye: ArrayLike, target: ArrayLike, up: ArrayLike = (0.0, 1.0, 0.0), roll_deg: float = 0.0) -> FloatArray:
    """Return ``T_world_camera`` (OpenCV axes) for a camera at ``eye`` looking at ``target``.

    The image ``-Y`` axis (up in the picture) is aligned as closely as possible with ``up``; ``roll_deg`` then
    rotates the camera about its optical axis (e.g. ``90`` for a phone held in portrait while the sensor image
    stays landscape).
    """
    eye = np.asarray(eye, dtype=np.float64)
    z = np.asarray(target, dtype=np.float64) - eye
    z /= np.linalg.norm(z)
    up_v = np.asarray(up, dtype=np.float64)
    x = np.cross(z, up_v)  # image right = forward x up
    if np.linalg.norm(x) < 1e-9:
        raise ValueError("look_at: view direction is parallel to up")
    x /= np.linalg.norm(x)
    y = np.cross(z, x)
    R = np.stack([x, y, z], axis=1)
    if roll_deg:
        R = R @ Rotation.from_euler("z", roll_deg, degrees=True).as_matrix()
    T = np.eye(4)
    T[:3, :3] = R
    T[:3, 3] = eye
    return T


def plane_basis(normal: ArrayLike) -> tuple[FloatArray, FloatArray]:
    """Return two unit vectors ``(e1, e2)`` spanning the plane orthogonal to ``normal``.

    ``e1, e2, normal`` form a right-handed frame. ``e1`` is chosen deterministically (closest to world +X).
    """
    n = np.asarray(normal, dtype=np.float64)
    n = n / np.linalg.norm(n)
    ref = np.array([1.0, 0.0, 0.0]) if abs(n[0]) < 0.9 else np.array([0.0, 0.0, 1.0])
    e1 = ref - n * (ref @ n)
    e1 /= np.linalg.norm(e1)
    e2 = np.cross(n, e1)
    return e1, e2
