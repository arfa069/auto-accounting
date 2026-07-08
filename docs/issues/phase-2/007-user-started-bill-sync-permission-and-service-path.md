# Wire User Started Bill Sync Permission And Service Path

## What to build

Make manual bill sync a real user-started Android flow: accessibility permission detection, settings deep-link, sync session state, bill-page parsing, deduplication, progress display, and pending-entry creation.

## Acceptance criteria

- [ ] Permission center reflects real accessibility service state and provides a settings deep-link.
- [ ] Starting bill sync requires explicit user action and shows sync steps from launch through completion/failure.
- [ ] WeChat/Alipay bill-page parsing creates pending entries or duplicate candidates through the capture pipeline.
- [ ] Sync cancellation/failure leaves the review queue and ledger in a coherent state.
- [ ] Tests cover permission-state mapping, parser/session state, dedupe handoff, and progress/failure rendering.

## Blocked by

- Issue 1: Baseline Audit And Phase 2 Risk Map
