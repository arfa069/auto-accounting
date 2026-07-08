# Persist Ledger Reports And Local Data Deletion

## What to build

Make the confirm-to-ledger path durable end to end: a reviewed pending entry becomes a ledger entry, appears in ledger search/filter and reports after restart, and is removed by the separate local data deletion flow.

## Acceptance criteria

- [ ] Confirming a pending entry writes a durable ledger entry and removes the resolved pending entry from the review queue.
- [ ] Ledger monthly summary, search/filter, category ranking, and trend data are derived from persisted ledger data.
- [ ] Reports continue to show the same confirmed ledger data after app restart.
- [ ] Local data deletion clears ledger entries, pending entries, ignored entries, settings covered by this issue, and related local metadata.
- [ ] Tests cover confirm-to-ledger, report aggregate persistence, restart/recreate behavior, and local data deletion.

## Blocked by

- Issue 1: Baseline Audit And Phase 2 Risk Map
