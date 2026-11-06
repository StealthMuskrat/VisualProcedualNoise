# Procedural Visual Engine

A Windows x64 desktop visual instrument using Java 25, LWJGL 3 and OpenGL 4.3. The design reference is [the project specification](Windows-Procedural-Visual-Engine-Project-Specification.md).

## Development

Install a JDK 25 and run `gradlew.bat run` on Windows. Linux development uses `./gradlew run`. Native artifacts follow the host OS; `-PnativePlatform=natives-windows` selects Windows x64 for distribution builds.

Space pauses, period advances one tick, R resets, P changes palette, F5 reloads engine shader resources, F11 toggles fullscreen and Escape closes. Hold the left mouse button over the artwork to interact.

The separate Controls window provides metadata-generated sliders, parameter locks, controlled randomization, undo, quality settings, preset import/export and clean PNG capture. Tab reopens it. Keeping controls in a separate native inspector makes pointer coordinates unambiguous and prevents painting through controls.

Presets are versioned JSON and restart simulation state. A PNG sidecar preserves its full configuration, reference tick, encoding and device information; it is not a resumable checkpoint. Files are stored under `%LOCALAPPDATA%/ProceduralVisualEngine` by default. Invalid presets leave the current configuration active.

Local offscreen checks can use `./gradlew run --args="--headless --frames 3 --size 480x300 --capture output.png"` with Mesa EGL (`EGL_PLATFORM=surfaceless`) on Linux. This is a development path; Windows remains the distribution target.

Use `--recipe fluid-palette --grid 257` to exercise pigment transport on an odd-sized grid. The scheduler runs at 120 Hz with an eight-tick interactive catch-up cap. Excess interactive backlog is discarded; offline runs advance integer ticks directly. The simulation grid survives viewport resize, and changing grid size resets it. The normalized state is remapped to the current viewport aspect.

Pigments use midpoint semi-Lagrangian transport, periodic boundaries, a small artistic diffusion term, exponential decay, then injection in domain units. This method is diffusive and does not conserve mass. Feedback stores bounded pre-tonemapped linear color on fixed ticks, with distinct previous and next images. Display encoding occurs once in the final pass. Quality settings never adapt the simulation automatically.

For editable engine resources: `gradlew.bat run --args="--shaders src/main/resources/shaders"`. These resources are for engine development; user effect authoring uses templates and bounded formulas.

Dependencies are pinned to LWJGL 3.4.3 and Gradle 9.1.0. Gradle's [9.1 release notes](https://docs.gradle.org/9.1.0/release-notes.html) confirm Java 25 support. See [LWJGL releases](https://github.com/LWJGL/lwjgl3/releases) and [FFM documentation](https://github.com/LWJGL/lwjgl3/blob/master/doc/FFM.md) for its Java 25 backend. Classpath launches explicitly enable native access.

GitHub testing is deferred until implementation is complete. Windows hardware validation, vendor measurements and release support claims require an actual tested device.
