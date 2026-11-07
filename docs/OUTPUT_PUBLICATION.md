# Artwork and map file publication

Version 0.16 saves an artwork PNG and its JSON sidecar through the same paired-output writer used by texture maps. This covers viewport captures, independent stills and each sequence frame. Discovery artwork also uses the writer inside its existing staged bundle.

Discovery and benchmark operations can discard their whole unpublished bundle on failure. Existing published bundles remain intact; recovery files inside a discarded unpublished bundle are not retained separately.

Before publishing, the writer validates both destinations, encodes the image, stages UTF-8 metadata and copies any existing files into a private directory alongside the destination. It then publishes the PNG and sidecar. Capture and map metadata are limited to 1 MiB and validated before publication. A destination that is a directory, symbolic link or other non-regular file is rejected without replacing it or following the link.

If encoding, validation or backup creation fails, neither destination is published. If a publication rename throws, the writer restores attempted destinations from their previous copies, or removes a newly created destination when it was previously absent. The save still reports the original failure. Successful saves and successful rollbacks remove their private staging directory.

## Recovery and limits

If a filesystem error also prevents restoration, the error includes the retained `.visual-output-…` directory. It contains `previous-image` and `previous-metadata` copies for files that existed before the save, plus `RECOVERY.txt` identifying their destinations. Recovery copies remain intact even if a restore rename fails. Restoration of the other destination is still attempted. Keep this directory until the files have been inspected and recovered manually; the application never replays it automatically.

A cleanup error after both files were saved is logged with the directory location. It does not roll back the completed save. Replacing existing files temporarily needs space for staged output and copies of the old files; the writer copies old bytes without promising to preserve all filesystem attributes.

Two paths cannot be replaced as one atomic filesystem operation. Other readers can briefly observe one new file and one old file between renames. These guarantees concern handled encoding and filesystem exceptions, not process termination, power loss, a failed disk, or concurrent external writers changing the destinations. No durable crash journal or automatic crash recovery is claimed. Texture maps continue to embed the complete authoritative JSON in their PNG; their sidecar contains the same document.

File work runs on the existing bounded worker. Offline exports apply backpressure and fail if a requested write fails; `sequence.json` is published only after successful frame output. Completed earlier frames remain. GPU readback, color encoding, map channels, settings and replay behavior retain their existing contracts. Preset schema 4, Effect API 2 and replay format 1 remain unchanged; checkpoints retain exact engine-version compatibility.

## Verification

Filesystem checks exercise existing and absent destinations, failures immediately before and after either publication rename, encoding failure, blocked destinations, symbolic links when supported, successful rollback and retained backups when rollback itself fails. Failures are injected at the move boundary while actual staging, copying, restoration and cleanup use the filesystem; exhausting a real disk is not required.

`python3 scripts/verify-output-pairs.py` exercises installed artwork/map overwrites and occupied PNG/sidecar destinations on Unicode/spaced paths. Successful overwrites match independent saves exactly, including metadata and checkpoint GPU bytes, clocks and replay position. Failed saves preserve existing output and occupied directories, and leave no normal staging directory behind.
