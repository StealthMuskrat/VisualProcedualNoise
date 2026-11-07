#!/usr/bin/env python3
"""Verify installed artwork/map overwrite consistency and failed destination preservation."""

from pathlib import Path
import argparse
import hashlib
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
fixture = reports / "replay-fixture.json"
if not launcher.is_file() or not fixture.is_file():
    raise SystemExit("Run coreCheck installDist before verify-output-pairs.py")

def same_checkpoint(a, b):
    with zipfile.ZipFile(a) as left, zipfile.ZipFile(b) as right:
        return set(left.namelist()) == set(right.namelist()) and all(
            json.loads(left.read(name)) == json.loads(right.read(name)) if name == "manifest.json"
            else left.read(name) == right.read(name) for name in left.namelist())

with tempfile.TemporaryDirectory(prefix="output-pair-check-", dir=reports) as temporary:
    root = Path(temporary) / "Paired output études with spaces"
    root.mkdir()
    environment = dict(os.environ, LOCALAPPDATA=str(root / "appdata"))
    diagnostics = []
    common = [str(launcher), "--headless", "--size", "96x60", "--replay", str(fixture)]
    def execute(frames, extra, success=True):
        result = subprocess.run([*common, "--frames", str(frames), *extra], env=environment,
                                capture_output=True, text=True, timeout=120)
        diagnostics.append(f"frames={frames}, arguments={extra}, exit={result.returncode}\n" + result.stderr[-6000:])
        if success != (result.returncode == 0):
            raise SystemExit(result.stdout + result.stderr)
        return result
    def outputs(name, frames):
        image, data = root / f"{name}.png", root / f"{name}-height.png"
        execute(frames, ["--capture", str(image), "--map", "height", "--map-output", str(data),
                         "--map-size", "191x127", "--save-checkpoint", str(root / f"{name}.vstate")])
        return image, data
    expected = outputs("expected", 8)
    outputs("overwritten", 4)
    overwritten = outputs("overwritten", 8)
    for reference, actual in zip(expected, overwritten):
        if reference.read_bytes() != actual.read_bytes() or json.loads(Path(str(reference) + ".json").read_text()) != json.loads(Path(str(actual) + ".json").read_text()):
            raise SystemExit("Paired overwrite differs from an independently saved image/metadata pair")
        if json.loads(Path(str(actual) + ".json").read_text())["referenceTick"] != 16:
            raise SystemExit("Overwrite retained metadata from the previous tick")
    if not same_checkpoint(root / "expected.vstate", root / "overwritten.vstate"):
        raise SystemExit("Paired publication changed persistent GPU state, clocks or replay")
    for kind, reference in zip(("artwork", "map"), expected):
        blocked = root / f"blocked-{kind}.png"
        blocked.write_bytes(reference.read_bytes())
        sidecar = Path(str(blocked) + ".json")
        sidecar.mkdir()
        marker = sidecar / "marker"
        marker.write_text("keep", encoding="utf-8")
        extra = ["--capture", str(blocked)] if kind == "artwork" else ["--map", "height", "--map-output", str(blocked)]
        result = execute(4, extra, False)
        if "not a regular file" not in result.stderr or blocked.read_bytes() != reference.read_bytes() or marker.read_text() != "keep":
            raise SystemExit(f"Blocked {kind} sidecar changed the existing image or directory")
        occupied = root / f"occupied-{kind}.png"
        occupied.mkdir()
        (occupied / "marker").write_text("keep", encoding="utf-8")
        metadata = Path(str(occupied) + ".json")
        metadata.write_text("previous metadata", encoding="utf-8")
        extra = ["--capture", str(occupied)] if kind == "artwork" else ["--map", "height", "--map-output", str(occupied)]
        execute(4, extra, False)
        if (occupied / "marker").read_text() != "keep" or metadata.read_text() != "previous metadata":
            raise SystemExit(f"Blocked {kind} image changed the occupied directory or metadata")
    leftovers = {path.name: sorted(child.name for child in path.iterdir()) for path in root.glob(".visual-output-*")}
    if leftovers:
        raise SystemExit("Completed or rejected paired save left staging files: " + json.dumps(leftovers)
                         + "\n" + "\n".join(diagnostics))
    summary = {"referenceTick": 16, "artworkSha256": hashlib.sha256(expected[0].read_bytes()).hexdigest(),
               "mapSha256": hashlib.sha256(expected[1].read_bytes()).hexdigest(),
               "outcome": "independent overwrite equivalence, exact GPU/clocks/replay, artwork/map destination preservation and staging cleanup"}
    (reports / "output-pair-verification.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print("Installed-launcher paired-output check passed: matching artwork/map overwrites, exact GPU/clocks/replay, failed destination preservation and staging cleanup")
