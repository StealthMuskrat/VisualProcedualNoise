#!/usr/bin/env python3
"""Exercise installed directional effects, map preservation and replay continuation."""

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
if not launcher.is_file() or not (reports / "directional/fibers.replay.json").is_file():
    raise SystemExit("Run coreCheck installDist before verify-directional.py")

with tempfile.TemporaryDirectory(prefix="directional-check-", dir=reports) as temporary:
    root = Path(temporary) / "Directional études"
    root.mkdir()

    def run(name, frames, *arguments):
        subprocess.run([str(launcher), "--headless", "--size", "96x64", "--frames", str(frames),
                        "--capture", str(root / f"{name}.png"), "--save-checkpoint", str(root / f"{name}.vstate"),
                        *map(str, arguments)], check=True)

    def equal(left, right):
        if (root / f"{left}.png").read_bytes() != (root / f"{right}.png").read_bytes():
            raise SystemExit(f"Directional images differ: {left} / {right}")
        with zipfile.ZipFile(root / f"{left}.vstate") as a, zipfile.ZipFile(root / f"{right}.vstate") as b:
            if set(a.namelist()) != set(b.namelist()):
                raise SystemExit("Directional GPU resource set changed")
            for name in a.namelist():
                same = json.loads(a.read(name)) == json.loads(b.read(name)) if name == "manifest.json" else a.read(name) == b.read(name)
                if not same:
                    raise SystemExit(f"Directional state differs: {name}")

    images = []
    for style in ("fibers", "weave"):
        effect = Path(f"examples/effects/gabor-{style}.json").resolve()
        if not effect.is_file():
            raise SystemExit(f"Missing bundled example: {effect}")
        # The same declared effect is loaded through an ordinary portable API 2 manifest.
        run(style, 4, "--effect", effect, "--grid", "65")
        run(style + "-mapped", 4, "--effect", effect, "--grid", "65", "--map", "height",
            "--map-output", root / f"{style}-height.png", "--map-size", "127x95")
        equal(style, style + "-mapped")
        images.append((root / f"{style}.png").read_bytes())
        metadata = json.loads((root / f"{style}-height.png.json").read_text())
        if metadata["mapKind"] != "height" or metadata["settings"]["effectValues"]["carrier"] != [3.2]:
            raise SystemExit("Directional map metadata lost its resolved effect controls")
        replay = reports / f"directional/{style}.replay.json"
        run(style + "-whole", 12, "--replay", replay)
        run(style + "-split", 8, "--replay", replay)
        run(style + "-resumed", 4, "--checkpoint", root / f"{style}-split.vstate")
        equal(style + "-whole", style + "-resumed")
        sidecar = json.loads((root / f"{style}-resumed.png.json").read_text())
        if sidecar["referenceTick"] != 24 or sidecar["settings"]["effectValues"]["bandwidth"] != [2.5]:
            raise SystemExit("Directional replay lost its post-checkpoint smoothing target")
    if images[0] == images[1]:
        raise SystemExit("Directional examples rendered the same image")
    print("Installed-launcher directional check passed: distinct hashed effects, state-preserving maps and exact replay/checkpoint continuation with smoothed controls")
