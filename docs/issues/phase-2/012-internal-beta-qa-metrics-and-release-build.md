# Package Internal Beta QA Metrics And Release Build

## What to build

Create the internal beta release package: repeatable build procedure, device matrix, manual test scripts, quality metrics, compliance checklist, known risks, and beta exit criteria.

## Acceptance criteria

- [ ] Device matrix covers Android 10-15 and major domestic ROM families targeted for beta.
- [ ] Manual QA scripts cover account/local mode, notification capture, bill sync, continuous monitoring, review queue, ledger, reports, AI, backup, deletion, and compliance pages.
- [ ] Capture accuracy, deduplication accuracy, review efficiency, and permission retention measurement plan is documented.
- [ ] Beta APK build command, artifact naming, output path, and signing assumptions are documented.
- [ ] Full test/build passes, beta APK is generated, and blocked manual checks are explicitly recorded.

## Blocked by

- Issue 2: Persist Review Queue And Ignored Entries
- Issue 3: Persist Ledger Reports And Local Data Deletion
- Issue 4: Persist Categorization Rules And AI Consent Settings
- Issue 5: Make Encrypted Backup Restore Real Persisted App Data
- Issue 6: Wire Real Notification Listener Permission And Capture Path
- Issue 7: Wire User Started Bill Sync Permission And Service Path
- Issue 8: Wire Continuous Monitoring Service Boundary And Guardrails
- Issue 9: Persist Backend Auth SMS And Registered Devices
- Issue 10: Persist Backend Cloud Configuration And AI Proxy State
- Issue 11: Persist Account Deletion And Scheduled Cloud Cleanup
