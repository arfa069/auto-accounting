# Android 模块指南

## 作用域与结构

本文件适用于 `apps/android/`。通用规则继承仓库根目录 `AGENTS.md`。

- `src/main/java/com/autoaccounting/data/local/`：Room 数据库、Entity、DAO、Converter 与本地 repository。
- `src/main/java/com/autoaccounting/feature/<feature>/`：按业务功能组织状态、界面、解析器和流程。
- `src/main/res/` 与 `AndroidManifest.xml`：应用资源、权限、Activity 及系统 Service 声明。
- `src/test/java/`：按生产包路径镜像组织 JVM、Robolectric、Compose 与 Room 测试。

## 实现约束

- 保持 `MainActivity` 只负责应用组装和顶层导航；业务逻辑放入对应 feature 或 repository。
- UI 状态与持久化状态必须明确区分。涉及账目、待确认队列、设置或账号状态时，验证进程重启后的恢复行为。
- 修改 Room 表或字段等 schema 时，递增 `SCHEMA_VERSION`，补充连续 Migration，在 `AutoAccountingDatabaseProvider` 注册，并提交新的 `schemas/.../<version>.json`。DAO 或 Converter 行为变化应补充对应持久化测试。禁止使用破坏性迁移掩盖缺失 Migration。
- 修改通知监听或无障碍账单同步时，保持 Service `exported=false`，不扩大权限或采集范围，并覆盖授权、拒绝、空输入与重复事件。
- Compose 界面沿用现有 Material 3 和 feature 内组件风格；可见行为变化需同步更新相关 UI 测试。

## 验证

先运行单个测试类：

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.<feature>.<TestClass>"
```

随后运行 `.\gradlew.bat :apps:android:testDebugUnitTest`；涉及资源、Manifest、权限或发布配置时，再运行 `.\gradlew.bat :apps:android:assembleDebug`。
