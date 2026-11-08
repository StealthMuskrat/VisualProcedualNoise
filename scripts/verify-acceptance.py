#!/usr/bin/env python3
"""Verify packaged acceptance evidence, truthful blocking and immutable result publication."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("--launcher", type=Path, default=Path("build/install/procedural-visual-engine/bin/procedural-visual-engine"))
parser.add_argument("--allow-blocked", action="store_true", help="Accept a truthful unavailable GPU on the hosted Windows packaging runner")
options = parser.parse_args()
launcher = options.launcher.resolve()
reports = Path("build/reports").resolve()
reports.mkdir(parents=True, exist_ok=True)
if not launcher.is_file():
    raise SystemExit("Run installDist before verify-acceptance.py")

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def require_clean_staging(root, phase):
    remaining = {path.name: [str(child.relative_to(path)) for child in sorted(path.rglob("*"))[:64]]
                 for path in sorted(root.glob(".acceptance-pending-*"))}
    if remaining:
        detail = {"phase": phase, "staging": remaining}
        (reports / "acceptance-staging-failure.json").write_text(json.dumps(detail, indent=2), encoding="utf-8")
        raise SystemExit("Acceptance left staging after " + phase + ": " + json.dumps(remaining))

with tempfile.TemporaryDirectory(prefix="acceptance-check-", dir=reports) as temporary:
    root = Path(temporary) / "Windows acceptance études with spaces"
    root.mkdir()
    environment = dict(os.environ, LOCALAPPDATA=str(root / "appdata"))
    preferences = root / "appdata" / "ProceduralVisualEngine" / "presentation.json"
    preferences.parent.mkdir(parents=True)
    preferences.write_bytes(b"existing preferences must stay untouched")
    destination = root / "run"
    command = [str(launcher), "--acceptance-run", str(destination), "--acceptance-environment", "hosted"]
    result = subprocess.run(command, env=environment, capture_output=True, text=True, timeout=180)
    require_clean_staging(root, "initial published run")
    if not (destination / "results.json").is_file():
        raise SystemExit("Acceptance run failed to publish evidence: " + result.stdout + result.stderr)
    report = json.loads((destination / "results.json").read_text(encoding="utf-8"))
    status = report["automatedStatus"]
    if status not in ("passed", "blocked") or result.returncode != (0 if status == "passed" else 2):
        raise SystemExit("Acceptance status/exit mismatch: " + result.stdout + result.stderr)
    if status == "blocked" and not options.allow_blocked:
        raise SystemExit("A compatible GPU was required for this acceptance verification: " + result.stdout + result.stderr)
    if report["reportVersion"] != 1 or report["profile"] != "smoke" or report["environmentDeclaration"] != "hosted":
        raise SystemExit("Acceptance report lost its version, default profile or declaration")
    if report["manualReview"] != "not_run" or report["hardwareAcceptance"] != "not_established" or report["releaseAcceptance"] != ("manual_review_required" if status == "passed" else "blocked"):
        raise SystemExit("Automated/hosted execution fabricated manual or hardware acceptance")
    manual = (destination / "manual-review.md").read_text(encoding="utf-8")
    if "- [x]" in manual or "- [ ]" not in manual or "winver" not in manual or "fullhd" not in manual:
        raise SystemExit("Manual review did not remain explicitly unrun")
    paths = set()
    for item in report["immutableEvidence"]:
        relative = Path(item["path"])
        if relative.is_absolute() or ".." in relative.parts or item["path"] in paths:
            raise SystemExit("Invalid or repeated evidence path")
        paths.add(item["path"])
        file = destination / relative
        if file.is_symlink() or file.stat().st_size != item["bytes"] or digest(file) != item["sha256"]:
            raise SystemExit("Collected evidence digest/size mismatch")
    if "manual-review.md" in paths or "results.json" in paths or "gpu.json" not in paths:
        raise SystemExit("Immutable machine evidence and editable review were not separated")
    app = report["application"]
    if app["kind"] != "jar" or not app["archives"] or app["mainArchive"] not in [a["path"] for a in app["archives"]]:
        raise SystemExit("Packaged application archive identity is incomplete")
    archives = [launcher.parent / "app", launcher.parent.parent / "lib"]
    archive_root = next((path for path in archives if (path / app["mainArchive"]).is_file()), None)
    if archive_root is None:
        raise SystemExit("Cannot locate the reported installed application archive")
    for item in app["archives"]:
        if Path(item["path"]).name != item["path"] or digest(archive_root / item["path"]) != item["sha256"]:
            raise SystemExit("Archive identity differs from installed application/dependencies")
    canonical = json.dumps(app["archives"], sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if hashlib.sha256(canonical).hexdigest() != app["archiveSetSha256"]:
        raise SystemExit("Archive-set fingerprint is inconsistent")
    bundle_root = launcher.parent if launcher.suffix.lower() == ".exe" else launcher.parent.parent
    if not (bundle_root / "docs" / "WINDOWS_ACCEPTANCE.md").is_file() or not (bundle_root / "acceptance-shaders" / "render" / "material.frag").is_file():
        raise SystemExit("Acceptance guide or editable shader-test copy is missing from the distribution")
    if status == "passed":
        suite = json.loads((destination / "benchmark" / "suite.json").read_text(encoding="utf-8"))
        if len(suite["cases"]) != 7 or any(report["steps"][step]["status"] != "passed" for step in ("installation", "gpu", "benchmark")):
            raise SystemExit("Passing acceptance omitted a required workload or check")
    elif report["steps"]["gpu"]["status"] != "blocked" or report["steps"]["benchmark"]["status"] != "not_run" or (destination / "benchmark").exists():
        raise SystemExit("Blocked GPU run fabricated benchmark results")
    original = {path.relative_to(destination): path.read_bytes() for path in destination.rglob("*") if path.is_file()}
    occupied = subprocess.run(command, env=environment, capture_output=True, text=True, timeout=60)
    if occupied.returncode == 0 or "must not already exist" not in occupied.stderr or any((destination / path).read_bytes() != data for path, data in original.items()):
        raise SystemExit("Occupied acceptance destination was modified")
    invalid_path = root / "invalid-run"
    invalid = subprocess.run([str(launcher), "--acceptance-run", str(invalid_path), "--shaders", "override"], env=environment, capture_output=True, text=True, timeout=60)
    if invalid.returncode == 0 or invalid_path.exists() or "unsupported override" not in invalid.stderr:
        raise SystemExit("Unpinned acceptance override was accepted")
    if sys.platform.startswith("linux"):
        blocked_path = root / "unavailable"
        blocked_env = dict(environment, __EGL_VENDOR_LIBRARY_FILENAMES=str(root / "absent-icd.json"))
        blocked = subprocess.run([str(launcher), "--acceptance-run", str(blocked_path), "--acceptance-environment", "physical"], env=blocked_env, capture_output=True, text=True, timeout=60)
        failure = json.loads((blocked_path / "results.json").read_text(encoding="utf-8"))
        require_clean_staging(root, "published unavailable-GPU run")
        if blocked.returncode != 2 or failure["automatedStatus"] != "blocked" or failure["hardwareAcceptance"] != "not_established" or failure["steps"]["benchmark"]["status"] != "not_run":
            raise SystemExit("Unavailable context or caller declaration manufactured acceptance")
    if preferences.read_bytes() != b"existing preferences must stay untouched":
        raise SystemExit("Acceptance changed desktop preferences")
    require_clean_staging(root, "completed preservation checks")
    help_result = subprocess.run([str(launcher), "--help"], capture_output=True, text=True, timeout=60, check=True)
    if "--acceptance-run" not in help_result.stdout or "fullhd" not in help_result.stdout:
        raise SystemExit("Installed help omits acceptance/fullhd modes")
    with zipfile.ZipFile(reports / "acceptance-example.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(destination.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(destination).as_posix())
    (reports / "acceptance-verification.json").write_text(json.dumps({"automatedStatus": status, "releaseAcceptance": report["releaseAcceptance"], "manualReview": report["manualReview"], "evidenceFiles": len(paths), "archiveSetSha256": app["archiveSetSha256"]}, indent=2), encoding="utf-8")
    print(f"Installed-launcher acceptance check passed: truthful {status} evidence, archive/file identity, pinned workloads, unrun manual review and destination preservation")
