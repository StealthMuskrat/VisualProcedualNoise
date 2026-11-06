#!/usr/bin/env python3
"""Verify installed independent still resolution, full quality and unchanged GPU state."""

from pathlib import Path
import argparse
import json
import struct
import subprocess
import tempfile
import zipfile
import shutil
import os

parser = argparse.ArgumentParser()
parser.add_argument("--launcher", type=Path, default=Path("build/install/procedural-visual-engine/bin/procedural-visual-engine"))
options = parser.parse_args()
launcher = options.launcher.resolve()
fixture = Path("build/reports/checkpoint-fixture.json").resolve()
if not launcher.is_file() or not fixture.is_file():
    raise SystemExit("Run coreCheck installDist before verify-still.py")

with tempfile.TemporaryDirectory(prefix="still-check-", dir=Path("build/reports")) as temporary:
    root = Path(temporary)
    common = ["--headless", "--frames", "4", "--size", "320x200", "--preset", str(fixture), "--quality", "draft", "--grid", "65"]
    for name, extra in [("normal", []), ("large", ["--capture-size", "640x400"])]:
        subprocess.run([str(launcher), *common, "--capture", str(root / f"{name}.png"),
                        "--save-checkpoint", str(root / f"{name}.vstate"), *extra], check=True)
    with zipfile.ZipFile(root / "normal.vstate") as a, zipfile.ZipFile(root / "large.vstate") as b:
        if set(a.namelist()) != set(b.namelist()):
            raise SystemExit("Still capture changed resource set")
        for name in a.namelist():
            same = json.loads(a.read(name)) == json.loads(b.read(name)) if name == "manifest.json" else a.read(name) == b.read(name)
            if not same:
                raise SystemExit(f"Still capture changed persistent state: {name}")
    metadata = json.loads((root / "large.png.json").read_text())
    extent = struct.unpack(">II", (root / "large.png").read_bytes()[16:24])
    if extent != (640, 400) or metadata["captureRenderWidth"] != 640 or metadata["captureRenderHeight"] != 400:
        raise SystemExit("Still output or render dimensions are incorrect")
    if metadata["settings"]["grid"] != 65 or metadata["settings"]["displayScale"] != .5 or metadata["referenceTick"] != 8:
        raise SystemExit("Still capture changed live quality or tick")
    if metadata["settings"]["particles"] != 4096 or metadata["settings"]["historyLayers"] != 4:
        raise SystemExit("CLI quality preset or grid override did not resolve")
    full = json.loads(json.dumps(metadata))
    full["settings"]["displayScale"] = 1
    target = root / "full.json"
    target.write_text(json.dumps(full))
    subprocess.run([str(launcher), "--headless", "--frames", "4", "--size", "640x400", "--preset", str(target),
                    "--capture", str(root / "full.png")], check=True)
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    comparison = subprocess.run([java, "scripts/CompareStill.java", str(root / "full.png"), str(root / "large.png")])
    if comparison.returncode:
        for name in ("full.png", "full.png.json", "large.png", "large.png.json", "large.vstate"):
            shutil.copyfile(root / name, Path("build/reports") / ("still-mismatch-" + name))
        raise SystemExit("Independent still differs from native full-resolution rendering")
    print("Installed-launcher still check passed: full-resolution PNG within one 8-bit step on at most 0.1% of pixels; exact persistent state at tick 8")
