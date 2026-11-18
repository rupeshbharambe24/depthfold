"""Bundle IO, frame selection and validation."""

from __future__ import annotations

import json
import shutil
import struct
import zipfile
from pathlib import Path

import numpy as np
import pytest

from depthfold.bundle import Bundle, BundleError, read_png8, read_png16, write_png8, write_png16
from depthfold.validate import validate_bundle


def _ihdr(path: Path) -> tuple[int, int, int, int]:
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    w, h, bit_depth, colour_type = struct.unpack(">IIBB", data[16:26])
    return w, h, bit_depth, colour_type


def test_png16_roundtrip_full_range(tmp_path: Path) -> None:
    a = np.random.default_rng(0).integers(0, 65536, size=(90, 160), dtype=np.uint16)
    a[0, :4] = [0, 1, 32768, 65535]
    p = tmp_path / "d.png"
    write_png16(p, a)
    assert _ihdr(p) == (160, 90, 16, 0)  # 16-bit greyscale
    b = read_png16(p)
    assert b.dtype == np.uint16 and np.array_equal(a, b)
    assert np.array_equal(read_png16(p.read_bytes()), a)


def test_png16_rejects_8bit_and_bad_values(tmp_path: Path) -> None:
    p = tmp_path / "c.png"
    write_png8(p, np.full((4, 4), 200))
    assert _ihdr(p)[2:] == (8, 0)
    assert read_png8(p)[0, 0] == 200
    with pytest.raises(ValueError):
        read_png16(p)
    with pytest.raises(ValueError):
        write_png16(tmp_path / "x.png", np.array([[70000]]))


def test_zip_layouts_match_folder(cylinder_bundle: Path, tmp_path: Path) -> None:
    nested = Path(shutil.make_archive(str(tmp_path / "nested"), "zip", cylinder_bundle.parent, cylinder_bundle.name))
    flat = Path(shutil.make_archive(str(tmp_path / "flat"), "zip", cylinder_bundle))
    with Bundle.open(cylinder_bundle) as a, Bundle.open(nested) as b, Bundle.open(flat) as c:
        assert len(a.frames) == len(b.frames) == len(c.frames)
        f = a.depth_samples().samples[0].frame
        assert np.array_equal(a.read_depth_mm(f), b.read_depth_mm(f))
        assert np.array_equal(a.read_rgb(f), c.read_rgb(f))
        assert a.capture_id == b.capture_id == c.capture_id


def test_open_errors(tmp_path: Path) -> None:
    with pytest.raises(BundleError):
        Bundle.open(tmp_path)
    bad = tmp_path / "bad.zip"
    with zipfile.ZipFile(bad, "w") as z:
        z.writestr("a/b/bundle.json", "{}")
    with pytest.raises(BundleError):
        Bundle.open(bad)


def test_depth_selection_rules(cylinder_bundle: Path, smooth_bundle: Path) -> None:
    with Bundle.open(cylinder_bundle) as b:
        sel = b.depth_samples()
        s = sel.summary()
        assert s["skipped_not_tracking"] == 2  # synthetic tracking loss
        assert s["skipped_no_depth"] >= 2  # first frame and frames whose repeated depth the writer skipped
        assert all(x.frame.is_tracking for x in sel.samples)
        repaired = [x for x in sel.samples if x.pose_source == "interpolated"]
        assert repaired, "lagged depth should be re-posed by interpolation"
        for x in repaired:
            assert not np.allclose(x.T_world_camera, x.frame.T_world_camera)
        strict = b.depth_samples(repair_stale_pose=False)
        assert strict.skipped_stale_depth == len(repaired)
    with Bundle.open(smooth_bundle) as b:
        sel = b.depth_samples()
        assert sel.skipped_repeated_depth > 0  # duplicates saved by a writer are detected by timestamp
        assert all(b.read_confidence(x.frame) is None for x in sel.samples)


def test_validate_synthetic_ok(cylinder_bundle: Path, smooth_bundle: Path) -> None:
    for path in (cylinder_bundle, smooth_bundle):
        rep = validate_bundle(path)
        assert rep.ok, rep.format()
        assert rep.stats["frames_total"] == 72
        assert rep.stats["depth_frames_used"] > 40
        assert rep.stats["frames_depth_from_earlier_camera_frame"] > 0


def _copy(src: Path, dst: Path) -> Path:
    shutil.copytree(src, dst)
    return dst


def _rewrite_frames(root: Path, edit) -> None:  # noqa: ANN001
    lines = (root / "frames.jsonl").read_text().splitlines()
    frames = [json.loads(line) for line in lines]
    edit(frames)
    (root / "frames.jsonl").write_text("\n".join(json.dumps(f) for f in frames) + "\n")


def test_validate_detects_problems(cylinder_bundle: Path, tmp_path: Path) -> None:
    root = _copy(cylinder_bundle, tmp_path / "broken")

    def edit(frames: list[dict]) -> None:
        frames[3]["T_world_camera"][0][0] = 2.0  # not rigid
        frames[4]["pose_arcore"]["tx"] += 0.5  # inconsistent with T_world_camera
        frames[5]["depth_intrinsics"]["width"] = 80  # wrong size for the depth PNG
        frames[0]["depth_timestamp_ns"] = 5  # depth fields must all be null without depth

    _rewrite_frames(root, edit)
    (root / "rgb" / "000007.jpg").unlink()
    m = json.loads((root / "bundle.json").read_text())
    m["frame_count"] = 0  # provisional manifest of an interrupted recording
    (root / "bundle.json").write_text(json.dumps(m))

    rep = validate_bundle(root)
    codes = {i.code: i for i in rep.issues}
    assert not rep.ok
    assert codes["frame.pose"].frames == [3]
    assert codes["frame.pose_arcore"].frames == [4]
    assert 5 in codes["file.depth.size"].frames
    assert codes["file.rgb"].frames == [7]
    assert codes["frame.depth_timestamp"].level == "warning"
    assert "provisional" in codes["manifest.frame_count"].message
    assert codes["manifest.frame_count"].level == "warning"


def test_validate_manifest_errors(cylinder_bundle: Path, tmp_path: Path) -> None:
    root = _copy(cylinder_bundle, tmp_path / "m")
    m = json.loads((root / "bundle.json").read_text())
    m["format"] = "something-else"
    m["arcore"]["depth_mode"] = "none"
    (root / "bundle.json").write_text(json.dumps(m))
    rep = validate_bundle(root, check_images=False)
    codes = {i.code for i in rep.errors}
    assert {"manifest.format", "manifest.depth", "frame.depth"} <= codes


def test_null_manifest_depth_is_allowed(cylinder_bundle: Path, tmp_path: Path) -> None:
    root = _copy(cylinder_bundle, tmp_path / "nodepth")
    m = json.loads((root / "bundle.json").read_text())
    m["depth"] = None
    (root / "bundle.json").write_text(json.dumps(m))
    _rewrite_frames(
        root,
        lambda fs: [f.update(depth=None, confidence=None, depth_timestamp_ns=None, depth_intrinsics=None) for f in fs],
    )
    rep = validate_bundle(root)
    assert rep.ok, rep.format()
    assert any(i.code == "frames.depth" for i in rep.warnings)  # nothing to fuse
