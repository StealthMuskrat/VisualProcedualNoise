# Directional fields and effects

Version 0.8 adds a bounded two-dimensional Gabor-style field to formulas and graph expressions, plus reusable **Gabor fibers** and **Gabor weave** effects. The existing sixteen recipe identifiers and preset schema remain unchanged. These effects use the ordinary CUSTOM recipe and Effect API 2.

## Use the effects

In **Authoring → Formulas**, select Gabor fibers or Gabor weave and press **Load effect and reset**. Selection alone leaves the scene intact. Activation follows the existing effect-import path, installs its controls and restarts state. The **Effect controls** tab exposes the editable values; the Scene tab still chooses the root seed and palette.

| Control | Meaning |
| --- | --- |
| Carrier orientation | Radians measured counterclockwise from +X in the artistic domain; visible ridges lie perpendicular to the carrier direction |
| Principal frequency | Carrier cycles per scaled domain unit; logarithmic control from 0.25 to 8 |
| Bandwidth / packet size | Inverse domain units, 0.25 to 4; larger values shrink wave packets and broaden their frequency content |
| Fiber bending | Amplitude of a seeded low-frequency coordinate warp |
| Orbit displacement | Displacement driven by sine/cosine of the integrated motion phase |
| Field amplitude | Signed scalar-field gain before the usual surface and palette mapping |
| Crossing fibers | Weave only: mix with a perpendicular carrier at 0.83 times the principal frequency |

Bending, bandwidth and orbit displacement use the existing fixed-tick smoothing. Other controls update uniforms immediately. The base motion control sets phase speed. Both effects use bounded phase rather than unbounded time, so they can use the existing analytic loop setting. Their animation and color phases repeat at the configured period; persistent simulation overlays are excluded from that analytic-loop contract.

The effects are also portable JSON manifests:

```powershell
gradlew.bat run --args="--effect examples/effects/gabor-fibers.json"
gradlew.bat run --args="--effect examples/effects/gabor-weave.json"
```

Use Files → Export reusable effect to save edits, or capture them in a preset, discovery, replay or checkpoint. The bundled manifests include canonical definition hashes and all control descriptors. Older builds that lack the `gabor` function reject these expressions; they require version 0.8 or later. Checkpoints retain their exact engine-version and shader-source compatibility rules.

## Formula contract

```text
gabor(p, orientationRadians, principalFrequency, bandwidth)
gabor(p + vec2(cos(phase),sin(phase))*drift, 0.65, frequency, 1.0)
```

The first argument is VEC2; the other three are FLOAT. The result is a signed FLOAT. The Gabor field template supplies the second example without adding custom descriptors. FORMULA and typed EXPRESSION graph nodes accept the same helper, including transformed coordinates and scalar input ports.

The field uses the existing root-seed derivation and an independent hashed impulse channel. Each unit cell in bandwidth-scaled coordinates contains exactly two hashed positions with signed weights. Evaluation visits a 5×5 neighborhood: at most 50 candidate wave packets per helper call. It allocates no persistent GPU resources.

Each packet multiplies a Gaussian envelope by a directional cosine. A smooth taper fades the envelope to zero between squared radii 2.25 and 4. Outside radius 2 the contribution is exactly zero, including its first derivative at the boundary. Moving between grid cells therefore changes the candidate list only where contributions have faded out.

The helper explicitly clamps principal frequency to [0, 16] and bandwidth to [0.25, 4]. Finite position components are capped at ±262144 before bandwidth scaling to keep integer hashing defined. Orientation is reduced modulo a full turn. Nonfinite arguments produce a nonfinite scalar for the existing display diagnostic or map-export rejection path. Normal artistic coordinates should remain much smaller than the coordinate cap because shader float precision decreases at large magnitudes.

The result is half the weighted sum. It is not variance-normalized or constrained to [-1, 1]; a conservative helper bound is ±25 before outer arithmetic. Texture-map export retains its explicit ranges and clipping counts. Complete-field finite differences supply surface gradients, including coordinate warps, rather than claiming analytic derivatives for this helper.

Each Gabor call costs 2048 units in the expression expansion budget. The existing limit of 8192 permits at most three calls in a formula, with the remaining budget available for their arguments. This is an authoring complexity measure, not a measured instruction or frame-time estimate.

## Research reference and limits

The kernel is inspired by equation 6 of Lagae, Lefebvre, Drettakis and Dutré, [Procedural Noise using Sparse Gabor Convolution](https://graphics.cs.kuleuven.be/publications/LLDD09PNSGC/), SIGGRAPH 2009. That work motivates orientation, principal frequency and bandwidth controls.

This implementation is an original bounded artistic variant. It uses a fixed number of impulses per cell, signed hash weights and an added compact taper. It does not implement the paper's Poisson process, exact spectral statistics, surface/solid-noise mapping or anisotropic filtering. The helper evaluates an unfiltered field: high frequency, large scene scale, strong coordinate warps or small output sizes can alias. Reduce frequency/scale or increase output resolution when inspecting thin fibers. The small verification gallery is a functional sample, not an antialiasing or performance claim.

Orientation reversals produce the same cosine carrier mathematically; finite shader precision can introduce small differences. Preset morphing follows the declared linear orientation values and does not choose a shortest angular arc. Root seeds, parameters and fixed ticks reproduce results on a compatible build/device; cross-vendor bit identity is not promised.

## Verification

JVM checks cover function typing, graph acceptance, bounded expansion, manifest hashes, control metadata, persistence and actual desktop actions. GPU checks compare 4096 raw probe samples and 6144 material-map samples with an independent double-precision reference at an absolute tolerance of 0.0002. They also check seed repeatability, principal-direction energy, half-turn symmetry, cell-boundary continuity, clamps, nonfinite diagnostics, normal output, uniform edits without relinking, formula/graph agreement and analytic-loop endpoints.

`python3 scripts/verify-directional.py` exercises both bundled manifests through the installed launcher, verifies that map capture preserves image/state, and compares uninterrupted replay with checkpoint continuation through a later smoothed-control edit. The recipe gallery additionally contains `gabor-fibers.png` and `gabor-weave.png` with ordinary capture sidecars. Windows hardware, DPI and performance acceptance remains pending.
