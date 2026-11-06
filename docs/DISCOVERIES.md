# Discovery history and branching

Version 0.5 adds a local discovery gallery. In Files, **Bookmark discovery** saves the finished artwork, its complete configuration and a thumbnail. Open **Discoveries** to browse, search, favorite or rename saved studies. Bookmarks use the scene name as their initial label.

## Restarting a configuration

Each entry shows its recipe, lossless root seed, recorded simulation tick, image dimensions, quality, engine/algorithm revision, embedded graph-group revisions and actual compiled shader-source signature. Captures also record whether the A/B reference was visible.

Every discovery is labeled **Configuration · restart**. Opening one restarts the simulation at tick 0 with its saved configuration. It does not restore the developed state shown in the thumbnail. Use a [checkpoint](CHECKPOINTS.md) to resume persistent GPU state, clocks, history and replay position. A discovery's shader signature records its source context; it does not turn a configuration into a cross-build or cross-driver image-equality guarantee.

| Action | Behavior |
| --- | --- |
| Open configuration (restart) | Applies the saved settings, stops replay/recording and resets simulation state |
| Branch palette | Opens a restarting copy with a different palette and the same seed and composition |
| Branch motion | Opens a restarting copy with new bounded motion/color rates, respecting existing parameter locks |
| Lock composition controls | Adds locks for scale, broad/fine warp, generator role and history mix when opening or branching |
| Use image as A/B reference | Loads the saved finished image on the left; the current live simulation remains on the right |
| Save label / favorite | Updates only gallery annotations; artwork and configuration remain unchanged |

Branches preserve custom controls, embedded group definitions, modulation, seed and quality. They leave the source discovery untouched. Composition locks constrain controlled randomization; the sliders remain editable. Opening a discovery makes its ID the parent of subsequent bookmarks; loading another preset, effect, replay or checkpoint clears that context. Merely bookmarking an entry does not make it the parent of later work.

A saved reference is an image comparison, not a second simulation. It is resampled into the current normalized viewport, so matching aspect ratios avoid stretching. Palette, finishing and simulation edits affect the live half. Clearing the reference restores the normal view. A frozen comparison is itself part of a bookmarked finished image when visible.

## Browsing and limits

Search matches labels, recipes, seed strings and embedded group revisions. Favorites filter the loaded history. The gallery scans the newest 512 recognized bookmark paths, skips invalid configurations and displays 24 entries per page. Older bundles remain on disk and can still be opened by exact ID. Thumbnails for the current page are loaded on the file worker. Stale scan or preview results cannot replace a newer page.

The default directory is `%LOCALAPPDATA%/ProceduralVisualEngine/discoveries`. Compatible legacy `study-<timestamp>.png` bookmarks and their JSON sidecars appear read-only. Their source files are never rewritten. Open one and save a new discovery to obtain an annotated managed entry.

Each completed managed directory contains `artwork.png`, `artwork.png.json` and `thumbnail.png`. The sidecar remains a valid configuration preset and adds a versioned discovery record, creation time, parent ID when present, annotation fields and SHA-256 checksums. Thumbnails are 160×100 with aspect-preserving letterboxing. Source images retain the capture path's opaque SDR sRGB, top-left convention.

Saves build and validate a temporary directory, then publish the completed bundle. Failed saves remove temporary output; scans ignore unfinished directories. Managed configuration and shader metadata have a checksum, while artwork and thumbnails are verified before decoding. Missing or damaged previews report an error without blocking other entries. Invalid references leave the active reference and scene intact. Labels and favorites remain editable independently of captured content.

Manifest size is limited to 1 MiB, source images to 192 MiB and 32 megapixels (8192 per axis), and thumbnails to 1 MiB. IDs and filenames are fixed by the application; path traversal and linked bundle files are rejected. These checks protect data integrity; discovery files are not signed packages. Manual creative edits belong in a new preset or branch, rather than inside an immutable managed capture.

## Command line

Save a named discovery after a finite run:

```powershell
gradlew.bat run --args="--headless --frames 120 --size 1280x800 --recipe liquid-opal --bookmark Opal"
```

The console reports the created ID. Use `--discoveries DIRECTORY` to select another history directory. Reopen the exact ID with `--discovery ID`; this is a configuration source and cannot accompany `--preset`, `--effect`, `--replay` or `--checkpoint`. Explicit recipe, seed, grid and quality overrides can form a new branch. `--bookmark` requires a positive `--frames` count.

`--reference-discovery ID` imports only its artwork as an A/B reference and can accompany another scene source. Both discovery options use the chosen history directory. The inspector shows the selected ID for copying into a command line.

## Verification

`./gradlew check gpuCheck installDist` covers bundle integrity, legacy reading, annotations, branching, filtering, pagination, stale results, reference orientation, state preservation and resource replacement. The JVM suite renders an offscreen gallery preview under `build/reports/ui`. On a suitable Linux Mesa installation, run `EGL_PLATFORM=surfaceless python3 scripts/verify-discovery.py` after building. It exercises the installed launcher, Unicode/spaced directories, lossless seeds, byte-exact state preservation, reset-to-tick reproduction, parent lineage, saved-image comparison and corruption rejection. Physical Windows desktop/DPI validation remains separate.
