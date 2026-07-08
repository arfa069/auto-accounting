# Correct Tester-Facing Android Copy Encoding

## What to build

Correct mojibake tester-facing Android string literals in Kotlin source and resources so account, review queue, ledger, reports, permissions, backup, deletion, AI, compliance, and beta-readiness copy is readable before any internal tester build.

## Acceptance criteria

- [ ] User-facing Android copy renders as intended Chinese text in the main app flows.
- [ ] Account, permission, local data deletion, account deletion, AI consent, backup/export, and compliance risk copy remains plain and clear.
- [ ] Any shared repeated copy is moved to resources or local helpers where that reduces future encoding drift.
- [ ] Unit or UI tests that assert key copy are updated to the corrected strings.
- [ ] Android build passes after copy correction.

## Blocked by

- Issue 1: Baseline Audit And Phase 2 Risk Map
