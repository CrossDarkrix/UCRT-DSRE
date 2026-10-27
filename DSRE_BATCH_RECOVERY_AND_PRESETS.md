# DSRE Batch Recovery and Preset Transfer

## Batch recovery

- Foreground-service startup failures roll coordinator state back to idle.
- Duplicate ACTION_START delivery cannot start a second conversion coroutine.
- A CoroutineExceptionHandler performs final state recovery for uncaught root-coroutine failures.
- Each track owns a fresh DsreFileConverter.
- Converter cancel and release operations are individually failure-isolated.
- Failed partial output documents are deleted.
- Notification failures do not terminate the batch.
- Cancellation remains distinct from conversion failure.
- A failed track remains marked FAILED and processing continues to the next track.

## Preset transfer

- Export uses the Storage Access Framework and writes UCRT-DSRE-Presets.json.
- Import uses the Storage Access Framework and accepts JSON or plain-text JSON providers.
- Schema: ucrt-dsre-presets, version 1.
- Existing names are updated case-insensitively, new names are added, and unrelated local presets remain.
- Invalid entries are skipped and reported. If no valid entry exists, local presets remain unchanged.
- Numeric values, sample rates, output formats, and preset names are validated before persistence.
