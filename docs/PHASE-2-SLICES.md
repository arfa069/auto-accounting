# Phase 2 Slices

Phase 1 in [Development Slices](./DEVELOPMENT-SLICES.md) produced a feature-complete internal beta skeleton. Phase 2 turns that skeleton into a real-device-testable internal beta.

Each slice should be independently testable and leave the app in a coherent state.

## P2-Slice 1: Review And Technical Debt Cleanup

Outcome:
- Phase 1 codebase is reviewed, cleaned, and documented as a stable baseline for Phase 2.

Includes:
- Review Slice 0-15 against the PRD and current code.
- Remove or isolate mock gateways, demo passphrases, and purely in-memory shortcuts where they block Phase 2.
- Identify duplicated UI/state logic and fragile module boundaries.
- Sync README and docs with actual behavior.
- Write a known-risk list for remaining non-production pieces.

Verification:
- Full test/build passes.
- Code review findings are triaged into Phase 2 follow-up issues.
- README and docs describe current limitations accurately.
- No unrelated refactors or behavior changes are mixed into cleanup.

## P2-Slice 2: Room Persistence Closure

Outcome:
- Core local app state survives process death and app restart.

Includes:
- Connect review queue, ledger, ignored entries, categorization rules, AI settings, continuous monitoring settings, and backup/restore to the Room/repository layer.
- Replace in-memory app state in `MainActivity` where persistence is required.
- Preserve the local-first ledger contract.
- Make local data deletion clear persisted data.
- Ensure encrypted backup uses persisted app data, not only Compose state.

Verification:
- Repository tests cover persisted settings, rules, and review state.
- UI or integration test proves restart/recreate restores data.
- Backup round-trip restores persisted data.
- Local data deletion clears persisted data.

## P2-Slice 3: Android Device Permission And Service Closure

Outcome:
- Notification capture, bill sync, and continuous monitoring are wired to real Android permission/service flows.

Includes:
- Notification listener permission state detection and settings deep-link.
- Accessibility permission state detection and settings deep-link.
- Real bill sync service/session boundary for user-started WeChat/Alipay bill reading.
- Continuous monitoring start/stop controls backed by a service boundary.
- ROM-specific guidance for background keep-alive and auto-start behavior.
- Guardrails: monitor only payment-related surfaces; never chat, messages, payment initiation, or transfers.

Verification:
- Unit tests cover permission-state mapping.
- Service boundary tests are added where possible.
- Manual device scripts cover Android 10-15 and major domestic ROMs.
- User can disable continuous monitoring at any time.

## P2-Slice 4: Backend Persistence And Real Provider Integration

Outcome:
- Backend account, device/config, deletion, SMS, and AI proxy state are durable and provider-ready.

Includes:
- PostgreSQL schema and migrations for users, SMS codes/limits, registered devices, cloud configuration, deletion requests, and AI logs.
- Token verification and route auth.
- Real SMS provider seam using environment variables.
- Real AI provider seam using environment variables.
- Scheduled account deletion job.
- Explicit secret handling: no provider keys in the client or repository.

Verification:
- Backend integration tests run against a database test container or local test database.
- Contract tests cover Android repository/client seams.
- Deletion job removes account, devices, cloud config, and AI logs.
- Missing provider environment variables fail safely.
- Secret scanner passes.

## P2-Slice 5: Internal Beta QA And Release Package

Outcome:
- The app can be distributed to controlled internal beta testers with a repeatable validation package.

Includes:
- Device matrix and manual test scripts.
- Capture accuracy, dedupe accuracy, review efficiency, and permission retention measurement plan.
- Store compliance package review checklist.
- Beta APK build procedure and artifact naming.
- Regression checklist for account, local mode, notification capture, bill sync, AI, backup, deletion, and compliance.
- Known risks and beta exit criteria.

Resolved during beta validation:
- Phase 2 Issue 15 persists the non-sensitive local-mode confirmation across force-stop/relaunch without storing account credentials.

Verification:
- `.\gradlew.bat --no-daemon test` passes.
- `.\gradlew.bat --no-daemon build` passes.
- Beta APK is generated and its path is recorded.
- Manual QA checklist is completed, or blocked items are documented.
- No known secrets are present in source or build artifacts.

## Issue Readiness

Each Phase 2 slice should be split into implementation issues only after P2-Slice 1 confirms the current code baseline. Each issue should include:
- Scope.
- Non-goals.
- Target files/modules.
- Acceptance tests.
- Manual verification, if device behavior is involved.
- Rollback or safety notes.

## Recommended Start

Start with P2-Slice 1 because it turns the Phase 1 skeleton into a trustworthy baseline before deeper persistence and Android service work.
