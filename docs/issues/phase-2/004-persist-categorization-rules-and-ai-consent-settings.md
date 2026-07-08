# Persist Categorization Rules And AI Consent Settings

## What to build

Make category corrections, saved categorization rules, AI categorization consent, enhanced AI context preference, and continuous-monitoring setting durable so future captures and review decisions use the same user choices after restart.

## Acceptance criteria

- [x] Saving a category correction as a categorization rule persists it and applies it to later matching pending entries.
- [x] Rule list, rule form, priority/matching behavior, and deletion/editing survive restart.
- [x] AI categorization consent and enhanced AI context preference persist locally and remain reflected in the profile/permission center UI.
- [x] Continuous monitoring enabled/disabled state persists but does not start monitoring unless the service boundary permits it.
- [x] Tests cover rule persistence, rule matching after restart, and settings persistence.

## Blocked by

- Issue 1: Baseline Audit And Phase 2 Risk Map

## Verification

- `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest`
- `.\gradlew.bat --no-daemon :apps:android:assembleDebug`
