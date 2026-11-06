#!/usr/bin/env python3
"""Verify installed morph resolution, ordinary replay events and checkpoint continuation."""

from pathlib import Path
import argparse
import json
import math
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("--launcher", type=Path, default=Path("build/install/procedural-visual-engine/bin/procedural-visual-engine"))
options = parser.parse_args()
launcher = options.launcher.resolve()
reports = Path("build/reports").resolve()
sources = {name: reports / f"morph-{name}.json" for name in ("a", "b", "half")}
take = reports / "morph-replay-fixture.json"
if not launcher.is_file() or not take.is_file() or not all(p.is_file() for p in sources.values()):
    raise SystemExit("Run coreCheck installDist before verify-morph.py")

with tempfile.TemporaryDirectory(prefix="morph-check-", dir=reports) as temporary:
    root = Path(temporary) / "Morph études with spaces"
    root.mkdir()
    target = root / "target preset.json"
    target.write_bytes(sources["b"].read_bytes())

    def run(name, frames=4, *arguments):
        subprocess.run([str(launcher), "--headless", "--frames", str(frames), "--size", "96x64",
                        "--capture", str(root / f"{name}.png"),
                        "--save-checkpoint", str(root / f"{name}.vstate"), *map(str, arguments)], check=True)

    def same_state(left, right):
        with zipfile.ZipFile(root / f"{left}.vstate") as a, zipfile.ZipFile(root / f"{right}.vstate") as b:
            if set(a.namelist()) != set(b.namelist()):
                raise SystemExit("Morph continuation changed the GPU resource set")
            for name in a.namelist():
                same = json.loads(a.read(name)) == json.loads(b.read(name)) if name == "manifest.json" else a.read(name) == b.read(name)
                if not same:
                    raise SystemExit(f"Morph state differs: {left} / {right} / {name}")

    for label, amount in (("a", "0"), ("half", ".5"), ("b", "1")):
        run("expected-" + label, 4, "--preset", sources[label])
        run("actual-" + label, 4, "--preset", sources["a"], "--morph-target", target, "--morph-amount", amount)
        if (root / f"expected-{label}.png").read_bytes() != (root / f"actual-{label}.png").read_bytes():
            raise SystemExit(f"CLI morph image differs from resolved preset: {label}")
        same_state("expected-" + label, "actual-" + label)

    run("uninterrupted", 12, "--replay", take)
    run("split", 8, "--replay", take)
    run("resumed", 4, "--checkpoint", root / "split.vstate")
    if (root / "uninterrupted.png").read_bytes() != (root / "resumed.png").read_bytes():
        raise SystemExit("Morph replay checkpoint changed the final image")
    same_state("uninterrupted", "resumed")
    metadata = json.loads((root / "resumed.png.json").read_text())
    if metadata["referenceTick"] != 24 or not math.isclose(metadata["settings"]["values"]["warp"], 1.5125, abs_tol=1e-12):
        raise SystemExit("Morph replay did not restore and apply its second control event")

    invalid = json.loads(target.read_text())
    invalid["settings"]["palette"] = 3
    target.write_text(json.dumps(invalid))
    preserved = root / "preserved.png"
    preserved.write_bytes(b"existing image")
    sidecar = root / "preserved.png.json"
    sidecar.write_bytes(b"existing metadata")
    failure = subprocess.run([str(launcher), "--headless", "--frames", "1", "--size", "96x64",
                              "--preset", str(sources["a"]), "--morph-target", str(target),
                              "--morph-amount", ".5", "--capture", str(preserved)], capture_output=True, text=True)
    if failure.returncode == 0 or "palette differs" not in failure.stderr + failure.stdout:
        raise SystemExit("Incompatible CLI morph did not explain its rejection")
    if preserved.read_bytes() != b"existing image" or sidecar.read_bytes() != b"existing metadata":
        raise SystemExit("Rejected morph changed an existing destination")
    print("Installed-launcher morph check passed: exact endpoints/midpoint, replay checkpoint image/clocks/GPU state and incompatible-output preservation")
