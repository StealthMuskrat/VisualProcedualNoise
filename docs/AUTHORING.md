# Custom fields and reusable effects

The Authoring tab offers scalar formulas, three starting templates and a typed node canvas. Numeric controls update uniforms. Applying a structural edit builds a candidate program; parse, type, compile or allocation failures preserve the current scene.

## Formula language

`p` is the centered artistic domain after scene scale. `t` is seconds since reset; `phase` is the bounded geometric phase. `frequency`, `drift` and `stripeRate` are promoted numeric controls. `pi` is a constant. The output must be scalar.

Examples:

```text
sin(length(p) * frequency - phase)
fbm(vec3(p * frequency, sin(phase)))
0.6 * noise3d(vec3(p * frequency, t * drift)) + 0.4 * sin(p.x * stripeRate + phase)
```

Supported operations are `+ - * /`, parentheses, `vec2/3/4`, `xyzw` swizzles, `sin`, `cos`, `abs`, `floor`, `fract`, `exp`, `length`, `dot`, `min`, `max`, `mix`, `clamp`, `smoothstep`, `noise3d`, `fbm`, `cellular`, `safeDiv`, `sqrtSafe`, `powSafe` and `normalizeSafe`.

`noise3d` is seed-controlled gradient noise in an approximate signed unit range. `fbm` has five normalized octaves. `cellular` returns F1, F2 and a stable normalized region hash, rather than an exact distance to a cell boundary.

Ordinary division retains ordinary floating-point behavior. `safeDiv` returns zero for denominator magnitude below 1e-6. `sqrtSafe` clamps its input at zero. `powSafe` clamps the base to at least 1e-6 and the exponent to ±16. `normalizeSafe` divides by at least 1e-6. Nonfinite scalar output is highlighted magenta. Formula time uses shader float precision; use bounded `phase` for long-running periodic work. Stateful scenes and arbitrary nonperiodic formulas do not have a seamless-loop guarantee.

Limits are 8192 source characters, 256 syntax nodes, depth 32, expanded cost 8192 and generated source 64 KiB. There are no user loops, recursion, scripts, resource declarations, arbitrary includes or shader imports. Formula compilation uses an AST, validates symbol/function types, and folds finite scalar constants. The engine generates gradients for a conservative smooth scalar arithmetic/trigonometric subset. Other fields use finite differences of the complete composition, including its coordinate transformations. Nonsmooth fields have no universal smooth derivative guarantee.

## Node graphs

Add an operation, select its node, and enter source node IDs in port order. Shift-click a target to connect from the selected source. Drag nodes to arrange them. Update selected applies property changes to the draft. Apply graph validates and activates it. Preview selected makes a scalar node the active output. Graph edits and scene changes have separate undo controls.

Ports distinguish scalar, vector2, vector3, distance and region ID. Convert distance through MASK and region identity through HASH_ID. Same-step cycles and missing references are rejected. PREVIOUS_COLOR reads the engine's previous fixed-tick linear visual feedback resource; PIGMENT and AGE read their explicit persistent systems. These are declared state boundaries, not arbitrary same-step connections.

The compiler validates graph fragments, removes disconnected execution work, topologically orders the selected output, and shares equivalent pure expressions with the same complete generated coordinate/seed context. Reachable pointwise operations become one specialized shader. Persistent updates stay in their own passes; no texture is allocated for each arithmetic node.

EXPRESSION nodes accept one to four numeric inputs, named `input0` through `input3` inside the expression. Inputs and output may be scalar or vector2/3/4. These names are reserved from control descriptors. Distances and region IDs still require explicit conversion before arithmetic. Expressions bind parsed syntax nodes to generated values, so input expressions never become unvalidated shader text.

## Reusable graph groups

Save graph as reusable group captures the current scalar graph under an ID and semantic version, replaces it with a GROUP instance, and embeds its definition. An existing revision cannot be overwritten; save a new version instead. Enter the exact `ID@version` in the Group revision field when adding instances. Inputs are listed in declaration order. Undo restores the previous draft.

Imported groups can declare named typed ports. INPUT nodes refer to those port names, while EXPRESSION nodes compose their values. For example, the [ported ripple](../examples/effects/ported-ripple.json) accepts a vector domain and scalar bias, and exposes custom amplitude, ring-frequency and vector-tint controls. Its outer graph warps the coordinates before passing them to the reusable group.

Definitions are embedded in presets and effects and identified by `ID@version`; there are no external template files to change behind a saved scene. Generator seed identities survive encapsulation. The compiler checks port types, missing revisions, recursion, cycles and expansion cost. Limits are 64 root nodes, 16 embedded revisions, 32 nodes and eight ports per definition, four nested instances, and 256 expanded nodes. The existing expression-cost and source-size limits still apply.

## Effect files

Effect API 2 adds user-defined FLOAT, VEC2, VEC3 and VEC4 controls. The Effect controls tab edits descriptors and generates component controls from their labels, units, defaults, bounds, steps, linear/logarithmic mapping and update policy. Use a descriptor ID directly in a formula. Values are uploaded as namespaced uniforms; ordinary value edits keep the linked program. SMOOTHED values converge on fixed ticks, while RESET controls restart persistent state.

Schemas are limited to sixteen controls. IDs cannot shadow built-in controls or formula symbols. Every vector component must match its declared width and bounds. Preset schema 4 stores descriptors and resolved values; older preset schemas retain their previous behavior with an empty custom schema.

Exported API 2 packages include custom controls, values and requested state-system flags. A canonical SHA-256 definition hash identifies their complete contents and is checked on import. For an intentional manual JSON edit, remove the old definitionHash and export again after validation. API 1 packages remain readable.

Export reusable effect writes a self-contained JSON manifest with API version, stable ID, version, author/license fields, fixed coordinate/time contract, controls and an expression or graph. Labels and comments stay with graph nodes. Imported manifests are capped at 1 MiB and accept data definitions only. External files and executable extensions are not loaded. Older hashed manifests are verified against their original JSON before being normalized to the current model.
