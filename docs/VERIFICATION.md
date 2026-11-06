# Verification status

Recorded November 6 2026. Source implementation includes the renderer, sixteen recipes, inspector, persistence, fixed-tick simulations, authoring, replay, export and Windows app-image build path. Windows release acceptance remains pending physical-device validation.

## Local results

| Check | Result |
| --- | --- |
| Java 25 compilation and distribution assembly | Passed with Temurin 25.0.5+8 |
| JVM contracts | 336 passed: parsing, types, bounds, graph cycles and pruning, seeds, preset migrations, modulation, clocks, replay, custom controls, hashes, typed groups, checkpoint archives, quality profiles, still metadata, discovery integrity/branching/gallery, 16-bit map encoding/metadata/UI, morph compatibility/interpolation/phases/command ordering/UI, directional formulas/effects/hashes/UI, seam/loop metrics/context/UI, benchmark attribution/percentiles/workloads/CLI and memory limits |
| OpenGL contracts | 137 passed on Mesa llvmpipe, OpenGL 4.5 core, Mesa 25.2.8, LLVM 20.1.2 |
| Authoring behavior | Custom values change the image without relinking; encapsulating seeded graphs preserves their rendered output; typed group expressions link and render |
| Recipe coverage | All sixteen recipes compiled and rendered; generated gallery includes PNGs and complete sidecars |
| Directional fields | 4096 raw probe and 6144 material-height samples matched an independent double-precision reference within 0.0002; orientation response, half-turn symmetry, seeded repeatability, boundary continuity, clamps, nonfinite inputs and finite normal export passed |
| Directional effects | Fibers/weave rendered distinct images; numeric controls retained the linked program; formula/graph field agreement and analytic-loop endpoints passed. Installed manifests preserved image/state through map capture and exact replay/checkpoint continuation through a smoothed bandwidth edit |
| Seam and loop inspection | Encoded-channel statistics, edge axes and neighboring-step counts, endpoint eligibility, resolution limits, lossless metadata and actual UI actions passed. GPU sampling excluded overlays and preserved framebuffer, state bytes, reference, clocks and allocation; matching analytic endpoints retained visible animation between them |
| Installed inspection workflow | Inspection preserved exact live image, GPU state, clocks, replay cursor, unfinished smoothing and pointer input. Spatial reports avoided future-state inference; analytic endpoints matched within one display level. Oversized requests and failed atomic publication preserved existing destinations |
| Benchmark timing | Completed queries retained unique sequence/frame attribution; delayed warmup data stayed outside measured samples; coverage accounted for render frames and individual ticks. Instrumentation preserved image, phase clocks, metadata and every GPU state byte |
| Installed benchmark suite | Two seven-case smoke suites reproduced workload identities, PNG bytes, state hashes, clocks and simulation metadata. The saved history replay reproduced ordinary engine image/state; single-scene benchmarking preserved output and state. Existing destinations were rejected and missing-shader runs created no staged output |
| Reset and pause | Repeated reset at the same seed/tick reproduced the image; repeated rendering without ticks preserved it |
| Pigment transport | Constant-field preservation and positive-flow impulse direction passed on a 65×65 grid |
| Reaction diffusion | Concentrations remained finite and bounded over 256 ticks at three exposed coefficient combinations; initialization responds to root seed |
| Particles | 257 particles exercised respawns; 300 ticks reproduced identical trail state after reset |
| History | Validity reset, bounded ring and tick timestamps passed |
| Failure preservation | Invalid allocation and intentionally broken shader retained the working image/resources |
| Replay export | Two independent eight-frame exports produced byte-identical PNG files |
| Checkpoint continuation | Independent installed processes reproduced the saved tick-306 image and exact tick-360 image, clocks, replay cursor and every GPU resource from an uninterrupted run |
| Checkpoint integrity | Corrupt, missing, unexpected, truncated and incompatible archive data rejected; failed save preserved its existing destination; failed staged GPU restore preserved the active scene |
| Quality profiles | Resolved values round-trip, preserve creative controls and fit the declared memory budget; recorded quality cannot be silently overridden |
| Independent stills | Offscreen output matches the 96×64 viewport exactly; the 640×400 native comparison differed by one 8-bit step in 10 of 256000 pixels (limit: one step in 0.1%); state stayed byte exact. Odd sizes, differing aspect, paused repeatability, A/B reference, live framebuffer preservation, temporary cleanup and pre-allocation budget rejection passed |
| Discovery history | Completed bundles, legacy reads, annotations, corruption isolation, path bounds, seed/dependency preservation and source-preserving branches passed; gallery filtering, pagination, actions and stale results checked through actual Swing components |
| Saved-image reference | Orientation, encoded color, live right-half preservation, byte-exact persistent state, checkpoint readback, replacement accounting and invalid-image preservation passed |
| Installed discovery workflow | Bookmarking preserved image and state; reopening restarted at tick 0 and reproduced the same reset-to-tick run; child provenance and corrupt-reference rejection passed, including Unicode/spaced paths |
| Texture maps | 16-bit scalar/RGB samples, embedded JSON, ranges, clipping, top-left orientation and absent display-transfer tags passed; GPU planes, normals, complete-field/steep derivatives, masks, emission threshold, display exclusion, state preservation and budget/interface rejection passed |
| Installed map workflow | All five map types preserved byte-exact image, GPU state and replay cursor; resolved smoothing/pointer metadata, full resolution, embedded/sidecar agreement and over-budget preservation passed |
| Compatible morphing | All built-in controls and custom scalar/vector controls follow their linear/logarithmic interpolation contracts; incompatible seeds, palettes, topology, resources, schema, reset values and modulation are rejected. Captures own their settings, stale loads are ignored, command ordering is preserved and phases integrate without restart |
| Morph state and installed workflow | Applying/returning to endpoints preserved paused images, every GPU state byte and simulation metadata. Installed CLI endpoints/midpoint matched resolved presets exactly; replay with morph control events resumed from tick 16 to 24 with identical image, clocks, replay cursor and GPU state. Incompatible output preserved its existing files |
| Sequence timing | 24 fps sequence used ticks 0, 5, 10 and 15 for its first four frames |
| Installation contents | Gradle distribution contains engine resources, pinned natives, notices and licenses |

The GPU suite intentionally compiles an invalid shader to test fallback. Its compiler error in the log is expected. The suite fails if the previous image is lost.

## Software baseline

The [raw version 0.1 baseline](benchmarks/llvmpipe-opal.json) records liquid opal at 320×200, seed 173496285120, a 65×65 state grid, no pointer input, 64 frames and 128 fixed ticks. Thirty frames warm up the driver; 34 frames contribute samples. This was a separate run without concurrent test jobs.

| Measurement | Median | 95th percentile |
| --- | --- | --- |
| CPU frame wall time | 38.71 ms | 61.65 ms |
| GPU render elapsed query | 36.37 ms | 60.81 ms |

The device is a CPU software renderer. These values do not establish Windows hardware requirements or satisfy the 1080p/60 fps target. The analytic preset schedules no persistent update, so its near-zero tick query measures an empty workload. No optimization speedup is claimed.

### Version 0.10 software quick suite

The [raw quick-suite report](benchmarks/llvmpipe-0.10-quick.json) records seven isolated workloads at 320×200, 129×129 simulation grid, 4096 particle allocation and eight history layers. Each case uses thirty warmup frames and sixty measured frames, ending at tick 180. The run used Mesa llvmpipe 25.2.8 / LLVM 20.1.2 with two software-renderer threads and Java 25.0.5, without a concurrent test or build job. Every case collected sixty render queries and 120 individual-tick queries, with no measured skips.

| Case | CPU frame p50 / p95 (ms) | GPU render p50 (ms) | GPU individual tick p50 (ms) |
| --- | --- | --- | --- |
| Opal | 19.58 / 48.16 | 19.24 | 0.000025 |
| Contours | 16.07 / 20.47 | 16.14 | 0.000025 |
| Nested warps | 47.78 / 84.86 | 47.40 | 0.000026 |
| Pigment | 27.63 / 33.53 | 25.18 | 0.815 |
| Reaction | 22.92 / 33.29 | 17.20 | 2.538 |
| Particles | 29.67 / 34.73 | 16.02 | 6.483 |
| History | 29.10 / 36.60 | 16.99 | 5.607 |

These are software-renderer development measurements. Analytic cases have no persistent update, so their near-zero tick scopes measure empty bookkeeping. Tick/query scopes are individual ticks, while CPU frames schedule two; percentile values must not be added to infer a frame percentile. This is a baseline, not an optimization speedup or Windows performance result. See [benchmark profiles and measurement contracts](BENCHMARKS.md). Legacy version 0.1 GPU summaries use different collection/percentile semantics and are not directly comparable.

## Windows and release acceptance

GitHub verification is introduced after the implementation batches. The Windows job checks JVM contracts, builds an x64 app image with its own Java runtime and verifies packaged resources/native loading. A hosted runner does not establish the application's GPU performance or desktop behavior.

Before public release, run the specification's matrix on physical Windows machines: NVIDIA, AMD and Intel drivers; clean account without Java; Unicode/spaced paths; DPI and monitor changes; fullscreen; focus loss; minimize/restore; long sessions; repeated quality changes; still/sequence color and orientation; and practical performance targets. Record the actual Windows versions and hardware before declaring support.

The installed Linux script launcher was exercised with replay exports. A native Linux app image was built, but its launcher could not be validated in this sandbox because the process filesystem is unavailable. Linux app images are outside the Windows distribution target.

## Scope of this release

Version 0.10 adds a [seven-case reproducible benchmark suite](BENCHMARKS.md), pinned smoke/quick/standard profiles, raw frame-attributed query samples, explicit coverage, setup measurements and deterministic image/state outcomes. Ordinary sessions no longer retain unused benchmark sample history. The suite records performance evidence without enforcing CI timing thresholds or claiming hardware targets.

Version 0.9 adds [rendered seam and analytic-loop inspection](INSPECTION.md), including numeric reports, independent-resolution sampling, target/resolved context and a repeated-image preview. It measures encoded pixel samples without automatically certifying seamlessness or predicting future persistent state.

Version 0.8 adds [directional Gabor-style fields and reusable fibers/weave effects](DIRECTIONAL_NOISE.md), including bounded evaluation, typed formula/graph support, generated controls, hashed manifests and export/replay/checkpoint verification. This is a fixed-impulse artistic variant without research spectral-parity or anisotropic-filtering claims.

Version 0.7 adds [compatible preset morphing](MORPHING.md), with explicit desktop apply, startup CLI resolution, phase/state preservation and ordinary preset/replay/checkpoint persistence. Discrete palette/topology transitions and automatic timed morphing remain later extensions.

Version 0.6 adds [16-bit texture-map exports](TEXTURE_MAPS.md) for height, normal, mask, linear color and emission, including embedded encoding/capture contracts. Version 0.5 introduced [discovery history and branching](DISCOVERIES.md), including a thumbnail gallery, favorites, annotations and saved-image A/B references. Version 0.4 introduced [named quality presets and independent still capture](QUALITY_AND_STILLS.md), including exact capture render-size metadata and state-preserving output. Version 0.3 introduced [full GPU checkpoints](CHECKPOINTS.md), including particle counters, history, time, smoothing, A/B reference and active replay continuation. Version 0.2 introduced Effect API 2: custom scalar/vector descriptors, resolved values, generated controls, definition hashes, embedded revision-pinned groups and typed expression ports. Graphs retain explicit reads of the engine's prior visual color, pigment and local-age resources. API 1 effects and older presets remain readable. Reaction and transport customization use the supported host-owned templates and bounded controls.

Arbitrary simulation kernels, external package dependency resolution, signed installers, bundled video encoding, audio/MIDI/OSC, 3D rendering, wallpaper integration and optional driver-specific optimization experiments remain later extensions. The implementation does not claim those features are present.
