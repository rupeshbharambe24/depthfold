"""Synthetic capture bundles of known objects, for tests and demos.

The generator ray-casts an analytic scene (a finite floor/table plane plus a capped cylinder or an oriented box)
from a camera orbiting the object on two rings, and writes a spec-compliant bundle with deliberately
ARCore-like imperfections:

* low-resolution depth (default 160x90, 16:9) with a different field of view and aspect ratio from the RGB image
  (default 640x480, 4:3), mimicking depth intrinsics derived from the GPU texture rather than the CPU image;
* z-depth noise growing with distance and with grazing incidence, random holes, a few outliers, and "flying"
  pixels at depth discontinuities (from 2x2 supersampling), all reflected in the confidence image;
* frames without fresh depth (``null`` depth fields, as the app writes them; or, optionally, the repeated image
  saved again), depth that comes from an earlier camera frame than the frame pose, a short tracking loss with a
  bogus pose, and a first frame without depth;
* ``raw`` (with confidence) or ``smooth`` (hole-filled, no confidence) depth mode;
* the phone held in portrait, so the sensor image +X axis points along world up/down;
* an arbitrary world origin and yaw, as in an ARCore session.

``ground_truth.json`` next to ``bundle.json`` records the true object dimensions and support plane. It is not part
of the bundle format and readers ignore it.
"""

from __future__ import annotations

import json
import math
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Literal

import numpy as np
from numpy.typing import NDArray
from PIL import Image
from scipy.spatial.transform import Rotation

from . import __version__
from .bundle import FORMAT_NAME, Frame, interpolate_pose, write_bundle_files, write_png8, write_png16
from .geometry import FloatArray, Intrinsics, look_at, pose_to_arcore_dict

Shape = Literal["cylinder", "box"]


@dataclass
class SynthConfig:
    """Parameters of a synthetic capture. Lengths are in metres, angles in degrees."""

    shape: Shape = "cylinder"
    cylinder_diameter: float = 0.070
    cylinder_height: float = 0.240
    box_size: tuple[float, float, float] = (0.180, 0.110, 0.075)  # length (local x), width (local z), height
    box_yaw_deg: float = 30.0
    frames: int = 72
    rgb_size: tuple[int, int] = (640, 480)
    depth_size: tuple[int, int] = (160, 90)
    rgb_hfov_deg: float = 65.0
    orbit_distance: float = 0.45
    ring_elevations_deg: tuple[float, ...] = (20.0, 45.0)
    portrait: bool = True
    table_size: float = 1.4
    depth_noise_base: float = 0.001  # sigma(z) = base + quad * z^2, metres
    depth_noise_quad: float = 0.004
    hole_fraction: float = 0.02
    outlier_fraction: float = 0.003
    max_range: float = 4.0
    depth_mode: Literal["raw", "smooth"] = "raw"
    repeat_depth_every: int = 6  # every n-th frame gets no fresh depth from ARCore (0 disables)
    repeat_depth_mode: Literal["null", "duplicate"] = "null"  # writer skips the repeat, or saves it again
    lag_depth_every: int = 7  # every n-th frame's depth comes from an earlier camera frame (0 disables)
    lag_depth_ms: float = 33.3  # age of lagged depth (one camera frame at 30 Hz)
    tracking_loss_frames: tuple[int, ...] = (9, 10)
    pose_noise_trans: float = 0.0  # metres, applied to recorded poses only
    pose_noise_rot_deg: float = 0.0
    frame_interval_ms: float = 100.0
    seed: int = 0
    world_offset: tuple[float, float, float] = (0.3512, -1.2537, -0.8021)  # scene origin (floor under object) in world
    world_yaw_deg: float = 37.0
    jpeg_quality: int = 90
    extra: dict[str, Any] = field(default_factory=dict)


# --------------------------------------------------------------------------------------------------------------
# Ray casting (scene frame: floor is y = 0, object stands on the floor centred at the origin)
# --------------------------------------------------------------------------------------------------------------

_MAT_NONE, _MAT_FLOOR, _MAT_SIDE, _MAT_TOP = 0, 1, 2, 3


@dataclass
class _Hits:
    t: FloatArray  # ray parameter (= camera z-depth for rays with unit camera z)
    normal: FloatArray
    material: NDArray[np.int8]
    point: FloatArray


def _rot_y(deg: float) -> FloatArray:
    return Rotation.from_euler("y", deg, degrees=True).as_matrix()


def _raycast(origin: FloatArray, dirs: FloatArray, cfg: SynthConfig) -> _Hits:
    """Intersect rays ``origin + t * dirs`` (``dirs`` need not be unit length) with the scene."""
    n = dirs.shape[0]
    t_best = np.full(n, np.inf)
    normal = np.zeros((n, 3))
    mat = np.zeros(n, dtype=np.int8)
    o = origin
    dx, dy, dz = dirs[:, 0], dirs[:, 1], dirs[:, 2]

    def take(t: FloatArray, valid: NDArray[np.bool_], nrm: NDArray[Any], m: int | NDArray[Any]) -> None:
        better = valid & (t > 1e-6) & (t < t_best)
        t_best[better] = t[better]
        normal[better] = nrm[better] if np.ndim(nrm) == 2 else nrm
        mat[better] = m[better] if np.ndim(m) else m

    with np.errstate(divide="ignore", invalid="ignore"):
        # Floor / table top.
        tf = -o[1] / dy
        px, pz = o[0] + tf * dx, o[2] + tf * dz
        half = cfg.table_size / 2
        take(tf, (dy < 0) & (np.abs(px) <= half) & (np.abs(pz) <= half), np.array([0.0, 1.0, 0.0]), _MAT_FLOOR)

        if cfg.shape == "cylinder":
            r, h = cfg.cylinder_diameter / 2, cfg.cylinder_height
            a = dx * dx + dz * dz
            b = 2 * (o[0] * dx + o[2] * dz)
            c = o[0] ** 2 + o[2] ** 2 - r * r
            disc = b * b - 4 * a * c
            ts = (-b - np.sqrt(np.maximum(disc, 0))) / (2 * a)
            py = o[1] + ts * dy
            side_n = np.stack([o[0] + ts * dx, np.zeros(n), o[2] + ts * dz], axis=1) / r
            take(ts, (disc >= 0) & (py >= 0) & (py <= h), side_n, _MAT_SIDE)
            tc = (h - o[1]) / dy
            cx, cz = o[0] + tc * dx, o[2] + tc * dz
            take(tc, (dy < 0) & (cx * cx + cz * cz <= r * r), np.array([0.0, 1.0, 0.0]), _MAT_TOP)
        else:
            L, W, H = cfg.box_size
            R = _rot_y(cfg.box_yaw_deg)  # box local -> scene
            ol = R.T @ o
            dl = dirs @ R  # == (R.T @ d.T).T
            lo = np.array([-L / 2, 0.0, -W / 2])
            hi = np.array([L / 2, H, W / 2])
            dl_safe = np.where(np.abs(dl) < 1e-12, 1e-12, dl)
            t1 = (lo - ol) / dl_safe
            t2 = (hi - ol) / dl_safe
            tnear = np.minimum(t1, t2)
            tfar = np.maximum(t1, t2)
            t_enter = tnear.max(axis=1)
            t_exit = tfar.min(axis=1)
            axis = tnear.argmax(axis=1)
            nl = np.zeros((n, 3))
            nl[np.arange(n), axis] = -np.sign(dl[np.arange(n), axis])
            nb = nl @ R.T
            take(t_enter, t_enter <= t_exit, nb, np.where(axis == 1, _MAT_TOP, _MAT_SIDE).astype(np.int8))

    hit = np.isfinite(t_best)
    point = np.full((n, 3), np.nan)
    point[hit] = o + t_best[hit, None] * dirs[hit]
    return _Hits(t=t_best, normal=normal, material=mat, point=point)


def _shade(hits: _Hits, dirs: FloatArray, cfg: SynthConfig, rng: np.random.Generator) -> NDArray[np.uint8]:
    """Procedural albedo and Lambertian shading for RGB rays."""
    n = dirs.shape[0]
    col = np.empty((n, 3))
    col[:] = (np.linspace(150, 210, n)[:, None]) * np.array([0.85, 0.9, 1.0])  # background
    p = hits.point
    m = hits.material
    f = m == _MAT_FLOOR
    checker = (np.floor(p[f, 0] / 0.05) + np.floor(p[f, 2] / 0.05)) % 2
    col[f] = np.where(checker[:, None] > 0, [196, 186, 165], [128, 116, 104])
    obj = (m == _MAT_SIDE) | (m == _MAT_TOP)
    if cfg.shape == "cylinder":
        phi = np.arctan2(p[obj, 2], p[obj, 0])
        stripe = (np.floor(phi / (2 * np.pi) * 12) % 2)[:, None]
        band = ((p[obj, 1] > 0.08) & (p[obj, 1] < 0.16))[:, None]
        base = np.where(stripe > 0, [40, 120, 70], [30, 90, 160])
        col[obj] = np.where(band, [235, 235, 225], base)
    else:
        local = p[obj] @ _rot_y(cfg.box_yaw_deg)
        grid = (np.floor(local[:, 0] / 0.03) + np.floor(local[:, 1] / 0.03) + np.floor(local[:, 2] / 0.03)) % 2
        col[obj] = np.where(grid[:, None] > 0, [200, 80, 50], [230, 160, 60])
    light = np.array([0.3, 1.0, 0.2])
    light /= np.linalg.norm(light)
    lam = 0.4 + 0.6 * np.clip(hits.normal @ light, 0, 1)
    hit = m != _MAT_NONE
    col[hit] *= lam[hit, None]
    col += rng.normal(0, 2.0, col.shape)
    return np.clip(col, 0, 255).astype(np.uint8)


# --------------------------------------------------------------------------------------------------------------
# Cameras
# --------------------------------------------------------------------------------------------------------------


def _intrinsics(cfg: SynthConfig) -> tuple[Intrinsics, Intrinsics]:
    """RGB (CPU image) intrinsics and depth intrinsics derived from a 16:9 'GPU texture'."""
    w, h = cfg.rgb_size
    fx = (w / 2) / math.tan(math.radians(cfg.rgb_hfov_deg) / 2)
    rgb = Intrinsics(fx, fx, w / 2 + 0.4, h / 2 - 0.4, w, h)
    # Texture: same horizontal field of view, cropped to the depth aspect ratio around the principal point,
    # slightly different principal point offset (as on real devices).
    dw, dh = cfg.depth_size
    tex_w = 1920
    s = tex_w / w
    tex_h = round(tex_w * dh / dw)
    tex = Intrinsics(fx * s, fx * s, rgb.cx * s + 1.5, rgb.cy * s - (h * s - tex_h) / 2 - 2.0, tex_w, tex_h)
    return rgb, tex.scaled(dw, dh)


def _orbit_poses(cfg: SynthConfig, rng: np.random.Generator) -> list[FloatArray]:
    """True ``T_scene_camera`` poses on rings around the object."""
    target_h = (cfg.cylinder_height if cfg.shape == "cylinder" else cfg.box_size[2]) * 0.5
    rings = cfg.ring_elevations_deg
    poses = []
    for i in range(cfg.frames):
        ring = i * len(rings) // cfg.frames
        k = i - ring * cfg.frames // len(rings)
        per_ring = cfg.frames // len(rings)
        theta = 2 * np.pi * k / per_ring + ring * 0.4
        elev = math.radians(rings[ring]) + rng.normal(0, 0.02)
        dist = cfg.orbit_distance * (1 + rng.normal(0, 0.04))
        target = np.array([0.0, target_h, 0.0]) + rng.normal(0, 0.01, 3)
        eye = target + dist * np.array(
            [math.cos(elev) * math.cos(theta), math.sin(elev), math.cos(elev) * math.sin(theta)]
        )
        roll = -90.0 if cfg.portrait else 0.0
        poses.append(look_at(eye, target, up=(0, 1, 0), roll_deg=roll + rng.normal(0, 2.0)))
    return poses


# --------------------------------------------------------------------------------------------------------------
# Sensor simulation
# --------------------------------------------------------------------------------------------------------------


def _render_depth(
    T_scene_cam: FloatArray,
    depth_intr: Intrinsics,
    cfg: SynthConfig,
    rng: np.random.Generator,  # noqa: N803
) -> tuple[NDArray[np.uint16], NDArray[np.uint8]]:
    """Render ARCore-like raw depth (mm) and confidence for one camera pose."""
    sup = depth_intr.scaled(depth_intr.width * 2, depth_intr.height * 2)
    rays_cam = sup.pixel_rays().reshape(-1, 3)  # z = 1, so ray parameter == z-depth
    R, o = T_scene_cam[:3, :3], T_scene_cam[:3, 3]
    dirs = rays_cam @ R.T
    hits = _raycast(o, dirs, cfg)
    z = hits.t.copy()
    z[~np.isfinite(z) | (z > cfg.max_range)] = np.nan
    cos_inc = np.abs(np.einsum("ij,ij->i", hits.normal, dirs)) / np.linalg.norm(dirs, axis=1)
    h2, w2 = sup.height, sup.width
    zb = z.reshape(h2 // 2, 2, w2 // 2, 2).transpose(0, 2, 1, 3).reshape(h2 // 2, w2 // 2, 4)
    cb = cos_inc.reshape(h2 // 2, 2, w2 // 2, 2).transpose(0, 2, 1, 3).reshape(h2 // 2, w2 // 2, 4)
    valid = np.isfinite(zb)
    n_valid = valid.sum(axis=2)
    cnt = np.maximum(n_valid, 1)
    zmean = np.where(n_valid > 0, np.where(valid, zb, 0.0).sum(axis=2) / cnt, np.nan)
    spread = np.where(
        n_valid > 0, np.where(valid, zb, -np.inf).max(axis=2) - np.where(valid, zb, np.inf).min(axis=2), 0.0
    )
    cos_px = np.where(n_valid > 0, np.where(valid, cb, 0.0).sum(axis=2) / cnt, 1.0)

    sigma = (cfg.depth_noise_base + cfg.depth_noise_quad * np.nan_to_num(zmean) ** 2) / np.sqrt(
        np.clip(cos_px, 0.1, 1.0)
    )
    depth = zmean + rng.normal(0, 1, zmean.shape) * sigma
    conf = 255.0 * np.sqrt(np.clip(cos_px, 0, 1)) * np.exp(-np.nan_to_num(zmean) / 4.0)
    conf *= rng.uniform(0.9, 1.0, conf.shape)

    mixed = (n_valid > 0) & ((n_valid < 4) | (spread > 0.02))
    conf[mixed] = rng.uniform(10, 60, mixed.sum())

    outlier = rng.random(depth.shape) < cfg.outlier_fraction
    depth[outlier] += rng.uniform(-0.2, 0.2, outlier.sum())
    conf[outlier] = rng.uniform(0, 255, outlier.sum())

    if cfg.depth_mode == "smooth":
        # Smooth depth is spatially filtered and hole-filled: less noise, no dropouts or outliers, but edges
        # still smear across depth discontinuities and there is no confidence to flag them.
        depth = zmean + rng.normal(0, 1, zmean.shape) * sigma * 0.5
        hole = ~np.isfinite(depth) | (depth <= 0)
    else:
        hole = (rng.random(depth.shape) < cfg.hole_fraction) | ~np.isfinite(depth) | (depth <= 0)
    depth_mm = np.where(hole, 0, np.round(np.nan_to_num(depth) * 1000.0))
    conf[hole] = 0
    return np.clip(depth_mm, 0, 65535).astype(np.uint16), np.clip(conf, 0, 255).astype(np.uint8)


def _render_rgb(
    T_scene_cam: FloatArray,
    rgb_intr: Intrinsics,
    cfg: SynthConfig,
    rng: np.random.Generator,  # noqa: N803
) -> NDArray[np.uint8]:
    rays_cam = rgb_intr.pixel_rays().reshape(-1, 3)
    dirs = rays_cam @ T_scene_cam[:3, :3].T
    hits = _raycast(T_scene_cam[:3, 3], dirs, cfg)
    return _shade(hits, dirs, cfg, rng).reshape(rgb_intr.height, rgb_intr.width, 3)


def _perturb(T: FloatArray, trans: float, rot_deg: float, rng: np.random.Generator) -> FloatArray:  # noqa: N803
    if trans <= 0 and rot_deg <= 0:
        return T
    out = T.copy()
    out[:3, :3] = Rotation.from_rotvec(np.radians(rng.normal(0, rot_deg, 3))).as_matrix() @ T[:3, :3]
    out[:3, 3] += rng.normal(0, trans, 3)
    return out


# --------------------------------------------------------------------------------------------------------------
# Public API
# --------------------------------------------------------------------------------------------------------------


def ground_truth(cfg: SynthConfig, T_world_scene: FloatArray) -> dict[str, Any]:  # noqa: N803
    """True object dimensions (mm), volume (mL) and the support plane in world coordinates."""
    n_world = T_world_scene[:3, :3] @ np.array([0.0, 1.0, 0.0])
    p0 = T_world_scene[:3, 3]
    gt: dict[str, Any] = {
        "shape": cfg.shape,
        "support_plane_world": {"normal": n_world.tolist(), "d": float(-n_world @ p0)},
        "object_base_center_world": p0.tolist(),
    }
    if cfg.shape == "cylinder":
        r, h = cfg.cylinder_diameter / 2, cfg.cylinder_height
        gt.update(
            diameter_mm=cfg.cylinder_diameter * 1000,
            height_mm=h * 1000,
            volume_ml=math.pi * r * r * h * 1e6,
        )
    else:
        L, W, H = cfg.box_size
        gt.update(
            length_mm=L * 1000,
            width_mm=W * 1000,
            height_mm=H * 1000,
            volume_ml=L * W * H * 1e6,
            yaw_deg_in_scene=cfg.box_yaw_deg,
        )
    return gt


def synthesize(out_dir: Path | str, cfg: SynthConfig | None = None) -> Path:
    """Write a synthetic capture bundle to ``out_dir`` and return its path.

    Args:
        out_dir: Bundle folder to create (existing bundle files in it are overwritten).
        cfg: Scene, camera and sensor parameters; defaults to a 70 x 240 mm cylinder.
    """
    cfg = cfg or SynthConfig()
    if cfg.shape not in ("cylinder", "box"):
        raise ValueError(f"unknown shape {cfg.shape!r}")
    root = Path(out_dir)
    subdirs = ("rgb", "depth") if cfg.depth_mode == "smooth" else ("rgb", "depth", "confidence")
    for sub in subdirs:
        (root / sub).mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(cfg.seed)
    rgb_intr, depth_intr = _intrinsics(cfg)
    T_world_scene = np.eye(4)  # noqa: N806
    T_world_scene[:3, :3] = _rot_y(cfg.world_yaw_deg)
    T_world_scene[:3, 3] = cfg.world_offset

    t0 = 1_700_000_000_000
    dt = int(cfg.frame_interval_ms * 1e6)
    frames: list[Frame] = []
    prev: tuple[NDArray[np.uint16], NDArray[np.uint8] | None, int] | None = None
    poses = _orbit_poses(cfg, rng)
    smooth = cfg.depth_mode == "smooth"
    for i, T_scene_cam in enumerate(poses):  # noqa: N806
        name = f"{i:06d}"
        ts = t0 + i * dt
        rgb = _render_rgb(T_scene_cam, rgb_intr, cfg, rng)
        Image.fromarray(rgb).save(root / "rgb" / f"{name}.jpg", quality=cfg.jpeg_quality)

        T_true = T_world_scene @ T_scene_cam  # noqa: N806
        tracking = "PAUSED" if i in cfg.tracking_loss_frames else "TRACKING"
        if tracking == "TRACKING":
            T_rec = _perturb(T_true, cfg.pose_noise_trans, cfg.pose_noise_rot_deg, rng)  # noqa: N806
        else:  # pose during tracking loss is unreliable: drift it visibly
            T_rec = T_true.copy()  # noqa: N806
            T_rec[:3, 3] += np.array([0.05, -0.03, 0.04])

        depth_rel = conf_rel = None
        dts: int | None = None
        depth_mm: NDArray[np.uint16] | None = None
        conf: NDArray[np.uint8] | None = None
        repeated = bool(prev is not None and cfg.repeat_depth_every and i % cfg.repeat_depth_every == 0)
        if i == 0:
            pass  # depth is "not ready yet" on the first frame
        elif repeated:
            if cfg.repeat_depth_mode == "duplicate":  # writer saved ARCore's repeated image again
                assert prev is not None
                depth_mm, conf, dts = prev
        elif cfg.lag_depth_every and i % cfg.lag_depth_every == 0:
            # Depth computed for an earlier camera frame (not recorded): render it at that pose.
            lag = int(cfg.lag_depth_ms * 1e6)
            T_lag = interpolate_pose(ts - lag, ts - dt, poses[i - 1], ts, T_scene_cam)  # noqa: N806
            depth_mm, conf = _render_depth(T_lag, depth_intr, cfg, rng)
            dts = ts - lag
        else:
            depth_mm, conf = _render_depth(T_scene_cam, depth_intr, cfg, rng)
            dts = ts  # fresh depth carries the camera frame's timestamp
        if depth_mm is not None:
            if smooth:
                conf = None
            depth_rel = f"depth/{name}.png"
            write_png16(root / depth_rel, depth_mm)
            if conf is not None:
                conf_rel = f"confidence/{name}.png"
                write_png8(root / conf_rel, conf)
            if not repeated:
                prev = (depth_mm, conf, int(dts or ts))

        frames.append(
            Frame(
                index=i,
                timestamp_ns=ts,
                tracking_state=tracking,
                rgb=f"rgb/{name}.jpg",
                depth=depth_rel,
                confidence=conf_rel,
                depth_timestamp_ns=dts,
                intrinsics=rgb_intr,
                depth_intrinsics=depth_intr if depth_rel else None,
                T_world_camera=T_rec,
                pose_arcore=pose_to_arcore_dict(T_rec),
            )
        )

    created = datetime.now(timezone.utc).replace(microsecond=0)
    manifest = {
        "format": FORMAT_NAME,
        "version": "0.1",
        "capture_id": f"synth-{cfg.shape}-{cfg.seed}",
        "created_utc": created.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "app": {"name": "depthfold-synth", "version": __version__},
        "device": {"manufacturer": "depthfold", "model": "synthetic", "android_sdk": 0},
        "arcore": {"sdk_version": "synthetic", "depth_mode": cfg.depth_mode},
        "rgb": {"width": rgb_intr.width, "height": rgb_intr.height, "format": "jpeg"},
        "depth": {
            "width": depth_intr.width,
            "height": depth_intr.height,
            "units": "mm",
            "source": cfg.depth_mode,
            "has_confidence": not smooth,
        },
        "frame_count": len(frames),
        "notes": f"Synthetic {cfg.shape} generated by depthfold synth.",
    }
    write_bundle_files(root, manifest, frames)
    cfg_dict = asdict(cfg)
    gt = ground_truth(cfg, T_world_scene)
    (root / "ground_truth.json").write_text(json.dumps({"config": cfg_dict, **gt}, indent=2) + "\n")
    return root
