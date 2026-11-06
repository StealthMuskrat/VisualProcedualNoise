#!/usr/bin/env python3
"""Verify installed seam/loop reporting without changing live image, clocks or replay state."""

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
reports = Path("build/reports").resolve()
loop_fixture = reports / "inspection-loop.json"
replay = reports / "morph-replay-fixture.json"
if not launcher.is_file() or not loop_fixture.is_file() or not replay.is_file():
    raise SystemExit("Run coreCheck installDist before verify-inspection.py")

with tempfile.TemporaryDirectory(prefix="inspection-check-", dir=reports) as temporary:
    root = Path(temporary) / "Inspection études with spaces"
    root.mkdir()

    def run(name, source, inspect=False):
        arguments = [str(launcher), "--headless", "--frames", "8", "--size", "96x64", *map(str, source),
                     "--capture", str(root / f"{name}.png"), "--save-checkpoint", str(root / f"{name}.vstate")]
        if inspect:
            arguments += ["--inspect", str(root / f"{name}.json"), "--inspect-size", "127x95"]
        subprocess.run(arguments, check=True)

    def same(left, right):
        if (root / f"{left}.png").read_bytes() != (root / f"{right}.png").read_bytes():
            raise SystemExit("Inspection changed the live finished frame")
        with zipfile.ZipFile(root / f"{left}.vstate") as a, zipfile.ZipFile(root / f"{right}.vstate") as b:
            if set(a.namelist()) != set(b.namelist()):
                raise SystemExit("Inspection changed the GPU resource set")
            for name in a.namelist():
                equal = json.loads(a.read(name)) == json.loads(b.read(name)) if name == "manifest.json" else a.read(name) == b.read(name)
                if not equal:
                    raise SystemExit(f"Inspection changed persistent state: {name}")

    run("stateful", ["--replay", replay])
    run("stateful-inspected", ["--replay", replay], True)
    same("stateful", "stateful-inspected")
    stateful = json.loads((root / "stateful-inspected.json").read_text())
    if stateful["inspection"]["loop"]["sampled"] or stateful["referenceTick"] != 16:
        raise SystemExit("Stateful inspection inferred a loop or changed its reference tick")
    if stateful["controlsWithinTolerance"] or stateful["renderSettings"]["values"]["warp"] >= stateful["settings"]["values"]["warp"]:
        raise SystemExit("Inspection lost unfinished smoothing context")
    if not stateful["pointer"]["brush"] or stateful["pointer"]["x"] != .2:
        raise SystemExit("Inspection lost its replay pointer context")

    run("loop", ["--preset", loop_fixture])
    run("loop-inspected", ["--preset", loop_fixture], True)
    same("loop", "loop-inspected")
    loop = json.loads((root / "loop-inspected.json").read_text())
    result = loop["inspection"]
    if result["size"] != {"width": 127, "height": 95} or result["edges"]["horizontalWrap"]["pixelPairs"] != 95:
        raise SystemExit("Inspection dimensions or edge sample counts are incorrect")
    if not result["loop"]["sampled"] or result["loop"]["seconds"] != 8 or result["loop"]["difference"]["maximum"] > 1:
        raise SystemExit("Configured analytic loop endpoints did not match within one display level")
    if loop["settings"]["debug"] != 8 or loop["renderSettings"]["debug"] != 0 or loop["renderSettings"]["tilePreview"] or loop["renderSettings"]["displayScale"] != 1:
        raise SystemExit("Inspection did not distinguish source settings from actual render conditions")
    if loop["includesReference"] or loop["includesDiagnostics"] or loop["includesWrapPreview"]:
        raise SystemExit("Inspection incorrectly included view overlays")

    destination = root / "preserved.json"
    destination.write_bytes(b"existing inspection")
    failed = subprocess.run([str(launcher), "--headless", "--frames", "1", "--inspect", str(destination),
                             "--inspect-size", "4096x4096"], capture_output=True, text=True)
    if failed.returncode == 0 or destination.read_bytes() != b"existing inspection":
        raise SystemExit("Over-budget inspection changed its existing destination")
    # Failed atomic publication leaves the existing directory and its files intact.
    blocked = root / "blocked.json"
    blocked.mkdir()
    (blocked / "marker").write_bytes(b"keep")
    failed = subprocess.run([str(launcher), "--headless", "--frames", "1", "--size", "96x64", "--grid", "65",
                             "--inspect", str(blocked), "--inspect-size", "96x64"], capture_output=True, text=True)
    if failed.returncode == 0 or (blocked / "marker").read_bytes() != b"keep":
        raise SystemExit("Failed inspection publication replaced an existing destination")
    print("Installed-launcher inspection check passed: spatial/analytic reports, resolved smoothing/pointer context, exact image/GPU/clocks/replay preservation and failure preservation")
