# Development Slices

Each slice should be independently testable and leave the app in a coherent state.

## Slice 0: Repository And Build Skeleton

Outcome:
- Android native project skeleton.
- Backend Ktor skeleton.
- Shared API/documentation conventions.

Includes:
- Kotlin/Compose/Room Android setup.
- Ktor/PostgreSQL backend setup.
- Basic CI commands documented.
- Placeholder app shell with four bottom tabs.

Verification:
- Android debug build compiles.
- Backend test task runs.
- Empty app launches.

## Slice 1: Local Ledger Foundation

Outcome:
- Local database can store named ledger books, their entries, pending entries, categories, shared funding accounts, and ignored entries.

Includes:
- Room schema.
- Persisted current ledger-book selection and single-ledger-to-default-ledger migration.
- Safe ledger-book creation, selection, and empty-book deletion.
- Shared funding-account create, update, reference-aware delete, and exact-match reuse.
- DAO tests.
- Seed categories.
- Basic repository APIs.

Verification:
- Unit tests for insert/update/query.
- Migration tests preserve active and soft-deleted ledger entries and assign them to the default ledger book.
- Deletion tests cover final-book, non-empty-book, and referenced-funding-account blocking.

## Slice 2: Review Queue UI Without Real Capture

Outcome:
- User can review sample pending entries and confirm or ignore them.

Includes:
- Review tab summary.
- Needs careful review / quick confirm grouping.
- Swipe confirm/ignore with undo Snackbar.
- Pending detail edit form.
- Ignored list recovery.

Verification:
- Compose UI tests for confirm, ignore, undo, detail edit.
- Pending-to-ledger transition test.

## Slice 3: Ledger And Reports From Local Data

Outcome:
- Confirmed entries appear in the selected ledger book and its reports.

Includes:
- Ledger-book and funding-account management pages.
- Ledger monthly summary.
- Ledger search and filter panel.
- Current-ledger recently deleted list and CSV scope.
- Report overview.
- Illustrated donut chart placeholder plus category ranking.
- 6-month category trend.
- Debug-only ledger provenance metadata with no Release composition.

Verification:
- Query tests for per-ledger monthly totals, category aggregates, recently deleted entries, and CSV rows.
- UI smoke tests for ledger switching, funding-account management, reports, and Debug/Release metadata visibility.

## Slice 4: Categorization Rules

Outcome:
- Local rules suggest categories and can be managed.

Includes:
- Rule model.
- Rule matching on merchant, title keyword, source, transaction kind.
- Rule list and simple rule form.
- Ask before saving category correction as a rule.

Verification:
- Rule matching tests.
- Rule priority and conflict tests.
- UI tests for creating and editing rules.

## Slice 5: Account UI And Local Mode

Outcome:
- Users can enter local mode or complete real registration, login, recovery, restart verification, logout, and account-deletion flows against the configured backend.

Includes:
- Login/register first screen.
- Local mode explanation.
- Agreement checkbox.
- Registration flow.
- Login flow.
- Forgot password flow.
- Form error copy.
- SMS countdown UI.
- Build-time backend URL and Debug-only cleartext boundary.
- Keystore-encrypted Session persistence with offline-unverified and invalid-Session recovery states.

Verification:
- UI and repository tests for field errors, agreement blocking, local mode entry, success-only countdown, stable network/business errors, persistence failure revocation, restart recovery, and local-ledger isolation.

## Slice 6: Backend Account And SMS

Outcome:
- Real backend supports account registration, login, recovery, SMS limits, and device/IP rate limits.

Includes:
- User table.
- Password hashing.
- SMS code issue/verify.
- Rate-limit logic.
- Login lockout.
- Token handling.
- Bearer-only protected identity, current-Session logout, HMAC verification codes, hashed Session storage, and legacy temporary-credential invalidation.

Verification:
- Backend unit/integration tests for SMS limits and login lockout.
- Contract tests for app API responses.

## Slice 7: Notification Capture

Outcome:
- App captures WeChat/Alipay payment notifications into pending entries.

Includes:
- Notification listener service.
- Permission center notification item.
- Parser interface.
- Initial parser patterns.
- Capture event evidence.

Verification:
- Parser unit tests with sample notification text.
- Manual Android test with notification samples.
- Review queue receives pending entries.

## Slice 8: Bill Sync

Outcome:
- User-started bill sync can read supported bill pages and create pending entries.

Includes:
- Accessibility service.
- Stepwise sync progress UI.
- WeChat/Alipay source selection.
- Bill page parser.
- Deduplication against notification entries.

Verification:
- Parser tests with captured bill text fixtures.
- Manual tests on target Android devices.
- Sync failure states are visible and recoverable.

## Slice 9: Deduplication

Outcome:
- High-confidence duplicates are auto-merged and low-confidence duplicates enter review.

Includes:
- Dedupe scoring.
- Merge evidence.
- Duplicate suspect confidence state.
- Review detail evidence display.

Verification:
- Unit tests for notification/bill duplicate pairs.
- Tests for false-positive avoidance.

## Slice 10: Cloud AI Categorization

Outcome:
- Opt-in users can request AI category suggestions through backend proxy.

Includes:
- AI consent UI.
- Minimal payload.
- Enhanced context opt-in.
- Backend AI proxy.
- AI categorization logs for beta.
- Clear/disable controls where feasible.

Verification:
- Backend tests for payload filtering.
- App tests for consent gating.
- No AI request when not logged in or not consented.

## Slice 11: Backup, Export, And Local Deletion

Outcome:
- Users can export the current ledger book as CSV, export/import all local ledger books in an encrypted backup, and delete local data with safeguards.

Includes:
- Current-ledger CSV export with the ledger-book name in the file name.
- Encrypted backup format with all ledger books, entry ownership, shared data, and current selection.
- Legacy backup import into the default ledger book.
- Restore flow.
- Local data deletion with backup reminder, typed phrase, and recreation of one selected default ledger book.

Verification:
- All-ledger backup round-trip and legacy-version import tests.
- Current-ledger CSV schema and isolation tests.
- Deletion confirmation test.

## Slice 12: Account Deletion

Outcome:
- Users can request account deletion with 7-day cooling-off and cancel it.

Includes:
- Deletion request backend state.
- Deletion pending UI.
- Cancel deletion.
- Pause cloud AI/config writes.
- Final deletion job.
- Server-owned pending status/deadline and cleanup-before-account deletion retry semantics.

Verification:
- Backend state machine, cleanup failure retention/retry, route anti-impersonation, and Android confirmation/exit-failure tests.
- App UI tests for pending state and cancel flow.

## Slice 13: Compliance Materials

Outcome:
- Internal beta includes store-ready compliance material drafts.

Includes:
- Privacy policy page.
- Personal information collection list.
- Third-party service list.
- Permission explanation page.
- Store review notes.

Verification:
- In-app compliance pages are reachable before and after login.
- Permission copy matches product decisions.
- No unlisted SDK or network service exists in build.

## Slice 14: Continuous Monitoring Advanced Mode

Outcome:
- Advanced users can enable continuous monitoring after trying bill sync.

Includes:
- Advanced settings entry.
- Post-bill-sync prompt.
- Explicit explanation.
- Start/stop controls.
- Permission center state.

Verification:
- Continuous monitoring is not shown in first-run main onboarding.
- User can disable it at any time.
- Monitoring only observes payment-related flows.

## Slice 15: Internal Beta Hardening

Outcome:
- App is ready for controlled beta.

Includes:
- Crash/log integration.
- Device matrix testing.
- Permission retention measurement.
- Capture accuracy measurement.
- Deduplication accuracy measurement.
- Review efficiency measurement.
- Store compliance package review.

Verification:
- Beta checklist complete.
- Known risks documented.
- No secret keys in client or repository.
