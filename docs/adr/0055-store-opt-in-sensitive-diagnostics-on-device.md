# ADR 0055: Store Opt-In Sensitive Diagnostics On Device

- Status: Accepted
- Date: 2026-07-17

## Context

自动记账和补录账单的失败可能发生在通知预分类、无障碍页面判定、OCR、解析、去重或持久化任一阶段。只记录固定结果或 Logcat 元数据无法还原商户、备注、支付账号、订单号、采集证据和异常上下文，但直接写明文、上传后端或扩大页面采集范围会违反既有隐私边界。

既有“截图和 OCR 原文不持久化”的合同需要拆分：截图仍必须瞬时处理并立即释放；OCR 文字不进入账本，但用户主动开启的独立诊断存储可以在严格支付/补录边界内保留加密副本。

## Decision

新增 `feature/diagnostics` 作为独立的敏感诊断能力：

- Debug 默认开启；Release 默认关闭，且只有用户在“合规与隐私 → 诊断日志”阅读说明并确认后才开启。
- 允许记录支付相关通知、已判定支付结果/支付记录页、当前补录会话的页面/OCR 文字、解析交易字段、采集证据和完整异常。普通通知、聊天、无关页面和不支持包名只记录拒绝元数据。
- 截图永不保存。密码、验证码、Token、Cookie、Authorization、API Key、备份口令、签名私钥等认证秘密在写入前强制脱敏。
- 每条事件在 256 KB 限制内单独使用随机 IV 和 Android Keystore AES-256-GCM 加密，存入 `noBackupFilesDir` 的 `.aadlog` 分段。分段上限 1 MB、总密文上限 10 MB，超过上限才轮转最旧分段；相同组件、来源和原因在 5 秒内合并。
- 自动链路使用随机 `traceId`，补录链路额外复用既有 `sessionId`。候选 ID 不作为 trace ID。
- Logcat 只镜像元数据、稳定原因、计数和关联 ID；日志系统失败只产生固定脱敏错误，不改变采集、去重或持久化结果。
- 日志不上传、不进入账本或系统备份。关闭保留历史；清空删除分段和 Keystore key；本机数据删除还重置 Release 开启偏好。
- 导出使用与账本 V4 备份共享但格式隔离的 PBKDF2-HMAC-SHA256 + AES-256-GCM 封装，前缀为 `AUTO_ACCOUNTING_DIAGNOSTICS_V1:`，扩展名为 `.aadiag`。导出口令至少 8 位、二次确认且不持久化。

账本备份继续保持 `AUTO_ACCOUNTING_BACKUP_V4` 格式兼容。

## Consequences

- 应用可在不依赖后端的情况下还原自动记账完整链路，同时把敏感数据限制在用户控制的设备内密文中。
- 原“原始 OCR 不持久化”的合规说明必须更新为“图片永不持久化；OCR 文字仅在独立诊断开关开启且命中支付/补录边界时加密留存”。
- 诊断页必须默认遮罩敏感内容，离页或进入后台重新遮罩，显示时使用 `FLAG_SECURE`；导出文件及解密明文由用户和排障人员自行安全管理。
- 实现需要持续覆盖密文落盘、秘密脱敏、范围拒绝、轮转、清空、导出互通和日志失败不影响业务的测试。

## Alternatives Rejected

- 仅使用 Logcat 或明文文件：无法提供安全的长期上下文，且容易被调试工具或备份暴露。
- 自动上传后端或接入崩溃服务：扩大数据接收方和合规范围，不符合当前本地优先策略。
- 永不记录原始交易上下文：无法可靠排查 OCR、解析和去重的边界失败。
- 保存截图：敏感范围显著大于排障所需文本，且无法稳定排除无关视觉信息。

## Related Decisions

- [ADR 0013: Support CSV Export And Encrypted Backup](./0013-support-csv-export-and-encrypted-backup.md)
- [ADR 0045: Require Separate Confirmation For Local Data Deletion](./0045-require-separate-confirmation-for-local-data-deletion.md)
- [ADR 0047: Treat Transaction Data As Sensitive Personal Information](./0047-treat-transaction-data-as-sensitive-personal-information.md)
- [ADR 0048: Save Backup To Downloads Directory](./0048-save-backup-to-downloads-directory.md)
