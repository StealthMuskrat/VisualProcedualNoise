#!/usr/bin/env python3
"""Verify installed capability reporting, context failure and local diagnostic retention."""

from pathlib import Path
import argparse
import json
import os
import subprocess
import sys
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument("--launcher", type=Path, default=Path("build/install/procedural-visual-engine/bin/procedural-visual-engine"))
parser.add_argument("--allow-unavailable", action="store_true", help="Accept a truthful failed report when a hosted runner lacks OpenGL 4.3")
options = parser.parse_args()
launcher = options.launcher.resolve()
reports = Path("build/reports").resolve()
reports.mkdir(parents=True, exist_ok=True)
if not launcher.is_file():
    raise SystemExit("Run installDist before verify-gpu-report.py")

with tempfile.TemporaryDirectory(prefix="gpu-report-check-", dir=reports) as temporary:
    root = Path(temporary) / "GPU diagnostics études with spaces"
    root.mkdir()
    data = root / "appdata/ProceduralVisualEngine"
    data.mkdir(parents=True)
    preferences = data / "presentation.json"
    preferences.write_text("invalid desktop preferences", encoding="utf-8")
    (data / "engine.log").write_text("previous session diagnostic", encoding="utf-8")
    environment = dict(os.environ, LOCALAPPDATA=str(data.parent))
    output = root / "gpu-report.json"
    result = subprocess.run([str(launcher), "--diagnose-gpu", str(output)], env=environment,
                            capture_output=True, text=True, timeout=60)
    if not output.is_file():
        raise SystemExit("Diagnostic mode did not write a report: " + result.stdout + result.stderr)
    report = json.loads(output.read_text(encoding="utf-8"))
    if report["reportVersion"] != 1 or not report["engineVersion"] or "performance test" not in report["scope"]:
        raise SystemExit("Incomplete capability-report contract")
    if result.returncode:
        if not options.allow_unavailable or report["status"] != "failed" or not report.get("failure", {}).get("message"):
            raise SystemExit("GPU probe unexpectedly failed: " + result.stdout + result.stderr)
        if report.get("capabilities") is not None and not report.get("baselineFailures"):
            raise SystemExit("Failed capability report invents compatible queried support")
    else:
        caps = report["capabilities"]
        if report["status"] != "compatible" or report["baselineFailures"] or not caps["renderer"]:
            raise SystemExit("Successful probe lacks actual compatible device support")
        if caps["limits"]["GL_MAX_COMPUTE_WORK_GROUP_SIZE_X"] < 256 or set(caps["formats"]) != {
            "R32F", "RG32F", "RGBA16F", "RGBA8", "RGBA32F", "RGBA16F_ARRAY"
        }:
            raise SystemExit("Required indexed limits or target/format entries are missing")
    if preferences.read_text(encoding="utf-8") != "invalid desktop preferences" or "Using default presentation" in result.stdout:
        raise SystemExit("Standalone GPU probe accessed desktop preferences")
    if (data / "engine.previous.log").read_text(encoding="utf-8") != "previous session diagnostic":
        raise SystemExit("Startup failed to preserve previous diagnostic log")
    if "Procedural Visual Engine" not in (data / "engine.log").read_text(encoding="utf-8") or (data / "engine.log").stat().st_size > 2 * 1024 * 1024:
        raise SystemExit("Current diagnostic log lacks a header or exceeds its bound")
    (reports / "gpu-probe.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    (reports / "gpu-probe.engine.log").write_bytes((data / "engine.log").read_bytes())
    output.write_text("existing destination", encoding="utf-8")
    rejected = subprocess.run([str(launcher), "--diagnose-gpu", str(output), "--frames", "1"], env=environment,
                              capture_output=True, text=True, timeout=60)
    if not rejected.returncode or output.read_text(encoding="utf-8") != "existing destination":
        raise SystemExit("Rejected combined diagnostic arguments changed their destination")
    blocked = root / "blocked.json"
    blocked.mkdir()
    (blocked / "marker").write_text("keep", encoding="utf-8")
    failed = subprocess.run([str(launcher), "--diagnose-gpu", str(blocked)], env=environment,
                            capture_output=True, text=True, timeout=60)
    if not failed.returncode or (blocked / "marker").read_text(encoding="utf-8") != "keep":
        raise SystemExit("Failed report publication changed its existing destination")
    if sys.platform.startswith("linux"):
        # GLVND's documented ICD override creates a context-creation failure without a product test flag.
        unavailable = root / "unavailable.json"
        broken_environment = dict(environment, __EGL_VENDOR_LIBRARY_FILENAMES=str(root / "absent-icd.json"))
        broken = subprocess.run([str(launcher), "--diagnose-gpu", str(unavailable)], env=broken_environment,
                                capture_output=True, text=True, timeout=60)
        failure = json.loads(unavailable.read_text(encoding="utf-8"))
        if not broken.returncode or failure["status"] != "failed" or failure["capabilities"] is not None or not failure["failure"]["message"]:
            raise SystemExit("Context creation failure was not truthfully recorded")
        (reports / "gpu-unavailable.json").write_text(json.dumps(failure, indent=2), encoding="utf-8")
    help_result = subprocess.run([str(launcher), "--help"], capture_output=True, text=True, check=True, timeout=60)
    if "--diagnose-gpu" not in help_result.stdout:
        raise SystemExit("Installed help omits capability reporting")
    print(f"Installed-launcher GPU-report check passed: {report['status']} support report, retained bounded logs, preference isolation and failure preservation")
