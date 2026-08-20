# Android 诊断日志指南

## 职责

本目录负责敏感诊断事件合同、认证秘密脱敏、事件限流与截断、设备内加密分段、查询、清空和口令加密导出。

## 安全不变量

- 生产链路只能通过 `DiagnosticRecorder` 最佳努力写入；记录、加密或轮转失败不得阻断采集、解析、去重或持久化。
- Logcat 只允许元数据、稳定原因、计数和关联 ID，不得输出敏感 payload、原始异常消息或完整堆栈。
- 聊天、无关页面和不支持包名只记录拒绝元数据，不得写入可见正文；截图在任何情况下都不得保存。
- 密码、验证码、Token、Cookie、Authorization、API Key、备份口令、签名私钥等认证秘密必须在写入和导出前脱敏。新增 payload 字段不得绕过 `DiagnosticSanitizer`。
- `.aadlog` 使用 Android Keystore 逐事件 AES-256-GCM 加密并存放在 `noBackupFilesDir`；保持 1 MB 分段、10 MB 总上限、256 KB 单事件上限和 5 秒合并语义。
- 保持 `AUTO_ACCOUNTING_DIAGNOSTICS_V1:` / `.aadiag` 格式兼容；不得改变 `AUTO_ACCOUNTING_BACKUP_V4` 备份格式。
- 关闭只停止新记录；清空必须同时删除分段和 Keystore key。本机数据删除还需重置 Release 开启偏好，但不得声称会删除 Downloads 中的导出文件。

## 验证

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.diagnostics.*"
```

涉及采集范围或合规界面时，同时运行 capture、billsync、compliance 定向测试，并复核 `docs/COMPLIANCE.md` 与 `docs/DIAGNOSTIC-LOGS.md`。
