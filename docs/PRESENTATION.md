# Desktop presentation and window behavior

Version 0.11 adds requested VSync and an optional desktop frame-rate cap. Version 0.12 adds monitor-aware fullscreen restore, explicit minimized suspension and focus-safe pointer gestures. These local transport preferences are separate from creative settings stored in presets, effects, discoveries, checkpoints and replay events. Defaults request VSync on, no additional frame cap and suspension while minimized.

## Controls and persistence

Open **Presentation**, select **Request VSync**, choose **Maximum FPS** and **Suspend while minimized**, and press **Apply and save presentation**. Zero means no application cap; integers 1..240 set a maximum submission rate. Draft edits alone change nothing. The panel shows the applied request and save status.

Application happens on the owning render thread without resetting simulation state, phase clocks, smoothing or scene undo history. Presentation actions do not add creative replay events. Playback continues normally.

`presentation.json` in the application data directory uses schema 2 with `schemaVersion`, boolean `vsync`, integer `frameLimit` and boolean `suspendWhenMinimized`. Schema 1 remains readable and gains suspension enabled in memory. Loading never rewrites a file; the next explicit save writes schema 2. Reads are bounded to 16 KiB and validate exact fields, types and ranges. Missing files use defaults; invalid files are reported and defaults are used without overwriting the file.

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

Paused playback or zero speed uses explicit redraw demand after requested updates. Inactive hover and non-rendering commands do not submit a new scene pass; brush previews, native refresh and frame-bound work still request frames. Idle waits are wakeable and discard hidden elapsed time without bypassing the frame cap. See [held-scene redraw policy](PAUSED_REDRAW.md). Close events are checked before another frame starts. No uninterruptible frame-limiter sleep is added.

Simulation retains the existing 120 Hz accumulator and eight-tick interactive catch-up budget. Low caps, high playback speed or slow frames can therefore make simulation advance more slowly than wall time. The limiter does not jump to future simulation state or change solver substeps. Use offline output for exact tick scheduling.

The [GLFW swap-interval contract](https://www.glfw.org/docs/latest/window_guide.html#buffer_swap) describes a driver request. Driver overrides, GPU load and OS timer granularity affect observed pacing. [Event waits and posted wakeups](https://www.glfw.org/docs/latest/input_guide.html#events) provide the scheduling mechanism. Measured cap accuracy, energy savings, adaptive VSync and variable-refresh behavior are not claimed.

## Window and input behavior

F11 queues a presentation action on the context thread. Fullscreen uses the current video mode of the connected monitor with greatest overlap with the window's content rectangle. If there is no overlap, the nearest monitor wins; ties use GLFW's primary-first order. Negative desktop coordinates are supported. The engine queries the native fullscreen attachment instead of maintaining a separate toggle flag.

Entering fullscreen saves the normal window's content position, size, decoration extents and maximized state. A maximized window is restored before its normal bounds are captured. Returning to windowed mode fits that saved rectangle and its current decorations within an available monitor's work area, shrinking oversized content if needed, then reapplies maximization. Bounds are held for this session, not saved between launches. The simulation grid remains independent of the framebuffer size.

If the fullscreen monitor disappears, GLFW returns the window to windowed mode. The next loop enumerates current monitors and fits the saved placement on an available display. Monitor handles are not stored in the saved placement. The application reapplies its VSync request after these transitions. OS/window-manager positioning and DPI behavior still require physical Windows acceptance testing.

**Suspend while minimized** defaults on. Native iconification suspends ticks and rendering even when GLFW still reports a positive framebuffer size. Events and commands continue to be processed with wakeable waits of at most 100 ms. Capture, map, inspection and step requests that need a frame wait for restoration. Playback's paused flag is unchanged. Restoring clears fractional accumulation, discards the hidden elapsed interval and resets the presentation deadline; it does not advance hidden-time catch-up ticks or reset developed state.

Turning suspension off permits normal tick/render scheduling while minimized if the framebuffer remains positive-sized. A zero-sized framebuffer always suspends dependent work. A visible window without focus continues playback. Focus and display transitions discard their blocking elapsed interval. A frame-bound request can still be delayed by the configured frame cap or an OS call.

Live pointer coordinates use window units for the cursor and framebuffer dimensions for the rendered aspect. Painting is limited to the artwork's content rectangle. Losing focus, iconification, fullscreen/DPI transitions or a desktop checkpoint restore cancels the live gesture. A focused release is required before painting again; unfocused synthetic button releases cannot rearm it. The next simulation tick emits a release before a new stroke, including when release/repress occurred while paused or between capped frames. This prevents a line from connecting to the old pigment position. The release follows the ordinary pointer replay path; no new replay event format is needed. Active replay uses its recorded pointer independently of live focus.

The implementation follows GLFW's [window monitor and restoration](https://www.glfw.org/docs/latest/window_guide.html#window_monitor), [framebuffer sizing](https://www.glfw.org/docs/latest/window_guide.html#window_fbsize) and [monitor disconnection](https://www.glfw.org/docs/latest/monitor_guide.html#monitor_event) contracts. Other desktop backends may provide fewer positioning or iconification capabilities; Windows remains the distribution target.

## Offline behavior

Headless rendering and sequence export do not read or write desktop preferences. Their context requests swap interval zero, their loop bypasses the cap and desktop minimization policy, and fullscreen/pointer queries avoid desktop monitor access. `--fps` remains the separate offline output rate with its existing integer-tick rules. Reports record `suspendWhenMinimized: false` for the offline request.

`--vsync` and `--frame-limit` are rejected in headless/export modes. Benchmark suites accept no presentation overrides and stay uncapped. Single-scene benchmark reports record the final presentation request; interactive VSync affects swap-inclusive CPU samples, so use the pinned headless suite for controlled comparisons.

Loading presets, replay or checkpoints preserves the local presentation request. Presentation is not resumable GPU state; checkpoints retain exact engine-version compatibility. Headless command failures use logs/exit status without an error dialog.

## Verification

JVM checks cover deadlines, early wakes, late frames, counter wraparound, preference migration/roundtrip/failure preservation, Swing actions, CLI restrictions, negative-coordinate monitor overlap, work-area restore fitting, DPI pointer conversion, focus rearming, tick releases and suspension boundaries. GPU checks verify the real headless context's forced offline policy and desktop no-ops. Interrupted pigment painting matches an explicit released stroke in every GPU byte and finished pixel, differs from a stale held stroke, and reproduces through ordinary serialized replay. The inspector was rendered offscreen for layout review.

`python3 scripts/verify-presentation.py` runs identical active replays with absent, legacy, suspend-enabled, suspend-disabled and corrupt preferences. It checks untouched files, identical PNGs and checkpoint resources/clocks/replay, explicit offline metadata and rejected-override destination preservation. The installed replay check also exercises the GPU-generated `build/reports/desktop-replay-fixture.json` interruption take.

Physical Windows validation remains necessary for observed rates, driver overrides, input/capture wakeups, minimize/restore, high DPI, fullscreen/monitor changes and long sessions. Scheduling tests and an offscreen software context do not establish those desktop behaviors or hardware performance.

Record Windows version, GPU/driver, monitor layout/scaling and the application version when running these acceptance checks:

| Action | Expected observation |
| --- | --- |
| Pause a developed scene, move/resize it on a secondary monitor, press F11 twice | Fullscreen uses that monitor; the prior accessible content bounds and paused artwork return |
| Maximize, press F11 twice, then restore from maximization | Maximized state returns first; restoring it reveals the prior normal window placement |
| Disconnect the fullscreen monitor | The window returns to an available display with accessible decorations; the next F11 follows its actual windowed state |
| Enable suspension, minimize for 30 seconds, restore | Ticks and frame-dependent requests wait while minimized; restoration does not integrate the hidden interval |
| Disable suspension and minimize | Playback continues if the framebuffer remains positive-sized; zero-sized framebuffers wait |
| Alt-tab while painting, release outside, return with the button held | No stuck injector; a focused release and new press start a separate stroke |
| Pause while painting, change focus and release/repress, then step and resume | The first simulation tick releases the old stroke; later painting does not bridge the old position |
| Move between monitors with different scaling and paint near each edge | Pointer position follows the artwork at the current framebuffer aspect; captured input outside the content does not inject |
| Record painting across focus/fullscreen changes, save, replay at the recorded aspect | Ordinary pointer events reproduce the interruptions; an aspect-changing transition stops recording under the existing replay contract |
