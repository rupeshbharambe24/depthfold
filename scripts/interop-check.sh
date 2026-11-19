#!/usr/bin/env bash
# Cross-checks the Android code against the Python toolkit. First the JVM test writes a bundle of a floor
# plane at world y = -12.3 mm, then the toolkit validates and fuses it and checks where the floor ends up.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

(cd "$ROOT/android" && DEPTHFOLD_INTEROP_DIR="$OUT" ./gradlew -q testDebugUnitTest --tests '*InteropBundleTest*' --rerun)
cd "$ROOT/processing"
uv run depthfold validate "$OUT/interop-floor"
uv run depthfold fuse "$OUT/interop-floor" -o "$OUT/fused" --voxel 0.01 --max-depth 4.0 >/dev/null
uv run python - "$OUT/fused/points.ply" <<'PY'
import sys
import numpy as np
import open3d as o3d

pts = np.asarray(o3d.io.read_point_cloud(sys.argv[1]).points)
assert len(pts) > 100, f"too few fused points: {len(pts)}"
y = np.median(pts[:, 1])
print(f"fused points: {len(pts)}, floor height (median y): {y * 1000:+.1f} mm (truth -12.3 mm), "
      f"spread: {np.std(pts[:, 1]) * 1000:.1f} mm")
assert abs(y + 0.0123) < 0.005, "floor should be at world y = -12.3 mm"
print("interop OK")
PY

# The other direction: the app's on-device model builder (run as a JVM test) fuses a synthetic bottle made by the
# toolkit, and the toolkit measures the phone-built mesh.
uv run depthfold synth -o "$OUT/bottle" >/dev/null
(cd "$ROOT/android" && DEPTHFOLD_FUSE_BUNDLE="$OUT/bottle" ./gradlew -q testDebugUnitTest --tests '*ModelBuilderTest.buildsExternalBundle' --rerun)
uv run depthfold measure "$OUT/bottle/model/mesh.glb" -o "$OUT/measure-phone" --shape cylinder >/dev/null
uv run python - "$OUT/measure-phone/measurements.json" <<'PY'
import json
import sys

m = json.load(open(sys.argv[1]))["measurements"]
d, h = m["diameter"]["value"], m["height"]["value"]
print(f"phone-built bottle mesh: diameter {d:.2f} mm (truth 70), height {h:.2f} mm (truth 240)")
assert abs(d - 70) < 2 and abs(h - 240) < 2, "on-device model is off"
print("on-device model OK")
PY
