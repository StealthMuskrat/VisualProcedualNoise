# GPU allocation peaks

Version 0.15 checks the simultaneous tracked texture and buffer storage needed while an operation is staged. The engine budget remains **512 MiB**. Named quality profiles keep their existing settled-storage estimates.

A replacement keeps the live resource until its candidate is ready. Its peak is therefore `resident + staged`; after committing, storage is `resident - replaced + retained`. Temporary exports retain zero staged bytes. Checked addition and multiplication reject overflow before issuing allocation calls.

| Operation | Staged storage | Released on commit or completion |
| --- | --- | --- |
| Simulation layout change | Complete new simulation, particle buffer, history and marks | Previous simulation and marks |
| Display resize or scale change | New RGBA16F scene and quarter-size bloom | Previous scene and bloom |
| Frozen or saved A/B reference | New RGBA8 reference | Previous reference |
| Checkpoint restore | Complete candidate renderer storage, including display and reference | Previous renderer when the caller commits the restore |
| Independent still | Full-size RGBA16F scene, quarter-size bloom and RGBA8 output | All staged textures |
| Texture-map export | R32F scalar or RGBA32F RGB target | Staged map texture |

For example, replacing 200 MiB of a 350 MiB scene with another 200 MiB would settle at 350 MiB, but its 550 MiB peak is rejected. A 8192×4096 RGB map needs 512 MiB for its RGBA32F target alone, so it cannot fit alongside a live scene. Odd bloom dimensions use integer division by four with a minimum of one pixel, matching allocation.

## Failure behavior

Budget preflight happens before candidate resource construction and before a material-cache lookup for a changed simulation layout. A rejected peak leaves the renderer's owned resources, program and simulation state intact. Candidate construction also restores the framebuffer, viewport, current texture-unit bindings, general shader-storage-buffer binding and vertex-array binding that allocation touches. Failed candidate construction deletes its partial resources; a reference is replaced only after its copy or upload succeeds.

Interactive reference capture reports failures in the status area. Existing caller error handling remains in effect for quality changes, display rendering and checkpoint restore; preserving owned renderer resources does not introduce automatic retry, a different quality setting or recovery from every driver failure. A successful replacement can delete a previously bound owned handle, with ordinary OpenGL unbinding behavior.

## Diagnostic fields

Files → **Save GPU capability report**, single-scene benchmarks and each benchmark-suite case include an `allocation` object:

| Field | Meaning |
| --- | --- |
| `limitBytes` | Fixed engine storage budget |
| `residentBytes` | Current owned texture and buffer storage |
| `largestAllowedPeakBytes` | Largest peak that passed preflight for this renderer, including an accepted staged restore |
| `lastPreflight` | Latest completed allocation plan, including a plan rejected by the budget |
| `lastPreflight.operation` | Requested operation |
| `residentBytes`, `replacedBytes`, `stagedBytes`, `retainedBytes` inside the plan | Inputs to the peak and settled calculations |
| `lastPreflight.peakBytes`, `lastPreflight.settledBytes` | Required overlap and predicted storage after success |

The largest allowed peak records a calculation, even if later shader compilation or allocation fails. It is not a measurement of physical GPU usage. A request rejected by device limits or invalid input before planning does not replace the last plan. Standalone `--diagnose-gpu` creates no renderer and has no allocation object.

Tracked storage excludes opaque driver allocations, shader-program internals, framebuffer/query objects, alignment, CPU readback buffers and other applications. It does not query free VRAM or guarantee that a driver allocation succeeds. Shader retention has separate entry/source bounds. Allocation statistics are session diagnostics and are absent from creative presets, effects, replays and checkpoint state. Those formats remain unchanged; checkpoints retain exact engine-version/source checks.

## Verification

JVM checks cover exact limits, old/new overlap, temporary release, quality estimates, integer bloom sizes and arithmetic overflow. Real OpenGL checks lower the internal test renderer's budget to 240,000 bytes, exercising peaks that fail while settled storage would fit without exhausting real GPU memory. They compare live images, every simulation byte, references and program identity; a deliberately broken particle shader exercises cleanup after candidate textures have been created. Successful simulation/display replacement, temporary maps and staged checkpoint accounting are also checked.

`python3 scripts/verify-allocation.py` compares installed replay runs with and without a map export, validates report arithmetic and RGBA32F storage, and checks that an over-budget map preserves its existing image and metadata. These checks establish tracked accounting and transaction behavior on the tested driver. Physical Windows memory pressure and multi-vendor acceptance remain pending.
