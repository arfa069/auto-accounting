# Android 本地数据指南

## 职责

本目录是设备端账本的持久化边界，包含 Room Entity、DAO、类型转换、数据库、Migration 与本地 Repository。完整账本数据只保存在设备端，不得为方便同步而加入 Backend 契约。

## 数据不变量

- `pending_entries`、`ignored_entries` 与 `ledger_entries` 表示不同生命周期状态；状态转换应通过 Repository 完整执行，禁止只改 UI 状态。
- 金额使用最小货币单位的整数；持久化枚举值视为稳定数据，重命名时必须考虑旧数据迁移。
- Entity、DAO、Converter 和 Repository 的字段语义必须一致。删除或恢复数据时检查关联的分类、资金账户、规则与设置。
- 初始分类规则必须作为 `categorization_rules` 真实记录初始化；升级不得覆盖用户编辑，也不得复活用户已删除的规则。
- schema 变化必须递增 `SCHEMA_VERSION`、补充连续 Migration、在 Provider 注册，并提交新的 Room schema JSON。禁止用破坏性迁移绕过升级。

## 验证

从仓库根目录运行：

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.autoaccounting.data.local.*"
```

新增 Migration 时补充从上一版本升级且保留数据的测试，并核对导出的 schema。
