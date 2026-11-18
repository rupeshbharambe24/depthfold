"""Reading and writing Depthfold capture bundles.

A bundle is a folder (or a ``.zip`` of that folder) containing ``bundle.json``, ``frames.jsonl`` and per-frame
``rgb/``, ``depth/`` and ``confidence/`` images. The format is specified in ``docs/capture-bundle.md``; this
module is the reference reader.

Typical use::

    with Bundle.open("capture.zip") as bundle:
        for sample in bundle.depth_samples():
            depth_m = bundle.read_depth_m(sample.frame)
            ...
"""

from __future__ import annotations

import io
import json
import shutil
import zipfile
from collections.abc import Iterator, Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from types import TracebackType
from typing import Any

import numpy as np
from numpy.typing import NDArray
from PIL import Image
from scipy.spatial.transform import Rotation, Slerp

from .geometry import FloatArray, Intrinsics, as_matrix4

FORMAT_NAME = "depthfold-bundle"
SUPPORTED_VERSIONS = ("0.1",)
TRACKING = "TRACKING"
TRACKING_STATES = ("TRACKING", "PAUSED", "STOPPED")
DEPTH_MODES = ("raw", "smooth", "none")


class BundleError(Exception):
    """Raised when a bundle cannot be read at all (missing manifest, unparsable frames, ...)."""


# --------------------------------------------------------------------------------------------------------------
# Image IO
# --------------------------------------------------------------------------------------------------------------


def read_png16(data: bytes | Path | str) -> NDArray[np.uint16]:
    """Decode a 16-bit greyscale PNG into a ``uint16`` array of shape ``(H, W)``.

    Raises :class:`ValueError` if the image is not single-channel 16-bit.
    """
    src = io.BytesIO(data) if isinstance(data, bytes | bytearray) else data
    with Image.open(src) as im:
        if im.mode in ("I;16", "I;16B", "I;16L"):
            return np.asarray(im, dtype=np.uint16).copy()
        if im.mode == "I":  # some Pillow versions decode 16-bit PNGs as 32-bit integer images
            a = np.asarray(im)
            if a.min() < 0 or a.max() > 65535:
                raise ValueError("depth PNG values outside the uint16 range")
            return a.astype(np.uint16)
        raise ValueError(f"expected a 16-bit greyscale PNG, got mode {im.mode!r}")


def write_png16(path: Path | str, array: NDArray[Any]) -> None:
    """Write a ``(H, W)`` array as a 16-bit greyscale PNG (values must fit in ``uint16``)."""
    a = np.asarray(array)
    if a.ndim != 2:
        raise ValueError("write_png16 expects a 2-D array")
    if a.dtype != np.uint16:
        if a.size and (a.min() < 0 or a.max() > 65535):
            raise ValueError("values outside the uint16 range")
        a = a.astype(np.uint16)
    Image.fromarray(a).save(path, format="PNG")


def read_png8(data: bytes | Path | str) -> NDArray[np.uint8]:
    """Decode an 8-bit greyscale PNG into a ``uint8`` array of shape ``(H, W)``."""
    src = io.BytesIO(data) if isinstance(data, bytes | bytearray) else data
    with Image.open(src) as im:
        if im.mode != "L":
            raise ValueError(f"expected an 8-bit greyscale PNG, got mode {im.mode!r}")
        return np.asarray(im, dtype=np.uint8).copy()


def write_png8(path: Path | str, array: NDArray[Any]) -> None:
    """Write a ``(H, W)`` array as an 8-bit greyscale PNG."""
    a = np.asarray(array)
    if a.ndim != 2:
        raise ValueError("write_png8 expects a 2-D array")
    Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), mode="L").save(path, format="PNG")


# --------------------------------------------------------------------------------------------------------------
# Folder / zip access
# --------------------------------------------------------------------------------------------------------------


class BundleSource:
    """Uniform read access to a bundle stored as a folder or a zip archive.

    For zips, ``bundle.json`` may sit at the archive root or inside a single top-level folder.
    """

    def __init__(self, path: Path | str) -> None:
        self.path = Path(path)
        self._zip: zipfile.ZipFile | None = None
        self._prefix = ""
        if self.path.is_dir():
            if not (self.path / "bundle.json").is_file():
                raise BundleError(f"{self.path}: no bundle.json in folder")
        elif self.path.is_file() and zipfile.is_zipfile(self.path):
            self._zip = zipfile.ZipFile(self.path)
            names = [n for n in self._zip.namelist() if PurePosixPath(n).name == "bundle.json"]
            names.sort(key=lambda n: n.count("/"))
            if not names or names[0].count("/") > 1:
                self._zip.close()
                raise BundleError(f"{self.path}: no bundle.json at the archive root or in a top-level folder")
            self._prefix = names[0][: -len("bundle.json")]
        else:
            raise BundleError(f"{self.path}: not a bundle folder or .zip file")

    @property
    def is_zip(self) -> bool:
        """``True`` when reading from a zip archive."""
        return self._zip is not None

    def exists(self, rel: str) -> bool:
        """Return whether the bundle-relative path ``rel`` exists."""
        if self._zip is not None:
            try:
                self._zip.getinfo(self._prefix + rel)
                return True
            except KeyError:
                return False
        return (self.path / rel).is_file()

    def read_bytes(self, rel: str) -> bytes:
        """Read a bundle-relative file."""
        try:
            if self._zip is not None:
                return self._zip.read(self._prefix + rel)
            return (self.path / rel).read_bytes()
        except (KeyError, FileNotFoundError) as exc:
            raise BundleError(f"missing file in bundle: {rel}") from exc

    def local_path(self, rel: str) -> Path | None:
        """Filesystem path of ``rel`` for folder bundles, ``None`` for zips."""
        return None if self._zip is not None else (self.path / rel).resolve()

    def copy_to(self, rel: str, dest: Path, link: bool = False) -> None:
        """Copy (or symlink, for folder bundles when ``link`` is set) ``rel`` to ``dest``."""
        dest.parent.mkdir(parents=True, exist_ok=True)
        if dest.exists() or dest.is_symlink():
            dest.unlink()
        local = self.local_path(rel)
        if local is not None and link:
            dest.symlink_to(local)
        elif local is not None:
            shutil.copyfile(local, dest)
        else:
            dest.write_bytes(self.read_bytes(rel))

    def close(self) -> None:
        """Release the zip handle, if any."""
        if self._zip is not None:
            self._zip.close()
            self._zip = None


# --------------------------------------------------------------------------------------------------------------
# Data model
# --------------------------------------------------------------------------------------------------------------


@dataclass
class Frame:
    """One line of ``frames.jsonl``."""

    index: int
    timestamp_ns: int
    tracking_state: str
    rgb: str
    depth: str | None
    confidence: str | None
    depth_timestamp_ns: int | None
    intrinsics: Intrinsics
    depth_intrinsics: Intrinsics | None
    T_world_camera: FloatArray  # noqa: N815
    pose_arcore: dict[str, float] | None = None
    raw: dict[str, Any] = field(default_factory=dict, repr=False)

    @property
    def is_tracking(self) -> bool:
        """Whether the pose of this frame is trustworthy."""
        return self.tracking_state == TRACKING

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> Frame:
        """Parse a ``frames.jsonl`` object. Raises ``KeyError``/``ValueError``/``TypeError`` on bad input."""
        depth = d.get("depth")
        di = d.get("depth_intrinsics")
        dts = d.get("depth_timestamp_ns")
        return cls(
            index=int(d["index"]),
            timestamp_ns=int(d["timestamp_ns"]),
            tracking_state=str(d["tracking_state"]),
            rgb=str(d["rgb"]),
            depth=str(depth) if depth else None,
            confidence=str(d["confidence"]) if d.get("confidence") else None,
            depth_timestamp_ns=int(dts) if dts is not None else None,
            intrinsics=Intrinsics.from_dict(d["intrinsics"]),
            depth_intrinsics=Intrinsics.from_dict(di) if (depth and di) else None,
            T_world_camera=as_matrix4(d["T_world_camera"]),
            pose_arcore=dict(d["pose_arcore"]) if d.get("pose_arcore") else None,
            raw=dict(d),
        )

    def to_dict(self) -> dict[str, Any]:
        """Serialise in the ``frames.jsonl`` format."""
        out: dict[str, Any] = {
            "index": self.index,
            "timestamp_ns": self.timestamp_ns,
            "tracking_state": self.tracking_state,
            "rgb": self.rgb,
            "depth": self.depth,
            "confidence": self.confidence,
            "depth_timestamp_ns": self.depth_timestamp_ns,
            "intrinsics": self.intrinsics.to_dict(),
            "depth_intrinsics": self.depth_intrinsics.to_dict() if self.depth_intrinsics else None,
            "T_world_camera": [[float(v) for v in row] for row in self.T_world_camera],
        }
        if self.pose_arcore is not None:
            out["pose_arcore"] = self.pose_arcore
        return out


@dataclass
class DepthSample:
    """A depth image paired with the camera pose at the time the depth was captured."""

    frame: Frame
    T_world_camera: FloatArray  # noqa: N815
    pose_source: str  # "frame", "matched_frame" or "interpolated"


@dataclass
class DepthSelection:
    """Result of :meth:`Bundle.depth_samples`: usable samples plus counts of what was skipped and why."""

    samples: list[DepthSample]
    frames_total: int = 0
    frames_tracking: int = 0
    frames_with_depth: int = 0
    skipped_not_tracking: int = 0
    skipped_no_depth: int = 0
    skipped_repeated_depth: int = 0
    skipped_stale_depth: int = 0
    repaired_poses: int = 0

    def summary(self) -> dict[str, int]:
        """Counts as a plain dict (for reports)."""
        return {
            "frames_total": self.frames_total,
            "frames_tracking": self.frames_tracking,
            "frames_with_depth": self.frames_with_depth,
            "depth_frames_used": len(self.samples),
            "skipped_not_tracking": self.skipped_not_tracking,
            "skipped_no_depth": self.skipped_no_depth,
            "skipped_repeated_depth": self.skipped_repeated_depth,
            "skipped_stale_depth": self.skipped_stale_depth,
            "stale_depth_repaired_pose": self.repaired_poses,
        }


def interpolate_pose(
    t_ns: int,
    t0_ns: int,
    T0: FloatArray,
    t1_ns: int,
    T1: FloatArray,  # noqa: N803
) -> FloatArray:
    """Interpolate a rigid pose at ``t_ns`` between two timestamped poses (slerp + linear translation)."""
    if t1_ns == t0_ns:
        return T0.copy()
    a = (t_ns - t0_ns) / (t1_ns - t0_ns)
    rots = Rotation.from_matrix(np.stack([T0[:3, :3], T1[:3, :3]]))
    out = np.eye(4)
    out[:3, :3] = Slerp([0.0, 1.0], rots)([a]).as_matrix()[0]
    out[:3, 3] = (1 - a) * T0[:3, 3] + a * T1[:3, 3]
    return out


class Bundle:
    """A parsed capture bundle. Use :meth:`open` (also usable as a context manager)."""

    def __init__(self, source: BundleSource, manifest: dict[str, Any], frames: list[Frame]) -> None:
        self.source = source
        self.manifest = manifest
        self.frames = frames

    # -- construction --------------------------------------------------------------------------------------

    @classmethod
    def open(cls, path: Path | str) -> Bundle:
        """Open and parse a bundle folder or zip. Raises :class:`BundleError` if it cannot be parsed."""
        source = BundleSource(path)
        try:
            manifest = json.loads(source.read_bytes("bundle.json"))
            if not isinstance(manifest, dict):
                raise BundleError("bundle.json is not a JSON object")
            frames = []
            text = source.read_bytes("frames.jsonl").decode("utf-8")
            for lineno, line in enumerate(text.splitlines(), start=1):
                if not line.strip():
                    continue
                try:
                    frames.append(Frame.from_dict(json.loads(line)))
                except (KeyError, ValueError, TypeError) as exc:
                    raise BundleError(f"frames.jsonl line {lineno}: {exc!r}") from exc
        except BundleError:
            source.close()
            raise
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            source.close()
            raise BundleError(f"cannot parse bundle: {exc}") from exc
        return cls(source, manifest, frames)

    def close(self) -> None:
        """Release file handles."""
        self.source.close()

    def __enter__(self) -> Bundle:
        return self

    def __exit__(
        self, exc_type: type[BaseException] | None, exc: BaseException | None, tb: TracebackType | None
    ) -> None:
        self.close()

    # -- metadata ------------------------------------------------------------------------------------------

    @property
    def capture_id(self) -> str:
        """``capture_id`` from the manifest (falls back to the folder/zip name)."""
        return str(self.manifest.get("capture_id") or self.source.path.stem)

    @property
    def depth_mode(self) -> str:
        """``arcore.depth_mode`` (``raw``, ``smooth`` or ``none``)."""
        return str((self.manifest.get("arcore") or {}).get("depth_mode", "none"))

    @property
    def device(self) -> dict[str, Any]:
        """The manifest ``device`` object."""
        return dict(self.manifest.get("device") or {})

    def provenance(self) -> dict[str, Any]:
        """A compact description of where data came from, for reports."""
        return {
            "bundle_path": str(self.source.path),
            "capture_id": self.capture_id,
            "created_utc": self.manifest.get("created_utc"),
            "format_version": self.manifest.get("version"),
            "app": self.manifest.get("app"),
            "device": self.device,
            "arcore": self.manifest.get("arcore"),
            "depth_mode": self.depth_mode,
        }

    # -- image access --------------------------------------------------------------------------------------

    def read_rgb(self, frame: Frame) -> NDArray[np.uint8]:
        """Decode the colour image of ``frame`` as ``(H, W, 3)`` uint8."""
        with Image.open(io.BytesIO(self.source.read_bytes(frame.rgb))) as im:
            return np.asarray(im.convert("RGB"), dtype=np.uint8).copy()

    def read_depth_mm(self, frame: Frame) -> NDArray[np.uint16]:
        """Raw depth of ``frame`` in millimetres (``0`` = no data)."""
        if frame.depth is None:
            raise BundleError(f"frame {frame.index} has no depth")
        return read_png16(self.source.read_bytes(frame.depth))

    def read_depth_m(self, frame: Frame) -> NDArray[np.float32]:
        """Depth of ``frame`` in metres (``0`` = no data)."""
        return self.read_depth_mm(frame).astype(np.float32) * np.float32(0.001)

    def read_confidence(self, frame: Frame) -> NDArray[np.uint8] | None:
        """Confidence of ``frame`` (0..255) or ``None`` if the frame has none."""
        if frame.confidence is None:
            return None
        return read_png8(self.source.read_bytes(frame.confidence))

    # -- frame selection -----------------------------------------------------------------------------------

    def depth_samples(
        self,
        max_depth_age_ms: float = 1.0,
        max_interp_gap_ms: float = 200.0,
        stride: int = 1,
        repair_stale_pose: bool = True,
    ) -> DepthSelection:
        """Select depth images that can be fused, each with the pose at which it was captured.

        Rules (from the bundle spec):

        * Frames whose ``tracking_state`` is not ``TRACKING`` are skipped.
        * Depth whose ``depth_timestamp_ns`` was already seen on an earlier frame is a repeat of older depth
          and is skipped.
        * If the depth is older than its frame by more than ``max_depth_age_ms``, the frame pose is wrong for
          it. It is paired with the tracking frame recorded at ``depth_timestamp_ns`` if there is one, or with
          a pose interpolated from the two tracking frames that bracket it if they are at most
          ``max_interp_gap_ms`` apart; otherwise (or if ``repair_stale_pose`` is false) it is skipped as stale.

        Args:
            max_depth_age_ms: Depth at most this much older than its frame uses the frame pose directly.
            max_interp_gap_ms: Largest gap between bracketing frames allowed for pose interpolation.
            stride: Keep every ``stride``-th usable sample (``1`` keeps all).
            repair_stale_pose: Re-pose depth that is older than its frame instead of dropping it.
        """
        sel = DepthSelection(samples=[], frames_total=len(self.frames))
        tracking = [f for f in self.frames if f.is_tracking]
        sel.frames_tracking = len(tracking)
        times = np.array([f.timestamp_ns for f in tracking], dtype=np.int64)
        seen: set[int] = set()
        max_age = int(max_depth_age_ms * 1e6)
        max_gap = int(max_interp_gap_ms * 1e6)
        for f in self.frames:
            if f.depth is not None:
                sel.frames_with_depth += 1
            dts = f.depth_timestamp_ns
            repeated = dts is not None and dts in seen
            if dts is not None and f.depth is not None:
                seen.add(dts)
            if not f.is_tracking:
                sel.skipped_not_tracking += 1
                continue
            if f.depth is None or f.depth_intrinsics is None:
                sel.skipped_no_depth += 1
                continue
            if repeated:
                sel.skipped_repeated_depth += 1
                continue
            if dts is None or f.timestamp_ns - dts <= max_age:
                sel.samples.append(DepthSample(f, f.T_world_camera, "frame"))
                continue
            if not repair_stale_pose:
                sel.skipped_stale_depth += 1
                continue
            # Depth older than its frame: use the pose of the frame it was captured with, if recorded,
            # otherwise interpolate between the two tracking frames that bracket the depth timestamp.
            k = int(np.searchsorted(times, dts, side="left"))
            near = [j for j in (k - 1, k) if 0 <= j < len(times) and abs(int(times[j]) - dts) <= max_age]
            if near:
                j = min(near, key=lambda j: abs(int(times[j]) - dts))
                sel.samples.append(DepthSample(f, tracking[j].T_world_camera, "matched_frame"))
                sel.repaired_poses += 1
            elif 0 < k < len(times) and times[k] - times[k - 1] <= max_gap:
                T = interpolate_pose(
                    dts,
                    int(times[k - 1]),
                    tracking[k - 1].T_world_camera,
                    int(times[k]),
                    tracking[k].T_world_camera,
                )
                sel.samples.append(DepthSample(f, T, "interpolated"))
                sel.repaired_poses += 1
            else:
                sel.skipped_stale_depth += 1
        if stride > 1:
            sel.samples = sel.samples[::stride]
        return sel

    def iter_tracking_frames(self) -> Iterator[Frame]:
        """Frames with a trustworthy pose, in capture order."""
        return (f for f in self.frames if f.is_tracking)


def write_bundle_files(root: Path, manifest: Mapping[str, Any], frames: Sequence[Frame]) -> None:
    """Write ``bundle.json`` and ``frames.jsonl`` into ``root`` (image files are written by the caller)."""
    root.mkdir(parents=True, exist_ok=True)
    (root / "bundle.json").write_text(json.dumps(manifest, indent=2) + "\n")
    with (root / "frames.jsonl").open("w") as fh:
        for f in frames:
            fh.write(json.dumps(f.to_dict(), separators=(",", ":")) + "\n")
