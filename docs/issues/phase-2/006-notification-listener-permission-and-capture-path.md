# Wire Real Notification Listener Permission And Capture Path

## What to build

Connect notification listener permission state, settings deep-link, payment notification parsing, deduplication, and pending-entry creation into one device-testable path for WeChat and Alipay payment notifications.

## Acceptance criteria

- [x] Permission center reflects real Android notification listener access state and provides a settings deep-link.
- [x] Notification listener ignores unrelated apps/content and only processes payment-source payment notifications.
- [x] Parsed WeChat/Alipay payment notifications create or merge pending entries through the same capture pipeline used by the review queue.
- [x] Capture reason, confidence state, source evidence, and duplicate handling are visible in review.
- [x] Tests cover permission-state mapping, parser behavior, unrelated notification rejection, and pending-entry creation.

## Blocked by

- Issue 1: Baseline Audit And Phase 2 Risk Map

## Verification

- `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest`
- `.\gradlew.bat --no-daemon :apps:android:assembleDebug`

## Device verification

Status: Not run on 2026-07-09 because no Android device was connected through ADB.

- [ ] Enable notification access from the profile permission center and confirm the status updates after returning.
- [ ] Complete one WeChat and one Alipay test payment and confirm pending entries contain source evidence.
- [ ] Receive an unrelated notification and confirm no pending entry is created.
- [ ] Repeat a matching payment notification and confirm duplicate merge/review behavior.
