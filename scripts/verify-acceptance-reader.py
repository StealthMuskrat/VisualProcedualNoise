#!/usr/bin/env python3
"""Check GPU-independent, read-only review of the preceding acceptance-run artifact."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("--launcher", type=Path, default=Path("build/install/procedural-visual-engine/bin/procedural-visual-engine"))
parser.add_argument("--bundle", type=Path, default=Path("build/reports/acceptance-example.zip"))
options = parser.parse_args()
launcher = options.launcher.resolve()
reports = Path("build/reports").resolve()
if not launcher.is_file() or not options.bundle.is_file():
    raise SystemExit("Install the launcher and run verify-acceptance.py first")

def snapshot(root):
    return {path.relative_to(root).as_posix(): path.read_bytes() for path in root.rglob("*") if path.is_file()}

with tempfile.TemporaryDirectory(prefix="acceptance-reader-", dir=reports) as temporary:
    root = Path(temporary)
    bundle = root / "Copied evidence études with spaces"
    with zipfile.ZipFile(options.bundle) as archive:
        for name in archive.namelist():
            if Path(name).is_absolute() or ".." in Path(name).parts or "\\" in name or ":" in name:
                raise SystemExit("Invalid verification-fixture archive path")
        archive.extractall(bundle)
    manifest = bundle / "results.json"
    original = snapshot(bundle)
    recorded = json.loads(manifest.read_text(encoding="utf-8"))
    digest = hashlib.sha256(manifest.read_bytes()).hexdigest()
    environment = dict(os.environ, LOCALAPPDATA=str(root / "appdata"), __EGL_VENDOR_LIBRARY_FILENAMES=str(root / "absent-icd.json"))
    preferences = root / "appdata/ProceduralVisualEngine/presentation.json"
    preferences.parent.mkdir(parents=True)
    preferences.write_bytes(b"reader must preserve preferences")
    command = [str(launcher), "--verify-acceptance", str(bundle)]

    def run(*extra):
        return subprocess.run([*command, *extra], env=environment, capture_output=True, text=True, timeout=60)

    def consistent(result, pinned):
        if result.returncode != 0:
            raise SystemExit("Valid evidence was rejected: " + result.stdout + result.stderr)
        summary = json.loads(result.stdout)
        if (summary["verificationStatus"] != "consistent" or summary["manifestSha256"] != digest
                or summary["expectedManifestHashChecked"] != pinned
                or summary["recordedEngineVersion"] != recorded["engineVersion"]
                or summary["automatedStatus"] != recorded["automatedStatus"]
                or summary["releaseAcceptance"] != recorded["releaseAcceptance"]
                or summary["manualReview"] != "not_run" or summary["hardwareAcceptance"] != "not_established"
                or summary["immutableFiles"] != len(recorded["immutableEvidence"])):
            raise SystemExit("Reader changed the recorded outcome or fabricated release acceptance")
        return summary

    summary = consistent(run(), False)
    consistent(run("--acceptance-manifest-sha256", digest.upper()), True)
    if snapshot(bundle) != original:
        raise SystemExit("Verification changed input bundle bytes")
    wrong = run("--acceptance-manifest-sha256", "0" * 64)
    if wrong.returncode != 1 or "SHA-256 mismatch" not in wrong.stderr or snapshot(bundle) != original:
        raise SystemExit("Wrong trusted manifest hash was accepted or changed input")
    invalid = run("--seed", "7")
    if invalid.returncode != 1 or "unsupported override" not in invalid.stderr:
        raise SystemExit("Reader accepted a scene override")
    (bundle / "manual-review.md").write_text("- [x] Operator observation remains separate\n", encoding="utf-8")
    (bundle / "review").mkdir()
    (bundle / "review/operator.txt").write_text("Supporting manual evidence\n", encoding="utf-8")
    edited = snapshot(bundle)
    notes = consistent(run("--acceptance-manifest-sha256", digest), True)
    if notes["unhashedReviewFiles"] != 1 or snapshot(bundle) != edited:
        raise SystemExit("Manual observations were certified or modified")
    first = bundle / recorded["immutableEvidence"][0]["path"]
    before = first.read_bytes()
    first.write_bytes(before + b"changed")
    damaged = snapshot(bundle)
    rejected = run()
    if rejected.returncode != 1 or "size/hash mismatch" not in rejected.stderr or snapshot(bundle) != damaged:
        raise SystemExit("Changed immutable evidence was accepted or rewritten")
    first.write_bytes(before)
    manifest_bytes = manifest.read_bytes()
    manifest.write_bytes(b'{"reportVersion":1,' + manifest_bytes.lstrip()[1:])
    malformed = snapshot(bundle)
    rejected = run()
    if rejected.returncode != 1 or "Duplicate evidence JSON key" not in rejected.stderr or snapshot(bundle) != malformed:
        raise SystemExit("Duplicate manifest key was accepted or source evidence changed")
    manifest.write_bytes(manifest_bytes)
    if preferences.read_bytes() != b"reader must preserve preferences":
        raise SystemExit("Reader changed desktop preferences")
    if snapshot(bundle) != edited:
        raise SystemExit("Reader did not preserve the restored evidence and operator notes")
    if os.name == "nt":
        target = root / "junction-target"
        target.mkdir()
        (target / "external.txt").write_text("outside the evidence root", encoding="utf-8")
        junction = bundle / "review/junction"
        creation = subprocess.run(["cmd", "/c", "mklink", "/J", str(junction), str(target)], capture_output=True, text=True, errors="replace", timeout=30)
        if creation.returncode != 0:
            raise SystemExit("Could not prepare Windows junction check: " + creation.stdout + creation.stderr)
        rejected = run()
        junction.rmdir()
        if rejected.returncode != 1 or not any(message in rejected.stderr for message in ("filesystem aliases", "symbolic link", "nonregular file")) or (target / "external.txt").read_text(encoding="utf-8") != "outside the evidence root":
            raise SystemExit("Windows directory junction was accepted or its target changed")
    help_result = subprocess.run([str(launcher), "--help"], env=environment, capture_output=True, text=True, timeout=60, check=True)
    if "--verify-acceptance" not in help_result.stdout or "--acceptance-manifest-sha256" not in help_result.stdout:
        raise SystemExit("Packaged help omits acceptance verification")
    (reports / "acceptance-reader-verification.json").write_text(json.dumps({
        "verificationStatus": summary["verificationStatus"], "recordedEngineVersion": recorded["engineVersion"],
        "recordedAutomatedStatus": recorded["automatedStatus"], "manifestSha256": digest,
        "immutableFiles": summary["immutableFiles"], "gpuRequired": False,
        "sourcePreserved": True, "manualReview": "not_run", "hardwareAcceptance": "not_established"
    }, indent=2), encoding="utf-8")
    print("Installed-launcher acceptance-reader check passed: no GPU required, unchanged bundle/preferences, trusted digest, corruption rejection and unestablished human/hardware acceptance")
