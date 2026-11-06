# Desktop presentation controls

Version 0.11 adds requested VSync and an optional desktop frame-rate cap. These local transport preferences are separate from creative settings stored in presets, effects, discoveries, checkpoints and replay events. Defaults preserve the previous desktop behavior: VSync requested on, no additional frame cap.

## Controls and persistence

Open **Presentation**, select **Request VSync**, choose **Maximum FPS**, and press **Apply and save presentation**. Zero means no application cap; integers 1..240 set a maximum submission rate. Draft edits alone change nothing. The panel shows the applied request and save status.

Application happens on the owning render thread without resetting simulation state, phase clocks, smoothing or scene undo history. Presentation actions do not add creative replay events. Playback continues normally.

`presentation.json` in the application data directory uses schema 1 with `schemaVersion`, boolean `vsync` and integer `frameLimit`. Reads are bounded to 16 KiB and validate types/ranges. Missing files use defaults; invalid files are reported and defaults are used without overwriting the file.

The bounded I/O worker saves by atomic temporary-file replacement. One save is accepted at a time, with controls temporarily disabled. A failed save leaves the session request applied and reports that it was not saved. A full queue rejects application before changing the request; a defensive enqueue failure after application is reported as session-only. Accepted worker tasks follow the existing shutdown policy.

Desktop command-line overrides apply for the current session:

```powershell
gradlew.bat run --args="--vsync off --frame-limit 60"
gradlew.bat run --args="--vsync on --frame-limit 0"
```

Saved preferences load first, then supplied overrides replace their fields. Overrides are not automatically persisted; the panel's apply/save action retains them. The selected request is supplied during context creation, avoiding an unnecessary initial VSync-on request when the saved setting is off.

## Scheduling and limits

The cap uses monotonic nanosecond deadlines. Its period rounds up to avoid intentionally submitting faster than requested. A late frame starts a new deadline without presentation catch-up bursts. VSync and the cap can both delay presentation.

Waiting uses `glfwWaitEventsTimeout`, allowing window events and posted commands to wake the context thread. Early wakes process queued work and recheck the same deadline; repeated pointer events cannot bypass the cap. Capture and other render-bound actions complete on the next due frame, so low caps can add latency. Applying a new presentation request resets the deadline for an immediate redraw.

Paused playback or zero speed continues using event-driven idle after requested updates. Minimized/zero-size windows skip rendering, reset the deadline and discard elapsed backlog before restoration. Close events are checked before another frame starts. No uninterruptible frame-limiter sleep is added.

Simulation retains the existing 120 Hz accumulator and eight-tick interactive catch-up budget. Low caps, high playback speed or slow frames can therefore make simulation advance more slowly than wall time. The limiter does not jump to future simulation state or change solver substeps. Use offline output for exact tick scheduling.

The [GLFW swap-interval contract](https://www.glfw.org/docs/latest/window_guide.html#buffer_swap) describes a driver request. Driver overrides, GPU load and OS timer granularity affect observed pacing. [Event waits and posted wakeups](https://www.glfw.org/docs/latest/input_guide.html#events) provide the scheduling mechanism. Measured cap accuracy, energy savings, adaptive VSync and variable-refresh behavior are not claimed.

## Offline behavior

Headless rendering and sequence export do not read or write desktop preferences. Their context requests swap interval zero and their loop bypasses the cap. `--fps` remains the separate offline output rate with its existing integer-tick rules.

`--vsync` and `--frame-limit` are rejected in headless/export modes. Benchmark suites accept no presentation overrides and stay uncapped. Single-scene benchmark reports record the final presentation request; interactive VSync affects swap-inclusive CPU samples, so use the pinned headless suite for controlled comparisons.

Loading presets, replay or checkpoints preserves the local presentation request. Presentation is not resumable GPU state; checkpoints retain exact engine-version compatibility. Headless command failures use logs/exit status without an error dialog.

## Verification

JVM checks cover bounds, deadlines, early wakes, late frames, counter wraparound, resets, uncapped behavior, preference validation/roundtrip/failure preservation, Swing actions and CLI restrictions. GPU checks verify the real headless context's forced offline policy. The inspector was rendered offscreen for layout review.

`python3 scripts/verify-presentation.py` runs identical active replays with absent, capped and corrupt preferences. It checks untouched files, identical PNGs and checkpoint resources/clocks/replay, explicit offline metadata and rejected-override destination preservation.

Physical Windows validation remains necessary for observed rates, driver overrides, input/capture wakeups, minimize/restore, high DPI, fullscreen/monitor changes and long sessions. Scheduling tests and an offscreen software context do not establish those desktop behaviors or hardware performance.
