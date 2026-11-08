# Windows acceptance runs

Version 0.29 adds a local acceptance runner to the packaged application. It needs the bundled runtime and matching native libraries, with no separate Java or Python installation. Extract the entire Windows archive before running it. Keep the executable, app/runtime directories, docs and acceptance-shaders together.

The automated run checks installation resources/native loading, probes advertised GPU support, then runs the seven pinned benchmark cases. It publishes a new evidence directory and an explicitly unrun manual-review form. A completed automated run still requires physical desktop, visual, endurance and performance review.

## First run

Open PowerShell in the extracted application folder. This example waits for the packaged GUI-subsystem executable and retains its stdout/stderr beside the evidence folder:

```powershell
$acceptanceApp = Join-Path (Get-Location) 'ProceduralVisualEngine.exe'
$acceptanceTag = Get-Date -Format 'yyyyMMdd-HHmmss'
$acceptanceOut = Join-Path (Get-Location) ('acceptance-smoke-' + $acceptanceTag)
$acceptanceArgs = '--acceptance-run "' + $acceptanceOut + '" --acceptance-environment physical --acceptance-profile smoke'
$acceptanceProcess = Start-Process -FilePath $acceptanceApp -ArgumentList $acceptanceArgs -Wait -PassThru -RedirectStandardOutput ($acceptanceOut + '.stdout.log') -RedirectStandardError ($acceptanceOut + '.stderr.log')
$acceptanceProcess.ExitCode
```

Use `physical` when testing a physical machine, `hosted` for CI/hosted environments, or leave the environment unspecified. This is a caller declaration, recorded separately from observed OS/JVM/GPU metadata. It does not establish hardware acceptance. All result destinations must be new; an existing directory or file is rejected before checks start.

The independent CLI form is:

```text
ProceduralVisualEngine.exe --acceptance-run NEW_DIRECTORY
  [--acceptance-profile smoke|quick|standard|fullhd]
  [--acceptance-environment unspecified|physical|hosted]
```

Scene, preset, shader, timing and quality overrides are rejected. The runner uses bundled shader resources and fixed workloads, and does not load or change desktop preferences. It creates invisible graphics contexts; the normal desktop UI is tested separately. Standard application diagnostic logs still rotate in the application data directory.

| Exit | Meaning |
| --- | --- |
| 0 | Installation, GPU baseline and all seven workloads completed consistently; manual review is still required |
| 2 | GPU support could not be established; the failed probe is retained and workloads are not run |
| 1 | Installation/workload/evidence failure or invalid arguments; inspect saved evidence and logs |

Handled check failures publish a finished failed/blocked evidence bundle where file publication succeeds. A failed bundle is useful diagnostic evidence. Abrupt termination or publication failure may leave a private `.acceptance-pending-*` directory rather than a completed result. Retain it for diagnosis; it is not a finished run.

## Evidence and identity

| File | Contents |
| --- | --- |
| `results.json` | Versioned outcome, step status/timing/failures, environment declaration, observed runtime, archive fingerprints and immutable evidence hashes |
| `gpu.json` | Actual GPU probe, including queried device/driver/limits or a truthful unavailable report |
| `benchmark/` | Seven case reports, raw timing samples, workload contracts, images, input replays and metadata, when the GPU check succeeds |
| `manual-review.md` | Unchecked desktop, artwork, endurance and release-decision form for the operator |

Archive identity covers the application JAR and sibling dependency/native JARs, with an ordered aggregate SHA-256 fingerprint. JVM/runtime versions are recorded separately. Development class-directory launches are marked `classes` and have no packaged archive fingerprint. Installed paths, account names and desktop preferences are not deliberately collected. Diagnostic failure messages can contain paths relevant to the failure.

`immutableEvidence` hashes the machine-produced files, excluding the result manifest itself and the editable manual-review form. Keep these files unchanged. Add observations and new evidence references to the manual form. The immutable report continues to say `manualReview: not_run` and `hardwareAcceptance: not_established`; record the human review and release decision in the manual form. Nothing is uploaded automatically.

The runner also checks that the benchmark used the same capability snapshot, current engine/algorithm, chosen profile, all seven expected workload hashes, exact dimensions/ticks and matching case/image evidence. A changed/missing case cannot become an automated pass.

## 1080p measurements

After the smoke run succeeds, repeat it into another new directory using `--acceptance-profile fullhd`. The fixed fullhd profile uses 1920×1080 output, a 257×257 simulation grid, 16,384 particles, 16 history layers, 60 warmup frames and 180 measured frames per case. Two 120 Hz simulation ticks advance per frame. It can be much slower on software rendering or weak hardware.

Review raw samples, query coverage, workload hashes and image/state results. Repeat runs without competing workloads and record CPU/GPU, driver, power mode, cooling and variance. Compare the observed scopes against the proposed 1080p/60 fps target while accounting for two ticks per frame. CPU frame time includes driver waits and instrumentation; render/tick percentile values cannot be added into a frame percentile. Smoke is functional evidence, and no profile automatically certifies interactive FPS or minimum hardware requirements.

## Manual acceptance

Open the normal desktop application after automated checks. Complete `manual-review.md` and attach evidence for:

- A clean Windows account without Java, exact Windows version/build from `winver`, physical CPU/GPU and driver.
- DPI, scaling, multiple monitors, fullscreen restoration, focus-safe painting, minimize/restore and paused edits.
- All sixteen recipes, useful parameter extremes, graph/group/preview workflows, modulation and undo.
- Repeated quality/preset changes, memory growth, long sessions, nonfinite artifacts and recovery from invalid edits.
- Still/sequence/map orientation, color, dimensions, metadata, replay and checkpoint behavior.
- Practical fullhd performance, tested system combinations, outstanding issues and the human release decision.

For a shader-reload failure test, copy the bundled `acceptance-shaders` directory elsewhere, launch with `--shaders COPY_DIRECTORY`, and edit that copy. Record the edits and observed recovery. The normal acceptance command always uses bundled shader resources.

Physical NVIDIA, AMD and Intel evidence remains separate from Linux software-renderer and hosted Windows packaging results. Do not mark unavailable scenarios as passed. Record what was exercised, failures and missing coverage before deciding supported Windows/driver combinations or release requirements.
