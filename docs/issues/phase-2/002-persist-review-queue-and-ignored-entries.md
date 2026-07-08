# Persist Review Queue And Ignored Entries

## What to build

Make captured pending entries, review queue state, ignored entries, undo-sensitive transitions, and recovery behavior durable across app restart while preserving the rule that captured transactions never enter the ledger without confirmation.

## Acceptance criteria

- [ ] A pending entry created from an app capture path remains visible in the review queue after Activity recreation and process restart.
- [ ] Confirm, ignore, undo, and ignored-entry recovery operate through the repository/persistence layer rather than only Compose state.
- [ ] Ignored entries retain recovery metadata and do not appear as ledger entries.
- [ ] Review queue grouping, confidence state, capture reason, duplicate suspect state, and evidence display survive restart.
- [ ] Repository and UI/integration tests cover pending-to-ignored and ignored-to-review recovery.

## Blocked by

- Issue 1: Baseline Audit And Phase 2 Risk Map
