"""Render the README figure from a real run of the toolkit on a synthetic bottle capture.

Usage (from ``processing/``)::

    uv run --group docs python scripts/render_readme_assets.py ../docs/assets

Every number in the figure comes from the toolkit's own output files, so the figure stays honest when the
algorithms change: re-run this script and commit the new image.
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import trimesh  # noqa: E402
from matplotlib.colors import LightSource  # noqa: E402
from mpl_toolkits.mplot3d.art3d import Poly3DCollection  # noqa: E402

from depthfold.bundle import Bundle  # noqa: E402

TRUTH = {"diameter": 70.0, "height": 240.0}


def run(*args: str) -> None:
    subprocess.run(["depthfold", *args], check=True, stdout=subprocess.DEVNULL)


def main(out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        run("synth", "-o", str(work / "bottle"))
        run("fuse", str(work / "bottle"), "-o", str(work / "fused"))
        run("measure", str(work / "bottle"), "-o", str(work / "measure"))

        bundle = Bundle.open(work / "bottle")
        frame = next(f for f in bundle.frames if f.depth is not None and f.tracking_state == "TRACKING")
        rgb = bundle.read_rgb(frame)
        depth = bundle.read_depth_m(frame)
        report = json.loads((work / "measure" / "measurements.json").read_text())
        mesh = trimesh.load(work / "fused" / "mesh.ply", process=False)

    fig = plt.figure(figsize=(15, 4.8), dpi=110)
    fig.patch.set_facecolor("white")

    ax = fig.add_subplot(1, 4, 1)
    # Bundle images are in sensor (landscape) orientation; rotate for display only.
    ax.imshow(np.rot90(rgb, k=1))
    ax.set_title("1. Camera frame (one of 72)", loc="left", fontsize=11)
    ax.axis("off")

    ax = fig.add_subplot(1, 4, 2)
    shown = np.rot90(np.where(depth > 0, depth, np.nan), k=1)
    im = ax.imshow(shown, cmap="viridis")
    ax.set_title(f"2. Raw depth ({depth.shape[1]}x{depth.shape[0]})", loc="left", fontsize=11)
    ax.axis("off")
    fig.colorbar(im, ax=ax, fraction=0.035, pad=0.02, label="metres")

    ax = fig.add_subplot(1, 4, 3, projection="3d")
    v = np.asarray(mesh.vertices)
    f = np.asarray(mesh.faces)
    floor_y = -report["support_plane"]["d_m"]  # plane n.x + d = 0 with n = +Y
    centre = np.median(v[v[:, 1] > floor_y + 0.02], axis=0)  # object region above the floor
    keep = np.all(np.abs(v[f][:, :, [0, 2]] - centre[[0, 2]]).max(axis=1) < 0.12, axis=1)
    tris = v[f[keep]][:, :, [0, 2, 1]]  # plot with world Y (up) as the vertical axis
    normals = np.cross(tris[:, 1] - tris[:, 0], tris[:, 2] - tris[:, 0])
    normals /= np.linalg.norm(normals, axis=1, keepdims=True) + 1e-12
    shade = LightSource(azdeg=315, altdeg=45).shade_normals(normals)
    colours = plt.cm.Greys(0.25 + 0.6 * shade)
    ax.add_collection3d(Poly3DCollection(tris, facecolors=colours, edgecolor="none"))
    lo, hi = tris.reshape(-1, 3).min(axis=0), tris.reshape(-1, 3).max(axis=0)
    ax.set_xlim(lo[0], hi[0])
    ax.set_ylim(lo[1], hi[1])
    ax.set_zlim(lo[2], hi[2])
    ax.set_box_aspect(hi - lo)
    ax.view_init(elev=18, azim=-60)
    ax.set_axis_off()
    ax.set_title(f"3. Fused mesh ({len(f):,} triangles)", loc="left", fontsize=11)

    ax = fig.add_subplot(1, 4, 4)
    prof = report["profile"]
    h = np.array([p["height_mm"] for p in prof])
    r = np.array([p["radius_mm"] for p in prof])
    m = report["measurements"]
    ax.fill_betweenx(h, -r, r, color="#d9e8f5")
    ax.plot(r, h, color="#1f5f99", lw=2)
    ax.plot(-r, h, color="#1f5f99", lw=2)
    ax.axhline(m["height"]["value"], color="#c0392b", ls="--", lw=1)
    ax.set_aspect("equal")
    ax.set_xlabel("radius (mm)")
    ax.set_ylabel("height above surface (mm)")
    ax.set_title("4. Measured profile", loc="left", fontsize=11)
    text = "\n".join(
        [
            f"height    {m['height']['value']:.1f} ± {m['height']['uncertainty']:.1f} mm  (true {TRUTH['height']:.0f})",
            f"diameter  {m['diameter']['value']:.1f} ± {m['diameter']['uncertainty']:.1f} mm  "
            f"(true {TRUTH['diameter']:.0f})",
            f"volume    {m['volume']['value']:.0f} ± {m['volume']['uncertainty']:.0f} mL",
        ]
    )
    ax.text(1.04, 0.5, text, transform=ax.transAxes, fontsize=9.5, family="monospace", va="center")

    fig.tight_layout()
    path = out_dir / "pipeline.png"
    fig.savefig(path, bbox_inches="tight", facecolor="white")
    print(f"wrote {path}")


if __name__ == "__main__":
    main(Path(sys.argv[1] if len(sys.argv) > 1 else "../docs/assets"))
