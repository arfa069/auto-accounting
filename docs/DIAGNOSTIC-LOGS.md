# 诊断日志操作手册

## 1. 用途与边界

诊断日志用于排查应用异常和本地持久化故障，不是自动记账页面内容的存储，也不是云端崩溃上报。

- Debug 默认开启；Release 默认关闭，用户必须在“我的 → 合规与隐私 → 诊断日志”阅读说明并主动开启。
- 当前生产事件记录应用异常；自动记账读取的页面文字、解析交易字段、未命中内容和无关页面信息不得写入诊断日志。
- 密码、验证码、Token、Cookie、Authorization、API Key、备份口令、签名私钥、微信 code/票据、OpenID 和 UnionID 始终在写入前脱敏。
- 日志不上传后端，也不进入账本备份或 Android 系统备份。

现行自动记账边界见 [ADR 0063](./adr/0063-replace-platform-capture-with-assists-generic-recognition.md)，合规口径见 [COMPLIANCE.md](./COMPLIANCE.md)。

## 2. 应用内操作

诊断页默认加载最新 1000 条，只显示时间、级别、组件、事件、结果、原因、计数和关联 ID。可按级别、组件，以及事件、原因、`traceId`/`sessionId` 的统一文本筛选。

- 敏感异常内容默认遮罩。确认“显示敏感内容”后只在本次页面会话显示；离页或应用进入后台会立即重新遮罩，显示期间窗口启用 `FLAG_SECURE`。
- 关闭开关只停止写入新事件，不删除历史。
- “清空”需要二次确认，并删除全部密文分段和 Android Keystore key；开关状态保持不变。
- 本机数据删除还会清除日志和 Release 开启偏好。已经导出到 Downloads 的 `.aadiag` 文件不会自动删除。

## 3. 存储、导出与解密

每条事件在脱敏和 256 KiB 限制后，使用随机 IV 通过 Android Keystore AES-256-GCM 单独加密，并追加到 `noBackupFilesDir/diagnostics` 下的 `.aadlog` 分段。每段最多 1 MiB，总密文最多 10 MiB；只有超过总上限时才轮转最旧分段。

应用导出要求至少 8 位且二次确认的临时口令，生成前缀为 `AUTO_ACCOUNTING_DIAGNOSTICS_V1:` 的 `.aadiag` 文件。口令不持久化。在仓库根目录可用 Windows PowerShell 5.1+ 和 Java 17 解密到明确且尚不存在的 JSONL 路径：

```powershell
.\tools\decrypt-diagnostics.ps1 `
  -InputPath "C:\path\to\export.aadiag" `
  -OutputPath "C:\path\to\diagnostics.jsonl"
```

不要把导出文件或解密后的 JSONL 提交到版本库、粘贴到 Issue/聊天或放入共享目录。

## 4. 新增生产事件检查

新增事件前必须确认：

1. 不包含自动记账页面文字、交易字段或页面拒绝内容。
2. 只使用随机关联 ID，不把可能编码交易数据的候选 ID 当作 Trace ID。
3. 所有认证秘密在写入前脱敏，Logcat 只输出固定元数据。
4. 记录失败不改变业务流程，且有对应脱敏、密文、轮转和清空测试。
