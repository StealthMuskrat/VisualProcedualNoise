# Capture and replay

PNG captures are opaque SDR sRGB, with top-left image origin. Their JSON sidecars preserve the base controls, modulation, root seed, reference tick, phase clocks, quality, algorithm revision and device. Loading a configuration restarts state; a still and its metadata are not a GPU checkpoint.

Use `--capture artwork.png --capture-size 2560x1600` for an independent full-resolution still at the current tick. The Files tab offers the same chosen-size capture. Live window dimensions, simulation resources and sequence timing stay intact. Sidecars identify the actual render size and whether the A/B reference is included. See [quality and still output](QUALITY_AND_STILLS.md) for bounds and behavior.

## Replay

Start replay recording resets the scene and begins a take. Pointer strokes and resolved control changes are assigned to simulation ticks in order. Stop recording freezes the take. Save recorded replay writes a portable JSON recording. Resetting during a recording begins a new take. Changing viewport aspect stops recording, since domain coordinates would otherwise change meaning. Replay at the recorded aspect ratio.

```powershell
gradlew.bat run --args="--headless --frames 240 --size 1280x800 --replay take.replay.json --capture replay.png"
```

Replay targets the same build, algorithm revision and compatible device. It does not promise bit-identical floating-point state across GPU vendors. Recordings are limited to 200000 events and 64 MiB. [Full GPU checkpoints](CHECKPOINTS.md) can resume developed state and an active replay cursor without rerunning its earlier ticks.

## Image sequences

```powershell
gradlew.bat run --args="--export frames --frames 240 --fps 24 --size 1280x800 --preset study.json --warmup 120"
```

The first frame follows the requested warmup ticks. At 24 fps, successive frames advance five 120 Hz ticks. Output rates must divide 120. Frames use a half-open time interval, so a loop's final frame does not duplicate its first. Export is independent of wall-clock rendering speed. The encoding queue is bounded and applies backpressure; failed writes make the process fail. `sequence.json` is written after successful frame output.

On Windows an invisible native window uses the installed GPU driver. On Linux the offscreen development path uses GLFW's null platform and Mesa EGL. Escape or interruption cancels a running process; already-written files remain. Video encoding is not bundled.

## Tiles and loops

Analytic spatial tile mode maps both UV axes through periodic sine/cosine coordinates before composition. It changes the artistic coordinate mapping. Enable the 2×2 wrap preview to inspect seams. This capability is intentionally restricted to analytic scenes without persistent systems, graph resources or modulation.

Analytic loop seconds synchronizes geometric and color phases to integer cycles over the chosen period. Formula loops use `phase`; formulas depending on unbounded `t` are rejected. Stateful patterns are not labeled seamless. Render one period at a matching output rate to inspect the result.

## Windows packaging

On Windows x64 with JDK 25:

```powershell
gradlew.bat clean coreCheck packageWindows -PnativePlatform=natives-windows
build\package\ProceduralVisualEngine\ProceduralVisualEngine.exe --help
```

Distribute the entire `build/package/ProceduralVisualEngine` folder. It contains a Java runtime and matching x64 native libraries. The app-image path requires no WiX installer tools. Building signed exe/msi installers, signing identities and supported Windows-version claims remain distribution decisions after hardware acceptance testing.
