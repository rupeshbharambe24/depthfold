"""``depthfold`` command-line interface.

Each subcommand is a thin wrapper around a library function:

========== =========================================
validate   :func:`depthfold.validate.validate_bundle`
fuse       :func:`depthfold.fuse.fuse_bundle`
measure    :func:`depthfold.measure.run_measure`
export     :func:`depthfold.export.export_bundle`
synth      :func:`depthfold.synth.synthesize`
========== =========================================
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from collections.abc import Sequence
from pathlib import Path

from . import __version__


def _size(text: str) -> tuple[int, int]:
    try:
        w, h = text.lower().split("x")
        return int(w), int(h)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(f"expected WIDTHxHEIGHT, got {text!r}") from exc


def _add_fuse_options(p: argparse.ArgumentParser) -> None:
    g = p.add_argument_group("fusion")
    g.add_argument("--voxel", type=float, default=0.005, help="TSDF voxel size in metres (default 0.005)")
    g.add_argument("--sdf-trunc", type=float, default=None, help="TSDF truncation in metres (default 3 voxels)")
    g.add_argument("--min-confidence", type=int, default=128, help="drop depth below this confidence (0-255)")
    g.add_argument("--min-depth", type=float, default=0.10, help="drop depth nearer than this, metres")
    g.add_argument("--max-depth", type=float, default=2.0, help="drop depth farther than this, metres")
    g.add_argument(
        "--max-depth-age-ms",
        type=float,
        default=1.0,
        help="depth older than its frame by more than this is re-posed (or dropped with --no-pose-repair)",
    )
    g.add_argument("--no-pose-repair", action="store_true", help="drop depth older than its frame instead")
    g.add_argument("--stride", type=int, default=1, help="use every n-th usable depth frame")
    g.add_argument("--min-weight", type=float, default=3.0, help="mesh only voxels seen by this many frames")


def _fuse_config(args: argparse.Namespace):  # noqa: ANN202 - imported lazily
    from .fuse import FuseConfig

    return FuseConfig(
        voxel=args.voxel,
        sdf_trunc=args.sdf_trunc,
        min_confidence=args.min_confidence,
        min_depth=args.min_depth,
        max_depth=args.max_depth,
        max_depth_age_ms=args.max_depth_age_ms,
        repair_stale_pose=not args.no_pose_repair,
        stride=args.stride,
        min_weight=args.min_weight,
        stl_units=getattr(args, "stl_units", "mm"),
    )


def cmd_validate(args: argparse.Namespace) -> int:
    from .validate import validate_bundle

    rep = validate_bundle(args.bundle, check_images=not args.quick)
    print(json.dumps(rep.to_dict(), indent=2) if args.json else rep.format())
    return 0 if rep.ok else 1


def cmd_fuse(args: argparse.Namespace) -> int:
    from .bundle import Bundle
    from .fuse import fuse_bundle, write_fuse_outputs

    with Bundle.open(args.bundle) as bundle:
        result = fuse_bundle(bundle, _fuse_config(args))
    paths = write_fuse_outputs(result, args.output)
    sel = result.selection.summary()
    print(
        f"Fused {sel['depth_frames_used']} of {sel['frames_total']} frames "
        f"({result.stats['vertices']} vertices, {result.stats['triangles']} triangles) "
        f"in {result.stats['seconds']} s"
    )
    for p in paths.values():
        print(f"  {p}")
    return 0


def cmd_measure(args: argparse.Namespace) -> int:
    from .measure import MeasureConfig, run_measure

    up = {"x": (1, 0, 0), "y": (0, 1, 0), "z": (0, 0, 1), "-x": (-1, 0, 0), "-y": (0, -1, 0), "-z": (0, 0, -1)}
    cfg = MeasureConfig(
        shape=args.shape,
        up=tuple(float(v) for v in up[args.up]),  # type: ignore[arg-type]
        slice_height=args.slice_mm / 1000.0,
        scale_uncertainty=args.scale_uncertainty,
    )
    report = run_measure(args.input, args.output, cfg, _fuse_config(args), units=args.units)
    m = report["measurements"]
    print(f"Shape: {report['shape']}")
    for key, v in m.items():
        print(f"  {key:13s} {v['value']:10.2f} {v['unit']:3s} +/- {v['uncertainty']:.2f}  ({v['method']})")
    for w in report["warnings"]:
        print(f"  warning: {w}")
    print(f"Wrote {Path(args.output) / 'measurements.json'}, profile.svg, profile.dxf, object.ply")
    return 0


def cmd_export(args: argparse.Namespace) -> int:
    from .bundle import Bundle
    from .export import export_bundle

    with Bundle.open(args.bundle) as bundle:
        path = export_bundle(bundle, args.format, args.output, stride=args.stride, link=args.link)
    print(f"Wrote {args.format} export: {path}")
    return 0


def cmd_synth(args: argparse.Namespace) -> int:
    from .synth import SynthConfig, synthesize

    cfg = SynthConfig(
        shape=args.shape,
        cylinder_diameter=args.diameter_mm / 1000.0,
        cylinder_height=args.height_mm / 1000.0,
        box_size=tuple(v / 1000.0 for v in args.box_mm),  # type: ignore[arg-type]
        frames=args.frames,
        rgb_size=args.rgb_size,
        depth_size=args.depth_size,
        depth_mode=args.depth_mode,
        pose_noise_trans=args.pose_noise_mm / 1000.0,
        pose_noise_rot_deg=args.pose_noise_deg,
        seed=args.seed,
    )
    out = Path(args.output)
    synthesize(out, cfg)
    if args.zip:
        archive = shutil.make_archive(str(out), "zip", root_dir=out.parent, base_dir=out.name)
        print(f"Wrote synthetic bundle {out} and {archive}")
    else:
        print(f"Wrote synthetic bundle {out}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    """The argument parser for the ``depthfold`` command."""
    parser = argparse.ArgumentParser(
        prog="depthfold",
        description="Process Depthfold capture bundles into meshes, measurements and reconstruction exports.",
    )
    parser.add_argument("--version", action="version", version=f"depthfold {__version__}")
    sub = parser.add_subparsers(dest="command", required=True, metavar="COMMAND")

    p = sub.add_parser("validate", help="check a bundle against the spec and report statistics")
    p.add_argument("bundle", type=Path, help="bundle folder or .zip")
    p.add_argument("--json", action="store_true", help="print the report as JSON")
    p.add_argument("--quick", action="store_true", help="skip decoding images")
    p.set_defaults(func=cmd_validate)

    p = sub.add_parser("fuse", help="TSDF-fuse depth into mesh.{ply,obj,glb,stl} and points.ply")
    p.add_argument("bundle", type=Path, help="bundle folder or .zip")
    p.add_argument("-o", "--output", type=Path, required=True, help="output folder")
    p.add_argument("--stl-units", choices=("mm", "m"), default="mm", help="units of mesh.stl (default mm)")
    _add_fuse_options(p)
    p.set_defaults(func=cmd_fuse)

    p = sub.add_parser("measure", help="measure the object on the support plane")
    p.add_argument("input", type=Path, help="bundle folder/.zip, or a mesh/point cloud (.ply/.obj/.glb/.stl)")
    p.add_argument("-o", "--output", type=Path, required=True, help="output folder")
    p.add_argument("--shape", choices=("auto", "box", "cylinder"), default="auto")
    p.add_argument(
        "--units",
        choices=("auto", "m", "mm"),
        default="auto",
        help="units of a mesh input (auto: .stl is mm, others m)",
    )
    p.add_argument(
        "--up",
        choices=("x", "y", "z", "-x", "-y", "-z"),
        default="y",
        help="world up axis of the input (default +Y, as in ARCore)",
    )
    p.add_argument("--slice-mm", type=float, default=5.0, help="height of profile slices in mm")
    p.add_argument(
        "--scale-uncertainty",
        type=float,
        default=0.01,
        help="assumed relative scale uncertainty of the tracking (default 0.01)",
    )
    _add_fuse_options(p)
    p.set_defaults(func=cmd_measure)

    p = sub.add_parser("export", help="export poses and images for Nerfstudio or COLMAP")
    p.add_argument("bundle", type=Path, help="bundle folder or .zip")
    p.add_argument("--format", choices=("nerfstudio", "colmap"), required=True)
    p.add_argument("-o", "--output", type=Path, required=True, help="output folder")
    p.add_argument("--stride", type=int, default=1, help="export every n-th tracking frame")
    p.add_argument("--link", action="store_true", help="symlink images instead of copying (folder bundles)")
    p.set_defaults(func=cmd_export)

    p = sub.add_parser("synth", help="generate a synthetic bundle of a known object")
    p.add_argument("-o", "--output", type=Path, required=True, help="bundle folder to create")
    p.add_argument("--shape", choices=("cylinder", "box"), default="cylinder")
    p.add_argument("--diameter-mm", type=float, default=70.0, help="cylinder diameter")
    p.add_argument("--height-mm", type=float, default=240.0, help="cylinder height")
    p.add_argument("--box-mm", type=float, nargs=3, default=(180.0, 110.0, 75.0), metavar=("L", "W", "H"))
    p.add_argument("--frames", type=int, default=72)
    p.add_argument("--rgb-size", type=_size, default=(640, 480), help="RGB size, e.g. 640x480")
    p.add_argument("--depth-size", type=_size, default=(160, 90), help="depth size, e.g. 160x90 or 160x120")
    p.add_argument("--depth-mode", choices=("raw", "smooth"), default="raw")
    p.add_argument("--pose-noise-mm", type=float, default=0.0, help="std-dev of recorded position noise")
    p.add_argument("--pose-noise-deg", type=float, default=0.0, help="std-dev of recorded rotation noise")
    p.add_argument("--seed", type=int, default=0)
    p.add_argument("--zip", action="store_true", help="also write <output>.zip")
    p.set_defaults(func=cmd_synth)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    """Entry point of the ``depthfold`` console script."""
    from .bundle import BundleError
    from .fuse import FusionError
    from .measure import MeasurementError

    args = build_parser().parse_args(argv)
    try:
        return int(args.func(args))
    except (BundleError, FusionError, MeasurementError, ValueError, FileNotFoundError) as exc:
        print(f"depthfold {args.command}: error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
