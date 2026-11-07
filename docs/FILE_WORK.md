# File work, export failures and shutdown

Version 0.17 gives the engine's single file worker an explicit lifecycle. It still allows **two queued jobs plus one running job**. Encoding, paired publication, checkpoint archives and file loads use this worker; GL calls remain on the context thread.

| Session | Full queue | A file job throws |
| --- | --- | --- |
| Desktop | Reject the new request with a status message | Report an ordinary exception and keep the worker available for later requests |
| Offline / image sequence | Wait for room without exceeding queue capacity | Keep the first failure, reject new work and cancel jobs that have not started |

Fatal worker errors stop either mode. A blocked producer wakes when room becomes available, when draining starts or when a terminal failure occurs. Interruption preserves the producer thread's interrupt flag and leaves its unaccepted job out of the queue. Blocking submission and shutdown from the file worker itself are rejected to prevent self-deadlock.

The render loop observes terminal file failures before starting another frame. A sequence's completion manifest is queued after its frames; it cannot run after an earlier file failure. Previously completed output remains. Later queued captures, checkpoints and benchmark output are cancelled without being written. A failure in the final asynchronous write also fails process shutdown, even if rendering already ended.

## Closing the application

| State | New jobs | Existing work |
| --- | --- | --- |
| `OPEN` | Accepted while capacity permits | Processed in FIFO order |
| `DRAINING` | Rejected; blocked producers wake | Accepted jobs finish in order |
| `CANCELLING` | Rejected | Queued jobs are discarded; an active operation can still be finishing |
| `TERMINATED` | Rejected | No active or queued work |

Normal shutdown waits up to 30 seconds for accepted work to drain. Timeout or interruption cancels queued jobs, interrupts the active operation and reports shutdown failure. It does not report successful completion after discarding writes. UI, renderer and window cleanup are attempted even if file shutdown fails.

An operation blocked in native filesystem work may ignore interruption. The file worker is a daemon so that it cannot keep the JVM alive by itself after a failed shutdown. This does not guarantee that an active write finishes before process exit. Paired publication retains its documented [exception rollback and crash limits](OUTPUT_PUBLICATION.md); there is no durable queue or automatic replay of unfinished jobs. Shutdown does not make power loss or forced process termination safe.

The queue bounds pending jobs, not total process memory. The render thread may hold a captured image while waiting to submit it. GPU readback is still synchronous; this milestone does not introduce asynchronous GPU transfers or a rendering speedup.

## Diagnostics

Saved session GPU reports and single-scene benchmark reports include a `fileQueue` snapshot:

| Field | Meaning |
| --- | --- |
| `state`, `capacity`, `queued`, `running` | Current lifecycle, pending-job bound, queue length and active-job flag |
| `accepted` | Jobs accepted since this engine session began |
| `completed`, `failed` | Jobs that returned successfully or threw |
| `cancelled` | Accepted jobs discarded before they started |
| `stopOnFailure` | Whether ordinary job errors stop later work, as in offline mode |

At a snapshot, `accepted = completed + failed + cancelled + queued + (running ? 1 : 0)`. Rejected or interrupted submissions are not counted as accepted. A job remains running while its failure callback finishes, so the counters describe completed handling rather than just the return from the write function.

The report snapshots the queue before submitting its own save. Other file work may still be pending at that instant; successful application exit establishes that accepted work was drained. Standalone `--diagnose-gpu` and the isolated benchmark suite do not create this engine worker and have no `fileQueue` field. Queue state is diagnostic only and is absent from presets, effects, replay and checkpoint state. Existing creative formats are unchanged; checkpoints keep exact engine-version compatibility.

## Verification

JVM checks use latches to hold active work at explicit boundaries. They verify FIFO order, queue bounds, fail-fast cancellation, producer wakeup during shutdown and failure, continuing after ordinary desktop errors, interrupt flags, timeout reporting, active cleanup after cancellation, callback failures and fatal worker errors. They do not depend on exhausting disk space or waiting for the production shutdown deadline.

`python3 scripts/verify-file-jobs.py` compares two installed 32-frame replay exports and their checkpoints, validates live queue accounting, checks that the first failed frame preserves later destinations and an existing completion manifest, and verifies a final asynchronous failure during shutdown. Physical Windows filesystem pressure and desktop close-event acceptance remain part of platform validation.
