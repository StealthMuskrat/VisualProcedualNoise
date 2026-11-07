#!/usr/bin/env python3
"""Compare installed cached/active-only shader switching and checkpoint continuation."""

from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("--launcher", type=Path, default=Path("build/install/procedural-visual-engine/bin/procedural-visual-engine"))
options = parser.parse_args()
launcher = options.launcher.resolve()
reports = Path("build/reports").resolve()
fixture = reports / "shader-cache-replay-fixture.json"
if not launcher.is_file() or not fixture.is_file():
    raise SystemExit("Run coreCheck installDist before verify-shader-cache.py")

def same_checkpoint(a, b):
    with zipfile.ZipFile(a) as left, zipfile.ZipFile(b) as right:
        if set(left.namelist()) != set(right.namelist()):
            return False
        return all(json.loads(left.read(name)) == json.loads(right.read(name)) if name == "manifest.json"
                   else left.read(name) == right.read(name) for name in left.namelist())

with tempfile.TemporaryDirectory(prefix="shader-cache-check-", dir=reports) as temporary:
    root = Path(temporary) / "Shader études with spaces"
    root.mkdir()
    environment = dict(os.environ, LOCALAPPDATA=str(root / "appdata"))
    def run(name, policy, frames, source):
        result = subprocess.run([str(launcher), "--headless", "--frames", str(frames), "--size", "96x64",
                                 "--shader-cache", policy, *source, "--capture", str(root / f"{name}.png"),
                                 "--save-checkpoint", str(root / f"{name}.vstate"), "--benchmark", str(root / f"{name}.json")],
                                env=environment, capture_output=True, text=True, timeout=120)
        if result.returncode:
            raise SystemExit(result.stdout + result.stderr)
        return json.loads((root / f"{name}.json").read_text(encoding="utf-8"))
    on = run("on", "on", 48, ["--replay", str(fixture)])
    off = run("off", "off", 48, ["--replay", str(fixture)])
    cached, active_only = on["shaderCache"], off["shaderCache"]
    if not cached["retainInactive"] or active_only["retainInactive"] or cached["variants"]["hits"] < 2:
        raise SystemExit("Installed replay did not exercise retained material-program hits")
    if cached["variants"]["misses"] >= active_only["variants"]["misses"] or active_only["variants"]["entries"] != 1:
        raise SystemExit("Inactive retention did not avoid material builds relative to the active-only policy")
    for report in (on, off):
        cache = report["shaderCache"]["variants"]
        if report["referenceTick"] != 96 or cache["entries"] > cache["maximumEntries"] or cache["sourceBytes"] > cache["maximumSourceBytes"]:
            raise SystemExit("Shader retention exceeded its declared bounds")
    if (root / "on.png").read_bytes() != (root / "off.png").read_bytes() or not same_checkpoint(root / "on.vstate", root / "off.vstate"):
        raise SystemExit("Shader caching changed image, clocks, replay, smoothing or persistent GPU state")
    full = run("full", "off", 80, ["--replay", str(fixture)])
    resumed = run("resumed", "off", 32, ["--checkpoint", str(root / "on.vstate")])
    if resumed["shaderCache"]["retainInactive"] or resumed["shaderCache"]["variants"]["maximumEntries"] != 1 or resumed["shaderCache"]["variants"]["hits"]:
        raise SystemExit("Checkpoint restore serialized or overrode the selected cache policy")
    if resumed["referenceTick"] != 160 or full["referenceTick"] != 160 or (root / "full.png").read_bytes() != (root / "resumed.png").read_bytes() or not same_checkpoint(root / "full.vstate", root / "resumed.vstate"):
        raise SystemExit("Cross-policy checkpoint continuation changed developed output or GPU state")
    preserved = root / "preserved.png"
    preserved.write_bytes(b"existing image")
    invalid = subprocess.run([str(launcher), "--headless", "--frames", "1", "--shader-cache", "invalid", "--capture", str(preserved)],
                             env=environment, capture_output=True, text=True, timeout=60)
    if not invalid.returncode or preserved.read_bytes() != b"existing image":
        raise SystemExit("Rejected cache option changed its destination")
    shaders = root / "engine shaders"
    shutil.copytree(Path("src/main/resources/shaders"), shaders)
    (shaders / "render/material.frag").write_bytes(b"x" * 262145)
    bounded = subprocess.run([str(launcher), "--headless", "--frames", "1", "--shaders", str(shaders), "--capture", str(preserved)],
                             env=environment, capture_output=True, text=True, timeout=60)
    if not bounded.returncode or "256 KiB" not in bounded.stderr or preserved.read_bytes() != b"existing image":
        raise SystemExit("Installed shader source bound failed to preserve the output destination")
    summary = {"cacheOn": cached, "cacheOff": active_only, "referenceTick": 96, "continuedTick": 160,
               "pngSha256": hashlib.sha256((root / "on.png").read_bytes()).hexdigest(),
               "outcome": "exact image, clocks, smoothing, replay and GPU bytes; cross-policy continuation and failure preservation"}
    (reports / "shader-cache-verification.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"Installed-launcher shader-cache check passed: {cached['variants']['hits']} retained hits, {cached['variants']['misses']} vs {active_only['variants']['misses']} builds; exact image/state/replay and cross-policy checkpoint continuation")
