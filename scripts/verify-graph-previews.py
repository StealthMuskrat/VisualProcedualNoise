#!/usr/bin/env python3
"""Verify typed previews match direct fields and persist through exact continuation."""
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
fixture = reports / "graph-preview-fixture.json"
if not launcher.is_file() or not fixture.is_file():
    raise SystemExit("Run check installDist before verify-graph-previews.py")
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

with tempfile.TemporaryDirectory(prefix="graph-previews-", dir=reports) as temporary:
    root = Path(temporary) / "Typed previews études with spaces"
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

    summaries = []
    for name in ("x", "y", "z", "w", "length", "distance", "region"):
        preview = run(name, 8, ["--preset", str(root / f"{name}.json")])
        expected = run(f"{name}Expected", 8, ["--preset", str(root / f"{name}Expected.json")])
        image = (root / f"{name}.png").read_bytes()
        if image != (root / f"{name}Expected.png").read_bytes() or preview[1] != expected[1] or context(preview[0]) != context(expected[0]):
            raise SystemExit(f"{name} preview changed image, GPU state, clocks or controls compared with its direct field")
        if preview[0]["preset"]["settings"]["graph"] != documents[name]["settings"]["graph"]:
            raise SystemExit("Preview adapter, original nodes, pins or seeds did not persist")
        full = run(f"{name}-full", 12, ["--preset", str(root / f"{name}.json")])
        resumed = run(f"{name}-resumed", 4, ["--checkpoint", str(root / f"{name}.vstate")])
        if full != resumed or full[0]["tick"] != 24 or (root / f"{name}-full.png").read_bytes() != (root / f"{name}-resumed.png").read_bytes():
            raise SystemExit(f"{name} preview continuation changed image, source, clocks or GPU state")
        summaries.append({"case": name, "tick": 16, "continuedTick": 24,
                          "programSignature": preview[0]["programSignature"],
                          "imageSha256": hashlib.sha256(image).hexdigest()})
    outputs = [root / "x.png", root / "x.png.json", root / "x.vstate"]
    preserved = {path: path.read_bytes() for path in outputs}
    cases = []
    invalid = copy.deepcopy(documents["x"])
    invalid["settings"]["graph"]["nodes"][-1]["expression"] = "input0.w"
    cases.append(("component", invalid, "Swizzle exceeds vector width"))
    invalid = copy.deepcopy(documents["distance"])
    invalid["settings"]["graph"]["nodes"][-1]["inputs"] = ["coord"]
    cases.append(("distance-type", invalid, "Expected DISTANCE"))
    invalid = copy.deepcopy(documents["region"])
    invalid["settings"]["graph"]["nodes"][-1]["inputs"] = ["coord"]
    cases.append(("region-type", invalid, "Expected REGION_ID"))
    for name, document, diagnostic in cases:
        path = root / f"invalid-{name}.json"
        path.write_text(json.dumps(document), encoding="utf-8")
        rejected = subprocess.run([*common, "--frames", "8", "--preset", str(path),
                                   "--capture", str(outputs[0]), "--save-checkpoint", str(outputs[-1])],
                                  env=environment, capture_output=True, text=True, timeout=120)
        if rejected.returncode == 0 or diagnostic not in rejected.stderr or any(path.read_bytes() != contents for path, contents in preserved.items()):
            raise SystemExit("Invalid preview did not preserve existing outputs: " + rejected.stdout + rejected.stderr)
    (reports / "graph-preview-verification.json").write_text(json.dumps(summaries, indent=2), encoding="utf-8")
    print("Installed-launcher graph-preview check passed: seven typed interpretations, direct-field equality, persistent adapters, checkpoint continuation and rejected-output preservation")
