# ADR 0048: 导出备份至系统 Downloads 目录

## 状态

已通过 (Accepted)

## 上下文

加密备份功能 (ADR 0013) 此前仅在内存中将备份内容保存为 Base64 字符串。用户无法将备份持久化为文件管理器中可见的文件，导致测试人员无法进行真实的备份与恢复。在内测验证 (Issue 14) 期间，这被识别为关键的 UX 阻断项。

## 决策

使用 `MediaStore.Downloads` API（自 Android Q / API 29 起可用，符合本应用的 `minSdk`）将加密备份直接导出至公共的 Downloads 目录。该路径无需申请额外的运行时存储权限。文件名遵循 `yyyy-MM-dd-HH-mm-ac-backup.bak` 格式。导入功能使用 SAF `OpenDocument` 允许用户选择任意 `.bak` 文件。导出和导入的结果均通过 `Snackbar` 反馈。

## 后果

- 用户可以在任何文件管理器的 Downloads 文件夹中找到其备份文件。
- 通过 SAF 导入意味着用户可以从云存储、U 盘或任何存储提供商中加载备份。
- 无需额外的存储权限（在 API 29+ 上使用 MediaStore Downloads 无需 `WRITE_EXTERNAL_STORAGE` 权限）。
