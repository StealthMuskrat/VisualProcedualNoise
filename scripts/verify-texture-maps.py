#!/usr/bin/env python3
"""Verify installed 16-bit map files, embedded contracts and exact replay/state preservation."""

from pathlib import Path
import argparse
import json
import struct
import subprocess
import tempfile
import zipfile
import zlib

parser = argparse.ArgumentParser()
parser.add_argument("--launcher", type=Path, default=Path("build/install/procedural-visual-engine/bin/procedural-visual-engine"))
options = parser.parse_args()
launcher = options.launcher.resolve()
fixture = Path("build/reports/replay-fixture.json")
if not launcher.is_file() or not fixture.is_file():
    raise SystemExit("Run coreCheck installDist before verify-texture-maps.py")

def inspect(path):
    raw = path.read_bytes()
    if raw[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit("Map is not a PNG")
    offset, chunks, embedded = 8, set(), None
    while offset < len(raw):
        length = struct.unpack(">I", raw[offset:offset + 4])[0]
        kind = raw[offset + 4:offset + 8]
        data = raw[offset + 8:offset + 8 + length]
        crc = struct.unpack(">I", raw[offset + 8 + length:offset + 12 + length])[0]
        if zlib.crc32(kind + data) != crc:
            raise SystemExit("Corrupt map chunk")
        chunks.add(kind)
        if kind == b"IHDR":
            extent = struct.unpack(">II", data[:8])
            depth, color = data[8:10]
        if kind == b"iTXt":
            key, rest = data.split(b"\0", 1)
            if key == b"VisualEngineTextureMap":
                if rest[:2] != b"\0\0":
                    raise SystemExit("Unexpected compressed map metadata")
                _, _, text = rest[2:].split(b"\0", 2)
                embedded = json.loads(text.decode("utf-8"))
        offset += length + 12
    if chunks & {b"sRGB", b"gAMA", b"iCCP", b"cHRM"}:
        raise SystemExit("Data map has a display color-transfer tag")
    return extent, depth, color, embedded

def same_state(a, b):
    with zipfile.ZipFile(a) as x, zipfile.ZipFile(b) as y:
        if set(x.namelist()) != set(y.namelist()):
            raise SystemExit("Map changed persistent resource set")
        for name in x.namelist():
            same = json.loads(x.read(name)) == json.loads(y.read(name)) if name == "manifest.json" else x.read(name) == y.read(name)
            if not same:
                raise SystemExit(f"Map changed state or replay position: {name}")

with tempfile.TemporaryDirectory(prefix="map-check-", dir=Path("build/reports")) as temporary:
    root = Path(temporary)
    replay = json.loads(fixture.read_text())
    for preset in [replay["initialPreset"], *[e["preset"] for e in replay["events"] if e["kind"] == "controls"]]:
        preset["settings"]["displayScale"] = .5
        preset["settings"]["debug"] = 8
    source = root / "mapped.replay.json"
    source.write_text(json.dumps(replay))
    common = ["--headless", "--frames", "4", "--size", "320x200", "--replay", str(source)]
    subprocess.run([str(launcher), *common, "--capture", str(root / "plain.png"), "--save-checkpoint", str(root / "plain.vstate")], check=True)
    for kind in ["height", "normal", "mask", "linear-color", "emission"]:
        target = root / (kind + ".png")
        extra = ["--map-range", "-0.001:0.001"] if kind == "height" else []
        subprocess.run([str(launcher), *common, "--map", kind, "--map-output", str(target), "--map-size", "191x127", *extra,
                        "--capture", str(root / (kind + "-view.png")), "--save-checkpoint", str(root / (kind + ".vstate"))], check=True)
        same_state(root / "plain.vstate", root / (kind + ".vstate"))
        if (root / "plain.png").read_bytes() != (root / (kind + "-view.png")).read_bytes():
            raise SystemExit("Map changed the live viewport")
        extent, depth, color, embedded = inspect(target)
        sidecar = json.loads(Path(str(target) + ".json").read_text())
        if extent != (191, 127) or depth != 16 or color != (0 if kind in ["height", "mask"] else 2) or embedded != sidecar:
            raise SystemExit("Map image type or embedded metadata is incorrect")
        if embedded["mapKind"] != kind or embedded["referenceTick"] != 8 or embedded["mapRenderScale"] != 1:
            raise SystemExit("Map capture context is incorrect")
        if embedded["settings"]["displayScale"] != .5 or not .35 < embedded["mapResolvedParameters"]["flow"] < .7 or embedded["mapPointer"] != [.2, .3, 0.0]:
            raise SystemExit("Map did not retain source quality, smoothing or pointer metadata")
        if kind == "height" and embedded["mapClippedSamples"] == 0:
            raise SystemExit("Map did not report its clipped range")
    target = root / "normal.png"
    previous = target.read_bytes(), Path(str(target) + ".json").read_bytes()
    failed = subprocess.run([str(launcher), *common, "--map", "normal", "--map-output", str(target), "--map-size", "8192x4096"],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if failed.returncode == 0 or previous != (target.read_bytes(), Path(str(target) + ".json").read_bytes()):
        raise SystemExit("Over-budget map did not preserve its previous files")
    print("Installed-launcher texture-map check passed: five 16-bit data maps, embedded contracts, clipping, exact image/state/replay and failed-save preservation")
