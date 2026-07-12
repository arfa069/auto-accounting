# Phase 2 Baseline Audit

Baseline commit: `cfa42ec chore: establish phase 1 baseline`

Source issue: [Phase 2 Issue 1](./issues/phase-2/001-baseline-audit-and-risk-map.md)

> Historical snapshot: this audit describes commit `cfa42ec` before the Phase 2 closures. Do not use its mock, in-memory, signing, or profile-layout findings as the current state. Current implementation and remaining validation are tracked in [Phase 2 Issue Files](./issues/phase-2/) and [Internal Beta Release](./INTERNAL-BETA-RELEASE.md).

## Conclusion

Phase 1 is a feature-complete skeleton that covers the intended product surface from PRD Slice 0-15. It is not yet a real-device-testable internal beta because several core flows are still in-memory, mocked, or represented by Android/backend boundaries without durable integration.

Phase 2 should treat this repository as a coherent baseline, not as production-ready behavior.

This audit does not remove every mock/demo seam in the same commit. Instead, it isolates them by naming the exact seam, owner area, and follow-up issue that must replace or close it. The seams that block real internal beta are scheduled in the follow-up map below.

## Reviewed Scope

Reviewed against:
- [PRD](./PRD.md)
- [Architecture Draft](./ARCHITECTURE.md)
- [Domain Glossary](../CONTEXT.md)
- [Development Slices](./DEVELOPMENT-SLICES.md)
- [Phase 2 Slices](./PHASE-2-SLICES.md)
- [Phase 2 Issues](./PHASE-2-ISSUES.md)

Reviewed implementation areas:
- Android app shell and Compose state wiring.
- Room schema, DAO, and local ledger repository.
- Review queue, ledger, reports, categorization rules, backup/export, account, permissions, capture, bill sync, AI, compliance, and beta readiness UI.
- Backend account, SMS, deletion, and AI categorization services/routes.
- Unit tests and build configuration.

## Baseline Reality

Real and useful:
- Kotlin/Gradle multi-module project exists for Android app, backend, and shared API.
- Room schema, DAO, entities, type converters, repository APIs, and DAO-style tests exist.
- Review queue, ledger, reports, categorization, account, compliance, backup/export, and beta readiness product surfaces exist.
- Notification parsing, bill-page parsing, dedupe, categorization rule logic, local backup crypto, account state, deletion state, compliance checks, and beta metrics have unit coverage.
- Android notification listener and accessibility service declarations/classes exist.
- Backend account, SMS-code, password hashing, login lockout, deletion cooling-off, and AI proxy/log models exist with route/service tests.

Still mocked, in-memory, or local-only:
- Main app state is mostly held in Compose `remember` state in `MainActivity`: account session, review queue, confirmed entries, categorization rules, AI settings, continuous monitoring, and deletion state. Owner area: Android app composition/local state. Follow-up: Issues 2, 3, 4, and 8.
- `LocalLedgerRepository` and Room are present but not wired into the main UI flow. Owner area: Android local data. Follow-up: Issues 2 and 3.
- The app starts with `sampleReviewQueueEntries()`, so review/ledger/report data is demonstration state by default. Owner area: Android review queue. Follow-up: Issue 2.
- Android account UI defaults to `FakeAccountRepository`, returning `mock-token` for login, registration, and recovery. Owner area: Android account/backend auth integration. Follow-up: Issue 9.
- `DemoAiCategorizationGateway` in `MainActivity` returns local suggestions instead of calling the backend AI route. Owner area: Android AI/backend AI integration. Follow-up: Issue 10.
- Backend `AccountService` stores users, SMS codes, issue times, deletion state, and tokens in process memory. Owner area: backend auth/SMS/deletion persistence. Follow-up: Issues 9 and 11.
- Backend `AiCategorizationService` stores AI logs in process memory and uses local heuristic suggestions, not a provider configured through environment variables. Owner area: backend AI provider/persistence. Follow-up: Issue 10.
- Backup/export UI uses a fixed `DEMO_BACKUP_PASSPHRASE` and operates on a `LocalDataSnapshot` assembled from current Compose state. Owner area: Android backup/export. Follow-up: Issue 5.
- Notification listener forwards notification text to an in-process capture bus; permission-state detection, settings deep-link, service lifecycle, and durable capture handoff are not closed. Owner area: Android notification capture/permissions. Follow-up: Issue 6.
- Accessibility service is declared but currently has no bill-sync event handling; bill sync uses sample page text in the review UI. Owner area: Android accessibility bill sync. Follow-up: Issue 7.
- Continuous monitoring is a UI/state reducer, not a real Android service boundary. Owner area: Android monitoring service. Follow-up: Issue 8.
- Permission center copy exists, but permission health is not backed by real Android permission checks. Owner area: Android permission center. Follow-up: Issues 6, 7, and 8.
- Several Android user-facing string literals in Kotlin source appear mojibake and should be corrected before any tester-facing build. Owner area: Android UI/copy. Follow-up: Issue 13.

## Follow-Up Issue Map

- Issue 2: replace sample/default pending review state with persisted review queue and ignored-entry state.
- Issue 3: connect confirmed ledger entries, reports, and local data deletion to persisted local data.
- Issue 4: persist categorization rules, AI consent, enhanced context, and monitoring setting state.
- Issue 5: replace demo backup passphrase and transient snapshot backup with user-entered passphrase handling over persisted app data.
- Issue 6: close notification listener permission, settings deep-link, filtering, and durable pending-entry capture.
- Issue 7: close accessibility bill-sync permission, user-started sync session, service event handling, and durable pending-entry capture.
- Issue 8: replace continuous monitoring UI-only state with a real opt-in service boundary and guardrails.
- Issue 9: replace Android fake account repository and backend in-memory auth/SMS/device state with durable backend integration.
- Issue 10: replace demo AI gateway and backend heuristic/provider placeholder with durable cloud configuration and provider-backed AI proxy.
- Issue 11: connect account deletion UI/backend state to durable scheduled cloud cleanup.
- Issue 12: package validated internal beta QA and release artifacts after Issues 2-11 and Issue 13.
- Issue 13: correct mojibake tester-facing Android strings before any internal tester build.

## Risk Map

### P0 Before Real Internal Beta

- Persist app state through Room/repositories before relying on any UX behavior.
  Owner area: Android local data and main app wiring.
  Follow-up: Phase 2 Issues 2, 3, 4, and 5.

- Replace fake Android account and AI gateways with backend clients and safe provider seams.
  Owner area: Android account/AI clients and backend services.
  Follow-up: Phase 2 Issues 9 and 10.

- Close real Android permission and service flows for notification capture, bill sync, and continuous monitoring.
  Owner area: Android permissions, notification listener, accessibility service, monitoring.
  Follow-up: Phase 2 Issues 6, 7, and 8.

- Replace demo backup passphrase with user-entered passphrase handling and persisted backup data.
  Owner area: Android backup/export.
  Follow-up: Phase 2 Issue 5.

### P1 Before Wider Testers

- Move backend account, SMS, deletion, registered-device, cloud configuration, and AI log state to PostgreSQL.
  Owner area: backend persistence and migrations.
  Follow-up: Phase 2 Issues 9, 10, and 11.

- Correct mojibake user-facing Kotlin string literals and verify app copy on device.
  Owner area: Android UI/copy.
  Follow-up: Phase 2 Issue 13.

- Add real permission health and ROM guidance without overpromising background behavior.
  Owner area: Android permission center.
  Follow-up: Phase 2 Issues 6-8.

- Confirm the backend does not store full ledger data while still supporting AI logs and account/device configuration.
  Owner area: backend AI/config contracts.
  Follow-up: Phase 2 Issue 10.

### P2 Cleanup And Maintainability

- `MainActivity` currently acts as app state container, capture handler, navigation host, and gateway selector.
  Owner area: Android app composition.
  Follow-up: introduce repository-backed app state and keep feature screens focused.

- Review queue state and Room repository model duplicate some concepts but are not connected.
  Owner area: Android local data model.
  Follow-up: Phase 2 Issues 2-3.

- Account deletion state exists in Android UI and backend service but is not contract-wired.
  Owner area: Android/backend account contract.
  Follow-up: Phase 2 Issue 11.

- AI settings exist locally and backend AI logs exist server-side, but consent/config synchronization is not durable.
  Owner area: Android/backend AI and cloud configuration.
  Follow-up: Phase 2 Issue 10.

## Slice Coverage Notes

- Slice 0-4: Product surfaces and local models exist; main UI still bypasses Room for most user-visible state.
- Slice 5-6: Account UI and backend account service exist; Android uses fake repository and backend state is in-memory.
- Slice 7-8: Notification and bill-sync parsing exist; real permission/session/service closure remains Phase 2 work.
- Slice 9: Dedupe logic exists and is used in the review reducer; persistence handoff remains open.
- Slice 10: AI consent and backend AI route exist; provider integration and durable config/logs remain open.
- Slice 11: CSV and encrypted backup helpers exist; UI uses demo passphrase and in-memory snapshot.
- Slice 12: Deletion state machines exist; durable backend deletion and client/backend contract remain open.
- Slice 13: Compliance materials exist; store package needs real permission/provider evidence before public submission.
- Slice 14: Continuous monitoring UI state exists; real service boundary remains open.
- Slice 15: Beta readiness surfaces exist; real device QA package and release artifact remain open.

## Architecture And Glossary Notes

- Architecture says the Android app owns ledger truth and the first backend must not sync or store the user's full ledger. Baseline keeps ledger data local, but main UI currently uses transient state rather than durable Room-backed local truth.
- Architecture says the capture pipeline never writes directly to confirmed ledger entries. Baseline follows this in UI state: notification/bill candidates become pending entries, then review actions confirm into ledger-facing state.
- Architecture names notification listener, accessibility bill sync, dedupe, categorization, pending entries, review queue, ledger, reports, CSV/export, backend, SMS, and AI as separate responsibilities. Baseline has these responsibilities as files/classes, but main app wiring still centralizes several responsibilities in `MainActivity`.
- Glossary terms used in this audit follow the project vocabulary: pending entry, ledger entry, review queue, ignored entry, duplicate candidate, categorization rule, AI categorization, bill sync, continuous monitoring, local mode, account deletion, local data deletion, internal beta, and sensitive transaction information.
- Avoided terms: this audit does not call pending entries "raw transactions", ignored entries "deleted entries", bill sync "scraping", or local mode a "guest account".

## Validation Record

Commands run during this audit:
- `rg -n -i "mock|demo|sample|placeholder|todo|fixme|in[- ]?memory|fake|stub|hardcoded|passphrase|password|token|secret|local mode|local-only" apps services shared docs README.md CONTEXT.md`
- `.\gradlew.bat --no-daemon test` - passed.
- `.\gradlew.bat --no-daemon build` - passed.

The root `test` and `build` tasks ran the documented narrower test/build tasks through Gradle task dependencies, including Android debug unit tests and backend tests. Separate explicit invocations of `:apps:android:testDebugUnitTest`, `:apps:android:assembleDebug`, and `:services:backend:test` were not rerun after root `build` passed.
