"""Structural validation of capture bundles against ``docs/capture-bundle.md``.

:func:`validate_bundle` never raises on malformed input; every problem becomes an :class:`Issue`. Issues with
the same code and message are merged, so a defect that affects every frame is reported once with the list of
affected frame indices.
"""

from __future__ import annotations

import io
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image

from .bundle import (
    DEPTH_MODES,
    FORMAT_NAME,
    SUPPORTED_VERSIONS,
    TRACKING_STATES,
    Bundle,
    BundleError,
    BundleSource,
    Frame,
    read_png8,
    read_png16,
)
from .geometry import T_world_camera_from_arcore, arcore_pose_to_matrix, rigidity_error

RIGID_TOL = 1e-3
POSE_MATCH_TOL = 1e-3


@dataclass
class Issue:
    """One problem found in a bundle."""

    level: str  # "error" or "warning"
    code: str
    message: str
    frames: list[int] = field(default_factory=list)

    def format(self, max_frames: int = 8) -> str:
        """Human-readable one-line form."""
        s = f"{self.level.upper():7s} {self.code}: {self.message}"
        if self.frames:
            shown = ", ".join(str(i) for i in self.frames[:max_frames])
            more = f", ... ({len(self.frames)} frames)" if len(self.frames) > max_frames else ""
            s += f" [frames {shown}{more}]"
        return s


@dataclass
class ValidationReport:
    """Result of :func:`validate_bundle`."""

    path: str
    issues: list[Issue] = field(default_factory=list)
    stats: dict[str, Any] = field(default_factory=dict)

    @property
    def errors(self) -> list[Issue]:
        """Issues that make the bundle non-compliant."""
        return [i for i in self.issues if i.level == "error"]

    @property
    def warnings(self) -> list[Issue]:
        """Issues that do not break compliance but may degrade results."""
        return [i for i in self.issues if i.level == "warning"]

    @property
    def ok(self) -> bool:
        """``True`` when there are no errors."""
        return not self.errors

    def add(self, level: str, code: str, message: str, frame: int | None = None) -> None:
        """Record an issue, merging it with an existing one of the same code and message."""
        for issue in self.issues:
            if issue.level == level and issue.code == code and issue.message == message:
                if frame is not None:
                    issue.frames.append(frame)
                return
        self.issues.append(Issue(level, code, message, [frame] if frame is not None else []))

    def to_dict(self) -> dict[str, Any]:
        """JSON-serialisable form."""
        return {
            "path": self.path,
            "ok": self.ok,
            "stats": self.stats,
            "issues": [
                {"level": i.level, "code": i.code, "message": i.message, "frames": i.frames} for i in self.issues
            ],
        }

    def format(self) -> str:
        """Multi-line human-readable summary."""
        lines = [f"Bundle: {self.path}"]
        for k, v in self.stats.items():
            lines.append(f"  {k}: {v}")
        if self.issues:
            lines.append("Problems:")
            lines.extend("  " + i.format() for i in self.issues)
        lines.append("Result: " + ("OK" if self.ok else f"INVALID ({len(self.errors)} error(s))"))
        if self.ok and self.warnings:
            lines[-1] += f" with {len(self.warnings)} warning(s)"
        return "\n".join(lines)


def _check_manifest(m: dict[str, Any], rep: ValidationReport) -> None:
    if m.get("format") != FORMAT_NAME:
        rep.add("error", "manifest.format", f"format must be {FORMAT_NAME!r}, got {m.get('format')!r}")
    if str(m.get("version")) not in SUPPORTED_VERSIONS:
        rep.add("warning", "manifest.version", f"unsupported version {m.get('version')!r}; reading as 0.1")
    for key in ("capture_id", "created_utc", "app", "device", "arcore", "rgb", "frame_count"):
        if key not in m:
            rep.add("error", "manifest.missing", f"bundle.json is missing {key!r}")
    arcore = m.get("arcore") or {}
    mode = arcore.get("depth_mode")
    if mode not in DEPTH_MODES:
        rep.add("error", "manifest.depth_mode", f"arcore.depth_mode must be one of {DEPTH_MODES}, got {mode!r}")
    depth = m.get("depth")
    if mode == "none" and depth is not None:
        rep.add("error", "manifest.depth", "depth must be null when depth_mode is 'none'")
    if mode in ("raw", "smooth"):
        if depth is not None and not isinstance(depth, dict):
            rep.add("error", "manifest.depth", "depth must be an object or null")
        elif isinstance(depth, dict):
            for key in ("width", "height", "units"):
                if key not in depth:
                    rep.add("error", "manifest.depth", f"depth is missing {key!r}")
            if depth.get("units", "mm") != "mm":
                rep.add("error", "manifest.depth.units", f"depth units must be 'mm', got {depth.get('units')!r}")
            if mode == "smooth" and depth.get("has_confidence"):
                rep.add("warning", "manifest.depth", "smooth depth has no confidence but has_confidence is true")
    rgb = m.get("rgb") or {}
    if rgb.get("format", "jpeg") not in ("jpeg", "jpg"):
        rep.add("warning", "manifest.rgb.format", f"rgb format {rgb.get('format')!r} is not 'jpeg'")


def _check_frame(
    f: Frame, m: dict[str, Any], src: BundleSource, rep: ValidationReport, check_images: bool, acc: dict[str, list]
) -> None:
    i = f.index
    if f.tracking_state not in TRACKING_STATES:
        rep.add("error", "frame.tracking_state", f"tracking_state must be one of {TRACKING_STATES}", i)
    if not f.rgb.startswith("rgb/"):
        rep.add("warning", "frame.path", "rgb path is not under rgb/", i)
    for intr, what in ((f.intrinsics, "intrinsics"), (f.depth_intrinsics, "depth_intrinsics")):
        if intr is None:
            continue
        if min(intr.fx, intr.fy, intr.width, intr.height) <= 0:
            rep.add("error", f"frame.{what}", f"{what} must have positive fx, fy, width, height", i)
        elif not (0 < intr.cx < intr.width and 0 < intr.cy < intr.height):
            rep.add("warning", f"frame.{what}", f"{what} principal point lies outside the image", i)
    rgb_m = m.get("rgb") or {}
    if (rgb_m.get("width"), rgb_m.get("height")) != (f.intrinsics.width, f.intrinsics.height):
        rep.add("warning", "frame.intrinsics", "intrinsics width/height differ from bundle.json rgb size", i)

    err = rigidity_error(f.T_world_camera)
    if err > RIGID_TOL:
        rep.add("error", "frame.pose", f"T_world_camera is not a rigid transform (tolerance {RIGID_TOL})", i)
    if f.pose_arcore is not None and err <= RIGID_TOL:
        try:
            expected = T_world_camera_from_arcore(arcore_pose_to_matrix(f.pose_arcore))
            if np.abs(expected - f.T_world_camera).max() > POSE_MATCH_TOL:
                rep.add(
                    "warning",
                    "frame.pose_arcore",
                    "T_world_camera != pose_arcore * diag(1,-1,-1,1); check the writer's axis conversion",
                    i,
                )
        except (KeyError, ValueError):
            rep.add("warning", "frame.pose_arcore", "pose_arcore is malformed", i)

    mode = (m.get("arcore") or {}).get("depth_mode")
    if f.depth is None:
        if f.confidence is not None:
            rep.add("error", "frame.confidence", "confidence given for a frame without depth", i)
        if f.raw.get("depth_intrinsics") not in (None, {}):
            rep.add("warning", "frame.depth_intrinsics", "depth_intrinsics should be null for a frame without depth", i)
        if f.depth_timestamp_ns is not None:
            rep.add(
                "warning", "frame.depth_timestamp", "depth_timestamp_ns should be null for a frame without depth", i
            )
    else:
        if mode == "smooth" and f.confidence is not None:
            rep.add("warning", "frame.confidence", "confidence should be null in smooth depth mode", i)
        if f.depth_timestamp_ns is not None and f.depth_timestamp_ns < f.timestamp_ns:
            acc["depth_lag_ms"].append((f.timestamp_ns - f.depth_timestamp_ns) / 1e6)
        if f.depth_intrinsics is None:
            rep.add("error", "frame.depth_intrinsics", "frame has depth but no depth_intrinsics", i)
        if f.depth_timestamp_ns is None:
            rep.add("warning", "frame.depth_timestamp", "frame has depth but no depth_timestamp_ns", i)
        elif f.depth_timestamp_ns > f.timestamp_ns + 1_000_000:
            rep.add("warning", "frame.depth_timestamp", "depth_timestamp_ns is later than the frame timestamp", i)
        depth_m = m.get("depth") or {}
        if f.depth_intrinsics is not None and (depth_m.get("width"), depth_m.get("height")) != (
            f.depth_intrinsics.width,
            f.depth_intrinsics.height,
        ):
            rep.add("warning", "frame.depth_intrinsics", "depth_intrinsics size differs from bundle.json depth", i)
        if f.confidence is None and mode == "raw" and (m.get("depth") or {}).get("has_confidence"):
            rep.add("warning", "frame.confidence", "has_confidence is true but frame has no confidence", i)

    for rel, kind in ((f.rgb, "rgb"), (f.depth, "depth"), (f.confidence, "confidence")):
        if rel is not None and not src.exists(rel):
            rep.add("error", f"file.{kind}", f"{kind} file is missing", i)
            continue
        if rel is None or not check_images:
            continue
        try:
            data = src.read_bytes(rel)
            if kind == "rgb":
                with Image.open(io.BytesIO(data)) as im:
                    if im.size != (f.intrinsics.width, f.intrinsics.height):
                        rep.add("error", "file.rgb.size", "rgb image size differs from intrinsics", i)
                    if im.format != "JPEG":
                        rep.add("warning", "file.rgb.format", "rgb image is not a JPEG", i)
            elif kind == "depth":
                d = read_png16(data)
                di = f.depth_intrinsics
                if di is not None and d.shape != (di.height, di.width):
                    rep.add("error", "file.depth.size", "depth image size differs from depth_intrinsics", i)
                acc["depth_valid"].append(float((d > 0).mean()))
                if (d > 0).any():
                    acc["depth_median_m"].append(float(np.median(d[d > 0])) / 1000.0)
                acc["depth_shape"].append(d.shape)
            else:
                c = read_png8(data)
                if acc["depth_shape"] and c.shape != acc["depth_shape"][-1]:
                    rep.add("error", "file.confidence.size", "confidence size differs from depth size", i)
                acc["conf_high"].append(float((c >= 128).mean()))
        except (ValueError, OSError) as exc:
            msg = str(exc).split(":")[0] if kind != "rgb" else "cannot decode image"
            rep.add("error", f"file.{kind}.decode", f"{kind}: {msg}", i)


def validate_bundle(path: Path | str, check_images: bool = True) -> ValidationReport:
    """Check a bundle folder or zip against the spec and gather statistics.

    Args:
        path: Bundle folder or ``.zip``.
        check_images: Decode every image to check sizes and pixel formats (slower but thorough).
    """
    rep = ValidationReport(path=str(path))
    try:
        src = BundleSource(path)
    except BundleError as exc:
        rep.add("error", "bundle.open", str(exc))
        return rep
    try:
        try:
            manifest = json.loads(src.read_bytes("bundle.json"))
            if not isinstance(manifest, dict):
                raise ValueError("not a JSON object")
        except (ValueError, BundleError) as exc:
            rep.add("error", "manifest.parse", f"bundle.json: {exc}")
            return rep
        _check_manifest(manifest, rep)
        if not src.exists("frames.jsonl"):
            rep.add("error", "frames.missing", "frames.jsonl is missing")
            return rep

        frames: list[Frame] = []
        text = src.read_bytes("frames.jsonl").decode("utf-8", errors="replace")
        for lineno, line in enumerate(text.splitlines(), start=1):
            if not line.strip():
                continue
            try:
                frames.append(Frame.from_dict(json.loads(line)))
            except (KeyError, ValueError, TypeError) as exc:
                rep.add("error", "frames.parse", f"frames.jsonl line {lineno}: {exc!r}")

        mode = (manifest.get("arcore") or {}).get("depth_mode")
        fc = manifest.get("frame_count")
        if fc == 0 and frames:
            rep.add(
                "warning",
                "manifest.frame_count",
                f"frame_count is a provisional 0 (interrupted recording?); using the {len(frames)} frames in "
                "frames.jsonl",
            )
        elif fc is not None and fc != len(frames):
            rep.add("warning", "manifest.frame_count", f"frame_count is {fc} but frames.jsonl has {len(frames)} frames")
        if mode in ("raw", "smooth") and manifest.get("depth") is None and any(f.depth for f in frames):
            rep.add("warning", "manifest.depth", "bundle.json depth is null although some frames have depth")
        indices = [f.index for f in frames]
        if indices != list(range(len(frames))) and indices != sorted(set(indices)):
            rep.add("error", "frames.index", "frame indices are not unique and increasing")
        ts = [f.timestamp_ns for f in frames]
        if any(b <= a for a, b in zip(ts, ts[1:], strict=False)):
            rep.add("error", "frames.timestamp", "timestamp_ns is not strictly increasing")
        if not frames:
            rep.add("error", "frames.empty", "frames.jsonl contains no frames")

        acc: dict[str, list] = {
            "depth_valid": [],
            "depth_median_m": [],
            "conf_high": [],
            "depth_shape": [],
            "depth_lag_ms": [],
        }
        for f in frames:
            _check_frame(f, manifest, src, rep, check_images, acc)
            if mode == "none" and f.depth is not None:
                rep.add("error", "frame.depth", "frame has depth but depth_mode is 'none'", f.index)

        bundle = Bundle(src, manifest, frames)
        sel = bundle.depth_samples()
        stats: dict[str, Any] = {
            "capture_id": manifest.get("capture_id"),
            "device": " ".join(str(v) for v in (manifest.get("device") or {}).values() if v not in (None, "")),
            "depth_mode": mode,
            "rgb_size": f"{(manifest.get('rgb') or {}).get('width')}x{(manifest.get('rgb') or {}).get('height')}",
            "depth_size": (
                f"{manifest['depth'].get('width')}x{manifest['depth'].get('height')}"
                if isinstance(manifest.get("depth"), dict)
                else None
            ),
            "duration_s": round((ts[-1] - ts[0]) / 1e9, 3) if len(ts) > 1 else 0.0,
            **sel.summary(),
            "frames_with_confidence": sum(f.confidence is not None for f in frames),
        }
        stats["frames_depth_from_earlier_camera_frame"] = len(acc["depth_lag_ms"])
        if acc["depth_lag_ms"]:
            stats["max_depth_lag_ms"] = round(max(acc["depth_lag_ms"]), 3)
        if acc["depth_valid"]:
            stats["mean_depth_valid_fraction"] = round(float(np.mean(acc["depth_valid"])), 3)
        if acc["depth_median_m"]:
            stats["median_depth_m"] = round(float(np.median(acc["depth_median_m"])), 3)
        if acc["conf_high"]:
            stats["mean_confidence_ge_128_fraction"] = round(float(np.mean(acc["conf_high"])), 3)
        rep.stats = stats
        if frames and sel.frames_tracking == 0:
            rep.add("warning", "frames.tracking", "no frame has tracking_state TRACKING")
        if mode in ("raw", "smooth") and not sel.samples:
            rep.add("warning", "frames.depth", "no usable (tracking, fresh) depth frames; fusion will fail")
        return rep
    finally:
        src.close()
