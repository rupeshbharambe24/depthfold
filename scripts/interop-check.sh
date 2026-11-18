#!/usr/bin/env bash
# Cross-checks the Android bundle writer against the Python toolkit: the JVM test writes a bundle of a floor
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
