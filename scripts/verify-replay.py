#!/usr/bin/env python3
"""Exercise the installed launcher and compare two complete offline replay exports."""

from pathlib import Path
import argparse
import json
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument("--launcher", type=Path, default=Path("build/install/procedural-visual-engine/bin/procedural-visual-engine"))
parser.add_argument("--fixture", type=Path, default=Path("build/reports/replay-fixture.json"))
options = parser.parse_args()
launcher = options.launcher.resolve()
fixture = options.fixture.resolve()
if not launcher.is_file() or not fixture.is_file():
    raise SystemExit("Run coreCheck installDist before verify-replay.py")
recorded = json.loads(fixture.read_text(encoding="utf-8"))
size = f"{recorded['width']}x{recorded['height']}"

temporary_parent = Path("build/reports")
temporary_parent.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix="replay-check-", dir=temporary_parent) as temporary:
    root = Path(temporary)
    for name in ("a", "b"):
        subprocess.run([
            str(launcher), "--export", str(root / name), "--frames", "8", "--fps", "60",
            "--size", size, "--replay", str(fixture)
        ], check=True)
    a = sorted((root / "a").glob("frame-*.png"))
    b = sorted((root / "b").glob("frame-*.png"))
    if len(a) != 8 or len(b) != 8 or any(x.read_bytes() != y.read_bytes() for x, y in zip(a, b)):
        raise SystemExit("Replay output differed between identical runs")
    print(f"Installed-launcher replay check passed: 8 identical PNG frames at recorded size {size}")
