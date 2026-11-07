# Quality presets and independent still output

Version 0.4 adds explicit quality presets and still capture at a resolution independent of the live window.

## Named quality presets

Select a preset in the Quality tab, review its resolved settings and estimated storage, then choose **Apply quality preset**. Selecting a name alone leaves the scene active. Manual edits report **Custom** when they no longer match a preset.

| Preset | Simulation grid | Particles | History layers | Display scale | Persistent GPU storage |
| --- | ---: | ---: | ---: | ---: | ---: |
| Draft | 128×128 | 4,096 | 4 | 50% | 2.125 MiB |
| Balanced | 256×256 | 16,384 | 8 | 100% | 10.5 MiB |
| Detailed | 512×512 | 65,536 | 16 | 100% | 32.5 MiB |

Display buffers and a frozen A/B reference add to these values. These profiles are starting points, not measured hardware performance tiers. The 512 MiB engine budget remains enforced.

Replacement also needs room for the old and new resources at the same time. The engine checks this peak before constructing the candidate; a settled scene that fits can still require too much temporary overlap. See [allocation plans and diagnostic fields](ALLOCATION_BUDGETS.md).

Quality changes preserve seed, creative parameters, locks, modulation, custom control definitions and embedded graph groups. Changing the grid, particle count or history layout restarts persistent state and its clock, including when undoing a quality change. A display-scale-only change preserves simulation state. Applying a named preset stops an active replay so future recorded controls cannot silently overwrite the choice. A running recording follows the existing reset/new-take behavior when its state layout changes.

```powershell
gradlew.bat run --args="--quality draft --grid 257"
```

The CLI applies quality after a preset, effect or recipe and before an explicit `--grid` override, regardless of flag order. Presets, replays and checkpoints store resolved values; they do not depend on a future definition of a profile name. A replay uses its recorded quality, and a checkpoint uses its stored state layout, so `--quality` cannot accompany `--replay` or `--checkpoint`.

Solver substeps and history cadence retain their existing fixed contracts. Changing simulation resolution can change dynamics and pattern detail. No adaptive simulation quality is introduced.

## Still output

**Capture artwork PNG** saves the current finished viewport, including its current display scale. In the Files tab, set **Still output width and height** and choose **Capture at chosen resolution** to render a separate still at 100% display scale.

```powershell
gradlew.bat run --args="--headless --frames 120 --size 1280x800 --quality draft --capture artwork.png --capture-size 2560x1600"
```

The window remains 1280×800 and the live render uses Draft's display scale. The still renders at 2560×1600 using the same simulation state and tick. Its material is reevaluated at that output resolution; it does not upscale the reduced live render. More output pixels do not add simulation-grid detail or particles.

Bounds are 32..8192 pixels per axis and at most 33,554,432 total pixels. Temporary linear scene, bloom and encoded output buffers must fit alongside the live resources within the GPU budget. Rejected or failed capture allocation leaves the live scene intact. Readback is synchronous; encoding and disk output use the bounded worker. Headless captures apply backpressure instead of dropping a requested image when the queue is full.

Still capture does not tick, reset, resize or replace persistent systems or live display buffers. It preserves the integrated phases, smoothing, particle records, history and active replay cursor. Same-aspect output preserves framing; a different aspect ratio reframes the material while retaining the existing normalized simulation fields. A frozen A/B reference remains part of the finished comparison image when active.

`--capture-size` requires `--capture`. Sequence frames retain their existing `--size`, fixed-tick timing and live display-scale behavior. A chosen-size still can accompany a sequence or a resumed checkpoint without changing either's state.

## Metadata and verification

PNG sidecars include image dimensions, actual `captureRenderWidth` and `captureRenderHeight`, the finished output stage, `captureIncludesReference`, and a derived quality-profile name or `custom`. The stored settings retain the live display scale. Thus a Draft scene can honestly report a full-resolution still while keeping its original configuration. Image data remains opaque SDR sRGB with top-left origin; a sidecar is a restarting configuration, not a checkpoint.

`./gradlew check gpuCheck installDist` covers quality preservation, bounds, sidecars, output equivalence, orientation, temporary-resource cleanup, paused repeatability, A/B handling and budget rejection. Run `EGL_PLATFORM=surfaceless python3 scripts/verify-still.py` on a suitable Linux Mesa installation with JDK 25 to check the installed launcher. Its native-window comparison permits a one-level 8-bit difference in at most 0.1% of pixels, accounting for sparse rounding differences between native and texture-backed normalized outputs. It compares every persistent resource, clock and replay field byte for byte with a run that performs no separate still render.
