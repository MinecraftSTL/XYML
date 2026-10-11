# Repository draft API

## Ownership and boundaries

`GameRepositorySnapshot` contains detached manifest JSON values; decoded records do not alias the captured payloads. Unknown JSON fields are retained by the local adapter.

`DefaultGameRepository.runDraftAsync` constructs a stopped task. A draft is opened only during execution, and terminal cleanup uses the existing Throwable-aware task continuation. Callers still declare the precise resources occupied by their preparation tasks.

A draft stages explicit manifest and regular-file writes inside `.xyml/repository-drafts`. Commit checks manifest dependencies and target identities, rebases metadata over the latest catalog, applies owner participants, and publishes one snapshot. Exceptions and errors restore attempted participants and files in reverse order. Interrupted cleanup retains the original cause and interruption status.

Private backup files retain their original relative names. If restoration fails, the owning repository quarantines the affected instance and keeps recovery copies. No cross-restart journal or automatic recovery exists. Cleanup failure after successful publication is exposed separately by `getCleanupFailure()`.

## Supported operations

- `put` stages a locally serialized manifest; it does not claim an existing unregistered directory.
- `stageFile` returns an owned output path. Its producer must finish and close its output before root commit.
- `putPrimaryJar` copies a completed source into owned staging and verifies that the source did not change.
- `removeFile` stages a regular-file deletion.
- `remove` removes only an index entry; its owner must explicitly stage the corresponding filesystem operation.
- `enlist` joins owner-managed state through validation, application and rollback callbacks outside the repository monitor.

Only open drafts accept mutation or commit. Commit is single-use. Closing an open draft discards it; repeated terminal closes do not delete retained recovery backups.

## Existing entrypoints

The draft API does not implicitly enlist existing save, instance rename/delete, modpack, add-on, GUI repair or MCP repair tasks. Those entrypoints retain their own existing behavior. A caller must not claim whole-operation rollback merely by opening a draft around a task that still writes directly to committed paths.

Owner-managed Java selection and repair checkpoints are not automatically captured by the draft API. No new rollback guarantee applies to the existing crash repair coordinators.

Persistent instance/settings formats and the local `modpack.json` layout are unchanged. Shared asset, library and runtime caches are outside the rollback boundary.
