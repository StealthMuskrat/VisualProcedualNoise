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

The compiler validates all fragments, removes disconnected execution work, topologically orders the selected output, and shares equivalent pure expressions with the same complete generated coordinate/seed context. Reachable pointwise operations become one specialized shader. Persistent updates stay in their own passes. Graphs have at most 64 nodes; no texture is allocated for each arithmetic node.

## Effect files

Export reusable effect writes a self-contained JSON manifest with API version, stable ID, version, author/license fields, coordinate/time contract, promoted controls and an expression or resolved graph. Group labels and comments stay with graph nodes; the exported graph pins its resolved content. Imported manifests are capped at 1 MiB and accept data definitions only. External files and executable extensions are not loaded. Presets use schema 2; schema 1 gains the three authoring controls through an explicit migration.
