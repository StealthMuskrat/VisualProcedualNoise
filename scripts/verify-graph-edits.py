#!/usr/bin/env python3
"""Verify renamed root/group graphs preserve rendering, state and checkpoint continuation."""

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
fixture = reports / "graph-edit-fixture.json"
if not launcher.is_file() or not fixture.is_file():
    raise SystemExit("Run check installDist before verify-graph-edits.py")
documents = json.loads(fixture.read_text(encoding="utf-8"))

def checkpoint(path):
    with zipfile.ZipFile(path) as archive:
        return json.loads(archive.read("manifest.json")), {
            name: archive.read(name) for name in archive.namelist() if name != "manifest.json"}

def renamed_context(header):
    result = copy.deepcopy(header)
    result.pop("programSignature")
    result["preset"]["settings"].pop("graph")
    return result

with tempfile.TemporaryDirectory(prefix="graph-edit-check-", dir=reports) as temporary:
    root = Path(temporary) / "Graph rename études with spaces"
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
    for prefix in ("", "group"):
        old_key, new_key = ("original", "renamed") if not prefix else ("groupOriginal", "groupRenamed")
        original = run(f"{old_key}-frame", 8, ["--preset", str(root / f"{old_key}.json")])
        renamed = run(f"{new_key}-frame", 8, ["--preset", str(root / f"{new_key}.json")])
        before, after = root / f"{old_key}-frame.png", root / f"{new_key}-frame.png"
        if before.read_bytes() != after.read_bytes() or original[1] != renamed[1] or renamed_context(original[0]) != renamed_context(renamed[0]):
            raise SystemExit(f"Renamed {prefix or 'root'} graph changed pixels, GPU resources, clocks, controls or scene context")
        if original[0]["programSignature"] == renamed[0]["programSignature"] or renamed[0]["tick"] != 16:
            raise SystemExit("Rename did not exercise a distinct generated source at the expected tick")
        if renamed[0]["preset"]["settings"]["graph"] != documents[new_key]["settings"]["graph"]:
            raise SystemExit("Renamed graph IDs, connections, seed keys or pinned groups did not persist")
        full = run(f"{new_key}-full", 12, ["--preset", str(root / f"{new_key}.json")])
        resumed = run(f"{new_key}-resumed", 4, ["--checkpoint", str(root / f"{new_key}-frame.vstate")])
        if full != resumed or full[0]["tick"] != 24 or (root / f"{new_key}-full.png").read_bytes() != (root / f"{new_key}-resumed.png").read_bytes():
            raise SystemExit(f"Renamed {prefix or 'root'} graph checkpoint continuation changed image, source identity, clocks or GPU state")
        outputs = [after, Path(str(after) + ".json"), root / f"{new_key}-frame.vstate"]
        preserved = {path: path.read_bytes() for path in outputs}
        invalid = copy.deepcopy(documents[new_key])
        invalid["settings"]["graph"]["nodes"][-1]["id"] = invalid["settings"]["graph"]["nodes"][0]["id"] if len(invalid["settings"]["graph"]["nodes"]) > 1 else "invalid id"
        invalid_path = root / "invalid.json"
        invalid_path.write_text(json.dumps(invalid), encoding="utf-8")
        rejected = subprocess.run([*common, "--frames", "8", "--preset", str(invalid_path),
                                   "--capture", str(after), "--save-checkpoint", str(outputs[-1])],
                                  env=environment, capture_output=True, text=True, timeout=120)
        if rejected.returncode == 0 or "Invalid or duplicate graph node ID" not in rejected.stderr or any(path.read_bytes() != contents for path, contents in preserved.items()):
            raise SystemExit("Invalid renamed graph did not preserve existing outputs: " + rejected.stdout + rejected.stderr)
        summaries.append({"case": prefix or "root", "referenceTick": 16, "continuedTick": 24,
                          "imageSha256": hashlib.sha256(after.read_bytes()).hexdigest(),
                          "originalProgramSignature": original[0]["programSignature"],
                          "renamedProgramSignature": renamed[0]["programSignature"]})
    (reports / "graph-edit-verification.json").write_text(json.dumps(summaries, indent=2), encoding="utf-8")
    print("Installed-launcher graph-edit check passed: distinct renamed sources, exact pixels/state/clocks, pinned definitions, checkpoint continuation and invalid-output preservation")
