#!/usr/bin/env python3
"""Verify installed authoring errors and preservation of existing output destinations."""

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
    raise SystemExit("Run check installDist before verify-authoring-diagnostics.py")

with tempfile.TemporaryDirectory(prefix="authoring-diagnostic-check-", dir=reports) as temporary:
    root = Path(temporary) / "Authoring études with spaces"
    root.mkdir()
    environment = dict(os.environ, LOCALAPPDATA=str(root / "appdata"))
    original = json.loads(Path("examples/effects/ported-ripple.json").read_text(encoding="utf-8"))
    original.pop("definitionHash", None)
    common = [str(launcher), "--headless", "--size", "96x64", "--frames", "8", "--grid", "65"]

    def execute(effect, extra):
        path = root / "effect.json"
        path.write_text(json.dumps(effect), encoding="utf-8")
        return subprocess.run([*common, "--effect", str(path), *extra], env=environment,
                              capture_output=True, text=True, timeout=120)

    image, metadata, checkpoint = root / "artwork.png", root / "artwork.png.json", root / "scene.vstate"
    result = execute(original, ["--capture", str(image), "--save-checkpoint", str(checkpoint)])
    if result.returncode:
        raise SystemExit(result.stdout + result.stderr)
    preserved = {path: path.read_bytes() for path in (image, metadata, checkpoint)}
    cases = []

    def reject(name, effect, fragments):
        result = execute(effect, ["--capture", str(image), "--save-checkpoint", str(checkpoint)])
        if result.returncode == 0 or any(fragment not in result.stderr for fragment in fragments):
            raise SystemExit(f"Missing {name} diagnostic: " + result.stdout + result.stderr)
        if any(path.read_bytes() != data for path, data in preserved.items()):
            raise SystemExit(f"Rejected {name} changed an existing output")
        cases.append({"case": name, "diagnostic": fragments})

    bad = copy.deepcopy(original)
    bad["authoring"] = "expression"
    bad["expression"] = "sin(p.x)\r\n + missing"
    reject("multiline symbol", bad, ["Unknown symbol: missing", "line 2, column 4"])
    bad["expression"] = "noise3d(p)"
    reject("function argument type", bad, ["Expected VEC3, found VEC2", "line 1, column 9"])
    bad["expression"] = "vec2(1)"
    reject("field output type", bad, ["Field expression must be scalar", "line 1, column 1"])
    bad = copy.deepcopy(original)
    bad["graph"]["groups"][0]["nodes"][-1]["expression"] = "input0.z"
    reject("group expression", bad, ["Node ripple / field", "Swizzle exceeds vector width", "line 1, column 8"])
    bad = copy.deepcopy(original)
    bad["graph"]["nodes"][-1]["inputs"][0] = "absent"
    reject("missing connection", bad, ["Node ripple", "Missing graph input: absent"])
    result = execute(original, ["--capture", str(root / "corrected.png"), "--save-checkpoint", str(root / "corrected.vstate")])
    if result.returncode or (root / "corrected.png").read_bytes() != preserved[image]:
        raise SystemExit("Valid effect failed after rejected imports: " + result.stdout + result.stderr)
    with zipfile.ZipFile(checkpoint) as left, zipfile.ZipFile(root / "corrected.vstate") as right:
        if set(left.namelist()) != set(right.namelist()):
            raise SystemExit("Valid rerun changed the checkpoint resource set")
        changed = [name for name in left.namelist() if (
            json.loads(left.read(name)) != json.loads(right.read(name)) if name == "manifest.json"
            else left.read(name) != right.read(name))]
        if changed:
            raise SystemExit("Valid rerun changed source identity, GPU bytes, clocks or replay state: " + ", ".join(changed))
    summary = {"rejectedCases": cases, "outcome": "located errors, original group node paths, unchanged destinations and exact valid rerun"}
    (reports / "authoring-diagnostics-verification.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print("Installed-launcher authoring-diagnostics check passed: five located failures, original node paths, preserved outputs and exact valid image/state rerun")
