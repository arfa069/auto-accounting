# 诊断日志操作手册

## 1. 用途与边界

诊断日志用于排查自动记账和补录账单链路，完整记录允许范围内的通知、页面/OCR 文字、解析字段、交易采集证据和异常。它独立于账本和待确认证据，也不是 Developer Tools 或云端崩溃上报。

- Debug 默认开启；Release 默认关闭，用户必须在“我的 → 合规与隐私 → 诊断日志”阅读说明并主动开启。
- 只有支付相关通知、已判定的支付结果/支付记录页和当前补录会话可保存正文。普通通知、聊天、无关页面和不支持包名只保存拒绝元数据。
- 截图永不保存或上传；支付结果页的通知、无障碍节点和 OCR 原文可作为对应待确认条目的本地证据，诊断日志中的副本仍只在上述边界内记录。
- 密码、验证码、Token、Cookie、Authorization、API Key、备份口令、签名私钥、微信 code/票据、OpenID 和 UnionID 始终在写入前脱敏。账号流程只允许稳定结果码，不记录昵称、头像 URL 或 Provider 正文。
- 日志不上传后端，也不进入账本备份或 Android 系统备份。

完整决策见 [ADR 0055](./adr/0055-store-opt-in-sensitive-diagnostics-on-device.md)，合规口径见 [COMPLIANCE.md](./COMPLIANCE.md)。

## 2. 应用内操作

诊断页默认加载最新 1000 条，只显示时间、级别、组件、事件、结果、原因、计数和关联 ID。可按级别、组件，以及事件、原因、`traceId`/`sessionId` 的统一文本筛选。

- 敏感内容默认遮罩。确认“显示敏感内容”后只在本次页面会话显示；离页或应用进入后台会立即重新遮罩，显示期间窗口启用 `FLAG_SECURE`。
- 关闭开关只停止写入新事件，不删除历史。
- “清空”需要二次确认，并删除全部密文分段和 Android Keystore key；开关状态保持不变。
- 本机数据删除还会清除日志和 Release 开启偏好。已经导出到 Downloads 的 `.aadiag` 文件不会自动删除。
- 导出期间页面显示“正在导出”，禁用重复提交但保留“取消导出”；完成、失败或取消都会显示独立结果弹窗，成功弹窗包含实际文件名。

## 3. 存储与保留

每条事件在脱敏和 256 KB 限制后，使用随机 IV 通过 Android Keystore AES-256-GCM 单独加密，并追加到 `noBackupFilesDir/diagnostics` 下的 `.aadlog` 分段。每段最多 1 MB，总密文最多 10 MB；只有超过总上限时才轮转最旧分段，不按时间自动删除。

相同组件、来源和原因在 5 秒内合并为一条事件并记录 `suppressedCount`。日志组件失败时业务继续运行，Logcat 只写固定脱敏错误和允许的元数据。

## 4. 导出与解密

应用导出要求至少 8 位且二次确认的临时口令，生成前缀为 `AUTO_ACCOUNTING_DIAGNOSTICS_V1:` 的 `.aadiag` 文件。口令不持久化。导出文件仍包含敏感交易信息，只应发送给明确授权的排障人员，并在排障完成后由用户自行删除。

在仓库根目录使用 Windows PowerShell 5.1+ 和 Java 17 解密到一个明确且尚不存在的 UTF-8 JSONL 路径：

```powershell
.\tools\decrypt-diagnostics.ps1 `
  -InputPath "C:\path\to\export.aadiag" `
  -OutputPath "C:\path\to\diagnostics.jsonl"
```

工具通过安全提示读取口令，不在终端回显或持久化。错误口令、损坏文件或格式不匹配会失败，且不会创建部分明文输出；工具也拒绝覆盖已存在的输出文件。

不要把解密后的 JSONL 提交到版本库、粘贴到 Issue/聊天或放入共享目录。明文输出的保存位置与删除由操作者负责。

## 5. 新增生产事件检查

新增或扩展诊断事件时必须同时确认：

1. 事件位于支付相关通知、允许支付页面或当前补录会话边界内；否则 payload 为空。
2. 使用随机 `traceId`，补录链路继续使用现有 `sessionId`，不得把含金额或时间的候选 ID 当作关联 ID。
3. 拒绝原因和失败原因使用稳定枚举，不从展示文案反推。
4. 敏感字段只放入 `DiagnosticSensitivePayload`，并经过认证秘密扫描、事件限长和存储加密。
5. 日志失败不改变业务结果，Logcat 不增加正文或完整异常。

## 6. 验证

最窄验证命令：

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.diagnostics.*"
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.capture.*"
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.billsync.*"
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.compliance.*"
```

跨链路变更还需运行完整 Android 单测和 Debug/Release 构建。签名材料齐全时，对 Release APK 继续执行 `apksigner verify --verbose`。未经明确要求，不自动安装或操作真机。
