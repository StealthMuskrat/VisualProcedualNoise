#!/usr/bin/env python3
"""Verify installed file draining, bounded queue diagnostics and fail-fast export preservation."""

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
    raise SystemExit("Run coreCheck installDist before verify-file-jobs.py")

def same_checkpoint(a, b):
    with zipfile.ZipFile(a) as left, zipfile.ZipFile(b) as right:
        return set(left.namelist()) == set(right.namelist()) and all(
            json.loads(left.read(name)) == json.loads(right.read(name)) if name == "manifest.json"
            else left.read(name) == right.read(name) for name in left.namelist())

def files(root):
    return {str(path.relative_to(root)): path.read_bytes() for path in root.rglob("*") if path.is_file()}

with tempfile.TemporaryDirectory(prefix="file-jobs-check-", dir=reports) as temporary:
    root = Path(temporary) / "File work études with spaces"
    root.mkdir()
    environment = dict(os.environ, LOCALAPPDATA=str(root / "appdata"))
    common = [str(launcher), "--headless", "--size", "96x60", "--replay", str(fixture)]
    def run(extra, success):
        result = subprocess.run([*common, *extra], env=environment, capture_output=True, text=True, timeout=120)
        if (result.returncode == 0) != success:
            raise SystemExit(result.stdout + result.stderr)
        return result
    snapshots = []
    for name in ("first", "second"):
        directory = root / name
        run(["--export", str(directory), "--frames", "32", "--fps", "60",
             "--save-checkpoint", str(root / f"{name}.vstate"), "--benchmark", str(root / f"{name}.json")], True)
        images = sorted(directory.glob("frame-*.png"))
        if len(images) != 32 or len(list(directory.glob("frame-*.png.json"))) != 32:
            raise SystemExit("Shutdown did not finish every accepted frame and sidecar")
        manifest = json.loads((directory / "sequence.json").read_text(encoding="utf-8"))
        if manifest["frames"] != 32 or manifest["referenceTick"] != 62:
            raise SystemExit("Sequence manifest was published before its completed fixed-tick output")
        queue = json.loads((root / f"{name}.json").read_text(encoding="utf-8"))["fileQueue"]
        if queue["state"] != "OPEN" or queue["capacity"] != 2 or not queue["stopOnFailure"] or not 0 <= queue["queued"] <= 2:
            raise SystemExit("Offline queue diagnostics lack their bounded policy and live snapshot")
        if queue["accepted"] != 34 or queue["failed"] or queue["cancelled"] or queue["accepted"] != queue["completed"] + queue["queued"] + int(queue["running"]):
            raise SystemExit("Queue counters disagree with accepted frame/checkpoint/manifest work")
        snapshots.append(queue)
        if list(directory.glob(".visual-output-*")):
            raise SystemExit("Normal file drain left paired-output staging")
    if files(root / "first") != files(root / "second") or not same_checkpoint(root / "first.vstate", root / "second.vstate"):
        raise SystemExit("Queue integration changed images, metadata, GPU bytes, clocks or replay")
    failed = root / "failed"
    failed.mkdir()
    (failed / "frame-000000.png").write_bytes((root / "first/frame-000000.png").read_bytes())
    blocked = failed / "frame-000000.png.json"
    blocked.mkdir()
    (blocked / "marker").write_text("keep", encoding="utf-8")
    for name in ("frame-000001.png", "frame-000002.png", "sequence.json", "after.vstate", "after.json"):
        (failed / name).write_text("previous output", encoding="utf-8")
    before = files(failed)
    result = run(["--export", str(failed), "--frames", "256", "--fps", "60",
                  "--save-checkpoint", str(failed / "after.vstate"), "--benchmark", str(failed / "after.json")], False)
    if "not a regular file" not in result.stderr or "A file operation failed" not in result.stderr or files(failed) != before:
        raise SystemExit("First file failure did not cancel later queued output and preserve previous destinations")
    if "Rendered 256 frames" in result.stdout or list(failed.glob(".visual-output-*")):
        raise SystemExit("Failed export completed the requested frame loop or left normal staging")
    # A final asynchronous write must fail the process even when rendering has already ended.
    last = root / "last.png"
    last.write_bytes(b"previous final image")
    Path(str(last) + ".json").mkdir()
    result = run(["--frames", "1", "--capture", str(last)], False)
    if "Application shutdown failed" not in result.stderr or last.read_bytes() != b"previous final image":
        raise SystemExit("Shutdown failed to surface its final asynchronous file failure")
    summary = {"queueSnapshots": snapshots, "sequenceFrames": 32, "referenceTick": 62,
               "firstFrameSha256": hashlib.sha256((root / "first/frame-000000.png").read_bytes()).hexdigest(),
               "outcome": "complete draining, exact sequence/checkpoint equivalence, bounded counters, fail-fast cancellation and final-write shutdown failure"}
    (reports / "file-jobs-verification.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print("Installed-launcher file-worker check passed: complete draining, exact sequence/state, bounded counters, fail-fast output preservation and final-write shutdown failure")
