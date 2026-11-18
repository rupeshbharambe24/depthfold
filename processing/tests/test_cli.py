"""Command-line interface smoke tests (small synthetic bundle)."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from depthfold.cli import main


@pytest.fixture(scope="module")
def small_bundle(tmp_path_factory: pytest.TempPathFactory) -> Path:
    out = tmp_path_factory.mktemp("cli") / "small"
    args = ["synth", "-o", str(out), "--shape", "box", "--frames", "48", "--rgb-size", "160x120", "--zip"]
    assert main(args) == 0
    return out


def test_version(capsys: pytest.CaptureFixture[str]) -> None:
    with pytest.raises(SystemExit) as exc:
        main(["--version"])
    assert exc.value.code == 0
    assert "depthfold" in capsys.readouterr().out


def test_validate_folder_and_zip(small_bundle: Path, capsys: pytest.CaptureFixture[str]) -> None:
    assert main(["validate", str(small_bundle)]) == 0
    assert "Result: OK" in capsys.readouterr().out
    assert main(["validate", str(small_bundle) + ".zip", "--json"]) == 0
    rep = json.loads(capsys.readouterr().out)
    assert rep["ok"] and rep["stats"]["frames_total"] == 48


def test_validate_fails_on_missing_bundle(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    assert main(["validate", str(tmp_path)]) == 1
    assert "INVALID" in capsys.readouterr().out


def test_fuse_measure_export(small_bundle: Path, tmp_path: Path) -> None:
    zipped = str(small_bundle) + ".zip"
    assert main(["fuse", zipped, "-o", str(tmp_path / "fused"), "--voxel", "0.006"]) == 0
    assert {p.name for p in (tmp_path / "fused").iterdir()} >= {
        "mesh.ply",
        "mesh.obj",
        "mesh.glb",
        "mesh.stl",
        "points.ply",
        "fuse.json",
    }
    assert main(["measure", str(tmp_path / "fused" / "mesh.ply"), "-o", str(tmp_path / "m"), "--shape", "box"]) == 0
    report = json.loads((tmp_path / "m" / "measurements.json").read_text())
    assert report["provenance"]["capture_id"] == "synth-box-0"  # picked up from fuse.json next to the mesh
    assert report["provenance"]["fusion"]["voxel"] == 0.006
    assert main(["export", zipped, "--format", "colmap", "-o", str(tmp_path / "c")]) == 0
    assert (tmp_path / "c" / "sparse" / "0" / "images.txt").is_file()
    assert main(["export", str(small_bundle), "--format", "nerfstudio", "-o", str(tmp_path / "n"), "--link"]) == 0
    assert (tmp_path / "n" / "images").iterdir().__next__().is_symlink()


def test_fuse_error_is_reported(small_bundle: Path, tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    assert main(["fuse", str(small_bundle), "-o", str(tmp_path), "--min-confidence", "256"]) == 1
    assert "error" in capsys.readouterr().err
