# Persist Review Queue And Ignored Entries

## What to build

Make captured pending entries, review queue state, ignored entries, undo-sensitive transitions, and recovery behavior durable across app restart while preserving the rule that captured transactions never enter the ledger without confirmation.

## Acceptance criteria

- [x] A pending entry created from an app capture path remains visible in the review queue after Activity recreation and process restart.
- [x] Confirm, ignore, undo, and ignored-entry recovery operate through the repository/persistence layer rather than only Compose state.
- [x] Ignored entries retain recovery metadata and do not appear as ledger entries.
- [x] Review queue grouping, confidence state, capture reason, duplicate suspect state, and evidence display survive restart.
- [x] Repository and UI/integration tests cover pending-to-ignored and ignored-to-review recovery.

## Verification record

- `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests com.autoaccounting.feature.review.ReviewQueuePersistenceTest --tests com.autoaccounting.data.local.LocalLedgerRepositoryTest` - passed, including 1-to-2 Room migration validation.
- `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest` - passed.
- `.\gradlew.bat --no-daemon :apps:android:assembleDebug` - passed.
- Manual device process-kill/restart smoke inspection is still pending; automated coverage verifies Room reopen/migration behavior plus the repository-backed review-queue persistence seam.

## Blocked by

- Issue 1: Baseline Audit And Phase 2 Risk Map
