# Bounded material-program reuse

Version 0.14 retains recent linked material shaders so switching back to a recipe, expression or graph can reuse its program. Ordinary numeric controls remain uniforms and do not perform a cache lookup. Simulation kernels, finishing programs and persistent state keep their existing ownership and update rules.

## Retention and identity

Each renderer retains at most eight material programs, including its active program, with a second limit of 4 MiB of source-byte weight. Weight is the sum of the expanded vertex/fragment UTF-8 input lengths for retained programs. The cache stores program objects, hashes and weights; this counter is not a measurement of driver executable memory or free VRAM. The existing 512 MiB texture/buffer budget remains independent.

Identity hashes the complete generated vertex and fragment stages, including expanded engine resources, recipe defines, graph/formula code and control declarations. Stage tags and length framing prevent ambiguous stage boundaries. Uniform values, seeds, animation clocks, pointer values and graph layout metadata do not enter the key unless they actually change generated source. Cache scope is one renderer in its owning context; no program object is shared across renderers or persisted to disk.

The `glsl-source-v2` fingerprint also identifies compute programs and changes program signatures relative to earlier versions. It does not change the simulation algorithm revision. Presets, effect manifests and replay formats are unchanged. Checkpoints retain their existing exact engine-version and source/device compatibility requirements.

## Candidate application and reload

A structural edit first selects a retained program or compiles and validates one candidate. The prior program and cache residency remain active while replacement simulation resources are prepared. Only a successful scene application commits the selection, updates its recency and evicts inactive programs in least-recently-selected order. Aborted cache hits retain their program; aborted new candidates are deleted. One candidate may be staged or built at a time, including protection against nested build requests. These operations stay on the context thread.

There can briefly be one additional staged material program alongside the retained set. A checkpoint restore also prepares a separate renderer under its existing allocation policy. Program deletion follows OpenGL's deferred lifetime rules: an evicted handle can remain queryable with its deletion flag set while the driver still references it. Linked programs detach and delete their compilation shader objects after linking.

F5 prepares a fresh material-cache generation and all ordinary finishing/simulation reload candidates. A failure preserves the prior generation, programs, bindings and developed state. A successful reload releases old variants and publishes the newly compiled active material as the first entry of the new generation. Reusing a program preserves its own uniform-location map; every render/tick uploads current values. A fresh program receives a fresh location map.

Engine files are read again for each root-source request. Within one expansion, repeated file reads share that request's text snapshot. Changed source cannot match an older cached key, and a broken edit cannot silently select an old program instead of reporting its error. Source preparation and GL compilation remain synchronous; this does not promise that first-time compilation or program use cannot stall.

## Session option and diagnostics

Default retention is on. To compare with an active-program-only policy:

```powershell
gradlew.bat run --args="--shader-cache off"
gradlew.bat run --args="--headless --frames 48 --size 96x64 --replay take.replay.json --shader-cache on --benchmark cached.json"
```

`off` keeps only the active material and releases it after another selection commits. Returning to an inactive effect then requires another build. The option belongs to the current process, not to creative presets, replay events, desktop preferences or checkpoints. Checkpoint restoration keeps the selected session policy and starts a fresh cache. The pinned benchmark suite and standalone GPU-diagnostic mode reject this override.

Single-scene benchmark reports, benchmark-case reports and the Files-tab GPU report include `shaderCache`: the retention policy, generation and variant statistics. Entries/weight describe current residency. Hits count retained lookups; misses count build attempts, including attempts whose later scene application fails. Failures count factory compile/link/interface errors. Source/parser failures before lookup and discarded reload-generation statistics are reported through existing errors rather than added to the active generation. Evictions count committed LRU removals. Lookup counters restart when a successful F5 publishes a new generation; each fresh renderer starts at generation 1.

The isolated benchmark suite normally has no repeated material switching, so its cache metadata is diagnostic context, not evidence of an editing speedup. No wall-time improvement, hardware performance target or asynchronous compilation guarantee is claimed from the reuse counts alone.

## Source bounds

Engine shader files use strict UTF-8 and reject NUL characters. Reads stop after 256 KiB plus one detection byte per file. Each root expansion permits at most 1 MiB of UTF-8 output, 128 file expansions including repeated includes, and sixteen active files in the include chain. Includes remain within the selected resource root, with cycle and parent-escape checks across slash styles. Existing `#line` diagnostics are retained.

Version 0.21 also limits each final vertex, fragment and compute stage to 1 MiB after recipe substitution and custom-code insertion. Every marker occurrence contributes to the projected size; insertion checks run before allocating the combined text. Program construction validates final size, Unicode and NULs before hashing or making native program/shader calls. The fingerprint-only helper keeps its existing stage-framing behavior. Accepted source text and fingerprints do not change.

An engine file expansion can fit and still leave too little space for a custom field. Such a candidate or F5 reload fails before a material-cache lookup or compile, preserving the previous generation and developed scene. Fix the source and reload again. Formulas have their own [combined expression/gradient budget](AUTHORING.md#formula-language), and graphs generate only requested values.

These are engine-development resource limits. User formulas, graphs, controls and effect imports retain their own smaller authoring bounds. Source rejection preserves the previous compiled scene through the normal failed-edit path; startup rejection logs the error before publishing capture output.

## Verification

JVM checks exercise LRU/weight limits, candidate commit/abort, nested and worker requests, disposal ownership, framed fingerprints, CLI isolation, source snapshots and all read/expansion bounds. GPU checks verify actual program reuse, uniform refresh against a fresh restored scene, map-output isolation, eviction/deletion requests, active-only behavior and both early and late reload failures. A resource failure after selecting a cache hit leaves both the active program and retained candidate intact.

`python3 scripts/verify-shader-cache.py` compares an installed switching replay with retention on and off, checks its reuse/build counts, and requires byte-identical PNGs, every GPU resource and checkpoint clocks/replay/smoothing. It resumes a retained-cache checkpoint under the active-only policy and compares its developed output with an uninterrupted run. Invalid options and oversized source files preserve existing destinations. The summary is written to `build/reports/shader-cache-verification.json`.

Program linking, uniform locations and deferred deletion follow the [OpenGL 4.3 core specification](https://registry.khronos.org/OpenGL/specs/gl/glspec43.core.pdf). Physical Windows editing latency, long sessions and multi-vendor behavior remain acceptance work. Driver binary caching and parallel compilation remain separate extensions.
