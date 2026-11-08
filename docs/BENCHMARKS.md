# Reproducible performance workloads

Version 0.10 adds a pinned seven-case suite and frame-attributed timing format 2. The suite is an offline CLI mode, separate from the live desktop scene. It records raw samples, coverage and deterministic outcomes so performance changes can be evaluated alongside image/state changes.

```powershell
gradlew.bat run --args="--benchmark-suite results-quick --benchmark-profile quick"
```

The destination must not exist. The suite uses an invisible window, swap interval zero, one process/context and a fresh renderer for each sequential case. All profiles advance exactly two 120 Hz ticks per rendered frame, independently of wall time. Profile names describe fixed workloads, not hardware quality recommendations or frame-rate guarantees.

| Profile | Image | Grid | Particles | History layers | Warmup frames | Measured frames | Final tick |
| --- | --- | --- | --- | --- | --- | --- | --- |
| smoke | 96×64 | 65×65 | 257 | 3 | 8 | 16 | 48 |
| quick (default) | 320×200 | 129×129 | 4096 | 8 | 30 | 60 | 180 |
| standard | 1280×720 | 256×256 | 16384 | 16 | 60 | 180 | 480 |
| fullhd (v0.29) | 1920×1080 | 257×257 | 16384 | 16 | 60 | 180 | 480 |

Version 0.29 adds fullhd without changing earlier profiles or their workload hashes. The odd simulation-grid dimension also exercises dispatch tails. Use the [acceptance runner](WINDOWS_ACCEPTANCE.md) to collect installation, GPU and archive identity alongside a chosen suite. Its default is smoke; ordinary benchmark suites retain quick as their default. Fullhd supplies a fixed workload for physical Windows measurements and does not automatically certify interactive 60 fps or minimum hardware requirements.

Each profile runs the same case identities with profile-specific resolution/layout and duration:

| Case | Composition and forcing |
| --- | --- |
| opal | Analytic liquid opal; inactive pointer |
| contours | Living contours at scale 3.6; inactive pointer |
| nested-warps | Nested fields with broad warp 1.6 and detail 0.7; inactive pointer |
| pigment | Fluid palette with an explicit curved brush path |
| reaction | Alien ecosystem with reaction diffusion and the same pinned brush path |
| particles | Cosmic silk with particles, trails and feedback |
| history | Time excavation with feedback, history-ring capture and history mix 0.7 |

Seeds derive from the fixed root, case ID and workload-version channel. Every tick has an explicit pointer event. Stateful cases use the recorded sinusoidal path and a repeating two-on/one-off pattern of 24-tick brush windows. Complete initial settings, graph, input events and schedule are included in each canonical workload hash. Engine version is excluded from that hash to permit implementation comparisons; algorithm revision, layout and rendering contract remain included. Actual compiled shader-source signatures are recorded separately.

## Timing scopes

`contextSetupWallMs` measures native window/context creation in an already running JVM. `caseSetupWallMs` measures a fresh renderer, programs/resources and a held tick-0 render, including synchronized completion. These are setup costs, not uncached process-startup measurements. Driver caches, JVM state and the preceding cases can affect them.

Warmup frames evolve the same scene used for measurement. A synchronization boundary after warmup removes its pending GPU work before the first measured frame. Another boundary drains results after the last measured frame. Neither wait is included in measured CPU frame samples.

| Field | Scope |
| --- | --- |
| `cpuFrameMs` | Wall time through event polling, fixed ticks, rendering, swap and nonblocking query collection; includes driver waits and query instrumentation |
| `gpuRenderMs` | One elapsed query around the material, bloom and finishing rendering scope |
| `gpuTickMs` | One elapsed query per individual fixed tick, including active simulation/feedback/history work; two ticks are scheduled per suite frame |

CPU statistics aggregation, final readback, state hashing, encoding and file publication occur outside measured frame intervals. CPU values are not CPU-only execution or end-to-end application throughput. Render and tick GPU samples have different sample counts and cannot be added as percentile values to obtain a frame percentile. Fine-grained per-kernel timing remains a later extension.

Each timer has an eight-query ring. During frames, results are read only after `GL_QUERY_RESULT_AVAILABLE` indicates availability. A full ring skips measurement while the workload continues. Each started tagged query is consumed once, with sequence and originating frame retained. Late warmup results remain warmup data. Missing results have count zero and no percentile fields; a valid zero-duration result remains a real sample. See the [Khronos query retrieval contract](https://wikis.khronos.org/opengl/Query_Object#Query_retrieval).

Coverage records completed/skipped warmup and measured queries and pending results. Final pending counts should be zero. CPU arrays and raw GPU records are retained alongside summaries. Percentiles use nearest rank, `sorted[ceil(p*N)-1]`. Smoke's sixteen measured frames are intended for functional verification, not statistically strong performance conclusions.

## Result bundle

The completed directory contains `suite.json` and one subdirectory per case. Each case saves `workload.json`, `initial.json`, `input.replay.json`, `report.json`, `final.png` and its ordinary capture sidecar. Reports include device/driver, Java/OS/architecture, dimensions, memory allocation, setup times, raw timing samples, query coverage, tick boundaries, source signature, phase/smoothing state, simulation metadata and SHA-256 hashes of initialized GPU resources.

The final PNG is re-rendered at the held final tick after timing because a swap need not preserve its back buffer. No simulation tick is added. Its hash covers encoded PNG bytes. Resource hashes follow the checkpoint byte layout and omit uninitialized particle/history content according to that contract. Raw GPU buffers are not duplicated into the bundle; use a checkpoint when full resumable state is needed.

The seven final images are held in bounded memory until all graphics cases succeed, then encoded and written into a private staging directory. A shader failure therefore creates no staged result files. Publication happens after every artifact is written, without intentionally replacing an existing destination. Handled file-write failures remove staging where possible; abrupt process or machine termination cannot guarantee cleanup. Never treat a staging directory as a completed suite.

Profiles pin resolution, settings, ticks and inputs. Only `--benchmark-suite`, `--benchmark-profile`, optional `--headless`, and optional `--shaders DIRECTORY` are accepted together. Keep editable shader files unchanged throughout a run. A framebuffer/DPI mismatch or window closure aborts rather than silently changing the workload.

## Single-scene measurements and comparisons

```powershell
gradlew.bat run --args="--headless --frames 120 --size 1280x800 --benchmark scene.json"
```

Single-scene measurement still discards thirty warmup frames, now by originating frame tag. It requires more than thirty finite frames and an output rate of at least 15 fps, bounding queries to at most eight ticks per headless frame. Other scene/export options retain their behavior, so captures, edits or I/O can affect these wall-time samples. The report records the actual final tick and phase clocks. Ordinary sessions collect no benchmark sample history.

Before comparing results, check workload hashes, algorithm revision, device/driver, Java environment, dimensions, sample windows and query coverage. Shader signatures may deliberately differ when evaluating an implementation change. Compare final images, state hashes and simulation metadata as well as timings; declare any accepted numeric error separately. Repeat runs to assess variation. Preserve raw reports rather than reporting only an FPS ratio. No performance threshold is enforced in CI.

The [recorded software-renderer quick suite](benchmarks/llvmpipe-0.10-quick.json) supplies a development baseline, not Windows hardware requirements. The older [version 0.1 single-scene baseline](benchmarks/llvmpipe-opal.json) uses legacy timing collection and percentile semantics; its GPU summaries are not directly comparable to format 2.

`python3 scripts/verify-benchmarks.py` runs two smoke suites through the installed launcher, compares workload/image/state outcomes, checks unique query coverage, replays a saved workload through the ordinary engine, verifies single-scene instrumentation preservation, and exercises destination/failure handling. Hosted Windows checks validate JVM contracts and packaging; physical Windows GPU and performance acceptance remains pending.
