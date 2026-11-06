# Texture-map export

Version 0.6 adds 16-bit PNG texture maps from the current frame. In Files, set the output width/height above the texture-map panel, choose the map and its value range, then export. The scene keeps its current simulation tick and persistent state.

## Map contracts

All channels are stored as unsigned 16-bit linear data, with top-left image origin. Import them as data/linear textures. The JSON metadata declares the numeric value represented by black (0) and white (65535):

`value = minimum + code / 65535 * (maximum - minimum)`

| Map | Channels | Default range | Meaning |
| --- | --- | --- | --- |
| Height | Gray | −2..2, adjustable | Complete procedural scalar field multiplied by the resolved surface-height control |
| Normal | RGB | −1..1, fixed | Unit XYZ normal of that height field; +X right, +Y up, +Z out |
| Mask | Gray | 0..1, fixed | Antialiased zero-level coverage of the field before height scaling; negative values approach 0, positive values approach 1, zero has half coverage |
| Linear color | RGB | 0..16, adjustable | Nonnegative linear material color with active pigment, reaction, particle, history and feedback overlays |
| Emission | RGB | 0..16, adjustable | `max(linear material color - 0.65, 0)` per channel, using the shipped bloom threshold without blur |

Height, normal and mask use the procedural field. Color-only simulation overlays are part of color/emission; they do not automatically become displacement. A custom graph can explicitly read persistent resources into its field.

Map normals use pixel derivatives of the complete field, including role mixing and coordinate transformations. Their base domain spans `2 * resolved scale` units vertically and that span times output aspect horizontally. Normalization uses scaling to avoid overflow for steep finite fields. These data normals have their own height-field convention; the artwork's existing artistic lighting approximation is unchanged. Decode XYZ with the stated range and renormalize after texture filtering. Consumers using the opposite green-channel convention need to flip Y.

Map evaluation runs at the requested full resolution. Output resolution affects field filtering, finite pixel derivatives and mask coverage. It does not increase the simulation grid. A single analytic tile is exported when tile mode is active. The 2×2 wrap preview, debug display, frozen A/B reference, bloom blur, exposure and display sRGB conversion are excluded. Use artwork capture for a display-ready image.

Values outside the selected encoding range are clipped and counted in `mapClippedSamples`. Increase the range to retain more of the field. This count is per channel sample. The normal/mask ranges are fixed; color/emission ranges start at zero or above. Adjustable ranges must be finite, increase by at least 0.000001, and remain within ±1000000. Nonfinite samples fail export before replacing existing output.

## Command line

```powershell
gradlew.bat run --args="--headless --frames 120 --size 1280x800 --map height --map-output height.png --map-size 1024x1024 --map-range -2:2"
```

`--map` accepts `height`, `normal`, `mask`, `linear-color` or `emission`. It requires `--map-output` and a positive `--frames` count. Omit `--map-size` to use the live framebuffer dimensions. A map can accompany ordinary capture, sequence export, discovery loading or checkpoint resume. It is written from the final requested frame; a one-frame sequence export can export a checkpoint's saved tick without advancing it.

The map size is independent of `--size`, `--capture-size` and live display scale. A different aspect ratio reframes the field. The captured pointer remains in the centered, aspect-correct artistic domain and is recorded with the metadata.

## Encoding, metadata and resource limits

The PNG has no sRGB, gamma, ICC or chromaticity tags. Its actual 16-bit samples are preserved without color conversion. A UTF-8 iTXt entry named `VisualEngineTextureMap` contains the complete JSON contract and capture context. `FILE.png.json` mirrors it for inspection and reuse as a restarting configuration preset.

Metadata includes map version/type, channel meanings, range/decode rule, clipping count, dimensions, base controls, resolved parameters and custom values, modulation, seed, integrated phase clocks, tick, pointer, quality, embedded graph definitions, device and actual shader-source signature. Base controls describe the restarting preset; resolved controls describe the sampled frame. Persistent simulation textures are represented by the exported map, with checkpoints remaining the format for resuming the whole simulation.

Each PNG is prepared with its embedded metadata in a temporary file before replacement. The JSON companion is prepared before publishing and replaced separately. These are two independent filesystem operations: if sidecar replacement fails, the process reports the error and the completed PNG's embedded metadata remains authoritative.

Bounds are 32..8192 per axis and 33,554,432 pixels. Temporary capture textures use R32F for scalar maps and RGBA32F for RGB maps. A 4096×4096 capture therefore adds 64 MiB or 256 MiB of GPU storage respectively, alongside live resources. The 512 MiB engine budget is checked before allocation. Large RGB maps may hit that budget before the pixel-count limit. Readback synchronizes explicitly; quantization, PNG encoding and file output run on the bounded worker with one map operation in flight.

Changing map type or range does not relink shaders, tick simulations or replace live output buffers. Failed allocation/readback leaves the live framebuffer and persistent resources intact. Editable engine shaders need the `mapOutput` interface and matching type semantics; an older shader without it rejects map capture while continuing to display.

## Verification

`./gradlew check gpuCheck installDist` checks raw 16-bit samples, embedded/sidecar agreement, absent display-transfer tags, analytical height planes and normals, complete-field derivatives, steep-field stability, mask coverage, emission thresholds, state preservation, clipping, budget limits and unsupported shader interfaces. The JVM suite also renders the map UI offscreen. On Linux with Mesa EGL and JDK 25, run `EGL_PLATFORM=surfaceless python3 scripts/verify-texture-maps.py` to exercise all five formats through the installed launcher, including active replay, smoothing/pointer metadata, byte-exact state/view preservation and failed-save preservation.
