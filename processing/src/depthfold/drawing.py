"""2D drawings of a measured object: SVG for viewing, DXF for CAD.

Both drawings use real units (millimetres, 1 drawing unit = 1 mm) and contain two views:

* **Side view** at the origin: for cylinders the revolve-ready *half profile* (radius vs height, closed along the
  axis; layer ``PROFILE``) plus its mirror image (layer ``SILHOUETTE``) and the axis (layer ``AXIS``); for boxes
  the front silhouette along the width axis. The support plane is the line ``y = 0`` (layer ``BASE``).
* **Top view** to the right: the footprint convex hull and the oriented bounding rectangle (layer
  ``FOOTPRINT``), plus the fitted circle for cylinders.
"""

from __future__ import annotations

from pathlib import Path
from xml.sax.saxutils import escape

import ezdxf
import numpy as np
from ezdxf import units

from .measure import MeasureResult

_GAP = 40.0  # mm between views
_MARGIN = 25.0


def _views(res: MeasureResult) -> dict[str, np.ndarray | float]:
    """Common geometry for both writers (all in mm)."""
    outline = np.asarray(res.outline_mm, dtype=float)
    height = res.values["height"].value
    if res.shape == "cylinder":
        side_full = np.vstack([outline, outline[::-1] * np.array([-1.0, 1.0])])
        side_xmin, side_xmax = -outline[:, 0].max(), outline[:, 0].max()
    else:
        side_full = outline
        side_xmin, side_xmax = outline[:, 0].min(), outline[:, 0].max()
    foot = np.asarray(res.footprint_mm, dtype=float)
    rect = np.asarray(res.rect_mm, dtype=float)
    top_pts = np.vstack([foot, rect])
    top_dx = side_xmax + _GAP - top_pts[:, 0].min()
    return {
        "outline": outline,
        "side_full": side_full,
        "side_xmin": float(side_xmin),
        "side_xmax": float(side_xmax),
        "height": float(height),
        "foot": foot,
        "rect": rect,
        "top_dx": float(top_dx),
        "top_ymin": float(top_pts[:, 1].min()),
        "top_ymax": float(top_pts[:, 1].max()),
        "top_xmax": float(top_pts[:, 0].max() + top_dx),
    }


def _label_lines(res: MeasureResult) -> list[str]:
    v = res.values
    lines = [f"H {v['height'].value:.1f} ± {v['height'].uncertainty:.1f} mm"]
    if res.shape == "cylinder":
        lines.append(f"Ø {v['diameter'].value:.1f} ± {v['diameter'].uncertainty:.1f} mm")
    lines.append(f"L {v['length'].value:.1f}  W {v['width'].value:.1f} mm")
    lines.append(f"V {v['volume'].value:.0f} mL ({'mesh' if 'watertight' in v['volume'].method else 'model'})")
    return lines


def write_profile_svg(res: MeasureResult, path: Path | str, title: str = "") -> None:
    """Write the side and top views as an SVG drawing in millimetres."""
    g = _views(res)
    x0 = _MARGIN - g["side_xmin"]  # svg x of drawing x = 0
    top_h = g["top_ymax"] - g["top_ymin"]
    content_h = max(g["height"], top_h)
    text_h = 8.0 * (len(_label_lines(res)) + 1)
    width = _MARGIN * 2 + (g["top_xmax"] - g["side_xmin"])
    height = _MARGIN * 2 + content_h + text_h
    base_y = _MARGIN + content_h  # svg y of drawing y = 0 (side view)

    def sx(x: float) -> float:
        return x0 + x

    def sy(y: float) -> float:
        return base_y - y

    def poly(pts: np.ndarray, cls: str, close: bool = True) -> str:
        d = " ".join(f"{sx(x):.2f},{sy(y):.2f}" for x, y in pts)
        tag = "polygon" if close else "polyline"
        return f'<{tag} class="{cls}" points="{d}"/>'

    top_cy = base_y - content_h / 2  # centre the top view vertically on the content area

    def top(pts: np.ndarray) -> np.ndarray:
        p = np.asarray(pts, dtype=float).copy()
        p[:, 0] += g["top_dx"]
        p[:, 1] = (base_y - top_cy) + p[:, 1] - (g["top_ymax"] + g["top_ymin"]) / 2
        return p

    parts = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width:.1f}mm" height="{height:.1f}mm" '
        f'viewBox="0 0 {width:.2f} {height:.2f}">',
        "<style>"
        ".sil{fill:#dfe7ef;stroke:#7a8a99;stroke-width:0.3}"
        ".prof{fill:none;stroke:#0b3d91;stroke-width:0.8}"
        ".axis{stroke:#c0392b;stroke-width:0.3;stroke-dasharray:6 2 1 2}"
        ".base{stroke:#555;stroke-width:0.5}"
        ".foot{fill:#eef3e6;stroke:#5b7a2f;stroke-width:0.4}"
        ".rect{fill:none;stroke:#5b7a2f;stroke-width:0.3;stroke-dasharray:2 1.5}"
        "text{font-family:Helvetica,Arial,sans-serif;font-size:4.2px;fill:#222}"
        "</style>",
        f'<rect x="0" y="0" width="{width:.2f}" height="{height:.2f}" fill="#ffffff"/>',
        poly(g["side_full"], "sil"),
    ]
    if res.shape == "cylinder":
        parts.append(poly(g["outline"], "prof"))
        parts.append(
            f'<line class="axis" x1="{sx(0):.2f}" y1="{sy(-5):.2f}" x2="{sx(0):.2f}" y2="{sy(g["height"] + 5):.2f}"/>'
        )
    else:
        parts.append(poly(g["outline"], "prof"))
    parts.append(
        f'<line class="base" x1="{sx(g["side_xmin"] - 8):.2f}" y1="{sy(0):.2f}" '
        f'x2="{sx(g["side_xmax"] + 8):.2f}" y2="{sy(0):.2f}"/>'
    )
    parts.append(poly(top(g["foot"]), "foot"))
    parts.append(poly(top(g["rect"]), "rect"))
    if res.shape == "cylinder":
        r = res.values["diameter"].value / 2
        c = top(np.array([[0.0, 0.0]]))[0]
        parts.append(f'<circle class="rect" cx="{sx(c[0]):.2f}" cy="{sy(c[1]):.2f}" r="{r:.2f}"/>')
    parts.append(f'<text x="{sx(g["side_xmin"]):.2f}" y="{_MARGIN - 8:.2f}">side view</text>')
    tl = top(np.array([[g["rect"][:, 0].min(), g["top_ymax"]]]))[0]
    parts.append(f'<text x="{sx(tl[0]):.2f}" y="{_MARGIN - 8:.2f}">top view</text>')
    y = base_y + 12
    for line in ([title] if title else []) + _label_lines(res):
        parts.append(f'<text x="{_MARGIN:.2f}" y="{y:.2f}">{escape(line)}</text>')
        y += 6
    parts.append("</svg>")
    Path(path).write_text("\n".join(parts) + "\n", encoding="utf-8")


def write_profile_dxf(res: MeasureResult, path: Path | str) -> None:
    """Write the side and top views as a DXF (R2010) drawing in millimetres."""
    g = _views(res)
    doc = ezdxf.new("R2010", setup=True)
    doc.units = units.MM
    doc.header["$MEASUREMENT"] = 1  # metric
    for name, colour, ltype in (
        ("PROFILE", 5, "CONTINUOUS"),
        ("SILHOUETTE", 8, "CONTINUOUS"),
        ("AXIS", 1, "CENTER"),
        ("BASE", 7, "CONTINUOUS"),
        ("FOOTPRINT", 3, "CONTINUOUS"),
        ("TEXT", 7, "CONTINUOUS"),
    ):
        doc.layers.add(name, color=colour, linetype=ltype)
    msp = doc.modelspace()
    msp.add_lwpolyline([tuple(p) for p in g["outline"]], close=True, dxfattribs={"layer": "PROFILE"})
    if res.shape == "cylinder":
        mirrored = g["outline"] * np.array([-1.0, 1.0])
        msp.add_lwpolyline([tuple(p) for p in mirrored], close=True, dxfattribs={"layer": "SILHOUETTE"})
        msp.add_line((0, -5), (0, g["height"] + 5), dxfattribs={"layer": "AXIS"})
    msp.add_line((g["side_xmin"] - 8, 0), (g["side_xmax"] + 8, 0), dxfattribs={"layer": "BASE"})
    dx = g["top_dx"]
    msp.add_lwpolyline([(x + dx, y) for x, y in g["foot"]], close=True, dxfattribs={"layer": "FOOTPRINT"})
    msp.add_lwpolyline([(x + dx, y) for x, y in g["rect"]], close=True, dxfattribs={"layer": "FOOTPRINT"})
    if res.shape == "cylinder":
        msp.add_circle((dx, 0), res.values["diameter"].value / 2, dxfattribs={"layer": "FOOTPRINT"})
    y = -12.0
    for line in _label_lines(res):
        msp.add_text(line, height=3.5, dxfattribs={"layer": "TEXT"}).set_placement((g["side_xmin"], y))
        y -= 6.0
    doc.saveas(str(path))
