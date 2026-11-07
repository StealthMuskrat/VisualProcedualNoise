#!/usr/bin/env python3
"""Verify installed offline isolation from desktop presentation preferences."""

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
replay = reports / "morph-replay-fixture.json"
if not launcher.is_file() or not replay.is_file():
    raise SystemExit("Run coreCheck installDist before verify-presentation.py")

with tempfile.TemporaryDirectory(prefix="presentation-check-", dir=reports) as temporary:
    root = Path(temporary) / "Presentation études with spaces"
    root.mkdir()
    app = root / "appdata/ProceduralVisualEngine"
    app.mkdir(parents=True)
    preferences = app / "presentation.json"
    environment = dict(os.environ, LOCALAPPDATA=str(app.parent))
    for name, content in (("missing", None), ("legacy", '{"schemaVersion":1,"vsync":true,"frameLimit":1}'),
                          ("suspend", '{"schemaVersion":2,"vsync":true,"frameLimit":1,"suspendWhenMinimized":true}'),
                          ("continue", '{"schemaVersion":2,"vsync":true,"frameLimit":1,"suspendWhenMinimized":false}'),
                          ("corrupt", "invalid preferences " * 1000)):
        if content is not None:
            preferences.write_text(content)
        completed = subprocess.run([str(launcher), "--headless", "--frames", "32", "--size", "96x64",
                                    "--replay", str(replay), "--capture", str(root / f"{name}.png"),
                                    "--save-checkpoint", str(root / f"{name}.vstate"), "--benchmark", str(root / f"{name}.json")],
                                   env=environment, capture_output=True, text=True)
        if completed.returncode:
            raise SystemExit(completed.stdout + completed.stderr)
        if "Using default presentation" in completed.stdout:
            raise SystemExit("Offline mode attempted to load desktop preferences")
        report = json.loads((root / f"{name}.json").read_text())
        if report["presentationRequest"] != {"vsync": False, "frameLimit": 0, "suspendWhenMinimized": False} or report["referenceTick"] != 64:
            raise SystemExit("Offline mode applied desktop presentation timing")
        if content is None and preferences.exists() or content is not None and preferences.read_text() != content:
            raise SystemExit("Offline mode changed presentation preferences")
    for name in ("legacy", "suspend", "continue", "corrupt"):
        if (root / f"{name}.png").read_bytes() != (root / "missing.png").read_bytes():
            raise SystemExit("Presentation preferences changed offline image output")
        with zipfile.ZipFile(root / "missing.vstate") as a, zipfile.ZipFile(root / f"{name}.vstate") as b:
            if set(a.namelist()) != set(b.namelist()):
                raise SystemExit("Presentation preferences changed offline resource set")
            for entry in a.namelist():
                same = json.loads(a.read(entry)) == json.loads(b.read(entry)) if entry == "manifest.json" else a.read(entry) == b.read(entry)
                if not same:
                    raise SystemExit(f"Presentation preferences changed offline clocks/replay/GPU state: {entry}")
    preserved = root / "preserved.png"
    preserved.write_bytes(b"existing image")
    invalid = subprocess.run([str(launcher), "--headless", "--frames", "1", "--vsync", "on", "--capture", str(preserved)],
                             env=environment, capture_output=True, text=True)
    if invalid.returncode == 0 or preserved.read_bytes() != b"existing image":
        raise SystemExit("Rejected desktop-only override changed its destination")
    help_result = subprocess.run([str(launcher), "--help"], capture_output=True, text=True, check=True)
    if "--vsync on|off" not in help_result.stdout or "--frame-limit" not in help_result.stdout:
        raise SystemExit("Installed help does not expose presentation overrides")
    print("Installed-launcher presentation check passed: offline preferences ignored and unchanged; exact image, clocks, replay and GPU state; desktop-only override preservation")
