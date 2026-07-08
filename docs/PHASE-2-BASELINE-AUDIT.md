# Phase 2 Baseline Audit

Baseline commit: `cfa42ec chore: establish phase 1 baseline`

Source issue: [Phase 2 Issue 1](./issues/phase-2/001-baseline-audit-and-risk-map.md)

## Conclusion

Phase 1 is a feature-complete skeleton that covers the intended product surface from PRD Slice 0-15. It is not yet a real-device-testable internal beta because several core flows are still in-memory, mocked, or represented by Android/backend boundaries without durable integration.

Phase 2 should treat this repository as a coherent baseline, not as production-ready behavior.

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
- Main app state is mostly held in Compose `remember` state in `MainActivity`: account session, review queue, confirmed entries, categorization rules, AI settings, continuous monitoring, and deletion state.
- `LocalLedgerRepository` and Room are present but not wired into the main UI flow.
- The app starts with `sampleReviewQueueEntries()`, so review/ledger/report data is demonstration state by default.
- Android account UI defaults to `FakeAccountRepository`, returning `mock-token` for login, registration, and recovery.
- `DemoAiCategorizationGateway` in `MainActivity` returns local suggestions instead of calling the backend AI route.
- Backend `AccountService` stores users, SMS codes, issue times, deletion state, and tokens in process memory.
- Backend `AiCategorizationService` stores AI logs in process memory and uses local heuristic suggestions, not a provider configured through environment variables.
- Backup/export UI uses a fixed `DEMO_BACKUP_PASSPHRASE` and operates on a `LocalDataSnapshot` assembled from current Compose state.
- Notification listener forwards notification text to an in-process capture bus; permission-state detection, settings deep-link, service lifecycle, and durable capture handoff are not closed.
- Accessibility service is declared but currently has no bill-sync event handling; bill sync uses sample page text in the review UI.
- Continuous monitoring is a UI/state reducer, not a real Android service boundary.
- Permission center copy exists, but permission health is not backed by real Android permission checks.
- Several Android user-facing string literals in Kotlin source appear mojibake and should be corrected before any tester-facing build.

## Risk Map

### P0 Before Real Internal Beta

- Persist app state through Room/repositories before relying on any UX behavior.
  Owner area: Android local data and main app wiring.
  Follow-up: Phase 2 Issues 2-5.

- Replace fake Android account and AI gateways with backend clients and safe provider seams.
  Owner area: Android account/AI clients and backend services.
  Follow-up: Phase 2 Issues 9-10.

- Close real Android permission and service flows for notification capture, bill sync, and continuous monitoring.
  Owner area: Android permissions, notification listener, accessibility service, monitoring.
  Follow-up: Phase 2 Issues 6-8.

- Replace demo backup passphrase with user-entered passphrase handling and persisted backup data.
  Owner area: Android backup/export.
  Follow-up: Phase 2 Issue 5.

### P1 Before Wider Testers

- Move backend account, SMS, deletion, registered-device, cloud configuration, and AI log state to PostgreSQL.
  Owner area: backend persistence and migrations.
  Follow-up: Phase 2 Issues 9-11.

- Correct mojibake user-facing Kotlin string literals and verify app copy on device.
  Owner area: Android UI/copy.
  Follow-up: add to the first Android UI-touching Phase 2 implementation issue or create a dedicated copy cleanup issue.

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

## Validation Record

Commands run during this audit:
- `rg -n -i "mock|demo|sample|placeholder|todo|fixme|in[- ]?memory|fake|stub|hardcoded|passphrase|password|token|secret|local mode|local-only" apps services shared docs README.md CONTEXT.md`
- `.\gradlew.bat --no-daemon test` - passed.
- `.\gradlew.bat --no-daemon build` - passed.
