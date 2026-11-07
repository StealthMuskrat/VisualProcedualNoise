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

Version 0.24 adds Find nodes above the canvas. Search is case-insensitive; every whitespace-separated word must match the node ID, operation, group label, comment or pinned template revision. Results retain root-node order and show each ID with its operation. The count includes matching and total root nodes. Embedded group internals remain represented by their root GROUP instance. Incomplete formula/link drafts are searchable too.

Filtering keeps the current node selected, even when it is absent from the results. Choose a result to select and reveal it; Show node reveals the current result again. Reset view returns to the original canvas origin. These actions submit no scene commands, change no graph data and consume no draft undo entries. They do not apply unfinished property fields. Scene sync clears the filter and resets the view.

Revealing a node outside the viewport changes a transient canvas projection. Negative and very large finite positions remain authored exactly, and dragging a revealed node applies the pointer delta in those coordinates. Located graph diagnostics use the same reveal path. Remote geometry outside the bounded paint range is omitted before integer conversion; reveal its node to bring it into view. Navigation preserves seed identities, pinned definitions, output and generated shader source. Creative formats and algorithm revision remain unchanged; checkpoints retain exact engine-version/source compatibility.

Version 0.23 makes renaming preserve every root-graph input connection and the active output. Update selected rejects duplicate or malformed IDs before changing the draft, while retaining the entered name for correction. IDs start with a letter and contain up to 48 letters, digits or underscores. Generator seed keys remain unchanged, so renaming a noise node preserves its seeded field. Embedded group definitions and pinned revision IDs remain unchanged too. Formula text, labels and comments are not rewritten as connection names.

Undo graph edit restores the draft and its selected node, including after a rename or deletion. Valid preview/output changes use the same 32-edit history. Invalid previews or output IDs preserve the prior model output and submit no scene change. Selecting a node consumes no history; dragging records one entry when its position first changes. Unchanged updates do not add an entry. Adding at the 64-root-node limit is rejected before mutation. Deleting a referenced/output node leaves a repairable missing-link/output diagnostic until it is reconnected or undone.

Rename changes generated variable names and program identity. Native checks compare both ordinary seeded graphs and group instances: the image, GPU bytes, clocks and next-tick evolution stay identical, and returning to the previous graph can reuse its material. Installed checks additionally verify persisted IDs/links/seed keys, pinned definitions, exact checkpoint continuation and preservation of existing files after invalid imports. Creative formats and algorithm revision remain unchanged; checkpoints retain exact engine-version/source compatibility.

Ports distinguish scalar, vector2, vector3, distance and region ID. Convert distance through MASK and region identity through HASH_ID. Same-step cycles and missing references are rejected. PREVIOUS_COLOR reads the engine's previous fixed-tick linear visual feedback resource; PIGMENT and AGE read their explicit persistent systems. These are declared state boundaries, not arbitrary same-step connections.

The compiler validates graph fragments, removes disconnected execution work, topologically orders the selected output, and shares equivalent pure expressions with the same complete generated coordinate/seed context. Reachable pointwise operations become one specialized shader. Persistent updates stay in their own passes; no texture is allocated for each arithmetic node.

EXPRESSION nodes accept one to four numeric inputs, named `input0` through `input3` inside the expression. Inputs and output may be scalar or vector2/3/4. These names are reserved from control descriptors. Distances and region IDs still require explicit conversion before arithmetic. Expressions bind parsed syntax nodes to generated values, so input expressions never become unvalidated shader text.

## Ordered connections

Version 0.25 adds a Connections tab sharing the selected root node with the canvas and node navigator. Choose a target, select an ordered input, and choose a source node. Replace input reroutes that one slot. Append input adds a final slot; Remove input removes only the selected occurrence, including when a source is used more than once. Move up and Move down preserve the relative order of other inputs. The list uses zero-based `input0`, `input1`, … ordering, matching EXPRESSION bindings and the declaration order of GROUP inputs.

Source and target choices show IDs and operations. Missing source IDs remain visible as missing nodes and can be replaced or removed. Selecting choices or slots changes no graph data and consumes no undo history. A changed connection records one draft undo entry; unchanged replacement/reordering records none. Shift-click append uses the same connection operations. Show target reveals the current target on the Node graph canvas.

Connection changes edit the draft and immediately show arity, type, missing-link or cycle diagnostics. Incomplete drafts remain editable. Apply graph in Node graph validates the complete graph before submitting it to the renderer. A valid reroute can change the field; invalid candidates retain the accepted scene. Undo graph edit restores the links and selected target. Renames, add/delete, undo and scene sync refresh the connection choices; scene sync clears source/slot selection and draft history.

Connection actions use the stored draft links and replace any unfinished text in Input node IDs with the accepted list. Other unfinished property fields remain available and still require Update selected. Rerouting preserves node IDs, seeds, coordinates, output, comments and embedded group revisions. These root edits do not alter the contents of pinned group definitions. Native verification confirms a changed domain changes the rendered field, held edits preserve simulation state, and undo restores the original material, image and next-tick evolution.

## Reusable graph groups

Version 0.26 adds the Groups tab; Save graph as reusable group opens it. Enter a group ID and new semantic version. With no exposed inputs, Capture graph as group embeds the complete scalar graph and replaces it with one GROUP instance, preserving the previous capture behavior. An existing revision cannot be overwritten; save a new version instead. Enter the exact `ID@version` in a GROUP node’s revision field when adding instances.

To create named inputs, choose a source node, enter an input name, and click Expose input. Remove or move entries to set their declaration order before capture. Names start with a letter and contain up to 48 letters, digits or underscores. Each source and name may occur once, with at most eight inputs. Source nodes must contribute to the current scalar output. Capture infers each port’s actual type, including vector widths, distances and region IDs; it does not convert semantic types to scalars.

Capture keeps each exposed source and its transitive dependencies outside the group. Inside the new definition, that source becomes a named INPUT boundary. The new instance connects the retained sources in declaration order. Existing node properties, seed identities and embedded revisions remain owned by their original definitions. If a retained node is named `instance`, the new instance receives an available suffixed ID. Internal definition size, root-node, revision and nesting limits still apply; a failed capture preserves the prior draft and form entries.

Capture edits the draft in one graph-undo step and reveals the new instance; Apply graph activates it. Form-only additions, removal and reordering consume no graph undo history and submit no scene commands. Successful capture clears the temporary input list. Undo restores the graph and selected node before capture; scene sync clears the form and initializes a fresh group identity. An unsubmitted input entry referring to a renamed/deleted root ID remains in the form for correction and is rejected at capture if it is no longer valid.

The Connections tab shows GROUP input names and types beside their zero-based slots. Replace a source there to reuse the pinned definition with a different domain or control signal. New revisions append definitions; they never rewrite a prior revision. Numeric custom controls remain effect-scoped uniforms under their existing descriptors. This capture workflow does not add an editor for the internals of an existing pinned definition.

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
