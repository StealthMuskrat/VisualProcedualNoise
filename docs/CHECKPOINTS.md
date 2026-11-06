# Resumable checkpoints

Version 0.3 adds full simulation checkpoints (`.vstate`). In the Files tab, use **Save full simulation checkpoint** and **Resume full simulation checkpoint**. A save captures a completed frame boundary. Resume restores the saved tick and pause setting and discards the wall-clock delay spent loading.

| Saved state | Contents |
| --- | --- |
| Configuration | Lossless root seed, controls, custom control descriptors and values, embedded groups, modulation, quality and toggles |
| Time | Integer simulation tick, integrated motion/color phases and unfinished control smoothing |
| Simulation | Flow; both sides and active indices of pigment, feedback, chemicals, local age and particle trails; reaction initialization flag |
| Particles | Initialized particle records, lifetimes and integer respawn counters, or an explicit uninitialized state |
| History | Valid layers, ring position, validity count and tick timestamps |
| Interaction | Previous simulation brush position/state and the saved frame's pointer |
| Replay | An active replay's complete event data, cursor and current pointer state |
| Comparison | The frozen finished A/B reference, when present |

Scratch render targets, timers, uninitialized particle storage and invalid history layers are excluded. They are recreated or overwritten before use. Loading stops recording and clears parameter undo history. Editor drafts, window placement and filesystem paths are not restored. A preset still restarts state; a PNG and its sidecar still describe a reference image.

## Command line

Save after the final rendered frame (or when an interactive run exits):

```powershell
gradlew.bat run --args="--headless --frames 180 --size 1280x800 --recipe temporal-painting --save-checkpoint study.vstate --capture study.png"
```

Continue for another 60 frames using the stored dimensions:

```powershell
gradlew.bat run --args="--headless --frames 60 --checkpoint study.vstate --capture continued.png"
```

Export the saved frame without advancing a tick:

```powershell
gradlew.bat run --args="--export restored --frames 1 --checkpoint study.vstate"
```

`--checkpoint` cannot be combined with `--preset`, `--effect`, `--replay`, `--recipe`, `--seed`, `--grid`, `--quality` or `--expression`. `--size` can change output resolution at the same aspect ratio; interactive resume requires the current viewport to have that ratio. `--warmup` advances additional ticks from the loaded state. Offline stepping retains its normal fixed-tick behavior regardless of the saved interactive pause setting. `--capture-size` can render a separate still from the resumed state without resizing its live viewport.

An embedded replay continues automatically. Live pointer input resumes from the current device after time advances. The first restored frame without ticks uses the saved pointer. Replays use their recorded pointer for both simulation and visible material interaction.

## Compatibility and cost

Checkpoints require the same engine version, algorithm revision, little-endian storage, compiled shader source hashes and reported OpenGL vendor/device/driver signature. Changing editable engine shader files can make a checkpoint incompatible. Cross-vendor or cross-driver floating-point equivalence is not promised; use configuration presets or replay when those constraints are unsuitable.

GPU readback blocks rendering while copying state. Compression, checksums and disk writes run through the bounded file worker. Only one checkpoint operation runs at a time. The raw state limit is 512 MiB, the JSON manifest limit is 72 MiB, and the archive limit is 640 MiB. Storage and save latency grow with quality, initialized systems, history and the frozen reference.

Resume validates metadata, resource dimensions, CRCs, SHA-256 checksums and numeric state before uploading. It prepares a separate renderer, including display buffers, and swaps it in only after successful allocation, compilation and upload. The old and candidate resources must together fit the 512 MiB GPU budget during an interactive restore. A fresh command-line launch avoids allocating a duplicate active simulation first. Invalid or incompatible files leave the current scene active.

The archive contains `manifest.json` and explicitly named `state/*.bin` entries; it is never extracted to arbitrary paths. Missing, duplicate, extra, truncated or oversized entries are rejected. Saves write a temporary file beside the destination and replace it after completion, preserving an existing save if validation or writing fails.

## Verification

`./gradlew check gpuCheck installDist` checks archive corruption, CPU metadata and actual GPU restoration. On Linux with Mesa EGL, run `EGL_PLATFORM=surfaceless python3 scripts/verify-checkpoint.py` after building the distribution. That check saves an active replay at tick 306, resumes in another process, and compares the tick-360 image, clocks, replay cursor, metadata and every persistent GPU byte with an uninterrupted run. It also restores the saved image without advancing time. Physical Windows GPU acceptance remains separate.
