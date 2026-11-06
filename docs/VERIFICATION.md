# Verification status

Recorded November 6 2026. Source implementation includes the renderer, sixteen recipes, inspector, persistence, fixed-tick simulations, authoring, replay, export and Windows app-image build path. Windows release acceptance remains pending physical-device validation.

## Local results

| Check | Result |
| --- | --- |
| Java 25 compilation and distribution assembly | Passed with Temurin 25.0.5+8 |
| JVM contracts | 142 passed: parsing, types, bounds, graph cycles and pruning, seeds, preset migrations, modulation, clocks, replay, custom controls, hashes, typed groups, checkpoint archives, quality profiles, still metadata, discovery integrity/branching/gallery and memory limits |
| OpenGL contracts | 82 passed on Mesa llvmpipe, OpenGL 4.5 core, Mesa 25.2.8, LLVM 20.1.2 |
| Authoring behavior | Custom values change the image without relinking; encapsulating seeded graphs preserves their rendered output; typed group expressions link and render |
| Recipe coverage | All sixteen recipes compiled and rendered; generated gallery includes PNGs and complete sidecars |
| Reset and pause | Repeated reset at the same seed/tick reproduced the image; repeated rendering without ticks preserved it |
| Pigment transport | Constant-field preservation and positive-flow impulse direction passed on a 65×65 grid |
| Reaction diffusion | Concentrations remained finite and bounded over 256 ticks at three exposed coefficient combinations; initialization responds to root seed |
| Particles | 257 particles exercised respawns; 300 ticks reproduced identical trail state after reset |
| History | Validity reset, bounded ring and tick timestamps passed |
| Failure preservation | Invalid allocation and intentionally broken shader retained the working image/resources |
| Replay export | Two independent eight-frame exports produced byte-identical PNG files |
| Checkpoint continuation | Independent installed processes reproduced the saved tick-306 image and exact tick-360 image, clocks, replay cursor and every GPU resource from an uninterrupted run |
| Checkpoint integrity | Corrupt, missing, unexpected, truncated and incompatible archive data rejected; failed save preserved its existing destination; failed staged GPU restore preserved the active scene |
| Quality profiles | Resolved values round-trip, preserve creative controls and fit the declared memory budget; recorded quality cannot be silently overridden |
| Independent stills | Offscreen output matches the 96×64 viewport exactly; the 640×400 native comparison differed by one 8-bit step in 10 of 256000 pixels (limit: one step in 0.1%); state stayed byte exact. Odd sizes, differing aspect, paused repeatability, A/B reference, live framebuffer preservation, temporary cleanup and pre-allocation budget rejection passed |
| Discovery history | Completed bundles, legacy reads, annotations, corruption isolation, path bounds, seed/dependency preservation and source-preserving branches passed; gallery filtering, pagination, actions and stale results checked through actual Swing components |
| Saved-image reference | Orientation, encoded color, live right-half preservation, byte-exact persistent state, checkpoint readback, replacement accounting and invalid-image preservation passed |
| Installed discovery workflow | Bookmarking preserved image and state; reopening restarted at tick 0 and reproduced the same reset-to-tick run; child provenance and corrupt-reference rejection passed, including Unicode/spaced paths |
| Sequence timing | 24 fps sequence used ticks 0, 5, 10 and 15 for its first four frames |
| Installation contents | Gradle distribution contains engine resources, pinned natives, notices and licenses |

The GPU suite intentionally compiles an invalid shader to test fallback. Its compiler error in the log is expected. The suite fails if the previous image is lost.

## Software baseline

The [raw version 0.1 baseline](benchmarks/llvmpipe-opal.json) records liquid opal at 320×200, seed 173496285120, a 65×65 state grid, no pointer input, 64 frames and 128 fixed ticks. Thirty frames warm up the driver; 34 frames contribute samples. This was a separate run without concurrent test jobs.

| Measurement | Median | 95th percentile |
| --- | --- | --- |
| CPU frame wall time | 38.71 ms | 61.65 ms |
| GPU render elapsed query | 36.37 ms | 60.81 ms |

The device is a CPU software renderer. These values do not establish Windows hardware requirements or satisfy the 1080p/60 fps target. The analytic preset schedules no persistent update, so its near-zero tick query measures an empty workload. No optimization speedup is claimed.

## Windows and release acceptance

GitHub verification is introduced after the implementation batches. The Windows job checks JVM contracts, builds an x64 app image with its own Java runtime and verifies packaged resources/native loading. A hosted runner does not establish the application's GPU performance or desktop behavior.

Before public release, run the specification's matrix on physical Windows machines: NVIDIA, AMD and Intel drivers; clean account without Java; Unicode/spaced paths; DPI and monitor changes; fullscreen; focus loss; minimize/restore; long sessions; repeated quality changes; still/sequence color and orientation; and practical performance targets. Record the actual Windows versions and hardware before declaring support.

The installed Linux script launcher was exercised with replay exports. A native Linux app image was built, but its launcher could not be validated in this sandbox because the process filesystem is unavailable. Linux app images are outside the Windows distribution target.

## Scope of this release

Version 0.5 adds [discovery history and branching](DISCOVERIES.md), including a thumbnail gallery, favorites, annotations and saved-image A/B references. Version 0.4 introduced [named quality presets and independent still capture](QUALITY_AND_STILLS.md), including exact capture render-size metadata and state-preserving output. Version 0.3 introduced [full GPU checkpoints](CHECKPOINTS.md), including particle counters, history, time, smoothing, A/B reference and active replay continuation. Version 0.2 introduced Effect API 2: custom scalar/vector descriptors, resolved values, generated controls, definition hashes, embedded revision-pinned groups and typed expression ports. Graphs retain explicit reads of the engine's prior visual color, pigment and local-age resources. API 1 effects and older presets remain readable. Reaction and transport customization use the supported host-owned templates and bounded controls.

Arbitrary simulation kernels, external package dependency resolution, signed installers, bundled video encoding, audio/MIDI/OSC, 3D rendering, wallpaper integration and optional driver-specific optimization experiments remain later extensions. The implementation does not claim those features are present.
