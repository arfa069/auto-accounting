# Wire User Started Bill Sync Permission And Service Path

## What to build

Make manual bill sync a real user-started Android flow: accessibility permission detection, settings deep-link, sync session state, bill-page parsing, deduplication, progress display, and pending-entry creation.

## Acceptance criteria

- [x] Permission center reflects real accessibility service state and provides a settings deep-link.
- [x] Starting bill sync requires explicit user action and shows sync steps from launch through completion/failure.
- [x] WeChat/Alipay bill-page parsing creates pending entries or duplicate candidates through the capture pipeline.
- [x] Sync cancellation/failure leaves the review queue and ledger in a coherent state.
- [x] Tests cover permission-state mapping, parser/session state, dedupe handoff, and progress/failure rendering.

## Blocked by

- Issue 1: Baseline Audit And Phase 2 Risk Map

## Verification

- `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest`
- `.\gradlew.bat --no-daemon :apps:android:assembleDebug`

## Device verification

Status: Not run on 2026-07-09 because no Android device was connected through ADB.

- [ ] Enable the bill-sync accessibility service from the profile permission center.
- [ ] Start WeChat sync and confirm the app opens only after explicit source selection.
- [ ] Start Alipay sync and confirm visible bill rows create pending entries.
- [ ] Cancel an active session and confirm later page events are ignored.
- [ ] Open a non-bill page and confirm failure progress without queue or ledger changes.
