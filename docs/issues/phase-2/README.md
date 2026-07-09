# Phase 2 Issue Index

Source plan: [../../PHASE-2-SLICES.md](../../PHASE-2-SLICES.md).

These issue files are ready for independent agent pickup. Work in dependency order unless a later issue is explicitly unblocked.

## Issues

1. [Baseline Audit And Phase 2 Risk Map](./001-baseline-audit-and-risk-map.md)
2. [Persist Review Queue And Ignored Entries](./002-persist-review-queue-and-ignored-entries.md)
3. [Persist Ledger Reports And Local Data Deletion](./003-persist-ledger-reports-and-local-data-deletion.md)
4. [Persist Categorization Rules And AI Consent Settings](./004-persist-categorization-rules-and-ai-consent-settings.md)
5. [Make Encrypted Backup Restore Real Persisted App Data](./005-encrypted-backup-restores-persisted-app-data.md)
6. [Wire Real Notification Listener Permission And Capture Path](./006-notification-listener-permission-and-capture-path.md)
7. [Wire User Started Bill Sync Permission And Service Path](./007-user-started-bill-sync-permission-and-service-path.md)
8. [Wire Continuous Monitoring Service Boundary And Guardrails](./008-continuous-monitoring-service-boundary-and-guardrails.md)
9. [Persist Backend Auth SMS And Registered Devices](./009-backend-auth-sms-and-registered-devices.md)
10. [Persist Backend Cloud Configuration And AI Proxy State](./010-backend-cloud-configuration-and-ai-proxy-state.md)
11. [Persist Account Deletion And Scheduled Cloud Cleanup](./011-account-deletion-and-scheduled-cloud-cleanup.md)
12. [Package Internal Beta QA Metrics And Release Build](./012-internal-beta-qa-metrics-and-release-build.md)
13. [Correct Tester-Facing Android Copy Encoding](./013-correct-tester-facing-android-copy-encoding.md)
14. [Execute Internal Beta Device Matrix And Capture Findings](./014-execute-internal-beta-device-matrix-and-capture-findings.md)
15. [Persist Local-Mode Session Across App Restarts](./015-persist-local-mode-session-across-restarts.md)

## Dependency Shape

- Issue 1 unblocks all Phase 2 implementation work.
- Issues 2, 3, and 4 unblock Issue 5.
- Issues 6 and 7 unblock Issue 8.
- Issue 9 unblocks Issues 10 and 11.
- Issues 2-11 and Issue 13 unblock Issue 12.
- Issue 12 unblocks Issue 14.
- Issue 13 can start after Issue 1 and must finish before any internal tester build.
- Issue 14 identified Issue 15; Issue 15 is independently implementable and must complete before beta distribution.
