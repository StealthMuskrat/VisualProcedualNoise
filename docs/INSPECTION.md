# Rendered seam and loop inspection

Version 0.9 adds measured inspection of repeated-image seams and configured analytic loop endpoints. The Inspect tab saves a JSON report and displays a small 2×2 repeat of the sampled image. It reports pixel differences without automatically certifying an effect as seamless.

## Desktop workflow

1. Configure the scene, including the existing spatial tile mapping or analytic loop period if wanted.
2. Open **Inspect**, choose the inspection width and height, and press **Inspect and save JSON report**.
3. Compare the left/right and top/bottom wrap differences with the nearby interior steps. Inspect the repeated-image preview for visible joins.
4. If an analytic loop period is configured, review its endpoint differences and the target/resolved control status.

The last completed measurement is labeled with its fixed tick. It does not update automatically when live settings change. The preview repeats the measured image using nearest-neighbor scaling; scaling can hide individual pixels, so use the numeric report for exact sampled differences.

Inspection renders at full requested resolution independently of the live display scale or window dimensions. It excludes diagnostic views, the live 2×2 wrap-preview overlay and frozen/saved A/B references. Exposure, bloom, material color, current persistent state and the captured pointer still contribute to the creative finished image. Their settings are recorded.

No simulation ticks, smoothing steps, replay events or phase integration occur during sampling. The live image, persistent buffers, history, particles, reference image and output allocations remain intact. Rendering/readback happens on the context thread; analysis, preview generation and atomic report writing use the existing file worker. One inspection may be in flight at a time, with normal queue backpressure.

## What the numbers mean

| Measurement | Compared samples |
| --- | --- |
| Horizontal wrap | First and last pixel columns at each row |
| Horizontal interior | First/second columns and next-to-last/last columns |
| Vertical wrap | First and last pixel rows at each column |
| Vertical interior | First/second rows and next-to-last/last rows |
| Loop endpoint | Every pixel of the two rendered endpoint images |

Each comparison records pixel-pair count, pairs with any changed RGB channel, maximum absolute channel difference, mean absolute channel difference and root-mean-square channel difference. Values use opaque, encoded 8-bit sRGB channel levels from 0 to 255. Alpha is excluded. These are display-sample statistics, not linear-light errors, perceptual color differences or comparisons of simulation buffers.

First/last pixel centers are neighbors across a repeated image. They are not duplicate samples of the exact mathematical boundary. A smoothly periodic image can have nonzero wrap differences, especially with thin features or small output dimensions. Nearby interior steps give a local comparison, without imposing an automatic pass threshold. Postprocessing and sampling can also affect visible joins. This version retains the existing unit-UV spatial tile mapping; requested spatial periods and exact function-boundary probes remain later extensions.

## Loop endpoints and resolved controls

A temporal pair is sampled only when the scene has a valid configured analytic loop period. Existing validation excludes persistent systems, graph mode, modulation and custom formulas using unbounded `t` from that mode. Without a configured period, spatial measurement still works and the report explicitly says that no future persistent state was inferred.

The two samples use `referenceSeconds` and `referenceSeconds + loopSeconds`, holding the resolved numeric values and pointer fixed. The second sample does not evolve a simulation or finish pending smoothing. Both use the configured analytic phase mapping. Matching endpoints therefore establish only the measured finished pixels at those conditions, not a guarantee about an exported sequence with changing inputs or controls.

The report measures the largest target/resolved control difference normalized by each descriptor's range. `controlsWithinTolerance` uses a threshold of 0.000001 of that range. Differences can come from smoothing or modulation. An unsettled result is called out in the panel; wait for controls to settle and inspect again when evaluating a steady loop. This control tolerance is separate from pixel error and does not declare images equivalent.

## Command line

The bundled example enables spatial mapping and an eight-second analytic Gabor loop:

```powershell
gradlew.bat run --args="--headless --frames 1 --size 640x400 --preset examples/presets/tiled-gabor-loop.json --inspect inspection.json --inspect-size 640x400"
```

Inspect developed state or an active replay without changing its continuation:

```powershell
gradlew.bat run --args="--headless --frames 8 --checkpoint study.vstate --inspect state-inspection.json --inspect-size 512x512 --save-checkpoint continued.vstate"
```

`--inspect FILE.json` requires a finite positive `--frames` count and samples the last rendered frame. `--inspect-size WIDTHxHEIGHT` requires `--inspect`. Its default is 512×512. Dimensions must be 32..4096 with at most 4194304 pixels. The stricter inspection limit bounds the two CPU endpoint images in addition to the existing 512 MiB GPU budget. Oversized requests fail before allocation or report replacement.

## Report and verification contract

Inspection format 1 embeds the ordinary restarting configuration, lossless root-seed string, reference tick and seconds, resolved phase clocks, actual render settings, pointer, device, compiled source signature, dimensions, spatial mapping declaration, measurements and interpretation strings. `endpointSeconds` is present only when a temporal endpoint was sampled. The actual render settings explicitly disable diagnostics and wrap preview and use display scale 1. Source settings remain recorded separately. Reports contain measurements and metadata; they do not contain the sampled images or resumable GPU state. Presets remain schema 4; use a checkpoint to resume developed state.

The report is published by temporary-file replacement after analysis succeeds. Failed publication preserves the existing destination. A previous on-screen report remains until another report saves successfully.

JVM checks cover axes, counts, encoded-channel statistics, target/resolved differences, lossless seed metadata, bounds, endpoint eligibility, persistence and actual Swing controls. GPU checks verify independent extents, framebuffer restoration, exclusion of overlays, preservation of every state byte and reference, analytic endpoint equality and visible animation between endpoints. `python3 scripts/verify-inspection.py` compares installed runs with/without inspection, including unfinished smoothing, pointer input, active replay, checkpoints, Unicode/spaced paths and failed destinations. Physical Windows GPU, DPI and performance validation remains pending.
