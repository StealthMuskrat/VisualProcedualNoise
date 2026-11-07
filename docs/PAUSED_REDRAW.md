# Redrawing held scenes

Version 0.19 tracks redraw demand while desktop playback is paused or speed is zero. After the held frame is presented, an event wake alone does not submit another render pass. Inactive pointer hover and non-rendering file/status actions can be processed while the image stays held.

| Event or request | Held-scene behavior |
| --- | --- |
| Pointer movement without an active brush | Keep the finished image; no render requested |
| Visible brush press, drag or release | Redraw its analytic preview; do not tick pigment or simulation |
| Creative edit, reference change or shader reload | Request a frame |
| Native refresh, framebuffer/DPI or focus transition | Retain redraw demand until a frame is presented |
| Capture, map, inspection, checkpoint, bookmark, reset or step | Keep frame-bound work pending until its frame can run |
| Metadata save/report, file-load preparation or inspector history work | Process the command without requesting a scene frame |
| Completed preset/effect/checkpoint/reference activation | Request a frame with the activated state |

Commands not yet classified by the application conservatively request a frame. Non-rendering work can still coincide with an independent native refresh or focus transition, which has its own redraw request.

## Input, timing and presentation

Window callbacks set coalesced redraw/input flags; they do not draw. Visible brush changes compare against the pointer used for the last presented frame. Framebuffer changes also request resampling because the artistic pointer domain depends on aspect ratio. Existing focus/release gating still controls whether live painting is armed. Replay uses its recorded pointer independently of live hover.

A held refresh without new input retains the previously rendered pointer, including a restored checkpoint's saved pointer. Explicit brush events can change the analytic preview without advancing the simulation. Pressing step advances one ordinary fixed tick; waiting does not inject pigment or move clocks.

Idle waits use wakeable GLFW event waits of at most 100 ms per requested wait, allowing queued work and file failures to be checked again. Newly queued commands are checked before waiting. Native scheduling and blocking OS calls can add latency; this is not a measured responsiveness guarantee.

The loop discards idle elapsed time and fractional tick backlog. It keeps the existing presentation deadline, so a new edit or brush event cannot bypass the configured frame cap. A relevant native transition or explicit presentation change retains its existing deadline-reset policy. Close events are checked before another frame starts.

Only explicit control edits resolve held modulation/smoothing targets. Metadata, transport, palette/reference and shader actions retain unfinished resolved values. Uniform/smoothed edits while speed is zero now use the same held-time resolution as paused edits. Reset and structural state changes retain their existing restart policies.

## Offline and finite runs

Headless output always bypasses held-scene idle, even when the saved settings have pause or zero speed. Offline ticks, sequence intervals and checkpoint continuation keep their fixed contracts. Desktop runs with an explicit finite `--frames` count also keep scheduling requested frames, including while held, so benchmarks and finite output can finish.

Playing desktop sessions keep normal presentation scheduling. Zero-sized framebuffers and minimized suspension are handled by the existing [desktop lifecycle policy](PRESENTATION.md#window-and-input-behavior); pending redraw requests survive suspension. Redraw demand does not change simulation quality, timestep, shader source or creative formats. Preset schema 5, Effect API 2 and replay format 1 remain unchanged; checkpoints retain exact engine-version checks.

## Diagnostics and verification

Session GPU reports and single-scene benchmark reports include `redraw`:

| Field | Meaning |
| --- | --- |
| `presentedFrames` | Frames completed through the main loop's swap call |
| `lastWidth`, `lastHeight` | Framebuffer dimensions used for the last completed frame |
| `idleWaits` | Held-scene waits that skipped rendering; excludes frame-cap and minimized waits |
| `invalidated` | Explicit revision demand not yet presented; not a summary of every native or frame-bound request |

A frame records its starting revision. If another explicit invalidation arrives before completion, the newer revision remains pending. These are session counters, not measured driver presentation, power consumption or GPU performance. Standalone GPU probes and the isolated benchmark suite do not use this desktop loop.

JVM checks cover hover/brush transitions, revision coalescing, invalidation during a frame, positive framebuffer requirements, frame-bound/offline bypass, command intent, frame-cap preservation and held control state. Real OpenGL checks drive the policy with a developed modulated scene: skipped hover preserves all pixels, GPU bytes, clocks, material cache and tracked storage; brush preview/release and refresh preserve state; one requested tick matches direct simulation. Window signal flags are checked without drawing in their callback path.

`python3 scripts/verify-redraw.py` checks installed offline runs with playing/paused/zero-speed combinations, exact image/GPU/clock/smoothing agreement, complete frame counters, half-open sequence ticks and a held checkpoint frame. Native Windows event delivery, actual idle power and physical desktop behavior remain acceptance work; no measured energy saving or rendering speedup is claimed.
