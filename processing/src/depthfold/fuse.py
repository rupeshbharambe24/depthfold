"""TSDF fusion of bundle depth into a coloured mesh and point cloud.

Each usable depth image (tracking pose, fresh depth; see :meth:`depthfold.bundle.Bundle.depth_samples`) is
confidence- and range-filtered and integrated into an Open3D sparse TSDF (:class:`open3d.t.geometry.VoxelBlockGrid`,
the tensor-API successor of ``ScalableTSDFVolume``) using the depth intrinsics and ``T_world_camera``. Voxel
colours come from projecting each voxel into the RGB image with the RGB intrinsics, so the depth and RGB images
may differ in resolution, field of view and aspect ratio. The mesh is extracted with marching cubes and lightly
cleaned (small disconnected pieces removed).

Note: the legacy ``open3d.pipelines.integration.ScalableTSDFVolume`` returns empty meshes with Open3D 0.20 on
macOS arm64, which is why the tensor API is used.

All geometry stays in the bundle's world frame (ARCore world, metres, +Y up), except the STL export, which is
written in millimetres by default because that is what slicers and CAD tools assume.
"""

from __future__ import annotations

import json
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

import numpy as np
import open3d as o3d
import open3d.core as o3c
import trimesh
from numpy.typing import NDArray
from PIL import Image

from . import __version__
from .bundle import Bundle, DepthSelection
from .geometry import Intrinsics, invert_rigid


class FusionError(RuntimeError):
    """Raised when a bundle cannot be fused (e.g. no usable depth)."""


@dataclass
class FuseConfig:
    """Fusion parameters. Lengths are in metres."""

    voxel: float = 0.005
    sdf_trunc: float | None = None  # default: 3 voxels
    min_confidence: int = 128
    min_depth: float = 0.10
    max_depth: float = 2.0
    max_depth_age_ms: float = 1.0
    repair_stale_pose: bool = True
    stride: int = 1
    min_component_triangles: int = 200
    min_weight: float = 3.0  # voxels observed by fewer frames are not meshed
    color_max_width: int | None = 640
    block_count: int = 40000
    stl_units: str = "mm"

    @property
    def trunc(self) -> float:
        """Effective TSDF truncation distance."""
        return self.sdf_trunc if self.sdf_trunc is not None else 3.0 * self.voxel


@dataclass
class FuseResult:
    """In-memory result of :func:`fuse_bundle`."""

    mesh: o3d.geometry.TriangleMesh
    points: o3d.geometry.PointCloud
    selection: DepthSelection
    config: FuseConfig
    provenance: dict[str, Any]
    stats: dict[str, Any] = field(default_factory=dict)

    def report(self) -> dict[str, Any]:
        """Everything worth recording in ``fuse.json``."""
        return {
            "tool": {"name": "depthfold", "version": __version__, "command": "fuse"},
            "provenance": self.provenance,
            "frames": self.selection.summary(),
            "frames_used": [s.frame.index for s in self.selection.samples],
            "config": asdict(self.config) | {"sdf_trunc_effective": self.config.trunc},
            "stats": self.stats,
            "units": {"mesh.ply/obj/glb, points.ply": "m", "mesh.stl": self.config.stl_units},
            "frame": "ARCore world (right-handed, +Y up)",
        }


def filter_depth(
    depth_m: NDArray[np.float32],
    confidence: NDArray[np.uint8] | None,
    min_confidence: int,
    min_depth: float,
    max_depth: float,
) -> NDArray[np.float32]:
    """Zero out depth pixels below ``min_confidence`` or outside ``[min_depth, max_depth]`` metres.

    Frames without a confidence image (``smooth`` depth mode) are only range-filtered.
    """
    d = depth_m.astype(np.float32, copy=True)
    bad = (d < min_depth) | (d > max_depth) | ~np.isfinite(d)
    if confidence is not None:
        if confidence.shape != d.shape:
            raise ValueError(f"confidence shape {confidence.shape} != depth shape {d.shape}")
        bad |= confidence < min_confidence
    d[bad] = 0.0
    return d


def color_canvas(
    rgb: NDArray[np.uint8], rgb_intr: Intrinsics, depth_intr: Intrinsics, max_width: int | None = 640
) -> tuple[NDArray[np.float32], Intrinsics]:
    """Prepare the colour image used to colour TSDF voxels.

    The image is optionally downscaled to ``max_width`` (colour detail beyond the voxel size only aliases), then
    padded with replicated edge pixels so that it covers the whole depth field of view. Without the padding,
    voxels seen by depth pixels outside the RGB image would be blended with black.

    Returns:
        ``(image, intrinsics)``: a float32 ``(H, W, 3)`` image in ``[0, 1]`` and its intrinsics.
    """
    if rgb.shape[:2] != (rgb_intr.height, rgb_intr.width):
        rgb_intr = rgb_intr.scaled(rgb.shape[1], rgb.shape[0])
    if max_width and rgb_intr.width > max_width:
        h = round(rgb_intr.height * max_width / rgb_intr.width)
        rgb = np.asarray(Image.fromarray(rgb).resize((max_width, h), Image.Resampling.BOX))
        rgb_intr = rgb_intr.scaled(max_width, h)
    # Where do the corners of the depth image land on the RGB image plane?
    corners = np.array([[0, 0], [depth_intr.width, 0], [0, depth_intr.height], [depth_intr.width, depth_intr.height]])
    x = (corners[:, 0] - depth_intr.cx) / depth_intr.fx
    y = (corners[:, 1] - depth_intr.cy) / depth_intr.fy
    u = rgb_intr.fx * x + rgb_intr.cx
    v = rgb_intr.fy * y + rgb_intr.cy
    cap_x, cap_y = rgb_intr.width // 2, rgb_intr.height // 2
    left = int(np.clip(np.ceil(-u.min()), 0, cap_x)) + 1
    right = int(np.clip(np.ceil(u.max() - rgb_intr.width), 0, cap_x)) + 1
    top = int(np.clip(np.ceil(-v.min()), 0, cap_y)) + 1
    bottom = int(np.clip(np.ceil(v.max() - rgb_intr.height), 0, cap_y)) + 1
    padded = np.pad(rgb, ((top, bottom), (left, right), (0, 0)), mode="edge")
    intr = Intrinsics(rgb_intr.fx, rgb_intr.fy, rgb_intr.cx + left, rgb_intr.cy + top, padded.shape[1], padded.shape[0])
    return padded.astype(np.float32) / 255.0, intr


def _k_tensor(intr: Intrinsics) -> o3c.Tensor:
    # Open3D's tensor TSDF kernels look up pixel floor(u), floor(v), i.e. pixel i covers [i, i + 1): the same
    # corner convention as the bundle, so K is passed unchanged (verified empirically against Open3D 0.20).
    return o3c.Tensor(intr.K, o3c.float64)


def clean_mesh(mesh: o3d.geometry.TriangleMesh, min_component_triangles: int) -> o3d.geometry.TriangleMesh:
    """Remove degenerate faces and connected components smaller than ``min_component_triangles``."""
    mesh.remove_degenerate_triangles()
    mesh.remove_duplicated_triangles()
    mesh.remove_duplicated_vertices()
    mesh.remove_non_manifold_edges()
    if len(mesh.triangles) and min_component_triangles > 0:
        labels, counts, _ = mesh.cluster_connected_triangles()
        labels = np.asarray(labels)
        counts = np.asarray(counts)
        mesh.remove_triangles_by_mask((counts[labels] < min_component_triangles).tolist())
    mesh.remove_unreferenced_vertices()
    mesh.compute_vertex_normals()
    return mesh


def fuse_bundle(bundle: Bundle, cfg: FuseConfig | None = None) -> FuseResult:
    """Fuse all usable depth frames of ``bundle`` into a TSDF and extract a mesh and point cloud.

    Raises:
        FusionError: if the bundle has no usable depth or the result is empty.
    """
    cfg = cfg or FuseConfig()
    t_start = time.perf_counter()
    sel = bundle.depth_samples(
        max_depth_age_ms=cfg.max_depth_age_ms, stride=cfg.stride, repair_stale_pose=cfg.repair_stale_pose
    )
    if not sel.samples:
        raise FusionError(
            f"no usable depth frames ({sel.summary()}); check tracking_state and depth_timestamp_ns, "
            "or raise --max-depth-age-ms"
        )
    device = o3c.Device("CPU:0")
    vbg = o3d.t.geometry.VoxelBlockGrid(
        attr_names=("tsdf", "weight", "color"),
        attr_dtypes=(o3c.float32, o3c.float32, o3c.float32),
        attr_channels=((1), (1), (3)),
        voxel_size=cfg.voxel,
        block_resolution=16,
        block_count=cfg.block_count,
        device=device,
    )
    trunc_mult = cfg.trunc / cfg.voxel
    valid_px: list[float] = []
    integrated = 0
    for s in sel.samples:
        f = s.frame
        assert f.depth_intrinsics is not None
        depth = bundle.read_depth_m(f)
        if depth.shape != (f.depth_intrinsics.height, f.depth_intrinsics.width):
            raise FusionError(f"frame {f.index}: depth size {depth.shape} does not match depth_intrinsics")
        conf = bundle.read_confidence(f)
        depth = filter_depth(depth, conf, cfg.min_confidence, cfg.min_depth, cfg.max_depth)
        valid_px.append(float((depth > 0).mean()))
        if not (depth > 0).any():
            continue
        integrated += 1
        colour, colour_intr = color_canvas(bundle.read_rgb(f), f.intrinsics, f.depth_intrinsics, cfg.color_max_width)
        depth_t = o3d.t.geometry.Image(o3c.Tensor(np.ascontiguousarray(depth)))
        colour_t = o3d.t.geometry.Image(o3c.Tensor(np.ascontiguousarray(colour)))
        k_depth = _k_tensor(f.depth_intrinsics)
        extrinsic = o3c.Tensor(invert_rigid(s.T_world_camera), o3c.float64)
        blocks = vbg.compute_unique_block_coordinates(depth_t, k_depth, extrinsic, 1.0, cfg.max_depth, trunc_mult)
        vbg.integrate(
            blocks, depth_t, colour_t, k_depth, _k_tensor(colour_intr), extrinsic, 1.0, cfg.max_depth, trunc_mult
        )

    if integrated == 0:
        raise FusionError(
            "every depth pixel was removed by the confidence/range filters; lower --min-confidence or widen "
            "--min-depth/--max-depth"
        )
    mesh = vbg.extract_triangle_mesh(weight_threshold=cfg.min_weight).to_legacy()
    mesh = clean_mesh(mesh, cfg.min_component_triangles)
    points = vbg.extract_point_cloud(weight_threshold=cfg.min_weight).to_legacy()
    if len(mesh.triangles) == 0:
        raise FusionError("fusion produced an empty mesh; check depth range and confidence thresholds")
    bbox = mesh.get_axis_aligned_bounding_box()
    stats = {
        "vertices": len(mesh.vertices),
        "triangles": len(mesh.triangles),
        "points": len(points.points),
        "depth_frames_integrated": integrated,
        "mean_valid_depth_fraction_after_filter": round(float(np.mean(valid_px)), 4),
        "bbox_min_m": np.asarray(bbox.min_bound).round(4).tolist(),
        "bbox_max_m": np.asarray(bbox.max_bound).round(4).tolist(),
        "watertight": bool(mesh.is_watertight()),
        "seconds": round(time.perf_counter() - t_start, 2),
    }
    return FuseResult(mesh, points, sel, cfg, bundle.provenance(), stats)


def o3d_to_trimesh(mesh: o3d.geometry.TriangleMesh) -> trimesh.Trimesh:
    """Convert an Open3D mesh (with optional vertex colours) to :class:`trimesh.Trimesh`."""
    colours = None
    if mesh.has_vertex_colors():
        c = np.clip(np.asarray(mesh.vertex_colors) * 255.0, 0, 255).astype(np.uint8)
        colours = np.concatenate([c, np.full((len(c), 1), 255, np.uint8)], axis=1)
    return trimesh.Trimesh(
        vertices=np.asarray(mesh.vertices),
        faces=np.asarray(mesh.triangles),
        vertex_colors=colours,
        process=False,
    )


def write_mesh_exports(
    mesh: o3d.geometry.TriangleMesh, out_dir: Path, stem: str = "mesh", stl_units: str = "mm"
) -> dict[str, Path]:
    """Write ``<stem>.ply/.obj/.glb/.stl``; returns the written paths keyed by format."""
    out_dir.mkdir(parents=True, exist_ok=True)
    paths = {ext: out_dir / f"{stem}.{ext}" for ext in ("ply", "obj", "glb", "stl")}
    o3d.io.write_triangle_mesh(str(paths["ply"]), mesh, write_ascii=False, write_vertex_normals=True)
    tm = o3d_to_trimesh(mesh)
    paths["obj"].write_text(trimesh.exchange.obj.export_obj(tm, include_color=True, include_normals=True))
    paths["glb"].write_bytes(tm.export(file_type="glb"))
    stl = tm.copy()
    if stl_units == "mm":
        stl.apply_scale(1000.0)
    elif stl_units != "m":
        raise ValueError(f"stl_units must be 'mm' or 'm', got {stl_units!r}")
    paths["stl"].write_bytes(stl.export(file_type="stl"))
    return paths


def write_fuse_outputs(result: FuseResult, out_dir: Path | str) -> dict[str, Path]:
    """Write mesh.{ply,obj,glb,stl}, points.ply and fuse.json into ``out_dir``."""
    out = Path(out_dir)
    paths = write_mesh_exports(result.mesh, out, "mesh", result.config.stl_units)
    paths["points"] = out / "points.ply"
    o3d.io.write_point_cloud(str(paths["points"]), result.points)
    paths["report"] = out / "fuse.json"
    paths["report"].write_text(json.dumps(result.report(), indent=2) + "\n")
    return paths
