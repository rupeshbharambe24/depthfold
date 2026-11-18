"""Real-world measurements of an object standing on a support plane.

Pipeline (all steps are deterministic for a given ``seed``):

1. **Support plane**: RANSAC over the scan points, accepting only planes whose normal is within
   ``max_plane_tilt_deg`` of world up (+Y), refined by least squares on the inliers. If no such plane is found, or
   if many points lie below it (e.g. a lone object mesh), the lowest point along up is used as the base.
2. **Object isolation**: points more than ``min_height`` above the plane are grouped into connected clusters
   (fixed-radius neighbour graph); the largest cluster is the object.
3. **Height**: high quantile of the object's heights above the plane.
4. **Gravity-aligned oriented bounding box**: minimum-area rectangle of the footprint (rotating calipers on the
   2D convex hull), with robust extents along its axes; height from step 3.
5. **Shape**: ``auto`` compares circle and rectangle fits of the side points; ``cylinder`` fits the vertical axis
   position (axis assumed perpendicular to the support plane; tilt is reported as a diagnostic) and a
   radius-vs-height profile from horizontal slices.
6. **Volume**: from the object's mesh component when it is watertight, otherwise by integrating the radius
   profile (cylinder) or as length x width x height (box). The method is recorded.

Every value carries an approximate 1-sigma uncertainty composed of surface discretisation, model-fit residuals,
support-plane noise and an assumed relative scale uncertainty of the AR tracking (see :class:`MeasureConfig`).
"""

from __future__ import annotations

import math
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Literal

import numpy as np
import trimesh
from numpy.typing import NDArray
from scipy.optimize import least_squares
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import connected_components
from scipy.spatial import ConvexHull, cKDTree

from . import __version__
from .geometry import FloatArray, plane_basis

ShapeName = Literal["auto", "cylinder", "box"]


class MeasurementError(RuntimeError):
    """Raised when no object can be isolated or measured."""


@dataclass
class MeasureConfig:
    """Measurement parameters. Lengths in metres unless stated otherwise."""

    shape: ShapeName = "auto"
    up: tuple[float, float, float] = (0.0, 1.0, 0.0)
    max_plane_tilt_deg: float = 20.0
    plane_threshold: float | None = None  # default: max(3 mm, point spacing)
    ransac_iterations: int = 2000
    min_height: float | None = None  # default: max(5 mm, 3 x plane RMS, 1.5 x spacing)
    cluster_radius: float | None = None  # default: 2.5 x max(spacing, surface_resolution)
    slice_height: float = 0.005
    height_quantile: float = 0.999
    extent_quantile: float = 0.001
    scale_uncertainty: float = 0.01  # relative; ARCore metric scale is typically good to ~1 %
    surface_resolution: float | None = None  # TSDF voxel size if known; default: point spacing
    seed: int = 0


@dataclass
class Value:
    """A measured quantity with units, uncertainty and provenance of the number."""

    value: float
    unit: str
    uncertainty: float
    method: str
    components: dict[str, float] = field(default_factory=dict)

    def to_dict(self, digits: int = 2) -> dict[str, Any]:
        """JSON form; uncertainty is an approximate 1-sigma value in the same unit."""
        return {
            "value": round(float(self.value), digits),
            "unit": self.unit,
            "uncertainty": round(float(self.uncertainty), digits),
            "uncertainty_kind": "approx. 1-sigma",
            "method": self.method,
            "uncertainty_components": {k: round(float(v), digits + 1) for k, v in self.components.items()},
        }


def _combine(components: dict[str, float]) -> float:
    return float(math.sqrt(sum(v * v for v in components.values())))


@dataclass
class Plane:
    """Plane ``n . p + d = 0`` with unit normal pointing up."""

    normal: FloatArray
    d: float
    rms: float
    inliers: int
    method: str

    def height(self, p: FloatArray) -> FloatArray:
        """Signed distance of points above the plane."""
        return p @ self.normal + self.d


@dataclass
class MeasureResult:
    """Everything computed by :func:`measure_points`; serialised by :meth:`report`."""

    shape: str
    values: dict[str, Value]
    plane: Plane
    origin: FloatArray  # point on the plane under the object centre
    axes: FloatArray  # 3x3, rows: e_length, e_width, up (world)
    object_points: FloatArray
    object_mask: NDArray[np.bool_]
    outline_mm: FloatArray  # closed side-view polygon (x, h) in mm; half profile for cylinders
    footprint_mm: FloatArray  # top-view convex hull (x, y) in mm, in the box axes
    rect_mm: FloatArray  # 4x2 footprint rectangle corners in mm
    profile: list[dict[str, float]] = field(default_factory=list)
    details: dict[str, Any] = field(default_factory=dict)
    warnings: list[str] = field(default_factory=list)

    def report(self, provenance: dict[str, Any], config: MeasureConfig, files: dict[str, str]) -> dict[str, Any]:
        """Build the ``measurements.json`` measurement report."""
        return {
            "report": "depthfold measurement report",
            "schema_version": "0.1",
            "created_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "tool": {"name": "depthfold", "version": __version__, "command": "measure"},
            "provenance": provenance,
            "parameters": asdict(config),
            "coordinate_frame": "input world frame (ARCore world for bundles: metres, +Y up); report values in mm",
            "shape": self.shape,
            "measurements": {k: v.to_dict() for k, v in self.values.items()},
            "support_plane": {
                "normal_world": [round(float(x), 6) for x in self.plane.normal],
                "d_m": round(self.plane.d, 6),
                "rms_mm": round(self.plane.rms * 1000, 3),
                "inliers": self.plane.inliers,
                "tilt_from_up_deg": round(
                    math.degrees(math.acos(float(np.clip(self.plane.normal @ self.axes[2], -1, 1)))), 3
                ),
                "method": self.plane.method,
            },
            "bounding_box": {
                "center_world": [
                    round(float(x), 5) for x in self.origin + self.axes[2] * self.values["height"].value / 2000
                ],
                "axes_world": {
                    "length": [round(float(x), 6) for x in self.axes[0]],
                    "width": [round(float(x), 6) for x in self.axes[1]],
                    "height": [round(float(x), 6) for x in self.axes[2]],
                },
                "size_mm": [round(self.values[k].value, 2) for k in ("length", "width", "height")],
                "kind": "gravity-aligned (support-plane-normal) minimum-area oriented box",
            },
            "profile": self.profile,
            "details": self.details,
            "object_points": int(self.object_mask.sum()),
            "warnings": self.warnings,
            "files": files,
            "notes": [
                "Uncertainties are approximate 1-sigma estimates combining surface resolution, model-fit residuals, "
                "support-plane noise and an assumed relative tracking-scale uncertainty; they do not cover "
                "systematic sensor errors on transparent, shiny or very dark surfaces.",
            ],
        }


# --------------------------------------------------------------------------------------------------------------
# Building blocks
# --------------------------------------------------------------------------------------------------------------


def point_spacing(points: FloatArray, sample: int = 5000, seed: int = 0) -> float:
    """Typical sampling distance of a surface point set: median distance to the 4th nearest neighbour.

    The 4th neighbour (rather than the 1st) keeps the estimate meaningful for marching-cubes vertices, which can
    come in near-coincident pairs where the surface crosses a voxel corner.
    """
    rng = np.random.default_rng(seed)
    tree = cKDTree(points)
    idx = rng.choice(len(points), size=min(sample, len(points)), replace=False)
    d, _ = tree.query(points[idx], k=min(5, len(points)))
    return float(np.median(d[:, -1]))


def estimate_normals(points: FloatArray, k: int = 12) -> FloatArray:
    """Unoriented PCA normals from ``k`` nearest neighbours."""
    tree = cKDTree(points)
    _, nn = tree.query(points, k=min(k, len(points)))
    nb = points[nn] - points[nn].mean(axis=1, keepdims=True)
    cov = np.einsum("nki,nkj->nij", nb, nb)
    _, vecs = np.linalg.eigh(cov)
    return vecs[:, :, 0]


def fit_support_plane(
    points: FloatArray,
    up: FloatArray,
    threshold: float,
    max_tilt_deg: float,
    iterations: int,
    rng: np.random.Generator,
) -> Plane | None:
    """RANSAC for the dominant plane whose normal is within ``max_tilt_deg`` of ``up``.

    Returns ``None`` if no plane with at least 5 % of the points (and 50 points) is found.
    """
    n_pts = len(points)
    if n_pts < 3:
        return None
    score_idx = rng.choice(n_pts, size=min(n_pts, 40000), replace=False)
    sample = points[score_idx]
    cos_min = math.cos(math.radians(max_tilt_deg))
    best_count, best = -1, None
    batch = 128
    for _ in range(max(1, iterations // batch)):
        tri = points[rng.integers(0, n_pts, (batch, 3))]
        n = np.cross(tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0])
        norm = np.linalg.norm(n, axis=1)
        ok = norm > 1e-12
        n[ok] /= norm[ok, None]
        n[n @ up < 0] *= -1
        ok &= n @ up >= cos_min
        if not ok.any():
            continue
        n = n[ok]
        d = -np.einsum("ij,ij->i", n, tri[ok, 0])
        counts = (np.abs(sample @ n.T + d) < threshold).sum(axis=0)
        j = int(np.argmax(counts))
        if counts[j] > best_count:
            best_count, best = int(counts[j]), (n[j], float(d[j]))
    if best is None or best_count < max(50, 0.05 * len(sample)):
        return None
    n, d = best
    for _ in range(3):  # least-squares refinement on inliers
        inl = points[np.abs(points @ n + d) < threshold]
        c = inl.mean(axis=0)
        _, _, vt = np.linalg.svd(inl - c, full_matrices=False)
        n = vt[2] if vt[2] @ up > 0 else -vt[2]
        d = float(-n @ c)
    dist = points @ n + d
    inl = np.abs(dist) < threshold
    return Plane(
        normal=n,
        d=d,
        rms=float(np.sqrt(np.mean(dist[inl] ** 2))),
        inliers=int(inl.sum()),
        method=f"RANSAC ({iterations} hypotheses, normal within {max_tilt_deg} deg of up, "
        f"inlier threshold {threshold * 1000:.1f} mm) + least-squares refinement",
    )


def largest_cluster(
    points: FloatArray, radius: float, extra_edges: NDArray[np.int64] | None = None
) -> NDArray[np.bool_]:
    """Mask of the largest connected component of the ``radius``-neighbour graph.

    ``extra_edges`` (``(E, 2)`` indices into ``points``, e.g. mesh edges) are added to the graph, so meshes with
    uneven vertex density stay connected.
    """
    if len(points) == 0:
        return np.zeros(0, dtype=bool)
    pairs = cKDTree(points).query_pairs(radius, output_type="ndarray")
    if extra_edges is not None and len(extra_edges):
        pairs = np.vstack([pairs.reshape(-1, 2), extra_edges])
    n = len(points)
    graph = coo_matrix((np.ones(len(pairs)), (pairs[:, 0], pairs[:, 1])), shape=(n, n))
    _, labels = connected_components(graph, directed=False)
    return labels == np.bincount(labels).argmax()


def fit_circle(xy: FloatArray) -> tuple[FloatArray, float, float]:
    """Algebraic (Kasa) circle fit refined geometrically. Returns ``(centre, radius, rms_residual)``."""
    A = np.column_stack([xy, np.ones(len(xy))])
    b = (xy**2).sum(axis=1)
    sol, *_ = np.linalg.lstsq(A, b, rcond=None)
    c0 = sol[:2] / 2
    r0 = math.sqrt(max(sol[2] + c0 @ c0, 1e-12))

    def res(p: FloatArray) -> FloatArray:
        return np.linalg.norm(xy - p[:2], axis=1) - p[2]

    fit = least_squares(res, np.array([c0[0], c0[1], r0]), loss="soft_l1", f_scale=0.002)
    c, r = fit.x[:2], float(abs(fit.x[2]))
    return c, r, float(np.sqrt(np.mean(res(fit.x) ** 2)))


def min_area_rectangle(xy: FloatArray) -> tuple[float, FloatArray]:
    """Orientation of the minimum-area enclosing rectangle (rotating calipers over hull edges).

    Returns ``(angle, rotation)`` where ``rotation`` (2x2) maps points into the rectangle-aligned frame.
    """
    if len(xy) < 3 or np.linalg.matrix_rank(xy - xy.mean(axis=0), tol=1e-9) < 2:
        raise MeasurementError("object footprint is degenerate (fewer than three non-collinear points)")
    hull = xy[ConvexHull(xy).vertices]
    edges = np.diff(np.vstack([hull, hull[:1]]), axis=0)
    angles = np.unique(np.mod(np.arctan2(edges[:, 1], edges[:, 0]), np.pi / 2))
    best_area, best_angle = np.inf, 0.0
    for a in angles:
        c, s = math.cos(a), math.sin(a)
        rot = np.array([[c, s], [-s, c]])
        p = hull @ rot.T
        area = np.ptp(p[:, 0]) * np.ptp(p[:, 1])
        if area < best_area:
            best_area, best_angle = area, float(a)
    c, s = math.cos(best_angle), math.sin(best_angle)
    return best_angle, np.array([[c, s], [-s, c]])


def _robust_extent(x: FloatArray, q: float) -> tuple[float, float]:
    lo, hi = np.quantile(x, [q, 1 - q])
    return float(lo), float(hi)


def _center_by_slices(xy: FloatArray, slice_idx: NDArray[np.int64], c0: FloatArray, scale: float) -> FloatArray:
    """Fit an axis position shared by all slices, each slice with its own radius (variable projection)."""
    counts = np.bincount(slice_idx).astype(float)
    counts[counts == 0] = 1

    def res(c: FloatArray) -> FloatArray:
        d = np.linalg.norm(xy - c, axis=1)
        r = np.bincount(slice_idx, weights=d) / counts
        return d - r[slice_idx]

    return np.asarray(least_squares(res, c0, loss="soft_l1", f_scale=scale).x)


# --------------------------------------------------------------------------------------------------------------
# Main entry point
# --------------------------------------------------------------------------------------------------------------


def measure_points(
    points: FloatArray,
    normals: FloatArray | None = None,
    cfg: MeasureConfig | None = None,
    mesh: trimesh.Trimesh | None = None,
) -> MeasureResult:
    """Measure the object standing on the dominant up-facing plane of a scan.

    Args:
        points: ``(N, 3)`` points in metres (mesh vertices or a point cloud), world frame with ``cfg.up`` up.
        normals: Optional ``(N, 3)`` unit normals (estimated by PCA if missing).
        cfg: Parameters.
        mesh: Optional mesh whose vertices are ``points``; used for the watertight-volume check.
    """
    cfg = cfg or MeasureConfig()
    rng = np.random.default_rng(cfg.seed)
    pts = np.asarray(points, dtype=np.float64)
    if len(pts) < 100:
        raise MeasurementError(f"too few points to measure ({len(pts)})")
    up = np.asarray(cfg.up, dtype=np.float64)
    up /= np.linalg.norm(up)
    nrm = estimate_normals(pts) if normals is None else np.asarray(normals, dtype=np.float64)
    spacing = point_spacing(pts, seed=cfg.seed)
    resolution = cfg.surface_resolution or spacing
    warnings: list[str] = []

    # 1. Support plane --------------------------------------------------------------------------------------
    thr = cfg.plane_threshold or max(0.003, spacing)
    plane = fit_support_plane(pts, up, thr, cfg.max_plane_tilt_deg, cfg.ransac_iterations, rng)
    if plane is not None and np.mean(plane.height(pts) < -3 * thr) > 0.05:
        warnings.append("dominant horizontal plane has many points below it; not treated as a support plane")
        plane = None
    if plane is None:
        h_all = pts @ up
        base = float(np.quantile(h_all, 0.001))
        plane = Plane(up.copy(), -base, 0.0, 0, "no support plane found: lowest point along up used as base")
        warnings.append("no support plane detected; heights are measured from the lowest point of the object")

    # 2. Object isolation ----------------------------------------------------------------------------------
    h = plane.height(pts)
    min_h = cfg.min_height or max(0.005, 3 * plane.rms, 1.5 * spacing)
    above = np.flatnonzero(h > min_h)
    if len(above) < 50:
        raise MeasurementError("nothing found above the support plane")
    radius = cfg.cluster_radius or 2.5 * max(spacing, cfg.surface_resolution or 0.0)
    edges = None
    if mesh is not None and len(mesh.faces) and len(mesh.vertices) == len(pts):
        local = np.full(len(pts), -1)
        local[above] = np.arange(len(above))
        e = local[mesh.edges_unique]
        edges = e[(e >= 0).all(axis=1)]
    cl = largest_cluster(pts[above], radius, edges)
    obj_idx = above[cl]
    mask = np.zeros(len(pts), dtype=bool)
    mask[obj_idx] = True
    P, H_pts, N = pts[obj_idx], h[obj_idx], nrm[obj_idx]
    if len(P) < 50:
        raise MeasurementError("object cluster too small to measure")

    # Plane coordinate frame centred under the object.
    n_up = plane.normal
    e1, e2 = plane_basis(n_up)
    centroid = P.mean(axis=0)
    origin = centroid - n_up * (centroid @ n_up + plane.d)
    rel = P - origin
    q = np.column_stack([rel @ e1, rel @ e2])

    # 3. Height ----------------------------------------------------------------------------------------------
    # Flat tops (caps, lids, box tops) are measured as the median height of the up-facing points near the top;
    # other tops (domes, spouts) fall back to a high quantile of all heights.
    h_top = float(np.quantile(H_pts, cfg.height_quantile))
    cap = (N @ n_up > 0.9) & (H_pts > h_top - 2 * resolution)
    if cap.sum() >= 20 and np.std(H_pts[cap]) < resolution:
        height = float(np.median(H_pts[cap]))
        top_spread = float(np.std(H_pts[cap]) / math.sqrt(cap.sum()) + np.std(H_pts[cap]) / 2)
        height_method = f"median height above the support plane of {int(cap.sum())} up-facing points on the flat top"
    else:
        height = h_top
        near = H_pts[H_pts > h_top - 2 * resolution]
        top_spread = float(np.std(near)) if len(near) > 3 else resolution
        height_method = f"{cfg.height_quantile:.3f} quantile of object point heights above the support plane"

    # 4. Footprint orientation and robust extents -------------------------------------------------------------
    # Side points away from the top and bottom: TSDF fusion leaves small outward "lips" just below convex top
    # edges (voxels within the truncation band behind the top surface), which extreme quantiles would pick up.
    side = np.abs(N @ n_up) < 0.9
    band = side & (H_pts > 0.15 * height) & (H_pts < 0.85 * height)
    if band.sum() < 30:
        band = side if side.sum() >= 30 else np.ones(len(P), dtype=bool)
    _, rot = min_area_rectangle(q)
    qr = q @ rot.T
    lo0, hi0 = _robust_extent(qr[band, 0], cfg.extent_quantile)
    lo1, hi1 = _robust_extent(qr[band, 1], cfg.extent_quantile)
    if hi1 - lo1 > hi0 - lo0:  # make axis 0 the length
        rot = np.array([rot[1], -rot[0]])
        qr = q @ rot.T
        lo0, hi0, lo1, hi1 = lo1, hi1, -hi0, -lo0
    ext_method = (
        f"gravity-aligned minimum-area rectangle; extent between the {cfg.extent_quantile:.3f} and "
        f"{1 - cfg.extent_quantile:.3f} quantiles of side points at 15-85 % of height"
    )

    # 5. Shape -------------------------------------------------------------------------------------------------
    c_tmp = np.array([(lo0 + hi0) / 2, (lo1 + hi1) / 2])
    _, r_c, rms_circle = fit_circle(qr[band] - c_tmp)
    a, b = (hi0 - lo0) / 2, (hi1 - lo1) / 2
    qb = qr[band] - c_tmp
    rms_rect = float(np.sqrt(np.mean(np.minimum(np.abs(a - np.abs(qb[:, 0])), np.abs(b - np.abs(qb[:, 1]))) ** 2)))
    shape = cfg.shape
    if shape == "auto":
        shape = "cylinder" if rms_circle <= rms_rect else "box"
    if shape == "box":
        # Planar faces: use the median position of points whose normal faces each side.
        nr = np.column_stack([N @ (rot[0, 0] * e1 + rot[0, 1] * e2), N @ (rot[1, 0] * e1 + rot[1, 1] * e2)])
        faces = []
        for ax in (0, 1):
            on_face = side & (np.abs(nr[:, ax]) > 0.9)
            mid = (lo0 + hi0) / 2 if ax == 0 else (lo1 + hi1) / 2
            neg = on_face & (qr[:, ax] < mid)
            pos = on_face & (qr[:, ax] >= mid)
            faces.append((neg.sum(), pos.sum()))
            if neg.sum() >= 20 and pos.sum() >= 20:
                lo, hi = float(np.median(qr[neg, ax])), float(np.median(qr[pos, ax]))
                if ax == 0:
                    lo0, hi0 = lo, hi
                else:
                    lo1, hi1 = lo, hi
        if all(min(f) >= 20 for f in faces):
            ext_method = (
                "gravity-aligned minimum-area rectangle; distance between the median positions of opposite face "
                "points (normal within 25 deg of the axis)"
            )
    length, width = hi0 - lo0, hi1 - lo1
    centre2 = np.array([(lo0 + hi0) / 2, (lo1 + hi1) / 2])
    e_len = rot[0, 0] * e1 + rot[0, 1] * e2
    e_wid = rot[1, 0] * e1 + rot[1, 1] * e2
    origin = origin + centre2[0] * e_len + centre2[1] * e_wid
    qr = qr - centre2
    axes = np.stack([e_len, e_wid, n_up])
    details: dict[str, Any] = {
        "point_spacing_mm": round(spacing * 1000, 3),
        "surface_resolution_mm": round(resolution * 1000, 3),
        "min_height_above_plane_mm": round(min_h * 1000, 2),
        "cluster_radius_mm": round(radius * 1000, 2),
        "shape_selection": {
            "requested": cfg.shape,
            "selected": shape,
            "circle_fit_rms_mm": round(rms_circle * 1000, 3),
            "rectangle_fit_rms_mm": round(rms_rect * 1000, 3),
            "rule": "auto picks the model with the smaller RMS residual of side points (15-85 % of height)",
        },
    }

    u_res = resolution / 4  # marching-cubes surface discretisation
    s = cfg.scale_uncertainty
    surf = rms_circle if shape == "cylinder" else rms_rect
    values: dict[str, Value] = {}
    comp_h = {
        "surface_resolution": u_res * 1000,
        "plane_rms": plane.rms * 1000,
        "top_spread": top_spread * 1000,
        "scale": s * height * 1000,
    }
    values["height"] = Value(
        height * 1000,
        "mm",
        _combine(comp_h),
        height_method,
        comp_h,
    )
    for key, val in (("length", length), ("width", width)):
        comp = {
            "surface_resolution": math.sqrt(2) * u_res * 1000,
            "fit_residual": math.sqrt(2) * surf * 1000,
            "scale": s * val * 1000,
        }
        values[key] = Value(
            val * 1000,
            "mm",
            _combine(comp),
            ext_method,
            comp,
        )

    footprint = qr[ConvexHull(qr).vertices] * 1000
    rect = np.array([[-1, -1], [1, -1], [1, 1], [-1, 1]]) * np.array([length, width]) / 2 * 1000
    profile: list[dict[str, float]] = []

    edges = np.arange(min_h, height + cfg.slice_height, cfg.slice_height)
    if shape == "cylinder":
        sp = side & (H_pts <= height)
        k = np.clip(np.digitize(H_pts[sp], edges) - 1, 0, len(edges) - 2)
        centre = _center_by_slices(qr[sp], k, np.zeros(2), max(spacing, 0.001))
        d = np.linalg.norm(qr[sp] - centre, axis=1)
        slice_r, slice_h, slice_n, slice_centres = [], [], [], []
        for i in range(len(edges) - 1):
            m = k == i
            if m.sum() < 8:
                continue
            slice_r.append(float(np.median(d[m])))
            slice_h.append(float((edges[i] + min(edges[i + 1], height)) / 2))
            slice_n.append(int(m.sum()))
            if m.sum() >= 20:
                c_i, _, _ = fit_circle(qr[sp][m])
                slice_centres.append((slice_h[-1], c_i[0], c_i[1]))
        if len(slice_r) < 3:
            raise MeasurementError("too few side points for a cylinder profile")
        r_arr, h_arr = np.array(slice_r), np.array(slice_h)
        body = (h_arr > 0.1 * height) & (h_arr < 0.9 * height)
        if body.sum() < 2:
            body = np.ones(len(r_arr), dtype=bool)
        r_body = float(np.median(r_arr[body]))
        mad = float(1.4826 * np.median(np.abs(r_arr[body] - r_body)))
        resid = d - np.interp(H_pts[sp], h_arr, r_arr)
        rms_fit = float(np.sqrt(np.mean(resid**2)))
        comp_d = {
            "surface_resolution": 2 * u_res * 1000,
            "slice_spread": 2 * mad / math.sqrt(body.sum()) * 1000,
            "fit_residual": 2 * rms_fit / math.sqrt(max(1, len(r_arr))) * 1000,
            "scale": s * 2 * r_body * 1000,
        }
        values["diameter"] = Value(
            2 * r_body * 1000,
            "mm",
            _combine(comp_d),
            "2 x median slice radius over 10-90 % of height; axis perpendicular to the support plane, position "
            "fitted by least squares with one radius per slice",
            comp_d,
        )
        i_max = int(np.flatnonzero(body)[np.argmax(r_arr[body])])
        comp_m = {
            "surface_resolution": 2 * u_res * 1000,
            "slice_noise": 2 * mad * 1000,
            "scale": s * 2 * r_arr[i_max] * 1000,
        }
        values["max_diameter"] = Value(
            2 * r_arr[i_max] * 1000,
            "mm",
            _combine(comp_m),
            f"2 x largest slice radius over 10-90 % of height (slice at {h_arr[i_max] * 1000:.0f} mm)",
            comp_m,
        )
        # Axis tilt diagnostic from per-slice circle centres.
        tilt = None
        if len(slice_centres) >= 4:
            sc = np.array(slice_centres)
            gx = np.polyfit(sc[:, 0], sc[:, 1], 1)[0]
            gy = np.polyfit(sc[:, 0], sc[:, 2], 1)[0]
            tilt = math.degrees(math.atan(math.hypot(gx, gy)))
        axis_point = origin + centre[0] * e_len + centre[1] * e_wid
        details["cylinder"] = {
            "axis_point_world": [round(float(x), 5) for x in axis_point],
            "axis_direction_world": [round(float(x), 6) for x in n_up],
            "axis_offset_from_box_centre_mm": [round(float(x) * 1000, 3) for x in centre],
            "axis_tilt_deg_diagnostic": None if tilt is None else round(tilt, 3),
            "side_fit_rms_mm": round(rms_fit * 1000, 3),
            "slice_height_mm": cfg.slice_height * 1000,
        }
        if tilt is not None and tilt > 3:
            warnings.append(f"per-slice centres suggest the axis is tilted by {tilt:.1f} deg; values assume vertical")
        profile = [
            {"height_mm": round(hh * 1000, 2), "radius_mm": round(rr * 1000, 3), "points": nn}
            for hh, rr, nn in zip(h_arr, r_arr, slice_n, strict=True)
        ]
        # Half profile, closed along the axis (revolve-ready): base -> side -> top -> axis.
        outline = [(0.0, 0.0), (r_arr[0], 0.0)]
        outline += list(zip(r_arr, h_arr, strict=True))
        outline += [(r_arr[-1], height), (0.0, height)]
        outline_mm = np.array(outline) * 1000
        # Volume by integrating pi r(h)^2 dh, extending the lowest slice to the plane.
        hh = np.concatenate([[0.0], h_arr, [height]])
        rr = np.concatenate([[r_arr[0]], r_arr, [r_arr[-1]]])
        vol_profile = float(np.trapezoid(np.pi * rr**2, hh))
        vol_method = "integration of pi r(h)^2 over the slice radius profile (lowest slice extended to the plane)"
        u_vol_rel = math.sqrt(
            (2 * values["diameter"].uncertainty / values["diameter"].value) ** 2
            + (values["height"].uncertainty / values["height"].value) ** 2
        )
    else:
        # Front silhouette along the length axis, per height slice.
        k = np.clip(np.digitize(H_pts, edges) - 1, 0, len(edges) - 2)
        right, left = [], []
        for i in range(len(edges) - 1):
            m = k == i
            if m.sum() < 5:
                continue
            hc = (edges[i] + min(edges[i + 1], height)) / 2
            lo, hi = _robust_extent(qr[m, 0], 0.01)
            right.append((hi, hc))
            left.append((lo, hc))
        outline_mm = (
            np.array(
                [(left[0][0], 0.0), (right[0][0], 0.0)]
                + right
                + [(right[-1][0], height), (left[-1][0], height)]
                + left[::-1]
            )
            * 1000
        )
        vol_profile = length * width * height
        vol_method = "box model: length x width x height"
        u_vol_rel = math.sqrt(
            sum((values[x].uncertainty / values[x].value) ** 2 for x in ("length", "width", "height"))
        )

    # 6. Volume ------------------------------------------------------------------------------------------------
    volume, method = vol_profile, vol_method
    if mesh is not None and len(mesh.faces):
        comp = _component_containing(mesh, obj_idx)
        if comp is not None and comp.is_watertight and comp.is_volume:
            volume, method = abs(float(comp.volume)), "watertight mesh volume (divergence theorem)"
            u_vol_rel = math.sqrt((3 * u_res / max(min(length, width, height), 1e-6)) ** 2 + (3 * s) ** 2)
        else:
            details["volume_note"] = "object mesh is not watertight (open at the support plane); model volume used"
    values["volume"] = Value(volume * 1e6, "mL", volume * 1e6 * u_vol_rel, method, {"relative_1sigma": u_vol_rel})
    return MeasureResult(
        shape=shape,
        values=values,
        plane=plane,
        origin=origin,
        axes=axes,
        object_points=P,
        object_mask=mask,
        outline_mm=outline_mm,
        footprint_mm=footprint,
        rect_mm=rect,
        profile=profile,
        details=details,
        warnings=warnings,
    )


def _component_containing(mesh: trimesh.Trimesh, vertex_idx: NDArray[np.int64]) -> trimesh.Trimesh | None:
    """The connected mesh component that contains most of the given vertices."""
    labels = trimesh.graph.connected_component_labels(mesh.face_adjacency, node_count=len(mesh.faces))
    vert_label = np.full(len(mesh.vertices), -1)
    vert_label[mesh.faces.ravel()] = np.repeat(labels, 3)
    obj_labels = vert_label[vertex_idx]
    obj_labels = obj_labels[obj_labels >= 0]
    if len(obj_labels) == 0:
        return None
    lab = np.bincount(obj_labels).argmax()
    sub = mesh.submesh([np.flatnonzero(labels == lab)], append=True)
    return sub if isinstance(sub, trimesh.Trimesh) else None


# --------------------------------------------------------------------------------------------------------------
# File-level entry point
# --------------------------------------------------------------------------------------------------------------

MESH_SUFFIXES = (".ply", ".obj", ".glb", ".gltf", ".stl", ".off")


def is_bundle_path(path: Path) -> bool:
    """Whether ``path`` looks like a capture bundle (folder with ``bundle.json`` or a ``.zip``)."""
    return (path.is_dir() and (path / "bundle.json").is_file()) or path.suffix.lower() == ".zip"


def load_geometry(path: Path, units: str = "auto") -> tuple[FloatArray, FloatArray | None, trimesh.Trimesh | None]:
    """Load a mesh or point cloud file as ``(points_m, normals, mesh)``.

    Args:
        path: ``.ply``/``.obj``/``.glb``/``.gltf``/``.stl``/``.off`` file.
        units: ``"m"``, ``"mm"`` or ``"auto"`` (``.stl`` is assumed to be in mm, everything else in metres).
    """
    loaded = trimesh.load(str(path), process=False)
    if isinstance(loaded, trimesh.Scene):
        loaded = loaded.to_mesh() if hasattr(loaded, "to_mesh") else loaded.dump(concatenate=True)
    scale = {"m": 1.0, "mm": 0.001}.get(units, 0.001 if path.suffix.lower() == ".stl" else 1.0)
    if isinstance(loaded, trimesh.Trimesh) and len(loaded.faces):
        mesh = loaded.copy()
        mesh.merge_vertices()  # STL and some OBJ files store unshared vertices per triangle
        mesh.apply_scale(scale)
        return np.asarray(mesh.vertices, dtype=np.float64), np.asarray(mesh.vertex_normals), mesh
    if isinstance(loaded, trimesh.PointCloud | trimesh.Trimesh):
        return np.asarray(loaded.vertices, dtype=np.float64) * scale, None, None
    raise MeasurementError(f"{path}: unsupported geometry type {type(loaded).__name__}")


def run_measure(
    path: Path | str,
    out_dir: Path | str,
    cfg: MeasureConfig | None = None,
    fuse_cfg: Any = None,
    units: str = "auto",
) -> dict[str, Any]:
    """Measure a bundle (fused on the fly) or a mesh/point-cloud file and write the outputs.

    Writes ``measurements.json``, ``profile.svg``, ``profile.dxf`` and ``object.ply`` (the isolated object) to
    ``out_dir`` and returns the report dictionary.
    """
    import json

    from .drawing import write_profile_dxf, write_profile_svg
    from .fuse import FuseConfig, fuse_bundle, o3d_to_trimesh

    cfg = cfg or MeasureConfig()
    src = Path(path)
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    provenance: dict[str, Any] = {"input": str(src)}
    if is_bundle_path(src):
        from .bundle import Bundle

        fcfg = fuse_cfg or FuseConfig()
        with Bundle.open(src) as bundle:
            fused = fuse_bundle(bundle, fcfg)
        mesh = o3d_to_trimesh(fused.mesh)
        points, normals = np.asarray(mesh.vertices), np.asarray(fused.mesh.vertex_normals)
        if cfg.surface_resolution is None:
            cfg.surface_resolution = fcfg.voxel
        fr = fused.report()
        provenance.update(
            input_kind="bundle",
            **fr["provenance"],
            frames=fr["frames"],
            frames_used=fr["frames_used"],
            fusion={"method": "TSDF (Open3D VoxelBlockGrid) + marching cubes", **fr["config"]},
        )
    else:
        points, normals, mesh = load_geometry(src, units)
        provenance.update(input_kind="mesh" if mesh is not None else "point_cloud", units=units)
        fuse_json = src.parent / "fuse.json"
        if fuse_json.is_file():
            try:
                fr = json.loads(fuse_json.read_text())
                provenance.update(
                    **fr.get("provenance", {}),
                    frames=fr.get("frames"),
                    frames_used=fr.get("frames_used"),
                    fusion={"method": "TSDF (Open3D VoxelBlockGrid) + marching cubes", **fr.get("config", {})},
                    fuse_report=str(fuse_json),
                )
                if cfg.surface_resolution is None and fr.get("config", {}).get("voxel"):
                    cfg.surface_resolution = float(fr["config"]["voxel"])
            except (OSError, ValueError):
                pass

    res = measure_points(points, normals, cfg, mesh)
    files = {
        "report": "measurements.json",
        "profile_svg": "profile.svg",
        "profile_dxf": "profile.dxf",
        "object": "object.ply",
    }
    title = f"{provenance.get('capture_id') or src.name} - {res.shape}"
    write_profile_svg(res, out / files["profile_svg"], title=title)
    write_profile_dxf(res, out / files["profile_dxf"])
    if mesh is not None:
        keep = np.flatnonzero(res.object_mask[mesh.faces].all(axis=1))
        obj = mesh.submesh([keep], append=True) if len(keep) else trimesh.PointCloud(res.object_points)
    else:
        obj = trimesh.PointCloud(res.object_points)
    obj.export(str(out / files["object"]))
    report = res.report(provenance, cfg, files)
    (out / files["report"]).write_text(json.dumps(report, indent=2) + "\n")
    return report
