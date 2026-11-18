"""Shared fixtures: synthetic bundles are generated once per test session."""

from __future__ import annotations

from pathlib import Path

import pytest

from depthfold.synth import SynthConfig, synthesize

#: Measured errors collected by the end-to-end tests and printed in the terminal summary.
MEASURED_ERRORS: list[tuple[str, str, float, float, float]] = []


def record_error(case: str, quantity: str, measured: float, truth: float) -> float:
    """Remember a measurement error for the summary table and return it."""
    err = measured - truth
    MEASURED_ERRORS.append((case, quantity, measured, truth, err))
    return err


def pytest_terminal_summary(terminalreporter: pytest.TerminalReporter) -> None:  # pragma: no cover - reporting
    if not MEASURED_ERRORS:
        return
    tr = terminalreporter
    tr.section("depthfold measured errors (synthetic ground truth)")
    tr.write_line(f"{'case':22s} {'quantity':14s} {'measured':>10s} {'truth':>10s} {'error':>8s} {'error %':>8s}")
    for case, q, m, t, e in MEASURED_ERRORS:
        tr.write_line(f"{case:22s} {q:14s} {m:10.2f} {t:10.2f} {e:+8.2f} {100 * e / t:+7.2f}%")


def _make(tmp_path_factory: pytest.TempPathFactory, name: str, **kw: object) -> Path:
    root = tmp_path_factory.mktemp(name) / name
    return synthesize(root, SynthConfig(rgb_size=(320, 240), **kw))  # type: ignore[arg-type]


@pytest.fixture(scope="session")
def cylinder_bundle(tmp_path_factory: pytest.TempPathFactory) -> Path:
    """70 mm x 240 mm cylinder, raw depth 160x90 with confidence, RGB 320x240."""
    return _make(tmp_path_factory, "cylinder", shape="cylinder")


@pytest.fixture(scope="session")
def box_bundle(tmp_path_factory: pytest.TempPathFactory) -> Path:
    """180 x 110 x 75 mm box rotated 30 deg, raw depth 160x90."""
    return _make(tmp_path_factory, "box", shape="box")


@pytest.fixture(scope="session")
def smooth_bundle(tmp_path_factory: pytest.TempPathFactory) -> Path:
    """Cylinder in smooth depth mode (no confidence), depth 160x120, repeated depth saved as duplicates."""
    return _make(
        tmp_path_factory,
        "smooth",
        shape="cylinder",
        depth_mode="smooth",
        depth_size=(160, 120),
        repeat_depth_mode="duplicate",
        seed=3,
    )


@pytest.fixture
def error_log():  # noqa: ANN201
    """The :func:`record_error` helper, as a fixture."""
    return record_error


@pytest.fixture(scope="session")
def noisy_pose_bundle(tmp_path_factory: pytest.TempPathFactory) -> Path:
    """Cylinder whose recorded poses carry 1 mm / 0.2 deg noise (rendering uses the true poses)."""
    return _make(tmp_path_factory, "noisy", shape="cylinder", pose_noise_trans=0.001, pose_noise_rot_deg=0.2, seed=5)
