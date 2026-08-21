# Android 模块指南

## 作用域与结构

本文件适用于 `apps/android/`。通用规则继承仓库根目录 `AGENTS.md`。

- `src/main/java/com/bks/data/local/`：Room 数据库、Entity、DAO、Converter 与本地 repository。
- `src/main/java/com/bks/feature/<feature>/`：按业务功能组织状态、界面、解析器和流程。
- `src/main/res/` 与 `AndroidManifest.xml`：应用资源、权限、Activity 及系统 Service 声明。
- `src/test/java/`：按生产包路径镜像组织 JVM、Robolectric、Compose 与 Room 测试。
- `src/androidTest/java/`：运行在真机或模拟器上的 Instrumentation 测试；用于验证真实 Android SQLite 或系统边界。

## 实现约束

- 保持 `MainActivity` 只保留系统生命周期、外部 Intent 转换与 `setContent` 装配；依赖创建与根组合由 `BksApp` 承载，业务逻辑放入对应 feature 或 repository。
- UI 状态与持久化状态必须明确区分。涉及账目、待确认队列、设置或账号状态时，验证进程重启后的恢复行为。
- 修改 Room 表或字段等 schema 时，递增 `SCHEMA_VERSION`，补充连续 Migration，在 `BksDatabaseProvider` 注册，并提交新的 `schemas/.../<version>.json`。DAO 或 Converter 行为变化应补充对应持久化测试。禁止使用破坏性迁移掩盖缺失 Migration。
- 修改自动记账无障碍 Service 时，保持 `exported=false`，不截图、不操作其他应用、不持久化原始页面文字，并覆盖开关/授权分离、拒绝、空输入、重复事件及采集上限。
- Compose 界面沿用现有 Material 3 和 feature 内组件风格；可见行为变化需同步更新相关 UI 测试。

## 验证

先运行与改动最相关的单个测试类或 feature package：

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.<feature>.<TestClass>"
```

- 每个开发阶段只运行专项测试与必要的 Detekt；
- 同一轮连续改动期间，不重复运行 Android 全量测试；
- 整轮改动完成后运行一次 `.\gradlew.bat :apps:android:testDebugUnitTest`；
- 仅跨模块、影响较大或准备提交/发布时运行完整 `build`；
- 除排查缓存、竞态或用户明确要求外，不使用 `--rerun-tasks`；
- Gradle Daemon 只在整轮最终验证后停止；
- 涉及资源、Manifest、权限或发布配置时，再运行 `.\gradlew.bat :apps:android:assembleDebug`。
- Compose 行为测试放在 `src/test` 并通过 Robolectric 运行；可恢复 UI 状态使用 `StateRestorationTester` 验证；窗口适配使用 `DeviceConfigurationOverride`，至少覆盖 400、610、900 dp 宽度以及 1.5 倍字体。

运行设备端 Room 测试：

```powershell
.\gradlew.bat :apps:android:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.bks.data.local.BksDatabaseInstrumentedTest"
```

连接多台设备时先设置当前 PowerShell 进程的 `ANDROID_SERIAL`。如果 Unified Test Platform 依赖暂时无法下载，可先运行 `:apps:android:assembleDebug :apps:android:assembleDebugAndroidTest`，再按根指南要求使用带 `-s <serial>` 的 ADB 安装两个 APK，并通过 `am instrument` 运行同一测试类；不得把网络失败误报成测试通过。

生成 Android JVM/Robolectric 覆盖率：

```powershell
.\gradlew.bat :apps:android:jacocoDebugTestReport
```

HTML 报告位于 `apps/android/build/reports/jacoco/jacocoDebugTestReport/html/index.html`，XML 报告位于同级任务目录。根目录的 `.\gradlew.bat coverageReport` 会同时生成 Android、后端和共享 API 三个模块的报告。
