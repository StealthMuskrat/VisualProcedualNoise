#!/usr/bin/env python3
"""Verify unpacked pinned groups preserve rendering, state, types and checkpoint continuation."""
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
fixture = reports / "group-unpack-fixture.json"
if not launcher.is_file() or not fixture.is_file():
    raise SystemExit("Run check installDist before verify-group-unpack.py")
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

with tempfile.TemporaryDirectory(prefix="group-unpack-", dir=reports) as temporary:
    root = Path(temporary) / "Unpacked groups études with spaces"
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

    references = {name: run(name, 8, ["--preset", str(root / f"{name}.json")]) for name in ("packed", "nestedPacked")}
    summaries = []
    for name, reference in (("unpacked", "packed"), ("nestedUnpacked", "nestedPacked"), ("fullyUnpacked", "nestedPacked")):
        unpacked = run(name, 8, ["--preset", str(root / f"{name}.json")])
        original = references[reference]
        image = (root / f"{name}.png").read_bytes()
        if image != (root / f"{reference}.png").read_bytes() or unpacked[1] != original[1] or context(unpacked[0]) != context(original[0]):
            raise SystemExit(f"{name} changed image, GPU state, clocks or controls")
        if unpacked[0]["programSignature"] == original[0]["programSignature"]:
            raise SystemExit("Unpacking did not exercise a distinct generated source")
        graph = unpacked[0]["preset"]["settings"]["graph"]
        if graph != documents[name]["settings"]["graph"] or graph["groups"] != documents[reference]["settings"]["graph"]["groups"]:
            raise SystemExit("Unpacked aliases, type boundaries, bindings, seeds or pinned definitions did not persist")
        full = run(f"{name}-full", 12, ["--preset", str(root / f"{name}.json")])
        resumed = run(f"{name}-resumed", 4, ["--checkpoint", str(root / f"{name}.vstate")])
        if full != resumed or full[0]["tick"] != 24 or (root / f"{name}-full.png").read_bytes() != (root / f"{name}-resumed.png").read_bytes():
            raise SystemExit(f"{name} continuation changed image, source, clocks or GPU state")
        summaries.append({"case": name, "tick": 16, "continuedTick": 24,
                          "programSignature": unpacked[0]["programSignature"],
                          "imageSha256": hashlib.sha256(image).hexdigest()})
    outputs = [root / "unpacked.png", root / "unpacked.png.json", root / "unpacked.vstate"]
    preserved = {path: path.read_bytes() for path in outputs}
    cases = []
    invalid = copy.deepcopy(documents["unpacked"])
    boundary = next(node for node in invalid["settings"]["graph"]["nodes"] if node["kind"] == "CHECK_SOURCE" and node["expression"] == "VECTOR2")
    boundary["inputs"] = ["time"]
    cases.append(("type", invalid, "Expected VECTOR2"))
    invalid = copy.deepcopy(documents["unpacked"])
    output = next(node for node in invalid["settings"]["graph"]["nodes"] if node["id"] == invalid["settings"]["graph"]["output"])
    output["inputs"] = ["absent"]
    cases.append(("missing-link", invalid, "Missing graph input"))
    invalid = copy.deepcopy(documents["nestedUnpacked"])
    invalid["settings"]["graph"]["groups"] = [group for group in invalid["settings"]["graph"]["groups"] if group["version"] != "1.0.0"]
    cases.append(("missing-revision", invalid, "Missing group revision"))
    for name, document, diagnostic in cases:
        path = root / f"invalid-{name}.json"
        path.write_text(json.dumps(document), encoding="utf-8")
        rejected = subprocess.run([*common, "--frames", "8", "--preset", str(path),
                                   "--capture", str(outputs[0]), "--save-checkpoint", str(outputs[-1])],
                                  env=environment, capture_output=True, text=True, timeout=120)
        if rejected.returncode == 0 or diagnostic not in rejected.stderr or any(path.read_bytes() != contents for path, contents in preserved.items()):
            raise SystemExit("Invalid unpacked graph did not preserve existing outputs: " + rejected.stdout + rejected.stderr)
    (reports / "group-unpack-verification.json").write_text(json.dumps(summaries, indent=2), encoding="utf-8")
    print("Installed-launcher group-unpack check passed: exact pixels/state, typed boundaries, pinned revisions, checkpoint continuation and rejected-output preservation")
