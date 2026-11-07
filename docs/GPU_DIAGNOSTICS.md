# GPU capability checks and diagnostics

Version 0.13 queries the active OpenGL device before creating the engine's shader programs and scene resources. It checks the operations this backend uses, records the advertised limits and formats, and rejects oversized resource requests before replacing the working scene. The Windows target and OpenGL 4.3 baseline are unchanged.

## Save a report

In the **Files** tab, choose **Save GPU capability report**. This saves the cached device snapshot plus the current tick, quality settings, framebuffer dimensions, tracked allocation and presentation request. Saving does not query or read back GPU resources, advance ticks, reset state, change undo history or add replay events. File output uses the existing bounded worker queue.

For startup diagnosis, run the standalone command:

```powershell
gradlew.bat run --args="--diagnose-gpu gpu-report.json"
# Or, from a packaged app image:
.\ProceduralVisualEngine.exe --diagnose-gpu gpu-report.json
```

The probe creates a hidden 96×64 context and reads its capabilities. It does not open Controls, load a scene or desktop preferences, compile engine shaders or run a performance workload. `--headless` is optional; other scene, export, benchmark and presentation arguments are rejected. `--verify-installation` remains the separate resource/native-loading check.

An accepted probe atomically replaces its requested report with the current attempt. A compatible baseline exits zero. Context creation or baseline failure writes a `failed` report, then exits nonzero. If no context could be queried, `capabilities` is explicitly `null`; if queried support fails a requirement, the actual snapshot and `baselineFailures` remain available. Publication failure preserves an existing destination and is reported through the log and exit status. Invalid combined arguments leave the destination untouched.

## Report contract

`reportVersion: 1` includes engine/algorithm revision, Java/OS/architecture, status, scope, queried capabilities and baseline failures. Failed attempts include the exception type and at most 8192 message characters. Reports contain no graphics-performance score. A `compatible` result means the advertised baseline requirements passed, not that every legal workload or Windows desktop operation was exercised.

The capability snapshot contains vendor, renderer, OpenGL/GLSL version strings, named limits and six target/format records: R32F, RG32F, RGBA16F, RGBA8, RGBA32F and RGBA16F texture arrays. Format entries record storage support, framebuffer attachment, linear filtering, fragment/compute sampling, image stores, blending and maximum dimensions/layers. `FULL`, `CAVEAT` and `NONE` retain the query's meaning; caveat support is accepted and visible in the report. Actual allocation, framebuffer-completeness and shader checks still apply.

| Backend use | Checked support |
| --- | --- |
| Analytic material and finishing | Texture/combined sampler bindings through unit 7, supported framebuffer formats, filtered fragment sampling and viewport/framebuffer extents |
| Simulation kernels | 8×8 work groups, image unit 0, three compute sampler units, required image-store formats and per-axis dispatch counts |
| Particles | 256-thread X work groups, compute and vertex shader storage blocks, binding 0, 32 bytes per allocated particle and dispatch group count |
| History | RGBA16F array storage, filtered sampling, single-layer framebuffer access and supported layer count; disabled history still allocates one layer |
| Stills and maps | Requested format, texture extent and viewport checks before temporary allocation; existing capture bounds and memory budget also apply |

The source model owns immutable maps. The native snapshot is installed once for the owning context and cleared at shutdown; worker threads cannot reuse it. Ordinary preflight checks use the cached values without new GL queries. Benchmark-suite and single-scene benchmark reports also include the snapshot, outside measured frame work.

Requested grid, particle count, history and output dimensions remain explicit. An unsupported request reports its requested capacity and relevant device limit; it does not silently reduce creative quality. The independent 512 MiB engine budget still applies. Advertised capacities are not free VRAM measurements and cannot guarantee that the driver will satisfy an allocation while other applications use memory.

## Startup logs

The application data directory contains `engine.log` and `engine.previous.log`. A normal launch preserves the prior session and starts a new timestamped log with engine, Java and OS information. Context startup records the actual device snapshot; scene startup records resolved quality and framebuffer dimensions. Shader, GLFW and startup failures continue to go to this log.

Each log is bounded to 2 MiB. A flooded current log ends with an omission marker; later messages are dropped for that session. A legacy larger log contributes only its most recent 2 MiB to the previous log. Log setup/rotation failure leaves existing diagnostics available and fails visibly. Help, recipe listing and installation verification retain their lightweight paths. Reports and logs remain local files; the engine does not upload them.

## Verification and limits

JVM contracts cover baseline requirements, exact SSBO boundaries, odd dispatch tails, layer/texture/viewport limits, immutable snapshots, report serialization, CLI isolation and bounded log retention/failure preservation. GPU checks compare queried and cached support, preserve GL bindings during queries, reject worker access, and constrain a renderer's advertised limits to exercise configuration and capture failures. The active program, finished image, tracked memory and every persistent GPU byte remain unchanged after these rejections.

`python3 scripts/verify-gpu-report.py` checks the installed command, Unicode/spaced paths, preference isolation, retained logs, invalid arguments and publication failure. On Linux/GLVND it also overrides ICD enumeration with an absent vendor file to exercise a real context-creation failure. The packaged Windows check allows a truthful unavailable report on a hosted runner; it still requires a valid report and correct exit status. Hosted diagnostics do not establish physical Windows GPU, monitor or performance acceptance.

The query and dispatch rules follow the [OpenGL 4.3 core specification](https://registry.khronos.org/OpenGL/specs/gl/glspec43.core.pdf), including its internal-format queries. Linux failure injection uses [GLVND's documented ICD enumeration override](https://github.com/NVIDIA/libglvnd/blob/master/src/EGL/icd_enumeration.md). GPU reset recovery, automatic tuning and driver program-binary caching remain separate future work.
