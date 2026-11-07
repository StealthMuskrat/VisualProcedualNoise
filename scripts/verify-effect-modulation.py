#!/usr/bin/env python3
"""Verify installed custom-control modulation, map metadata and checkpoint/replay continuation."""

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
fixtures = reports / "effect-modulation"
example = Path("examples/presets/modulated-custom.json").resolve()
if not launcher.is_file() or not (fixtures / "replay.json").is_file() or not example.is_file():
    raise SystemExit("Run coreCheck installDist before verify-effect-modulation.py")

def same_checkpoint(a, b):
    with zipfile.ZipFile(a) as left, zipfile.ZipFile(b) as right:
        return set(left.namelist()) == set(right.namelist()) and all(
            json.loads(left.read(name)) == json.loads(right.read(name)) if name == "manifest.json"
            else left.read(name) == right.read(name) for name in left.namelist())

with tempfile.TemporaryDirectory(prefix="effect-modulation-check-", dir=reports) as temporary:
    root = Path(temporary) / "Modulation études with spaces"
    root.mkdir()
    environment = dict(os.environ, LOCALAPPDATA=str(root / "appdata"))
    common = [str(launcher), "--headless", "--size", "96x64"]
    def run(name, frames, source, extra=()):
        result = subprocess.run([*common, "--frames", str(frames), *source,
                                 "--capture", str(root / f"{name}.png"),
                                 "--save-checkpoint", str(root / f"{name}.vstate"), *extra],
                                env=environment, capture_output=True, text=True, timeout=120)
        if result.returncode:
            raise SystemExit(result.stdout + result.stderr)
    def equal(a, b):
        if (root / f"{a}.png").read_bytes() != (root / f"{b}.png").read_bytes() or not same_checkpoint(root / f"{a}.vstate", root / f"{b}.vstate"):
            raise SystemExit(f"Custom modulation changed image, GPU state, clocks, smoothing or replay: {a} / {b}")
    run("example", 4, ["--preset", str(example)])
    run("fixture", 4, ["--preset", str(fixtures / "preset.json")])
    equal("example", "fixture")
    replay = ["--replay", str(fixtures / "replay.json")]
    run("whole", 12, replay)
    run("mapped", 12, replay, ["--map", "height", "--map-output", str(root / "height.png"), "--map-size", "127x95"])
    equal("whole", "mapped")
    run("split", 8, replay)
    run("resumed", 4, ["--checkpoint", str(root / "split.vstate")])
    equal("whole", "resumed")
    result = subprocess.run([*common, "--export", str(root / "held"), "--frames", "1", "--checkpoint", str(root / "split.vstate")],
                            env=environment, capture_output=True, text=True, timeout=120)
    if result.returncode or (root / "held/frame-000000.png").read_bytes() != (root / "split.png").read_bytes():
        raise SystemExit("Restoring a held custom-modulation frame advanced or lost its resolved values")
    metadata = json.loads((root / "height.png.json").read_text(encoding="utf-8"))
    with zipfile.ZipFile(root / "whole.vstate") as archive:
        manifest = json.loads(archive.read("manifest.json"))
    resolved, base = metadata["mapResolvedEffectValues"], metadata["settings"]["effectValues"]
    if metadata["schemaVersion"] != 5 or metadata["referenceTick"] != 24 or resolved != manifest["signals"]["effectSmooth"]:
        raise SystemExit("Map did not retain the actual resolved custom values and saved smoothing state")
    if resolved["amplitude"] == base["amplitude"] or resolved["offset"] == base["offset"]:
        raise SystemExit("Custom sources did not affect the sampled scalar and vector controls")
    document = json.loads((fixtures / "preset.json").read_text(encoding="utf-8"))
    preserved = root / "preserved.png"
    preserved.write_bytes(b"existing artwork")
    sidecar = Path(str(preserved) + ".json")
    sidecar.write_text("existing metadata", encoding="utf-8")
    for name in ("missing-component", "reset-policy", "legacy-schema"):
        bad = copy.deepcopy(document)
        if name == "missing-component":
            bad["settings"]["modulations"][0]["destination"] = "effect:offset.w"
        elif name == "reset-policy":
            bad["settings"]["effectControls"][0]["update"] = "RESET"
        else:
            bad["schemaVersion"] = 4
        path = root / f"{name}.json"
        path.write_text(json.dumps(bad), encoding="utf-8")
        failure = subprocess.run([*common, "--frames", "1", "--preset", str(path), "--capture", str(preserved)],
                                 env=environment, capture_output=True, text=True, timeout=120)
        if not failure.returncode or preserved.read_bytes() != b"existing artwork" or sidecar.read_text() != "existing metadata":
            raise SystemExit("Invalid contextual modulation changed its capture destination: " + name)
    summary = {"schemaVersion": 5, "referenceTick": 24, "checkpointTick": 16,
               "resolvedEffectValues": resolved, "baseEffectValues": base,
               "pngSha256": hashlib.sha256((root / "whole.png").read_bytes()).hexdigest(),
               "outcome": "example equivalence, exact image/GPU/clocks/replay continuation, held frame and resolved map metadata; failed destination preservation"}
    (reports / "effect-modulation-verification.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print("Installed-launcher effect-modulation check passed: exact image/state/replay continuation, held frame, resolved map values and invalid-destination preservation")
