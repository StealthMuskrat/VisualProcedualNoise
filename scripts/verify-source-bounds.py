#!/usr/bin/env python3
"""Verify generated-gradient rejection and bounded graph-value continuation through the launcher."""

from pathlib import Path
import argparse
import copy
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
if not launcher.is_file():
    raise SystemExit("Run check installDist before verify-source-bounds.py")

control = "a" * 48
def expression(divisions):
    return "p.x" + (f"/(({control}+{control})+{control})" * divisions)

def checkpoint(path):
    with zipfile.ZipFile(path) as archive:
        return json.loads(archive.read("manifest.json")), {
            name: archive.read(name) for name in archive.namelist() if name != "manifest.json"}

with tempfile.TemporaryDirectory(prefix="source-bound-check-", dir=reports) as temporary:
    root = Path(temporary) / "Generated source études with spaces"
    root.mkdir()
    environment = dict(os.environ, LOCALAPPDATA=str(root / "appdata"))
    effect = {"effectApiVersion": 2, "id": "local.source-bound", "version": "1.0.0",
              "authoring": "expression", "expression": expression(24),
              "controls": [{"id": control, "label": "Denominator", "type": "FLOAT", "units": "",
                            "initial": [1.0], "minimum": 0.5, "maximum": 2.0, "step": 0.01,
                            "scale": "LINEAR", "update": "UNIFORM"}], "controlValues": {control: [1.0]}}
    common = [str(launcher), "--headless", "--size", "96x64"]

    def run(name, frames, source):
        configuration = [] if "--checkpoint" in source else ["--grid", "65"]
        result = subprocess.run([*common, *configuration, "--frames", str(frames), *source,
                                 "--capture", str(root / f"{name}.png"),
                                 "--save-checkpoint", str(root / f"{name}.vstate")],
                                env=environment, capture_output=True, text=True, timeout=120)
        if result.returncode:
            raise SystemExit(result.stdout + result.stderr)
        return checkpoint(root / f"{name}.vstate")

    path = root / "effect.json"
    path.write_text(json.dumps(effect), encoding="utf-8")
    run("bounded", 4, ["--effect", str(path)])
    outputs = [root / "bounded.png", root / "bounded.png.json", root / "bounded.vstate"]
    preserved = {output: output.read_bytes() for output in outputs}
    over = copy.deepcopy(effect)
    over["expression"] = expression(26)
    path.write_text(json.dumps(over), encoding="utf-8")
    result = subprocess.run([*common, "--frames", "4", "--effect", str(path),
                             "--capture", str(outputs[0]), "--save-checkpoint", str(outputs[2])],
                            env=environment, capture_output=True, text=True, timeout=120)
    if result.returncode == 0 or "expression and gradient exceed 64 KiB" not in result.stderr or "line 1, column 1" not in result.stderr:
        raise SystemExit("Missing located generated-gradient bound: " + result.stdout + result.stderr)
    if any(output.read_bytes() != contents for output, contents in preserved.items()):
        raise SystemExit("Rejected gradient changed existing image, metadata or checkpoint")

    over["authoring"] = "graph"
    field = {"id": "field", "kind": "FORMULA", "inputs": [], "expression": expression(26)}
    over["graph"] = {"output": "field", "nodes": [field], "groups": []}
    path.write_text(json.dumps(over), encoding="utf-8")
    graph = run("graph", 4, ["--effect", str(path)])
    full = run("full", 8, ["--effect", str(path)])
    resumed = run("resumed", 4, ["--checkpoint", str(root / "graph.vstate")])
    if full != resumed or (root / "full.png").read_bytes() != (root / "resumed.png").read_bytes() or full[0]["tick"] != 16:
        raise SystemExit("Bounded graph checkpoint continuation changed image, source identity, clocks or GPU resources")
    field["kind"], field["inputs"] = "EXPRESSION", ["base"]
    over["graph"]["nodes"].insert(0, {"id": "base", "kind": "FORMULA", "inputs": [], "expression": "0"})
    path.write_text(json.dumps(over), encoding="utf-8")
    alternate = run("expression-node", 4, ["--effect", str(path)])
    context = ("tick", "width", "height", "signals", "pointer", "simulation", "resources")
    if graph[1] != alternate[1] or any(graph[0][key] != alternate[0][key] for key in context) or (root / "graph.png").read_bytes() != (root / "expression-node.png").read_bytes():
        raise SystemExit("Graph formula/expression values changed image, clocks or GPU resources")
    summary = {"rejectedSourceCharacters": len(expression(26)), "continuedTick": full[0]["tick"],
               "outcome": "located gradient rejection, preserved destinations, bounded graph values and exact checkpoint continuation"}
    (reports / "source-bounds-verification.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print("Installed-launcher source-bounds check passed: located gradient limit, preserved outputs, bounded graph values and exact image/state/checkpoint continuation")
