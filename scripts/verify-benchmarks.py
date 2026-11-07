#!/usr/bin/env python3
"""Verify installed benchmark provenance, unique query coverage and deterministic outcomes."""

from pathlib import Path
import argparse
import hashlib
import json
import math
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("--launcher", type=Path, default=Path("build/install/procedural-visual-engine/bin/procedural-visual-engine"))
options = parser.parse_args()
launcher = options.launcher.resolve()
reports = Path("build/reports").resolve()
if not launcher.is_file():
    raise SystemExit("Run installDist before verify-benchmarks.py")

def validate(report, warmup, samples, ticks):
    if report["benchmarkVersion"] != 2 or report["samples"] != samples or report["warmupFrames"] != warmup:
        raise SystemExit("Benchmark sample window metadata is incorrect")
    if report["cpuFrameMs"]["count"] != samples or len(report["cpuFrameSamplesMs"]) != samples:
        raise SystemExit("Benchmark CPU sample count is incorrect")
    for key, raw, expected in (("render", "gpuRenderSamples", samples), ("tick", "gpuTickSamples", samples * ticks)):
        coverage = report[key + "Coverage"]
        records = report[raw]
        if coverage["completedMeasured"] + coverage["skippedMeasured"] != expected or coverage["pending"] != 0:
            raise SystemExit("Benchmark query coverage is incomplete")
        if len(records) != coverage["completedMeasured"] or len({x["sequence"] for x in records}) != len(records):
            raise SystemExit("Benchmark counted a completed query more than once")
        if any(not warmup <= x["frame"] < warmup + samples or not math.isfinite(x["milliseconds"]) or x["milliseconds"] < 0 for x in records):
            raise SystemExit("Benchmark retained a warmup or invalid query result")

with tempfile.TemporaryDirectory(prefix="benchmark-check-", dir=reports) as temporary:
    root = Path(temporary) / "Benchmark études with spaces"
    root.mkdir()
    for name in ("first", "second"):
        subprocess.run([str(launcher), "--benchmark-suite", str(root / name), "--benchmark-profile", "smoke"], check=True)
    first = json.loads((root / "first/suite.json").read_text())
    second = json.loads((root / "second/suite.json").read_text())
    if len(first["cases"]) != 7 or first["profile"] != "smoke" or first["device"] != second["device"]:
        raise SystemExit("Suite manifest is incomplete")
    if len({case["workloadHash"] for case in first["cases"]}) != 7:
        raise SystemExit("Suite workloads do not have distinct identities")
    for a, b in zip(first["cases"], second["cases"]):
        validate(a, 8, 16, 2)
        for case in (a, b):
            allocation = case["allocation"]
            plan = allocation["lastPreflight"]
            if allocation["limitBytes"] != 512 * 1024 * 1024 or not case["allocatedBytes"] == allocation["residentBytes"] <= allocation["largestAllowedPeakBytes"] <= allocation["limitBytes"]:
                raise SystemExit("Suite allocation statistics disagree with tracked storage or budget")
            if plan["peakBytes"] != plan["residentBytes"] + plan["stagedBytes"] or plan["settledBytes"] != allocation["residentBytes"]:
                raise SystemExit("Suite allocation plan does not describe its completed resources")
        if a["referenceTick"] != 48 or a["firstMeasuredTick"] != 17 or a["firstMeasuredRenderTick"] != 18:
            raise SystemExit("Suite tick schedule differs from its pinned contract")
        for key in ("id", "workloadHash", "programSignature", "imageHash", "stateHashes", "signals", "simulation"):
            if a[key] != b[key]:
                raise SystemExit(f"Repeated benchmark changed deterministic outcome: {a['id']} / {key}")
        for filename in ("workload.json", "initial.json", "input.replay.json", "final.png"):
            if (root / "first" / a["id"] / filename).read_bytes() != (root / "second" / b["id"] / filename).read_bytes():
                raise SystemExit("Repeated suite changed a pinned artifact")
        png = root / "first" / a["id"] / "final.png"
        if hashlib.sha256(png.read_bytes()).hexdigest() != a["imageHash"]:
            raise SystemExit("Benchmark final image hash is incorrect")

    history = root / "first/history"
    subprocess.run([str(launcher), "--headless", "--frames", "24", "--size", "96x64", "--replay", str(history / "input.replay.json"),
                    "--capture", str(root / "replayed.png"), "--save-checkpoint", str(root / "replayed.vstate")], check=True)
    if (root / "replayed.png").read_bytes() != (history / "final.png").read_bytes():
        raise SystemExit("Suite final image differs from ordinary replay execution")
    report = next(case for case in first["cases"] if case["id"] == "history")
    with zipfile.ZipFile(root / "replayed.vstate") as saved:
        manifest = json.loads(saved.read("manifest.json"))
        actual = {name: hashlib.sha256(saved.read(f"state/{name}.bin")).hexdigest() for name in manifest["resources"]}
        if actual != report["stateHashes"] or manifest["signals"] != report["signals"] or manifest["simulation"] != report["simulation"]:
            raise SystemExit("Suite state differs from ordinary replay execution")

    for name, extra in (("normal", []), ("timed", ["--benchmark", str(root / "single.json")])):
        subprocess.run([str(launcher), "--headless", "--frames", "64", "--size", "96x64", "--grid", "65",
                        "--capture", str(root / f"{name}.png"), "--save-checkpoint", str(root / f"{name}.vstate"), *extra], check=True)
    if (root / "normal.png").read_bytes() != (root / "timed.png").read_bytes():
        raise SystemExit("Single-scene benchmarking changed image output")
    with zipfile.ZipFile(root / "normal.vstate") as a, zipfile.ZipFile(root / "timed.vstate") as b:
        if set(a.namelist()) != set(b.namelist()) or any(a.read(name) != b.read(name) for name in a.namelist() if name != "manifest.json"):
            raise SystemExit("Single-scene benchmarking changed GPU state")
        if json.loads(a.read("manifest.json")) != json.loads(b.read("manifest.json")):
            raise SystemExit("Single-scene benchmarking changed clocks or metadata")
    single = json.loads((root / "single.json").read_text())
    validate(single, 30, 34, 2)
    if single["referenceTick"] != 128:
        raise SystemExit("Single-scene benchmark lost its final tick")

    before = (root / "first/suite.json").read_bytes()
    failed = subprocess.run([str(launcher), "--benchmark-suite", str(root / "first"), "--benchmark-profile", "smoke"], capture_output=True, text=True)
    if failed.returncode == 0 or (root / "first/suite.json").read_bytes() != before:
        raise SystemExit("Suite overwrote an existing destination")
    bad = root / "bad shaders"
    bad.mkdir()
    failed = subprocess.run([str(launcher), "--benchmark-suite", str(root / "failed"), "--benchmark-profile", "smoke", "--shaders", str(bad)], capture_output=True, text=True)
    remaining = list(root.glob(".benchmark-pending-*"))
    if failed.returncode == 0 or (root / "failed").exists() or remaining:
        raise SystemExit(f"Failed suite preservation: exit={failed.returncode}, destination={(root / 'failed').exists()}, staging={[p.name for p in remaining]}\n{failed.stdout}\n{failed.stderr[-3000:]}")
    print("Installed-launcher benchmark check passed: unique query coverage, seven repeatable workload/image/state outcomes, ordinary replay equivalence and destination/failure preservation")
