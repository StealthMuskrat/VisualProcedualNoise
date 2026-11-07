#!/usr/bin/env python3
"""Verify installed peak-plan diagnostics and exact state preservation through temporary exports."""

from pathlib import Path
import argparse
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
    raise SystemExit("Run coreCheck installDist before verify-allocation.py")

def same_checkpoint(a, b):
    with zipfile.ZipFile(a) as left, zipfile.ZipFile(b) as right:
        return set(left.namelist()) == set(right.namelist()) and all(
            json.loads(left.read(name)) == json.loads(right.read(name)) if name == "manifest.json"
            else left.read(name) == right.read(name) for name in left.namelist())

def validate(report):
    stats = report["allocation"]
    plan = stats["lastPreflight"]
    if stats["limitBytes"] != 512 * 1024 * 1024 or stats["residentBytes"] != report["allocatedBytes"]:
        raise SystemExit("Allocation diagnostics disagree with the fixed budget or tracked resources")
    if not stats["residentBytes"] <= stats["largestAllowedPeakBytes"] <= stats["limitBytes"]:
        raise SystemExit("Allowed peak lies outside the fixed storage budget")
    if plan["peakBytes"] != plan["residentBytes"] + plan["stagedBytes"] or plan["settledBytes"] != plan["residentBytes"] - plan["replacedBytes"] + plan["retainedBytes"]:
        raise SystemExit("Allocation plan has inconsistent peak or settled accounting")
    if plan["settledBytes"] != stats["residentBytes"] or plan["peakBytes"] > stats["largestAllowedPeakBytes"]:
        raise SystemExit("Successful final plan does not describe settled tracked storage")
    return stats

with tempfile.TemporaryDirectory(prefix="allocation-check-", dir=reports) as temporary:
    root = Path(temporary) / "Allocation études with spaces"
    root.mkdir()
    environment = dict(os.environ, LOCALAPPDATA=str(root / "appdata"))
    common = [str(launcher), "--headless", "--frames", "32", "--size", "96x60", "--replay", str(fixture)]
    def run(name, extra):
        result = subprocess.run([*common, "--capture", str(root / f"{name}.png"),
                                 "--save-checkpoint", str(root / f"{name}.vstate"),
                                 "--benchmark", str(root / f"{name}.json"), *extra],
                                env=environment, capture_output=True, text=True, timeout=120)
        if result.returncode:
            raise SystemExit(result.stdout + result.stderr)
        return json.loads((root / f"{name}.json").read_text(encoding="utf-8"))
    baseline = run("baseline", [])
    mapped = run("mapped", ["--map", "linear-color", "--map-output", str(root / "color.png"), "--map-size", "191x127"])
    base_stats, map_stats = validate(baseline), validate(mapped)
    plan = map_stats["lastPreflight"]
    if plan["operation"] != "texture-map capture" or plan["stagedBytes"] != 191 * 127 * 16 or plan["replacedBytes"] or plan["retainedBytes"]:
        raise SystemExit("RGB map did not account for its RGBA32F temporary storage")
    if (root / "baseline.png").read_bytes() != (root / "mapped.png").read_bytes() or not same_checkpoint(root / "baseline.vstate", root / "mapped.vstate"):
        raise SystemExit("Temporary map allocation changed image, clocks, replay or GPU state")
    # Exactly 512 MiB for the map alone must fail once any live renderer storage is included.
    target = root / "color.png"
    sidecar = Path(str(target) + ".json")
    previous = target.read_bytes(), sidecar.read_bytes()
    rejected = subprocess.run([*common, "--map", "linear-color", "--map-output", str(target), "--map-size", "8192x4096"],
                              env=environment, capture_output=True, text=True, timeout=120)
    if not rejected.returncode or "GPU allocation peak" not in rejected.stderr or previous != (target.read_bytes(), sidecar.read_bytes()):
        raise SystemExit("Peak rejection failed to preserve the existing map and metadata")
    summary = {"baseline": base_stats, "mapped": map_stats, "referenceTick": mapped["referenceTick"],
               "outcome": "exact image, clocks, replay and GPU bytes; checked RGBA32F overlap and destination preservation"}
    (reports / "allocation-verification.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print("Installed-launcher allocation check passed: exact temporary storage plans, image/state/replay preservation and over-budget map destination preservation")
