# Phase 2 Issues

Source plan: [Phase 2 Slices](./PHASE-2-SLICES.md).

These issues are written as independently grabbable tracer bullets. Each issue should leave the app in a coherent, testable state and avoid broad unrelated refactors.

## Proposed Breakdown

1. Baseline audit and Phase 2 risk map
   - Blocked by: None
   - User stories covered: internal beta readiness, maintainability, truthful docs
2. Persist review queue and ignored entries
   - Blocked by: 1
   - User stories covered: capture payment activity, review pending entries, recover ignored entries
3. Persist ledger, reports, and local data deletion
   - Blocked by: 1
   - User stories covered: review pending entries, view ledger and reports, delete local data
4. Persist categorization rules and AI consent/settings
   - Blocked by: 1
   - User stories covered: build categorization rules, manage AI categorization consent
5. Make encrypted backup restore real persisted app data
   - Blocked by: 2, 3, 4
   - User stories covered: export CSV, create encrypted backups, restore app data
6. Wire real notification listener permission and capture path
   - Blocked by: 1
   - User stories covered: capture WeChat/Alipay payment notifications, manage permissions
7. Wire user-started bill sync permission and service path
   - Blocked by: 1
   - User stories covered: manually sync WeChat/Alipay bills, review captured pending entries
8. Wire continuous monitoring service boundary and guardrails
   - Blocked by: 6, 7
   - User stories covered: opt into continuous monitoring, disable monitoring at any time
9. Persist backend auth, SMS, and registered devices
   - Blocked by: 1
   - User stories covered: register, log in, recover account, register device
10. Persist backend cloud configuration and AI proxy state
    - Blocked by: 9
    - User stories covered: manage cloud AI consent, request AI categorization safely
11. Persist account deletion and scheduled cloud cleanup
    - Blocked by: 9, 10
    - User stories covered: request account deletion, cancel during cooling-off, delete cloud account data
12. Package internal beta QA, metrics, and release build
    - Blocked by: 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 13
    - User stories covered: internal beta validation, capture quality measurement, release readiness
13. Correct tester-facing Android copy encoding
    - Blocked by: 1
    - User stories covered: internal beta readability, compliance clarity, account and permission comprehension
14. Execute internal beta device matrix and capture findings
    - Blocked by: 12, 13
    - User stories covered: internal beta validation, device-matrix evidence, release go/no-go
15. Persist local-mode session across app restarts
    - Blocked by: 14
    - User stories covered: local-only onboarding, restart resilience
16. Capture WeChat/Alipay payment results automatically and report bookkeeping status
    - Builds on: 6, 8
    - User stories covered: capture supported payment-result screens without manual bill sync, apply category suggestions, deduplicate sources, and create pending entries

## Issue 1: Baseline Audit And Phase 2 Risk Map

## What to build

Review the completed Phase 1 skeleton against the PRD, architecture, glossary, and Phase 2 plan. Produce a clean baseline for agent work by isolating non-production shortcuts, documenting current limitations, and filing follow-up risks without changing unrelated behavior.

## Acceptance criteria

- [ ] Current implementation is reviewed against Slice 0-15, PRD, architecture, and glossary terminology.
- [ ] Mock gateways, demo passphrases, in-memory shortcuts, and non-production seams are listed with owner area and follow-up recommendation.
- [ ] README/docs accurately describe what is real, what is mocked, and what is still local-only.
- [ ] Duplicate or fragile UI/state paths are identified without broad refactoring.
- [ ] Full test/build command set is run, or every skipped command is documented with reason.

## Blocked by

None - can start immediately.

## Issue 2: Persist Review Queue And Ignored Entries

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

## Issue 3: Persist Ledger, Reports, And Local Data Deletion

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

## Issue 4: Persist Categorization Rules And AI Consent Settings

## What to build

Make category corrections, saved categorization rules, AI categorization consent, enhanced AI context preference, and continuous-monitoring setting durable so future captures and review decisions use the same user choices after restart.

## Acceptance criteria

- [ ] Saving a category correction as a categorization rule persists it and applies it to later matching pending entries.
- [ ] Rule list, rule form, priority/matching behavior, and deletion/editing survive restart.
- [ ] AI categorization consent and enhanced AI context preference persist locally and remain reflected in the profile/permission center UI.
- [ ] Continuous monitoring enabled/disabled state persists but does not start monitoring unless the service boundary permits it.
- [ ] Tests cover rule persistence, rule matching after restart, and settings persistence.

## Blocked by

- Issue 1: Baseline Audit And Phase 2 Risk Map

## Issue 5: Make Encrypted Backup Restore Real Persisted App Data

## What to build

Change encrypted backup export/import so it operates on the real persisted app state instead of transient UI state, covering ledger, pending entries, ignored entries, categorization rules, funding accounts, and relevant local settings.

## Acceptance criteria

- [ ] Encrypted backup export reads from persisted app data and contains the expected local-first bookkeeping state.
- [ ] Backup import restores data into the repository/database layer and updates review queue, ledger, reports, rules, and settings.
- [ ] Backup reminder remains separate from local data deletion confirmation.
- [ ] CSV export remains a plain ledger export and is not confused with encrypted backup.
- [ ] Tests cover backup round-trip, wrong passphrase failure, and restored UI/repository state.

## Blocked by

- Issue 2: Persist Review Queue And Ignored Entries
- Issue 3: Persist Ledger, Reports, And Local Data Deletion
- Issue 4: Persist Categorization Rules And AI Consent Settings

## Issue 6: Wire Real Notification Listener Permission And Capture Path

## What to build

Connect notification listener permission state, settings deep-link, payment notification parsing, deduplication, and pending-entry creation into one device-testable path for WeChat and Alipay payment notifications.

## Acceptance criteria

- [ ] Permission center reflects real Android notification listener access state and provides a settings deep-link.
- [ ] Notification listener ignores unrelated apps/content and only processes payment-source payment notifications.
- [ ] Parsed WeChat/Alipay payment notifications create or merge pending entries through the same capture pipeline used by the review queue.
- [ ] Capture reason, confidence state, source evidence, and duplicate handling are visible in review.
- [ ] Tests cover permission-state mapping, parser behavior, unrelated notification rejection, and pending-entry creation.

## Blocked by

- Issue 1: Baseline Audit And Phase 2 Risk Map

## Issue 7: Wire User-Started Bill Sync Permission And Service Path

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

## Issue 8: Wire Continuous Monitoring Service Boundary And Guardrails

## What to build

Make continuous monitoring an advanced opt-in Android service path with clear start/stop controls, persisted enabled state, permission health, and strict payment-surface guardrails.

## Acceptance criteria

- [ ] Continuous monitoring can only start after required permission states are healthy and the user explicitly enables it.
- [ ] User can disable continuous monitoring at any time, and the service stops cleanly.
- [ ] Monitoring observes only payment-related WeChat/Alipay surfaces and never chat, messages, payment initiation, or transfers.
- [ ] Background keep-alive/auto-start guidance is visible without pretending to guarantee ROM behavior.
- [ ] Tests cover service state transitions, guardrail filtering, permission health mapping, and disabled-state behavior.

## Blocked by

- Issue 6: Wire Real Notification Listener Permission And Capture Path
- Issue 7: Wire User-Started Bill Sync Permission And Service Path

## Issue 9: Persist Backend Auth, SMS, And Registered Devices

## What to build

Move backend account registration, login, SMS verification, password recovery, token verification, and registered device state onto durable PostgreSQL-backed storage with safe provider boundaries.

## Acceptance criteria

- [ ] Users, password credentials, SMS verification codes/limits, sessions/tokens, and registered devices are persisted through migrations.
- [ ] Registration, login, account recovery, and token-protected routes work after backend restart.
- [ ] SMS sending uses an environment-configured provider seam and fails safely when provider configuration is missing.
- [ ] Login failure, SMS expiry, retry limits, and lockout behavior match the PRD without leaking whether a phone number exists.
- [ ] Backend integration tests cover database persistence, auth flows, SMS limits, and token verification.

## Blocked by

- Issue 1: Baseline Audit And Phase 2 Risk Map

## Issue 10: Persist Backend Cloud Configuration And AI Proxy State

## What to build

Make cloud configuration and AI categorization provider integration durable and provider-ready while preserving the product boundary that the backend does not store the user's full ledger.

## Acceptance criteria

- [x] Cloud configuration persists AI consent, enhanced AI context preference, feature flags, and device/account settings needed by the app.
- [x] AI categorization requests are routed through an environment-configured backend provider seam with safe missing-config behavior.
- [x] AI categorization logs are persisted for internal beta without storing full local ledger data.
- [x] Android/backend contract tests cover consent/config reads and AI categorization request/response payloads.
- [x] Secret scanner or equivalent check confirms provider keys are not committed or shipped in client code.

## Blocked by

- Issue 9: Persist Backend Auth, SMS, And Registered Devices

## Issue 11: Persist Account Deletion And Scheduled Cloud Cleanup

## What to build

Make account deletion durable end to end: request deletion, enter deletion pending state, pause cloud writes, allow cancellation during the cooling-off period, and execute scheduled deletion of cloud account data.

## Acceptance criteria

- [x] Account deletion requests persist with cooling-off deadline and deletion pending state.
- [x] Users can log in and cancel deletion during the cooling-off period.
- [x] Cloud AI and device/config writes are paused while deletion is pending.
- [x] Scheduled deletion removes account, registered devices, cloud configuration, and AI categorization logs.
- [x] Tests cover request, cancel, write blocking, scheduled execution, and idempotent deletion behavior.

## Blocked by

- Issue 9: Persist Backend Auth, SMS, And Registered Devices
- Issue 10: Persist Backend Cloud Configuration And AI Proxy State

## Issue 12: Package Internal Beta QA, Metrics, And Release Build

## What to build

Create the internal beta release package: repeatable build procedure, device matrix, manual test scripts, quality metrics, compliance checklist, known risks, and beta exit criteria.

## Acceptance criteria

- [x] Device matrix covers Android 10-15 and major domestic ROM families targeted for beta.
- [x] Manual QA scripts cover account/local mode, notification capture, bill sync, continuous monitoring, review queue, ledger, reports, AI, backup, deletion, and compliance pages.
- [x] Capture accuracy, deduplication accuracy, review efficiency, and permission retention measurement plan is documented.
- [x] Beta APK build command, artifact naming, output path, and signing assumptions are documented.
- [x] Full test/build passes, beta APK is generated, and blocked manual checks are explicitly recorded.

## Blocked by

- Issue 2: Persist Review Queue And Ignored Entries
- Issue 3: Persist Ledger, Reports, And Local Data Deletion
- Issue 4: Persist Categorization Rules And AI Consent Settings
- Issue 5: Make Encrypted Backup Restore Real Persisted App Data
- Issue 6: Wire Real Notification Listener Permission And Capture Path
- Issue 7: Wire User-Started Bill Sync Permission And Service Path
- Issue 8: Wire Continuous Monitoring Service Boundary And Guardrails
- Issue 9: Persist Backend Auth, SMS, And Registered Devices
- Issue 10: Persist Backend Cloud Configuration And AI Proxy State
- Issue 11: Persist Account Deletion And Scheduled Cloud Cleanup
- Issue 13: Correct Tester-Facing Android Copy Encoding

## Issue 13: Correct Tester-Facing Android Copy Encoding

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

## Approval Questions

- Does this granularity feel right, too coarse, or too fine?
- Are the blocker relationships correct?
- Should any of the backend issues or Android permission/service issues be merged or split further before publishing to an issue tracker?
