# Compatible preset morphing

Version 0.7 adds an explicit blend between two compatible configurations. It edits ordinary numeric controls while the current simulation and animation clocks continue. The Morph tab captures or loads endpoints A and B, checks them against each other and the live scene, and explains incompatibilities before enabling Apply blend.

## Desktop workflow

1. Open a scene and choose **Capture current** for endpoint A.
2. Edit its numeric controls, then capture endpoint B. Alternatively, load a preset into either endpoint. Capturing or loading an endpoint leaves the live settings, tick, smoothing and GPU state intact.
3. Choose the A/B percentage and press **Apply blend**. Moving the slider alone previews the chosen percentage. A is 0% B, and B is 100% B.
4. Continue editing or choose another percentage. **Undo controls** in the Scene tab restores the preceding settings. Use the Files tab to save a preset, checkpoint, still or discovery.

In play mode the usual fixed-tick control smoothing still applies. Pause before applying for immediate numeric values. Applying never resets integrated motion/color phases; the next tick integrates the new rates from the existing clocks. Simulation systems retain their current buffers, ping-pong indices, particle counters and history ring. A frozen A/B image remains a separate display reference.

Loading an endpoint does not activate it as a scene. To use a pair from another scene, first open its base preset through Files, then load the endpoints. Subsequent structural changes to the live scene disable the pair until it is compatible again. Endpoint file reads run on the I/O worker; a late read cannot replace a newer capture or load for that slot. Failed reads preserve the previous endpoint.

## Blend contract

| Values | Behavior |
| --- | --- |
| All 21 built-in numeric effect parameters | Linear interpolation in their declared units |
| Custom FLOAT / VEC2 / VEC3 / VEC4 controls with linear scale | Component-wise linear interpolation |
| Custom controls with logarithmic scale | Component-wise interpolation in log space; descriptor bounds remain enforced |
| Custom controls marked RESET | Must have identical values; preserved exactly |
| Endpoint values at 0 and 1 | Exact numeric endpoint values, before the ordinary smoothing stage |
| Live name, randomization locks, pause, playback speed, diagnostic mode, display scale and tile preview | Preserved from the live scene |
| Live animation phase, fixed tick and GPU resources | Continue; endpoint reference clocks and ticks are not imported |

The blend amount must be finite and in [0, 1]. Endpoint settings are copied, so later live edits cannot change a captured endpoint. Capturing after a slider edit observes that edit in command submission order.

The following must agree between both endpoints and the live scene: recipe, full root seed, palette, simulation grid/particle/history layout, enabled persistent systems, tile/loop topology, expression and complete graph definition, custom control descriptors, reset-required values and modulation mappings. Comparisons include dormant graph/expression definitions, embedded group revisions, mapping order and mapping phases. This conservative rule keeps the blend's meaning explicit. Modulation still evaluates after blending the base controls.

Different palettes or topology choices require a deliberate scene edit and a new compatible pair. This version does not crossfade unrelated simulations, interpolate buffers, schedule an automatic transition or wrap distinct phase endpoints. It preserves the live circular phases instead. Numeric changes reuse the current shader structure and persistent allocation; there is no second scene allocation.

## Command line

The bundled example pair shares its recipe, seed and state layout:

```powershell
gradlew.bat run --args="--preset examples/presets/morph-a.json --morph-target examples/presets/morph-b.json --morph-amount 0.5"
```

To capture the same resolved starting configuration after 120 ticks:

```powershell
gradlew.bat run --args="--headless --frames 60 --size 1280x800 --preset examples/presets/morph-a.json --morph-target examples/presets/morph-b.json --morph-amount 0.5 --capture morph.png"
```

Both `--morph-target FILE` and `--morph-amount 0..1` are required. Startup blending happens after the base preset/effect/discovery and explicit scene/quality overrides, before creating a window or allocating GPU resources. The target must match that resolved base, including the persistent layout. Display settings come from the base. Incompatible input fails before producing output and preserves existing destinations.

Startup morphing uses a restarting configuration and rejects `--replay` or `--checkpoint`. To edit a running resumed state, use the desktop Morph tab. A successful desktop apply stops replay playback, preserving the current state; a rejected blend leaves replay active. During recording, applies are saved as normal complete control events at the next tick. Endpoint capture/load operations do not add events.

## Persistence and verification

Presets, discoveries, captures and checkpoints store the resulting ordinary settings. Replay records those settings rather than references to endpoint files. Endpoint selections and the chosen slider position are transient editing tools and are not saved. Preset schema 4 and Effect API 2 remain unchanged; checkpoints retain their exact engine-version compatibility requirement.

JVM checks cover compatibility, ranges, linear/logarithmic scalar/vector interpolation, exact endpoints, phase integration, immutable captures, stale loads, command ordering and actual Swing actions. GPU checks compare paused images and every persistent state byte before/after blends, reject incompatible edits without changing the image, and continue simulation after a blend. `python3 scripts/verify-morph.py` exercises the installed launcher, Unicode/spaced paths, endpoint/midpoint equivalence, replay control events, checkpoint continuation and failure preservation. Physical Windows desktop and driver validation remains part of release acceptance.
