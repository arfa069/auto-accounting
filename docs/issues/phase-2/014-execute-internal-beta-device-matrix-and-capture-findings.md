# Execute Internal Beta Device Matrix And Capture Findings

## What to build

Execute the packaged internal beta against the planned Android device matrix, record real-device findings, and turn observed regressions, ROM-specific blockers, and rollout risks into explicit follow-up work.

## Scope

Run the manual beta checklist from the existing release package on a controlled set of Android 10-15 devices and major domestic ROM families. Capture outcome, evidence, blockers, and recommended next actions for notification capture, bill sync, continuous monitoring, AI categorization, backup/restore, deletion, and permission retention.

## Non-goals

- Do not redesign features or expand Phase 2 scope during this issue.
- Do not silently fix discovered bugs inside the validation pass; log them into follow-up issues unless a separate implementation issue is explicitly opened.
- Do not treat missing signing materials, missing devices, or ROM restrictions as solved unless evidence is recorded.

## Target files/modules

- `docs/INTERNAL-BETA-RELEASE.md`
- `docs/issues/phase-2`
- Optional evidence artifacts under `docs/` if screenshots, pass/fail logs, or ROM notes need a stable home

## Acceptance criteria

- [ ] The device matrix run is recorded with at least one outcome or explicit blocker for each planned Android version / ROM family in scope.
- [ ] Manual beta flows for account, local mode, notification capture, bill sync, continuous monitoring, review queue, ledger, reports, AI, backup/restore, local deletion, account deletion, and compliance are marked pass, fail, or blocked with notes.
- [ ] Capture accuracy, deduplication accuracy, review efficiency, and permission retention observations are summarized with enough detail to support a go / no-go beta decision.
- [ ] Every material bug, regression, or rollout blocker found during the run is linked to a follow-up issue or documented as an explicit known risk.
- [ ] Remaining signing, distribution, and tester-onboarding blockers are documented clearly enough for the next operator to continue without rediscovery.

## Acceptance tests

- [ ] `.\gradlew.bat --no-daemon :services:backend:test`
- [ ] `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest`
- [ ] `.\gradlew.bat --no-daemon :apps:android:assembleRelease`

## Device verification

- [ ] Run the release artifact on the target Android 10-15 device matrix and record install / launch outcome.
- [ ] Verify notification listener permission, accessibility permission, and continuous monitoring enable/disable behavior on each available ROM family.
- [ ] Complete one WeChat and one Alipay payment capture flow per available device and record capture accuracy, duplicate behavior, and review ergonomics.
- [ ] Exercise backup/restore, local data deletion, and account deletion flows where the backend and tester setup allow it.
- [ ] Record screenshots, notes, or blocker reasons for any failed or skipped device scenario instead of leaving blanks.

## Rollback or safety notes

- This issue is primarily validation and documentation work. Keep code changes, if any, limited to evidence capture or clearly separated follow-up fixes so the beta-readiness record stays trustworthy.
- Do not distribute builds beyond the controlled tester set described in the release package until the recorded blockers are reviewed.

## Blocked by

- Issue 12: Package Internal Beta QA Metrics And Release Build
- Issue 13: Correct Tester-Facing Android Copy Encoding
