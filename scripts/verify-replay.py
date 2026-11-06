#!/usr/bin/env python3
"""Exercise the installed launcher and compare two complete offline replay exports."""

from pathlib import Path
import argparse
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument("--launcher", type=Path, default=Path("build/install/procedural-visual-engine/bin/procedural-visual-engine"))
options = parser.parse_args()
launcher = options.launcher.resolve()
fixture = Path("build/reports/replay-fixture.json").resolve()
if not launcher.is_file() or not fixture.is_file():
    raise SystemExit("Run coreCheck installDist before verify-replay.py")

with tempfile.TemporaryDirectory(prefix="replay-check-") as temporary:
    root = Path(temporary)
    for name in ("a", "b"):
        subprocess.run([
            str(launcher), "--export", str(root / name), "--frames", "8", "--fps", "60",
            "--size", "320x200", "--replay", str(fixture)
        ], check=True)
    a = sorted((root / "a").glob("frame-*.png"))
    b = sorted((root / "b").glob("frame-*.png"))
    if len(a) != 8 or len(b) != 8 or any(x.read_bytes() != y.read_bytes() for x, y in zip(a, b)):
        raise SystemExit("Replay output differed between identical runs")
    print("Installed-launcher replay check passed: 8 identical PNG frames")
