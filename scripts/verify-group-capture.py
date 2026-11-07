#!/usr/bin/env python3
"""Verify named group capture, pinned bindings and exact checkpoint continuation."""
from pathlib import Path
import argparse
import copy
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
fixture = reports / "group-capture-fixture.json"
if not launcher.is_file() or not fixture.is_file():
    raise SystemExit("Run check installDist before verify-group-capture.py")
documents = json.loads(fixture.read_text(encoding="utf-8"))

def checkpoint(path):
    with zipfile.ZipFile(path) as archive:
        return json.loads(archive.read("manifest.json")), {
            name: archive.read(name) for name in archive.namelist() if name != "manifest.json"}

def context(header):
    result = copy.deepcopy(header)
    result.pop("programSignature")
    result["preset"]["settings"].pop("graph")
    return result

with tempfile.TemporaryDirectory(prefix="group-capture-", dir=reports) as temporary:
    root = Path(temporary) / "Named inputs études with spaces"
    root.mkdir()
    environment = dict(os.environ, LOCALAPPDATA=str(root / "appdata"))
    common = [str(launcher), "--headless", "--size", "96x64"]
    for key, document in documents.items():
        (root / f"{key}.json").write_text(json.dumps(document), encoding="utf-8")

    def run(name, frames, source):
        result = subprocess.run([*common, "--frames", str(frames), *source,
                                 "--capture", str(root / f"{name}.png"),
                                 "--save-checkpoint", str(root / f"{name}.vstate")],
                                env=environment, capture_output=True, text=True, timeout=120)
        if result.returncode:
            raise SystemExit(result.stdout + result.stderr)
        return checkpoint(root / f"{name}.vstate")

    original = run("original", 8, ["--preset", str(root / "original.json")])
    pixels = (root / "original.png").read_bytes()
    summaries = []
    for name in ("captured", "nested", "retargeted"):
        captured = run(name, 8, ["--preset", str(root / f"{name}.json")])
        image = (root / f"{name}.png").read_bytes()
        if name != "retargeted" and (image != pixels or captured[1] != original[1] or context(captured[0]) != context(original[0])):
            raise SystemExit(f"{name} group changed image, GPU state, clocks or controls")
        if name == "retargeted" and image == pixels:
            raise SystemExit("Exposed domain reroute did not change the output")
        if captured[0]["programSignature"] == original[0]["programSignature"]:
            raise SystemExit("Capture did not exercise a distinct generated source")
        if captured[0]["preset"]["settings"]["graph"] != documents[name]["settings"]["graph"]:
            raise SystemExit("Named ports, pinned definitions, bindings or seed identities did not persist")
        full = run(f"{name}-full", 12, ["--preset", str(root / f"{name}.json")])
        resumed = run(f"{name}-resumed", 4, ["--checkpoint", str(root / f"{name}.vstate")])
        if full != resumed or full[0]["tick"] != 24 or (root / f"{name}-full.png").read_bytes() != (root / f"{name}-resumed.png").read_bytes():
            raise SystemExit(f"{name} checkpoint continuation changed image, source, clocks or GPU state")
        summaries.append({"case": name, "tick": 16, "continuedTick": 24,
                          "programSignature": captured[0]["programSignature"],
                          "imageSha256": hashlib.sha256(image).hexdigest()})
    outputs = [root / "captured.png", root / "captured.png.json", root / "captured.vstate"]
    preserved = {path: path.read_bytes() for path in outputs}
    cases = []
    invalid = copy.deepcopy(documents["captured"])
    invalid["settings"]["graph"]["nodes"][-1]["inputs"][0] = "time"
    cases.append(("type", invalid, "Expected VECTOR2"))
    invalid = copy.deepcopy(documents["captured"])
    invalid["settings"]["graph"]["groups"][0]["inputs"][1]["name"] = "domain"
    cases.append(("duplicate-port", invalid, "Invalid group input"))
    invalid = copy.deepcopy(documents["captured"])
    invalid["settings"]["graph"]["groups"].append(copy.deepcopy(invalid["settings"]["graph"]["groups"][0]))
    cases.append(("duplicate-revision", invalid, "group revision"))
    invalid = copy.deepcopy(documents["captured"])
    invalid["settings"]["graph"]["nodes"][-1]["inputs"][0] = "absent"
    cases.append(("missing-source", invalid, "Missing graph input"))
    for name, document, diagnostic in cases:
        path = root / f"invalid-{name}.json"
        path.write_text(json.dumps(document), encoding="utf-8")
        rejected = subprocess.run([*common, "--frames", "8", "--preset", str(path),
                                   "--capture", str(outputs[0]), "--save-checkpoint", str(outputs[-1])],
                                  env=environment, capture_output=True, text=True, timeout=120)
        if rejected.returncode == 0 or diagnostic not in rejected.stderr or any(path.read_bytes() != contents for path, contents in preserved.items()):
            raise SystemExit("Invalid group did not preserve existing outputs: " + rejected.stdout + rejected.stderr)
    (reports / "group-capture-verification.json").write_text(json.dumps(summaries, indent=2), encoding="utf-8")
    print("Installed-launcher group-capture check passed: named ports, pinned definitions, exact encapsulation, rerouting, checkpoint continuation and rejected-output preservation")
