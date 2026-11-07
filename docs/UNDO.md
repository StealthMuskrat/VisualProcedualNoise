# Accepted scene edits and undo

Version 0.22 makes **Undo controls** history commit with an accepted scene. A failed edit adds no entry and cannot evict the oldest useful configuration. Undo selects an owned candidate without removing its entry; validation, shader compilation or allocation failure leaves that entry available for a later retry.

History holds at most 64 configurations. Unchanged configurations do not add duplicate adjacent entries. Empty undo leaves held controls and recording untouched. Parameter locks, simulation switches, layout controls, display/timing settings and diagnostic view changes now use the same history policy as numeric controls, palettes, custom fields, modulation, quality and preset/effect activation.

## State and restart behavior

Undo restores configuration, not an earlier GPU snapshot or recording cursor. The current tick, phase clocks and developed simulation remain current unless the restored configuration requires a restart. A successful undo stops active replay so future recorded inputs cannot overwrite the restored choice; a failed undo leaves replay active. A simulation restart runs through the existing next-frame reset path.

| Restored change | Simulation behavior |
| --- | --- |
| Numeric uniform/smoothed control, modulation mapping or compatible morph | Preserve developed state; resolve held controls at the current tick when paused or speed is zero |
| Palette, locks, diagnostic view or other change with identical numeric controls/mappings | Preserve developed state and unfinished held smoothing |
| Root seed or recipe | Request restart |
| Grid, particle count or history layout | Request restart and validate replacement allocations |
| RESET custom value, or addition/removal/type change of a RESET control | Request restart; cosmetic descriptor labels alone do not require it |
| Undo of preset, effect or discovery activation | Retain that activation's explicit restart policy |
| Successful checkpoint resume | Restore the saved state and clear configuration history |

Configuration snapshots include the saved playback/display settings. They do not contain simulation buffers, history images, replay cursors or editor drafts. Source/graph changes retain their existing state-preservation policy. The Authoring tab's **Undo graph edit** remains a separate draft history.

## Accepting a command

The context thread prepares history, held numeric values and any recording event before asking the renderer to accept the configuration. A full recording rejects a control edit before it can replace GPU programs or resources. Acceptance publishes the prepared values/event and commits the history change. Rejected replay activation also restores the prior replay cursor, recording, brush coordinates and pending timing/reset intent.

The history owns its snapshots, permits one pending change on its owning thread and invalidates pending history work when a checkpoint replaces the scene. History is session-only and is absent from presets, effects, replay and checkpoint data. Preset schema 5, Effect API 2, replay format 1 and the algorithm revision are unchanged; checkpoints retain exact engine-version compatibility.

## Verification

JVM checks cover deferred history mutation, full-capacity rejection without eviction, undo retry, owned snapshots, no-op edits, checkpoint invalidation, thread ownership, restart policy, held-value preparation and recording capacity/event ownership.

The OpenGL suite drives the actual engine command boundary after developing a modulated scene. It checks invalid controls/source/layout, palette and numeric undo, failed shader compilation during undo and a successful retry, a full recording, rejected replay activation, all 64 entries, seed/RESET/layout/preset restart intent and checkpoint failure/success. It compares pixels, GPU state, program identity, smoothing and clocks where preservation is required. These are headless engine checks; physical Windows desktop and driver acceptance remains pending.
