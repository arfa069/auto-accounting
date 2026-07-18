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
16. [实时捕获微信/支付宝支付结果并反馈记账状态](./016-cover-in-app-payment-message-capture-paths.md)
17. [实现单笔账新增、查看、编辑、删除与恢复](./017-support-ledger-entry-crud-and-recovery.md)
18. [完成：重构“我的”总览与账户管理](./018-rebuild-profile-overview-and-account-management.md)
19. [完成：将自动记账迁移为独立页面](./019-move-automatic-bookkeeping-into-its-own-page.md)
20. [完成：拆分分类规则与智能分类同意链](./020-separate-categorization-rules-and-ai-consent.md)
21. [完成：迁移数据与备份并保护恢复操作](./021-protect-data-and-backup-restore.md)
22. [完成：拆分合规与隐私并隔离开发者工具](./022-separate-compliance-privacy-and-developer-tools.md)
23. [管理本地多账本、资金账户并隔离账目调试信息](./023-manage-local-ledgers-and-funding-accounts.md)
24. [完成：新增报表环形图与七个月现金流](./024-add-report-donut-and-seven-month-cash-flow.md)
25. [完成：接通 Android 真实账户核心闭环](./025-connect-android-account-core-loop.md)

## Supporting Lists

- [可选真机验证清单](./OPTIONAL-VALIDATIONS.md)：记录因隐私或范围影响而默认不执行、需产品负责人另行决定的验证。

## Dependency Shape

- Issue 1 unblocks all Phase 2 implementation work.
- Issues 2, 3, and 4 unblock Issue 5.
- Issues 6 and 7 unblock Issue 8.
- Issue 9 unblocks Issues 10 and 11.
- Issues 2-11 and Issue 13 unblock Issue 12.
- Issue 12 unblocks Issue 14.
- Issue 13 can start after Issue 1 and must finish before any internal tester build.
- Issue 14 identified Issue 15; Issue 15 is independently implementable and must complete before beta distribution.
- Issue 14 identified Issue 16; Issue 16 builds on Issues 6 and 8, removes manual bill sync from the automatic-capture prerequisite, and preserves the pending-only capture boundary.
- Issue 17 builds on Issues 2, 3, and 5 to complete the persisted ledger-entry lifecycle without changing the pending-only automatic-capture boundary.
- Issue 18 establishes the profile overview and account-management navigation used by Issues 19-22.
- Issues 19-22 are independently implementable after Issue 18.
- Issue 23 builds on the persisted ledger lifecycle, protected backup flow, and Debug/Release isolation established by Issues 17, 21, and 22; it remains entirely local and does not depend on backend work.
- Issue 24 builds on the current-ledger scope and report semantics established by Issues 17 and 23; it replaces the category-selectable six-month trend without changing persisted data, backend APIs, or shared contracts.
- Issue 25 closes the Android/backend account boundary built by Issues 9, 11, and 18; it keeps local ledger data and registered-device UI outside the account transport scope.
