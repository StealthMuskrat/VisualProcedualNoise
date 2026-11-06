#!/usr/bin/env python3
"""Exercise installed bookmarks, restart policy, provenance and saved-image references."""

from pathlib import Path
import argparse
import hashlib
import json
import os
import struct
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("--launcher", type=Path, default=Path("build/install/procedural-visual-engine/bin/procedural-visual-engine"))
options = parser.parse_args()
launcher = options.launcher.resolve()
fixture = Path("build/reports/checkpoint-fixture.json").resolve()
if not launcher.is_file() or not fixture.is_file():
    raise SystemExit("Run coreCheck installDist before verify-discovery.py")

def same_state(a, b):
    with zipfile.ZipFile(a) as x, zipfile.ZipFile(b) as y:
        if set(x.namelist()) != set(y.namelist()):
            raise SystemExit("Bookmark changed the persistent resource set")
        for name in x.namelist():
            same = json.loads(x.read(name)) == json.loads(y.read(name)) if name == "manifest.json" else x.read(name) == y.read(name)
            if not same:
                raise SystemExit(f"Bookmark changed simulation state: {name}")

with tempfile.TemporaryDirectory(prefix="discovery-check-", dir=Path("build/reports")) as temporary:
    root = Path(temporary)
    history = root / "études and studies"
    common = ["--headless", "--frames", "4", "--size", "320x200", "--preset", str(fixture), "--seed", "-9223372036854775808"]
    for name, extra in [("plain", []), ("bookmarked", ["--bookmark", "Opal discovery", "--discoveries", str(history)])]:
        subprocess.run([str(launcher), *common, "--capture", str(root / f"{name}.png"),
                        "--save-checkpoint", str(root / f"{name}.vstate"), *extra], check=True)
    same_state(root / "plain.vstate", root / "bookmarked.vstate")
    if (root / "plain.png").read_bytes() != (root / "bookmarked.png").read_bytes():
        raise SystemExit("Bookmark changed the live image")
    folders = [p for p in history.iterdir() if p.is_dir()]
    if len(folders) != 1 or folders[0].name.startswith(".pending-"):
        raise SystemExit("Bookmark did not publish one completed bundle")
    entry = folders[0]
    document = json.loads((entry / "artwork.png.json").read_text())
    metadata = document["discovery"]
    if document["rootSeed"] != "-9223372036854775808" or document["referenceTick"] != 8 or metadata["reproducibility"] != "configuration-restart":
        raise SystemExit("Discovery did not preserve seed, tick or restart policy")
    for filename, key in [("artwork.png", "artworkHash"), ("thumbnail.png", "thumbnailHash")]:
        if hashlib.sha256((entry / filename).read_bytes()).hexdigest() != metadata[key]:
            raise SystemExit("Discovery image checksum is incorrect")
    if struct.unpack(">II", (entry / "thumbnail.png").read_bytes()[16:24]) != (160, 100):
        raise SystemExit("Discovery thumbnail has the wrong extent")
    subprocess.run([str(launcher), "--export", str(root / "restart"), "--frames", "1", "--size", "320x200",
                    "--discoveries", str(history), "--discovery", entry.name, "--save-checkpoint", str(root / "restart.vstate")], check=True)
    with zipfile.ZipFile(root / "restart.vstate") as saved:
        state = json.loads(saved.read("manifest.json"))
    if state["tick"] != 0 or state["simulation"]["history"]["count"] != 0:
        raise SystemExit("Discovery incorrectly resumed developed simulation state")
    subprocess.run([str(launcher), "--headless", "--frames", "4", "--size", "320x200", "--discoveries", str(history),
                    "--discovery", entry.name, "--capture", str(root / "reopened.png"), "--bookmark", "Child study"], check=True)
    if (root / "reopened.png").read_bytes() != (root / "plain.png").read_bytes():
        raise SystemExit("Discovery configuration did not reproduce the same reset-to-tick run")
    child = next(p for p in history.iterdir() if p.is_dir() and p != entry)
    if json.loads((child / "artwork.png.json").read_text())["discovery"]["parent"] != entry.name:
        raise SystemExit("Discovery child did not preserve provenance")
    subprocess.run([str(launcher), "--headless", "--frames", "1", "--size", "320x200", "--recipe", "liquid-opal",
                    "--discoveries", str(history), "--reference-discovery", entry.name, "--capture", str(root / "reference.png")], check=True)
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    subprocess.run([java, "scripts/CompareStill.java", str(entry / "artwork.png"), str(root / "reference.png"), "left"], check=True)
    with (entry / "artwork.png").open("ab") as file:
        file.write(b"corrupt")
    invalid = subprocess.run([str(launcher), "--headless", "--frames", "1", "--size", "96x64", "--discoveries", str(history),
                              "--reference-discovery", entry.name, "--capture", str(root / "invalid.png")], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if invalid.returncode == 0 or (root / "invalid.png").exists():
        raise SystemExit("Corrupt reference artwork was accepted")
    print("Installed-launcher discovery check passed: exact state, restart reproduction, lineage, reference image and corruption rejection")
