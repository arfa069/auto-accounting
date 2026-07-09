# ADR 0048: Save Backup to Downloads Directory

## Status

Accepted

## Context

The encrypted backup feature (ADR 0013) previously held backup content only in memory as a Base64 string. Users could not persist the backup to a file visible in the file manager, making real backup/restore impossible for testers. During internal beta validation (Issue 14), this was identified as a critical UX blocker.

## Decision

Export encrypted backups directly to the public Downloads directory using the `MediaStore.Downloads` API (available since Android Q / API 29, which matches our `minSdk`). No additional runtime permission is required for this path. File names follow the format `yyyy-MM-dd-HH-mm-ac-backup.bak`. Import uses SAF `OpenDocument` to let the user pick any `.bak` file. Both export and import outcomes are communicated via `Snackbar`.

## Consequences

- Users can find their backup files in the Downloads folder of any file manager.
- Import via SAF means users can load backups from cloud storage, USB drives, or any storage provider.
- No additional permissions needed (MediaStore Downloads is available without `WRITE_EXTERNAL_STORAGE` on API 29+).
