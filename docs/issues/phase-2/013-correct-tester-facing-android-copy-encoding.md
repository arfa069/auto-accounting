# Correct Tester-Facing Android Copy Encoding

## What to build

Correct mojibake tester-facing Android string literals in Kotlin source and resources so account, review queue, ledger, reports, permissions, backup, deletion, AI, compliance, and beta-readiness copy is readable before any internal tester build.

## Scope

Correct tester-facing Android copy in Kotlin source, Android resources, and tests that assert visible strings. Keep the work limited to copy readability and encoding cleanup.

## Non-goals

- Do not redesign screens, navigation, or visual style.
- Do not change account, capture, backup, deletion, AI, or monitoring behavior except where tests need updated expected copy.
- Do not translate backend-only test fixtures unless they surface in Android tester-facing UI.

## Target files/modules

- `apps/android/src/main/java/com/autoaccounting`
- `apps/android/src/main/res`
- `apps/android/src/test/java/com/autoaccounting`

## Acceptance criteria

- [ ] User-facing Android copy renders as intended Chinese text in the main app flows.
- [ ] Account, permission, local data deletion, account deletion, AI consent, backup/export, and compliance risk copy remains plain and clear.
- [ ] Any shared repeated copy is moved to resources or local helpers where that reduces future encoding drift.
- [ ] Unit or UI tests that assert key copy are updated to the corrected strings.
- [ ] Android build passes after copy correction.

## Acceptance tests

- [ ] `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest`
- [ ] `.\gradlew.bat --no-daemon :apps:android:assembleDebug`

## Manual verification

- Open the Android app and inspect account, review queue, ledger, reports, profile/permission center, backup/export, deletion, AI consent, compliance, and beta-readiness screens for readable Chinese copy.

## Rollback or safety notes

- Copy-only changes should be easy to revert. Keep behavior changes out of this issue so rollback does not affect product state or persistence.

## Blocked by

- Issue 1: Baseline Audit And Phase 2 Risk Map
