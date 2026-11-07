#!/usr/bin/env python3
"""Verify ordered connection persistence, restored output, continuation and rejection isolation."""

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
fixture = reports / "graph-connection-fixture.json"
if not launcher.is_file() or not fixture.is_file():
    raise SystemExit("Run check installDist before verify-graph-connections.py")
documents = json.loads(fixture.read_text(encoding="utf-8"))

def checkpoint(path):
    with zipfile.ZipFile(path) as archive:
        return json.loads(archive.read("manifest.json")), {
            name: archive.read(name) for name in archive.namelist() if name != "manifest.json"}

with tempfile.TemporaryDirectory(prefix="graph-connections-", dir=reports) as temporary:
    root = Path(temporary) / "Ordered links études with spaces"
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

    states = {name: run(name, 8, ["--preset", str(root / f"{name}.json")]) for name in documents}
    if states["original"] != states["restored"] or (root / "original.png").read_bytes() != (root / "restored.png").read_bytes():
        raise SystemExit("Restored connection changed shader identity, image, clocks or GPU resources")
    if states["original"][0]["programSignature"] == states["rewired"][0]["programSignature"] or (root / "original.png").read_bytes() == (root / "rewired.png").read_bytes():
        raise SystemExit("Rerouted graph did not exercise distinct source and output")
    if states["rewired"][0]["preset"]["settings"]["graph"] != documents["rewired"]["settings"]["graph"]:
        raise SystemExit("Ordered connections, metadata or seed identities did not persist")
    full = run("rewired-full", 12, ["--preset", str(root / "rewired.json")])
    resumed = run("rewired-resumed", 4, ["--checkpoint", str(root / "rewired.vstate")])
    if full != resumed or full[0]["tick"] != 24 or (root / "rewired-full.png").read_bytes() != (root / "rewired-resumed.png").read_bytes():
        raise SystemExit("Rewired checkpoint continuation changed image, clocks, source or GPU state")
    outputs = [root / "rewired.png", root / "rewired.png.json", root / "rewired.vstate"]
    preserved = {path: path.read_bytes() for path in outputs}
    cases = [("type", ["time", "shifted"], "Expected VECTOR2"),
             ("arity", ["time"], "requires 2 inputs"),
             ("missing", ["absent", "time"], "Missing graph input"),
             ("cycle", ["field", "time"], "Same-step cycle")]
    for name, links, diagnostic in cases:
        invalid = copy.deepcopy(documents["rewired"])
        invalid["settings"]["graph"]["nodes"][2]["inputs"] = links
        path = root / f"invalid-{name}.json"
        path.write_text(json.dumps(invalid), encoding="utf-8")
        rejected = subprocess.run([*common, "--frames", "8", "--preset", str(path),
                                   "--capture", str(outputs[0]), "--save-checkpoint", str(outputs[-1])],
                                  env=environment, capture_output=True, text=True, timeout=120)
        if rejected.returncode == 0 or diagnostic not in rejected.stderr or any(path.read_bytes() != contents for path, contents in preserved.items()):
            raise SystemExit("Invalid connection import did not preserve existing outputs: " + rejected.stdout + rejected.stderr)
    report = {"referenceTick": 16, "continuedTick": 24, "rejectedDrafts": [case[0] for case in cases],
              "originalProgramSignature": states["original"][0]["programSignature"],
              "rewiredProgramSignature": states["rewired"][0]["programSignature"],
              "rewiredImageSha256": hashlib.sha256(outputs[0].read_bytes()).hexdigest()}
    (reports / "graph-connection-verification.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print("Installed-launcher graph-connection check passed: ordered persistence, distinct rerouted output, exact restored state, checkpoint continuation and rejected-output preservation")
