# Make Encrypted Backup Restore Real Persisted App Data

## What to build

Change encrypted backup export/import so it operates on the real persisted app state instead of transient UI state, covering ledger, pending entries, ignored entries, categorization rules, funding accounts, and relevant local settings.

## Acceptance criteria

- [x] Encrypted backup export reads from persisted app data and contains the expected local-first bookkeeping state.
- [x] Backup import restores data into the repository/database layer and updates review queue, ledger, reports, rules, and settings.
- [x] Backup reminder remains separate from local data deletion confirmation.
- [x] CSV export remains a plain ledger export and is not confused with encrypted backup.
- [x] Tests cover backup round-trip, wrong passphrase failure, and restored UI/repository state.

## Blocked by

- Issue 2: Persist Review Queue And Ignored Entries
- Issue 3: Persist Ledger Reports And Local Data Deletion
- Issue 4: Persist Categorization Rules And AI Consent Settings

## Verification

- `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest`
- `.\gradlew.bat --no-daemon :apps:android:assembleDebug`
