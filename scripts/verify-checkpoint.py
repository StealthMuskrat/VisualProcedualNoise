#!/usr/bin/env python3
"""Compare cross-process checkpoint continuation with an uninterrupted replay."""

from pathlib import Path
import argparse
import json
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("--launcher", type=Path, default=Path("build/install/procedural-visual-engine/bin/procedural-visual-engine"))
options = parser.parse_args()
launcher = options.launcher.resolve()
fixture = Path("build/reports/checkpoint-fixture.json")
if not launcher.is_file() or not fixture.is_file():
    raise SystemExit("Run coreCheck installDist before verify-checkpoint.py")

parent = Path("build/reports")
with tempfile.TemporaryDirectory(prefix="checkpoint-check-", dir=parent) as temporary:
    root = Path(temporary)
    preset = json.loads(fixture.read_text())
    edited = json.loads(json.dumps(preset))
    edited["settings"]["values"]["warp"] = 1.8
    edited["settings"]["effectValues"]["amplitude"] = [2.0]
    replay = {
        "replayVersion": 1, "width": 320, "height": 200,
        "initialPreset": preset, "endTick": 360,
        "events": [
            {"tick": 1, "kind": "pointer", "x": -.2, "y": .1, "radius": .12, "strength": 1},
            {"tick": 301, "kind": "controls", "preset": edited},
            {"tick": 320, "kind": "pointer", "x": .3, "y": -.1, "radius": .12, "strength": 1},
            {"tick": 350, "kind": "pointer", "x": .3, "y": -.1, "radius": .12, "strength": 0},
        ],
    }
    take = root / "take.replay.json"
    take.write_text(json.dumps(replay))

    def run(name, frames, *arguments):
        subprocess.run([
            str(launcher), "--headless", "--frames", str(frames),
            "--capture", str(root / f"{name}.png"),
            "--save-checkpoint", str(root / f"{name}.vstate"), *map(str, arguments),
        ], check=True)

    run("uninterrupted", 180, "--size", "320x200", "--replay", take)
    run("split", 153, "--size", "320x200", "--replay", take)
    run("resumed", 27, "--checkpoint", root / "split.vstate")
    subprocess.run([
        str(launcher), "--export", str(root / "instant"), "--frames", "1",
        "--checkpoint", str(root / "split.vstate"),
    ], check=True)
    if (root / "split.png").read_bytes() != (root / "instant/frame-000000.png").read_bytes():
        raise SystemExit("Restored checkpoint did not reproduce its saved image without advancing time")
    if (root / "uninterrupted.png").read_bytes() != (root / "resumed.png").read_bytes():
        raise SystemExit("Resumed image differs from uninterrupted rendering")
    with zipfile.ZipFile(root / "uninterrupted.vstate") as expected, zipfile.ZipFile(root / "resumed.vstate") as actual:
        if set(expected.namelist()) != set(actual.namelist()):
            raise SystemExit("Resumed GPU resource set differs")
        for name in expected.namelist():
            if name == "manifest.json":
                if json.loads(expected.read(name)) != json.loads(actual.read(name)):
                    raise SystemExit("Resumed clocks, replay cursor, or simulation metadata differ")
            elif expected.read(name) != actual.read(name):
                raise SystemExit(f"Resumed GPU resource differs: {name}")
    print("Installed-launcher checkpoint check passed: exact image, clocks, replay cursor and GPU bytes at tick 360")
