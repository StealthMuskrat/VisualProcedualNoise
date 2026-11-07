# Custom fields and reusable effects

The Authoring tab offers scalar formulas, four starting templates, editable directional effects and a typed node canvas. Numeric controls update uniforms. Applying a structural edit builds a candidate program; parse, type, compile or allocation failures preserve the current scene.

## Finding and correcting errors

Version 0.20 locates formula syntax/type failures with a line, column and source range. Apply formula selects the offending text; a missing token at the end places the caret at that position. A vector result is rejected before submitting a scalar material field. Correct the draft and apply it again.

Located graph failures identify the original node. Apply graph selects and scrolls to that node; direct FORMULA/EXPRESSION errors also select their source range when the displayed single-line field matches the original expression. The affected node has a warm outline. Compiled expression errors inside an embedded group show the instance path, for example `ripple / field`, and select the outer instance. Inner offsets are relative to the inner expression and are never applied to the outer node's formula. Definitions are still embedded and revision-pinned; this does not add an inline group-definition editor.

The wrapping, scrolling status area retains the complete diagnostic within a fixed preferred height. Editing an invalid draft updates its diagnostic without changing tabs or submitting the scene. Undo graph edit restores the draft and its visible fields; loading/synchronizing another scene clears the previous scene's graph undo history. Scene undo remains a separate operation.

Scene **Undo controls** now records only accepted changes. A failed source edit leaves history intact, and a failed undo retains its entry for retry after repair. See [configuration undo, held values and restart policy](UNDO.md).

| Failure | Located range |
| --- | --- |
| Unknown symbol or function | Its name |
| Invalid swizzle or number | The invalid token |
| Arithmetic width mismatch | The operator |
| Function argument type mismatch | The incompatible argument |
| Invalid constructor, argument count or scalar-field result | The call or complete field expression |
| Missing delimiter or other syntax error | The unexpected character, or an empty range at end of input |
| Missing graph link, cycle or group port mismatch | The original consuming/member node; no invented formula range |

Diagnostics report the first detected error. Definition validation can identify a group revision and its node before instance expansion. Global schema, dependency, size and resource failures can remain unlocated. Locations are parser/type diagnostics, not a reconstruction of vendor shader-driver error positions. Programmatic spans use zero-based, half-open Java/Swing UTF-16 offsets; displayed line and column are one-based, with CRLF counted as one line break.

Location tracking does not enter generated shader text, structural keys, package hashes, presets, replay or checkpoints. A pinned v0.19 source fixture checks nine formula cases and the bundled typed-group function. Native checks reject invalid candidates while preserving the active program, finished image, every GPU state byte, resolved controls and tracked storage, then activate a corrected source. Creative formats and algorithm revision are unchanged; checkpoints retain exact engine-version compatibility.

## Formula language

`p` is the centered artistic domain after scene scale. `t` is seconds since reset; `phase` is the bounded geometric phase. `frequency`, `drift` and `stripeRate` are promoted numeric controls. `pi` is a constant. The output must be scalar.

Examples:

```text
sin(length(p) * frequency - phase)
fbm(vec3(p * frequency, sin(phase)))
0.6 * noise3d(vec3(p * frequency, t * drift)) + 0.4 * sin(p.x * stripeRate + phase)
```

Supported operations are `+ - * /`, parentheses, `vec2/3/4`, `xyzw` swizzles, `sin`, `cos`, `abs`, `floor`, `fract`, `exp`, `length`, `dot`, `min`, `max`, `mix`, `clamp`, `smoothstep`, `noise3d`, `fbm`, `cellular`, `gabor`, `safeDiv`, `sqrtSafe`, `powSafe` and `normalizeSafe`.

`noise3d` is seed-controlled gradient noise in an approximate signed unit range. `fbm` has five normalized octaves. `cellular` returns F1, F2 and a stable normalized region hash, rather than an exact distance to a cell boundary.

`gabor(vec2, orientation, principalFrequency, bandwidth)` returns a bounded-cost signed directional field. It is available in formulas and typed graph expressions. The Authoring tab can also load complete fibers/weave effects with custom controls. See [directional field units, clamps, filtering limits and examples](DIRECTIONAL_NOISE.md).

Ordinary division retains ordinary floating-point behavior. `safeDiv` returns zero for denominator magnitude below 1e-6. `sqrtSafe` clamps its input at zero. `powSafe` clamps the base to at least 1e-6 and the exponent to ±16. `normalizeSafe` divides by at least 1e-6. Nonfinite scalar output is highlighted magenta. Formula time uses shader float precision; use bounded `phase` for long-running periodic work. Stateful scenes and arbitrary nonperiodic formulas do not have a seamless-loop guarantee.

Limits are 8192 source characters, 256 syntax nodes, depth 32, expanded cost 8192 and 64 KiB of UTF-8 expression plus requested gradient text. There are no user loops, recursion, scripts, resource declarations, arbitrary includes or shader imports. Formula compilation uses an AST, validates symbol/function types, and folds finite scalar constants. The engine generates gradients for a conservative smooth scalar arithmetic/trigonometric subset. Other fields use finite differences of the complete composition, including its coordinate transformations. Nonsmooth fields have no universal smooth derivative guarantee.

Version 0.21 checks every chain-rule concatenation before building it. A supported analytic gradient that exceeds the combined limit rejects the candidate with a located error; it does not silently switch differentiation methods. Unsupported trees retain the existing complete-field finite-difference fallback without building an unused derivative first. Accepted generated expressions and gradients retain their previous text and meaning.

Graph FORMULA and EXPRESSION nodes compile values only. The completed graph already uses full-field finite differences, so these nodes do not request analytic gradients that would be discarded. Their syntax/depth/cost checks remain in force, and the completed graph function retains its separate 64 KiB UTF-8 cap. A formula whose gradient would be too large can still be a bounded graph value; the graph's existing derivative method remains unchanged.

Generated function wrappers and control declarations join engine resources under the [final shader-stage limit](SHADER_CACHE.md#source-bounds). All final vertex, fragment and compute stages must fit 1 MiB of valid UTF-8 text, including inserted custom code. These limits bound source preparation; they do not predict driver compilation time or GPU execution cost. Preset schema 5, Effect API 2, replay format 1 and the simulation algorithm revision remain unchanged; checkpoints retain exact engine-version checks.

## Node graphs

Add an operation, select its node, and enter source node IDs in port order. Shift-click a target to connect from the selected source. Drag nodes to arrange them. Update selected applies property changes to the draft. Apply graph validates and activates it. Preview selected makes a scalar node the active output. Graph edits and scene changes have separate undo controls.

Ports distinguish scalar, vector2, vector3, distance and region ID. Convert distance through MASK and region identity through HASH_ID. Same-step cycles and missing references are rejected. PREVIOUS_COLOR reads the engine's previous fixed-tick linear visual feedback resource; PIGMENT and AGE read their explicit persistent systems. These are declared state boundaries, not arbitrary same-step connections.

The compiler validates graph fragments, removes disconnected execution work, topologically orders the selected output, and shares equivalent pure expressions with the same complete generated coordinate/seed context. Reachable pointwise operations become one specialized shader. Persistent updates stay in their own passes; no texture is allocated for each arithmetic node.

EXPRESSION nodes accept one to four numeric inputs, named `input0` through `input3` inside the expression. Inputs and output may be scalar or vector2/3/4. These names are reserved from control descriptors. Distances and region IDs still require explicit conversion before arithmetic. Expressions bind parsed syntax nodes to generated values, so input expressions never become unvalidated shader text.

## Reusable graph groups

Save graph as reusable group captures the current scalar graph under an ID and semantic version, replaces it with a GROUP instance, and embeds its definition. An existing revision cannot be overwritten; save a new version instead. Enter the exact `ID@version` in the Group revision field when adding instances. Inputs are listed in declaration order. Undo restores the previous draft.

Imported groups can declare named typed ports. INPUT nodes refer to those port names, while EXPRESSION nodes compose their values. For example, the [ported ripple](../examples/effects/ported-ripple.json) accepts a vector domain and scalar bias, and exposes custom amplitude, ring-frequency and vector-tint controls. Its outer graph warps the coordinates before passing them to the reusable group.

Definitions are embedded in presets and effects and identified by `ID@version`; there are no external template files to change behind a saved scene. Generator seed identities survive encapsulation. The compiler checks port types, missing revisions, recursion, cycles and expansion cost. Limits are 64 root nodes, 16 embedded revisions, 32 nodes and eight ports per definition, four nested instances, and 256 expanded nodes. The existing expression-cost and source-size limits still apply.

## Linked program reuse

Recent recipes, formulas and graphs can reuse their linked material program when revisited. Numeric uniforms still update without compiling. Retention is bounded and failed edits keep the working scene; a successful F5 starts a fresh generation. See [shader cache policy, source limits and diagnostics](SHADER_CACHE.md).

## Effect files

Effect API 2 adds user-defined FLOAT, VEC2, VEC3 and VEC4 controls. The Effect controls tab edits descriptors and generates component controls from their labels, units, defaults, bounds, steps, linear/logarithmic mapping and update policy. Use a descriptor ID directly in a formula. Values are uploaded as namespaced uniforms; ordinary value edits keep the linked program. SMOOTHED values converge on fixed ticks, while RESET controls restart persistent state.

Schemas are limited to sixteen controls. IDs cannot shadow built-in controls or formula symbols. Every vector component must match its declared width and bounds. Preset schema 4 introduced descriptors and base values; schema 5 adds contextual modulation destinations. Legacy schemas remain readable.

The Modulation tab can animate uniform/smoothed effect scalars and individual vector components. Draft edits and reordering require explicit Apply, and reset-required controls are excluded. See [modulation order, timing, smoothing and persistence](MODULATION.md).

Exported API 2 packages include custom controls, values and requested state-system flags. A canonical SHA-256 definition hash identifies their complete contents and is checked on import. For an intentional manual JSON edit, remove the old definitionHash and export again after validation. API 1 packages remain readable.

Export reusable effect writes a self-contained JSON manifest with API version, stable ID, version, author/license fields, fixed coordinate/time contract, controls and an expression or graph. Labels and comments stay with graph nodes. Imported manifests are capped at 1 MiB and accept data definitions only. External files and executable extensions are not loaded. Older hashed manifests are verified against their original JSON before being normalized to the current model.
