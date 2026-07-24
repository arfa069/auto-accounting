# Phase 2 Issue 索引与跟踪状态

本文档作为 Phase 2 的核心追踪索引，记录各 Issue 的状态、关系及解决记录。

## 来源规划

- 计划文档：[Phase 2 Slices](../../PHASE-2-SLICES.md)
- 历史基线：[Phase 2 Baseline Audit](../../PHASE-2-BASELINE-AUDIT.md)

## 状态总览

| Issue | 名称 | 状态 | 阻断项 | 覆盖主要故事 |
|---|---|---|---|---|
| [001](./001-baseline-audit-and-risk-map.md) | 基线审计与 Phase 2 风险映射表 | 已完成 | 无 | 内测就绪、可维护性、真实文档 |
| [002](./002-persist-review-queue-and-ignored-entries.md) | 持久化待确认队列与已忽略条目 | 已完成 | 001 | 捕获支付活动、审核待确认条目、恢复已忽略条目 |
| [003](./003-persist-ledger-reports-and-local-data-deletion.md) | 持久化账本、报表及本机数据删除 | 已完成 | 001 | 审核待确认条目、查看账本与报表、删除本机数据 |
| [004](./004-persist-categorization-rules-and-ai-consent-settings.md) | 持久化分类规则与 AI 同意/设置 | 已完成 | 001 | 构建分类规则、管理 AI 分类授权同意 |
| [005](./005-encrypted-backup-restores-persisted-app-data.md) | 实现加密备份恢复真实持久化应用数据 | 已完成 | 002, 003, 004 | 导出 CSV、创建加密备份、恢复应用数据 |
| [006](./006-notification-listener-permission-and-capture-path.md) | 接通真实通知监听权限与捕获路径 | 已完成 | 001 | 捕获微信/支付宝支付通知、管理权限 |
| [007](./007-user-started-bill-sync-permission-and-service-path.md) | 接通用户发起的账单同步权限与服务路径 | 已完成 | 001 | 手动同步微信/支付宝账单、审核捕获的待确认条目 |
| [008](./008-continuous-monitoring-service-boundary-and-guardrails.md) | 接通连续监控服务边界与安全护栏 | 已完成 | 006, 007 | 开启连续监控、随时关闭监控 |
| [009](./009-backend-auth-sms-and-registered-devices.md) | 持久化后端认证、短信及已注册设备 | 已完成 | 001 | 注册、登录、找回账号、注册设备 |
| [010](./010-backend-cloud-configuration-and-ai-proxy-state.md) | 持久化后端云端配置与 AI 代理状态 | 已完成 | 009 | 管理云端 AI 同意、安全请求 AI 分类 |
| [011](./011-account-deletion-and-scheduled-cloud-cleanup.md) | 持久化账号注销与定时云端清理 | 已完成 | 009, 010 | 申请账号注销、冷静期内取消注销、删除云端账号数据 |
| [012](./012-internal-beta-qa-metrics-and-release-build.md) | 打包内测 QA、质量指标与发布构建产物 | 已完成 | 002-011, 013 | 内测验证、捕获质量测量、发布就绪 |
| [013](./013-correct-tester-facing-android-copy-encoding.md) | 修正面向测试人员的 Android 端文案编码乱码 | 已完成 | 001 | 内测可读性、合规透明度、账号与权限易理解度 |
| [014](./014-execute-internal-beta-device-matrix-and-capture-findings.md) | 执行内测设备矩阵并记录测试结论 | 进行中 | 012, 013 | 内测验证、设备矩阵证据、发布 Go/No-Go 决策 |
| [015](./015-persist-local-mode-session-across-restarts.md) | 跨应用重启持久化本地模式会话 | 已完成 | 014 | 纯本地模式引导、重启恢复能力 |
| [016](./016-cover-in-app-payment-message-capture-paths.md) | 覆盖应用内支付消息捕获路径 | 已完成 | 006, 008 | 自动捕获包含受支持支付结果的账单页面 |
| [017](./017-support-ledger-entry-crud-and-recovery.md) | 支持账本条目 CRUD 与恢复能力 | 已完成 | 003, 005 | 手动账本条目增删改查、软删除与恢复 |
| [018](./018-rebuild-profile-overview-and-account-management.md) | 重构 Profile 概览与账号管理 | 已完成 | 009, 015 | 个人中心整体框架与账号管理二级页 |
| [019](./019-move-automatic-bookkeeping-into-its-own-page.md) | 移动自动记账至独立二级页 | 已完成 | 006, 008 | 自动记账控制与权限中心 |
| [020](./020-separate-categorization-rules-and-ai-consent.md) | 独立分类规则与 AI 同意控制 | 已完成 | 004, 010 | 分类规则与 AI 同意管理 |
| [021](./021-protect-data-and-backup-restore.md) | 受保护的数据与备份恢复 | 已完成 | 003, 005 | CSV 导出、加密备份与本机数据删除危险区 |
| [022](./022-separate-compliance-privacy-and-developer-tools.md) | 拆分合规与隐私与开发者工具 | 已完成 | 001, 013 | 合规与隐私二级页及独立开发者工具 |
| [023](./023-manage-local-ledgers-and-funding-accounts.md) | 管理本地账本与资金账户 | 已完成 | 003, 005, 017 | 多账本与资金账户独立管理 |
| [024](./024-add-report-donut-and-seven-month-cash-flow.md) | 增加报表环形图与七个月现金流 | 已完成 | 003, 017, 023 | 报表手绘环形图与七个月现金流趋势 |
| [025](./025-connect-android-account-core-loop.md) | 接通 Android 账号核心闭环 | 已完成 | 009, 018 | 真机/后端账号登录与 Session 恢复 |
| [026](./026-add-wechat-login-and-registration.md) | 增加微信登录、注册与账号身份管理 | 进行中 | 009-011, 018, 022, 025 | 微信登录注册、身份绑定、账号合并与安全解绑 |
| [027](./027-unify-account-identifiers-and-verification.md) | 统一用户名、邮箱、手机号认证与验证码 | 已完成 | 009, 018, 025, 026（自动化部分） | 多标识注册登录、短信与邮件验证码、账号绑定与找回 |
