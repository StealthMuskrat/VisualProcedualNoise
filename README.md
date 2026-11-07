# Procedural Visual Engine

A Windows x64 desktop visual instrument using Java 25, LWJGL 3 and OpenGL 4.3. The design reference is [the project specification](Windows-Procedural-Visual-Engine-Project-Specification.md).

## Development

Install a JDK 25 and run `gradlew.bat run` on Windows. Linux development uses `./gradlew run`. Native artifacts follow the host OS; `-PnativePlatform=natives-windows` selects Windows x64 for distribution builds.

Space pauses, period advances one tick, R resets, P changes palette, F5 reloads engine shader resources, F11 toggles fullscreen and Escape closes. Hold the left mouse button over the artwork to interact.

The separate Controls window provides metadata-generated sliders, parameter locks, controlled randomization, undo, quality settings, preset import/export and clean PNG capture. Tab reopens it. Keeping controls in a separate native inspector makes pointer coordinates unambiguous and prevents painting through controls.

Presets are versioned JSON and restart simulation state. A PNG sidecar preserves its full configuration, reference tick, encoding and device information; it is not a resumable checkpoint. Files are stored under `%LOCALAPPDATA%/ProceduralVisualEngine` by default. Invalid presets leave the current configuration active.

Local offscreen checks can use `./gradlew run --args="--headless --frames 3 --size 480x300 --capture output.png"` with Mesa EGL (`EGL_PLATFORM=surfaceless`) on Linux. This is a development path; Windows remains the distribution target.

Use `--recipe fluid-palette --grid 257` to exercise pigment transport on an odd-sized grid. The scheduler runs at 120 Hz with an eight-tick interactive catch-up cap. Excess interactive backlog is discarded; offline runs advance integer ticks directly. The simulation grid survives viewport resize, and changing grid size resets it. The normalized state is remapped to the current viewport aspect.

Pigments use midpoint semi-Lagrangian transport, periodic boundaries, a small artistic diffusion term, exponential decay, then injection in domain units. This method is diffusive and does not conserve mass. Feedback stores bounded pre-tonemapped linear color on fixed ticks, with distinct previous and next images. Display encoding occurs once in the final pass. Quality settings never adapt the simulation automatically.

Reaction diffusion uses a direct nine-point Gray–Scott stencil: center −1, axial 0.2, diagonal 0.05, diffusion A=1 and B=0.5 in artistic grid units. Each 120 Hz tick executes four explicit steps of 0.25 grid-time units. Feed and kill controls have bounded ranges; changing the grid resets the pattern and changes its spatial detail. This is an artistic model, not a biological simulation.

Particles use aligned 32-byte SSBO records, midpoint flow integration, deterministic ID/seed/respawn hashing, and a separate fixed-tick fading trail pair. Local clocks use R32F state. History captures every eight simulation ticks into a bounded array at up to 256×256 per layer; invalid layers are excluded and neighboring valid slices are interpolated. A history image is visual memory, not reversible physical state.

For editable engine resources: `gradlew.bat run --args="--shaders src/main/resources/shaders"`. These resources are for engine development; user effect authoring uses templates and bounded formulas.

Dependencies are pinned to LWJGL 3.4.3 and Gradle 9.1.0. Gradle's [9.1 release notes](https://docs.gradle.org/9.1.0/release-notes.html) confirm Java 25 support. See [LWJGL releases](https://github.com/LWJGL/lwjgl3/releases) and [FFM documentation](https://github.com/LWJGL/lwjgl3/blob/master/doc/FFM.md) for its Java 25 backend. Classpath launches explicitly enable native access.

The recipe menu includes sixteen analytic and stateful compositions. The Authoring tab provides templates, bounded scalar formulas and a typed graph canvas. The Modulation tab provides oscillators, smooth random signals, envelopes, keyframes and inspectable macro mappings. See [authoring](docs/AUTHORING.md) and [export, replay and packaging](docs/EXPORT_AND_REPLAY.md) for contracts and limits.

Version 0.2 adds custom scalar/vector effect controls and embedded reusable groups with typed ports and pinned revisions. Import [the example effect](examples/effects/ported-ripple.json) in the Files tab, or run `./gradlew run --args="--effect examples/effects/ported-ripple.json"`. Ordinary numeric edits update uniforms without recompiling shaders.

Version 0.3 adds full simulation checkpoints in the Files tab. Use `--save-checkpoint study.vstate` to save developed GPU state, clocks, smoothing, history, particle counters and an active replay cursor; use `--checkpoint study.vstate` to resume it. See [checkpoint compatibility, limits and examples](docs/CHECKPOINTS.md).

Version 0.4 adds Draft, Balanced and Detailed quality presets with visible settings and memory estimates. Still captures can render at a chosen resolution without resizing the live window or advancing state: `--capture artwork.png --capture-size 2560x1600`. See [quality presets and still output](docs/QUALITY_AND_STILLS.md).

Version 0.5 adds a discovery gallery with thumbnails, search, favorites, labels, composition locks and palette/motion branching. Saved artwork can become an A/B reference. Use the Files bookmark action or `--bookmark LABEL` after a finite run; `--discovery ID` reopens its configuration from tick 0. See [discovery history](docs/DISCOVERIES.md).

Version 0.6 adds height, normal, mask, linear-color and emission texture maps as 16-bit linear-data PNGs with embedded metadata. Export from the Files tab or use `--map height --map-output height.png --map-size 1024x1024 --map-range -2:2`. See [map units, encoding and limits](docs/TEXTURE_MAPS.md).

Version 0.7 adds compatible preset morphing. Capture or load two endpoints in the Morph tab, then apply a blend of numeric controls while simulation state and animation phase continue. Use `--preset examples/presets/morph-a.json --morph-target examples/presets/morph-b.json --morph-amount 0.5` for the bundled example. See [compatibility, interpolation and persistence](docs/MORPHING.md).

Version 0.8 adds directional Gabor-style noise, a formula template, and editable fibers/weave effects in the Authoring tab. Try `--effect examples/effects/gabor-fibers.json` or `--effect examples/effects/gabor-weave.json`. See [controls, bounded evaluation, research reference and limits](docs/DIRECTIONAL_NOISE.md).

Version 0.9 adds rendered seam and analytic-loop inspection in the Inspect tab and CLI: `--inspect inspection.json --inspect-size 512x512` after a finite run. Reports distinguish target controls from resolved values and preserve the live scene, clocks and replay. See [measurements, interpretation and examples](docs/INSPECTION.md).

Version 0.10 adds a seven-case performance suite with pinned workloads, replay inputs, raw frame-attributed timings, query coverage and image/state hashes. Run `--benchmark-suite results --benchmark-profile quick`. See [profiles, measurement scopes and comparison guidance](docs/BENCHMARKS.md).

Version 0.11 adds saved desktop VSync and frame-cap controls in the Presentation tab, with session overrides such as `--vsync off --frame-limit 60`. Wakeable event waits preserve responsiveness; offline output and benchmark suites stay uncapped. See [pacing, persistence and catch-up limits](docs/PRESENTATION.md).

Version 0.12 adds monitor-aware fullscreen restore, a saved **Suspend while minimized** preference, and focus-safe pointer gestures. F11 selects the monitor with the greatest window overlap and restores the prior window placement; minimized suspension discards hidden-time backlog. See [desktop window and input behavior](docs/PRESENTATION.md#window-and-input-behavior).

Version 0.13 adds explicit GPU capability checks, resource-limit preflight, saved capability reports and bounded startup logs with previous-session retention. Use **Files → Save GPU capability report** or `--diagnose-gpu gpu-report.json`. See [GPU reports, limits and failure diagnosis](docs/GPU_DIAGNOSTICS.md).

Version 0.14 adds bounded reuse of linked material shaders, transactional cache generations for reloads, and bounded engine-source expansion. Use `--shader-cache off` for active-program-only retention; diagnostic and benchmark reports expose cache counts. See [shader retention, source bounds and verification](docs/SHADER_CACHE.md).

Version 0.15 checks peak GPU storage while old and replacement resources coexist, preserves allocation bindings during candidate construction, and reports allocation plans in diagnostics and benchmarks. Temporary stills and maps share the same checked accounting. See [allocation budgets and failure behavior](docs/ALLOCATION_BUDGETS.md).

Discovery bookmarks save artwork and its complete restarting configuration under the user-data directory. A/B comparison shows a frozen or saved finished frame on the left and the live scene on the right; this does not run two independent simulations. GPU storage is estimated before quality changes and capped at 512 MiB.

## Verification

`./gradlew check` runs the JVM contract suite. `EGL_PLATFORM=surfaceless ./gradlew gpuCheck` runs actual OpenGL checks on a suitable Linux/Mesa installation. The GPU suite also writes a recipe gallery under `build/reports/gpu-gallery`. Windows GPU checks require a capable physical driver.

Use `--headless --frames 120 --size 1280x800 --benchmark benchmark.json` for a benchmark with thirty warmup frames. It reports CPU frame wall time, nonblocking GPU render and tick timings, percentiles, exact controls, dimensions, memory and device metadata. Use the same seed, quality, input and device for comparisons. Software-driver timings do not establish hardware requirements.

See [verification status](docs/VERIFICATION.md) for tested behavior and remaining platform acceptance work. GitHub verification is introduced after the implementation batches; Windows hardware and multi-vendor performance claims require actual tested devices.
