# Procedural Visual Engine

A Windows x64 desktop visual instrument using Java 25, LWJGL 3 and OpenGL 4.3. The design reference is [the project specification](Windows-Procedural-Visual-Engine-Project-Specification.md).

## Development

Install a JDK 25 and run `gradlew.bat run` on Windows. Linux development uses `./gradlew run`. Native artifacts follow the host OS; `-PnativePlatform=natives-windows` selects Windows x64 for distribution builds.

Space pauses, period advances one tick, R resets, P changes palette, F5 reloads engine shader resources, F11 toggles fullscreen and Escape closes. Hold the left mouse button over the artwork to interact.

For editable engine resources: `gradlew.bat run --args="--shaders src/main/resources/shaders"`. These resources are for engine development; user effect authoring uses templates and bounded formulas.

Dependencies are pinned to LWJGL 3.4.3 and Gradle 9.1.0. Gradle's [9.1 release notes](https://docs.gradle.org/9.1.0/release-notes.html) confirm Java 25 support. See [LWJGL releases](https://github.com/LWJGL/lwjgl3/releases) and [FFM documentation](https://github.com/LWJGL/lwjgl3/blob/master/doc/FFM.md) for its Java 25 backend. Classpath launches explicitly enable native access.

GitHub testing is deferred until implementation is complete. Windows hardware validation, vendor measurements and release support claims require an actual tested device.
