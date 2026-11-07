# Modulation of built-in and custom controls

Version 0.18 extends the modulation matrix to custom FLOAT controls and individual VEC2/3/4 components. These are numeric uniform updates: changing resolved values or mappings keeps the material program and persistent simulation resources.

Try the [modulated custom-field preset](../examples/presets/modulated-custom.json):

```powershell
gradlew.bat run --args="--preset examples/presets/modulated-custom.json"
```

It combines a smoothed amplitude wave, a triangular domain-offset component, a keyframed offset component and the Agitation macro. Its feedback and pigment systems make it a stateful study, without a seamless-loop claim.

## Edit and apply

The Modulation tab lists mappings in execution order. Select a row to inspect it. **Update selected** edits the draft while preserving its source ID; **Move up/down** changes combination order. **Add mapping** creates another draft, and **Remove selected** removes one. **Apply mappings** submits the whole draft as one undoable scene edit. Selecting a row or editing fields does not apply it.

The destination selector shows custom component labels, bounds, units and whether descriptor smoothing follows mapping. Reset-required controls are excluded. Remove mappings that reference a control before deleting it, changing its width incompatibly or making it reset-required. Such schema edits are rejected rather than silently discarding mappings.

| Stored destination | Meaning |
| --- | --- |
| `warp`, `flow`, `colorRate`, etc. | Existing built-in parameter ID |
| `effect:amplitude` | Declared scalar effect control |
| `effect:offset.x`, `effect:offset.y` | One component of a declared vector |

Scalar `.x` aliases, whole-vector destinations, nonexistent components and color-style component aliases are rejected. Each vector component has its own mapping; unmapped components retain their base values.

## Evaluation

Mappings use simulation seconds and the stored time offset: `(time + offset) / period`. Period/duration is 0.1..3600 seconds and offset is −3600..3600 seconds. There are at most 32 mappings.

| Source | Behavior |
| --- | --- |
| Sine / triangle | Repeating normalized wave |
| Smooth random | Seeded values interpolated smoothly between intervals; stable mapping ID selects its stream |
| Envelope | One-shot smoothstep from zero to one, held outside the duration |
| Keyframes | 2..32 increasing time:value pairs in [0,1]; linear interpolation and held endpoints, without repetition |
| Macro | The base Agitation value in [0,1]; period and offset do not change this source |

Each source maps its normalized output linearly from Low to High. ADD adds that value to the currently resolved destination, MULTIPLY multiplies it, and REPLACE assigns it. Mappings run in list order and clamp after each operation to the destination's declared range. Macro sources all read base Agitation, so list order does not create macro-source feedback.

Low/High are bounded to ±100 for built-in destinations and ±1,000,000 for custom destinations. These input bounds do not widen a control's declared range. A logarithmic manual slider does not change source remapping into a logarithmic curve, and the descriptor's UI step does not quantize modulation.

After mapping, SMOOTHED effect controls converge on fixed ticks with `alpha = 1 - exp(-8 * dt)`. UNIFORM controls take the mapped value on that tick. RESET controls cannot be destinations. Built-in smoothing retains its existing parameter policy. Base effect values remain the restarting settings rather than being overwritten with animated values.

Held frames reuse the last resolved values. Pausing and requesting a single step no longer snap modulation back to base values. Editing a mapping or uniform/smoothed value while paused resolves at the held simulation time without advancing phase clocks or GPU state. Before the first simulation tick, an unedited scene displays its base values. Mapped exposure and bloom now reach held display frames; unmapped finishing edits remain immediate.

## Persistence and continuation

Preset schema **5** stores contextual destinations, source IDs, ranges, offsets and keys alongside base effect descriptors/values. Schemas 1..4 remain readable for their existing built-in destinations. A file containing custom destinations must declare schema 5; older builds reject that schema explicitly.

Effect API 2 remains unchanged. Effect manifests describe authoring definitions and base values; save a preset to retain modulation. Loading another effect clears prior mappings before replacing its control schema. Replay format 1 embeds the new presets in ordinary tick-indexed control events, including future mapping edits. Checkpoints retain resolved smoothing and phase state and still require the exact engine version, shader sources and device signature.

Texture-map metadata distinguishes base `settings.effectValues` from sampled `mapResolvedEffectValues`. A restarting preset or artwork sidecar does not replace a checkpoint of unfinished smoothing and persistent GPU state. Analytic tile/loop modes continue to reject modulation rather than certify arbitrary sources as seamless.

## Verification

JVM checks compare sine/triangle/keyframe values with independent formulas, mapping order, clamping, descriptor smoothing, immutable key arrays, held-time edits, schema rejection/roundtrips and exact signal continuation. Actual Swing actions check draft/apply separation, updates, reordering, source-ID retention and schema synchronization.

OpenGL checks compare a modulated scene against identical resolved values supplied directly as uniforms at ticks 8, 16 and 24, including every persistent GPU byte. Mapping edits perform no material-cache lookup. Invalid destinations preserve the working scene, and map export preserves its state and finished image.

`python3 scripts/verify-effect-modulation.py` verifies the bundled example, replay edits, checkpoint continuation through future random/component mappings, a held restored frame, actual resolved map values and failed-output preservation through the installed launcher.
