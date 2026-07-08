# Persist Ledger Reports And Local Data Deletion

## What to build

Make the confirm-to-ledger path durable end to end: a reviewed pending entry becomes a ledger entry, appears in ledger search/filter and reports after restart, and is removed by the separate local data deletion flow.

## Acceptance criteria

- [x] Confirming a pending entry writes a durable ledger entry and removes the resolved pending entry from the review queue.
- [x] Ledger monthly summary, search/filter, category ranking, and trend data are derived from persisted ledger data.
- [x] Reports continue to show the same confirmed ledger data after app restart.
- [x] Local data deletion clears ledger entries, pending entries, ignored entries, settings covered by this issue, and related local metadata.
- [x] Tests cover confirm-to-ledger, report aggregate persistence, restart/recreate behavior, and local data deletion.

## Verification record

- `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests com.autoaccounting.data.local.LocalLedgerRepositoryTest --tests com.autoaccounting.feature.ledger.LedgerModelsTest` - passed.
- `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest` - passed.
- `.\gradlew.bat --no-daemon :apps:android:assembleDebug` - passed.

## Blocked by

- Issue 1: Baseline Audit And Phase 2 Risk Map
