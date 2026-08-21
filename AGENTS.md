# 仓库指南

## 项目结构与模块组织

本项目是基于 Java 17 的 Kotlin/Gradle 多模块工程。

- `apps/android/`：Android 客户端，使用 Jetpack Compose 与 Room；
- `apps/android/src/main/java`生产代码位置；
- `apps/android/src/test/java`单元测试和 Robolectric 测试位置；
- `apps/android/src/main/res`资源位置；
- `apps/android/schemas/` Room schema 位置。
- `services/backend/`：Ktor 后端、PostgreSQL 持久化实现及后端测试。
- `shared/api/`：客户端与服务端共享的 Kotlin API 契约。
- `docs/`：产品、架构、合规、发布、ADR 与阶段规划文档。

注意：功能代码应放入所属模块；测试目录应镜像生产代码的包路径。各子目录（如 `apps/android/`、`services/backend/`、`shared/api/` 及 `docs/`）均包含特定模块的 `AGENTS.md` 指南文件，在开发相应模块时请同时遵从子模块规范。

## 构建、测试与本地开发

在 PowerShell 中使用仓库自带的 Gradle Wrapper：

- `.\gradlew.bat :apps:android:testDebugUnitTest --tests`：仅测试修改后的 Android JVM、Compose、Room 与 Robolectric 相关的单元测试。
- `.\gradlew.bat :services:backend:test`：运行后端单元测试及 Ktor 集成测试。
- `.\gradlew.bat coverageReport`：运行三个模块的测试并生成各模块 JaCoCo HTML/XML 覆盖率报告。
- `.\gradlew.bat detekt`：运行 Kotlin 静态代码规范、复杂度及架构规则检查。增量检查：仅对 Git 暂存区的文件运行检查。跳过检查：在快速验证逻辑时使用 ./gradlew <task> -x detekt 跳过静态检查任务。
- `.\gradlew.bat :apps:android:assembleDebug`：构建调试版 APK。
- `.\gradlew.bat :apps:android:assembleRelease`：构建 Release APK；只有本地 keystore 与三项签名凭据齐全时才会生成可分发的已签名产物。
- `.\gradlew.bat :services:backend:run`：本地启动后端。
- `.\gradlew.bat build`：编译并测试全部模块。
- `.\gradlew.bat --stop`：停止 Gradle Daemon。

- 先运行与改动最相关的最窄测试；
- 跨模块或影响较大的改动在提交前再运行完整 `build`。
- 最终测试或构建完成后，运行 `.\gradlew.bat --stop` 释放 Gradle Daemon。
- 真机账号验收使用本地安全保存的专用测试凭据；不得把账号、密码或其他认证秘密写入仓库、命令输出或验收记录。

## CI 与 Termux 发布

- 本地 `git commit` 不触发 CI；推送 `master` 才在 GitHub 托管 Runner 上运行完整 CI。
- Termux CD 必须显式创建并推送新的严格语义版本标签，例如 `v0.1.1`；普通
  `master` push 不发布 Release，也不部署服务器。
- 发布标签、GitHub prerelease 和已发布资产不得复用或覆盖。创建标签属于发布
  操作，必须得到用户明确要求，并先确认目标提交的 CI 已通过。
- 完整发布链、服务器目录、健康检查与回滚步骤见
  `docs/TERMUX-DEPLOYMENT.md`。

## 编码风格与命名

- 遵循 Kotlin 官方代码风格（`kotlin.code.style=official`），使用四空格缩进；
- 类、对象及 Compose 函数使用 `PascalCase`；
- 普通函数和属性使用 `camelCase`；
- 包名使用 `com.bks` 下的全小写名称；
- 每个文件应聚焦一个主要职责；
- 数据持久层使用领域细分接口（`LedgerBookRepository`、`LedgerEntryRepository`、`FundingAccountRepository`）解耦操作，并通过 `LocalLedgerRepository` Facade 对外透出；
- Activity 回调与自动记账无障碍服务状态由 `BillSyncStateCoordinator` 托管，组合导航状态由 `BksAppState` (State Holder) 管理；
- 优先沿用现有 feature、repository 与 service 组织方式，不为简单问题引入新抽象。ADR 使用下一个四位编号，例如 `docs/adr/0063-describe-decision.md`。

## 测试规范

- 测试框架为 JUnit 4。Android 测试可使用 Robolectric、Compose UI Test 与 Room Testing；后端测试使用 Ktor Test Host 和 H2；
- 测试文件以 `*Test.kt` 结尾，并覆盖本次改动涉及的成功、失败、空值及持久化场景。数据库迁移改变 schema 时，必须同步提交更新后的 Room schema JSON。
- 对照验收条件检查界面文本、页面跳转、持久化结果和关键日志，报告实际结果与证据；未经用户明确许可，不修改系统授权、不清除应用数据、不卸载应用，也不执行验收用例之外的真机操作。

## Android 真机测试（ADB）

### 准备测试

1. 运行 `adb devices -l` 确认目标真机状态为 `device`，记录序列号（连接多台设备时，以下所有命令都必须使用 `adb -s <serial> ...` 指定目标）；
2. 运行 `adb -s <serial> shell wm size` 记录设备逻辑分辨率；
3. 运行 `adb -s <serial> shell dumpsys package com.bks` 确认已安装版本、权限与包状态符合测试前提；

### 测试开始前

4. 测试开始前，运行 `adb -s <serial> logcat -c` 清空旧日志和运行 `adb -s <serial> shell am force-stop com.bks` 重置进程；
5. 运行 `adb -s <serial> shell monkey -p com.bks -c android.intent.category.LAUNCHER 1` 启动应用。

### 测试过程（按验收用例）

6. 使用 `adb -s <serial> shell input tap <x> <y>`、`swipe`、`text` 和 `keyevent` 操作界面；
7. 普通页面的关键状态可运行 `adb -s <serial> shell uiautomator dump /sdcard/window.xml` 与 `adb -s <serial> shell screencap -p /sdcard/screen.png`，再将 XML 和截图拉取到版本库外的本机临时目录作为证据。
8. 涉及权限或自动记账无障碍服务时，分别使用 `adb -s <serial> shell dumpsys accessibility`、`dumpsys package com.bks` 和相关系统服务的 `dumpsys` 输出核对真实状态；Xiaomi/MIUI 上验证无障碍服务时禁止使用 `uiautomator dump`，避免测试工具临时重建服务并制造错误状态，此时只使用普通截图和 `dumpsys`。复现后用 `adb -s <serial> logcat -d` 获取日志，并在展示或保存前过滤无关内容、脱敏敏感数据。

## 提交与 Pull Request

- 近期提交通常使用简短、祈使语气的标题，并优先采用 `feat:`、`fix:`、`refactor:`、`docs:`、`config:` 或 `chore:` 等前缀。每个提交只包含一个逻辑变更。
- Pull Request 应说明行为变化、列出验证命令并关联对应 issue 或阶段文档。
- Android 界面发生可见变化时附截图；
- 迁移、配置变更及已知后续工作必须明确标注。

## 安全与配置

- 禁止提交凭据、令牌、签名材料、`local.properties` 和生产配置；
- 不得在终端输出、文档、提交或 PR 中复制其内容。
- 敏感配置应通过环境变量或本地 Gradle 属性提供；
- 输出日志前先脱敏，并保留后端现有的密钥扫描测试；
- Android Release 签名使用 `apps/android/release.jks` 与根目录 `local.properties` 中的 `RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`。这些文件和值仅限本机使用，均已被 Git 忽略；
- 修改本地签名配置后，以 `:apps:android:assembleRelease` 构建，并用 `apksigner verify --verbose` 校验生成的 APK。
