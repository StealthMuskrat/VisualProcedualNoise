#!/usr/bin/env python3
"""Verify installed redraw diagnostics and offline isolation from held-scene scheduling."""

from pathlib import Path
import argparse
import copy
import json
import os
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("--launcher", type=Path, default=Path("build/install/procedural-visual-engine/bin/procedural-visual-engine"))
options = parser.parse_args()
launcher = options.launcher.resolve()
reports = Path("build/reports").resolve()
fixture = reports / "effect-modulation/preset.json"
if not launcher.is_file() or not fixture.is_file():
    raise SystemExit("Run coreCheck installDist before verify-redraw.py")

def same_state(a, b):
    with zipfile.ZipFile(a) as left, zipfile.ZipFile(b) as right:
        if set(left.namelist()) != set(right.namelist()):
            return False
        for name in left.namelist():
            if name != "manifest.json":
                if left.read(name) != right.read(name):
                    return False
            else:
                first, second = json.loads(left.read(name)), json.loads(right.read(name))
                for manifest in (first, second):
                    manifest["preset"]["settings"]["paused"] = False
                    manifest["preset"]["settings"]["speed"] = 1
                if first != second:
                    return False
        return True

with tempfile.TemporaryDirectory(prefix="redraw-check-", dir=reports) as temporary:
    root = Path(temporary) / "Redraw études with spaces"
    root.mkdir()
    environment = dict(os.environ, LOCALAPPDATA=str(root / "appdata"))
    original = json.loads(fixture.read_text(encoding="utf-8"))
    snapshots = {}
    def run(extra):
        result = subprocess.run([str(launcher), "--headless", "--size", "96x64", *extra],
                                env=environment, capture_output=True, text=True, timeout=120)
        if result.returncode:
            raise SystemExit(result.stdout + result.stderr)
    for name, paused, speed in (("playing", False, 1), ("paused", True, 1), ("zero", False, 0), ("both", True, 0)):
        document = copy.deepcopy(original)
        document["settings"]["paused"], document["settings"]["speed"] = paused, speed
        source = root / f"{name}.preset.json"
        source.write_text(json.dumps(document), encoding="utf-8")
        run(["--frames", "32", "--preset", str(source), "--capture", str(root / f"{name}.png"),
             "--save-checkpoint", str(root / f"{name}.vstate"), "--benchmark", str(root / f"{name}.json")])
        report = json.loads((root / f"{name}.json").read_text(encoding="utf-8"))
        redraw = report["redraw"]
        if report["referenceTick"] != 64 or redraw != {"invalidated": False, "lastWidth": 96, "lastHeight": 64, "presentedFrames": 32, "idleWaits": 0}:
            raise SystemExit("Offline run entered held-scene idle or disagreed with presentation diagnostics")
        if name != "playing" and ((root / "playing.png").read_bytes() != (root / f"{name}.png").read_bytes()
                                  or not same_state(root / "playing.vstate", root / f"{name}.vstate")):
            raise SystemExit("Held-scene scheduling changed offline image, clocks, smoothing or GPU bytes")
        snapshots[name] = redraw
    run(["--frames", "4", "--fps", "30", "--preset", str(root / "both.preset.json"), "--export", str(root / "sequence")])
    ticks = [json.loads(path.read_text(encoding="utf-8"))["referenceTick"] for path in sorted((root / "sequence").glob("frame-*.png.json"))]
    if ticks != [0, 4, 8, 12]:
        raise SystemExit("Frozen transport settings changed offline half-open sequence scheduling")
    run(["--frames", "1", "--checkpoint", str(root / "both.vstate"), "--export", str(root / "held")])
    if (root / "held/frame-000000.png").read_bytes() != (root / "both.png").read_bytes():
        raise SystemExit("Held checkpoint redraw lost the saved finished frame")
    (reports / "redraw-verification.json").write_text(json.dumps({"snapshots": snapshots,
        "referenceTick": 64, "sequenceTicks": ticks,
        "outcome": "offline policy bypass, exact image/GPU/clocks/smoothing, complete frame counts and held checkpoint"}, indent=2), encoding="utf-8")
    print("Installed-launcher redraw check passed: offline policy bypass, exact image/state/clocks, fixed sequence ticks, frame counters and held checkpoint")
