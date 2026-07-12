# 仓库指南

## 项目结构与模块组织

本项目是基于 Java 17 的 Kotlin/Gradle 多模块工程。

- `apps/android/`：Android 客户端，使用 Jetpack Compose 与 Room。生产代码位于 `src/main/java`，单元测试和 Robolectric 测试位于 `src/test/java`，资源位于 `src/main/res`，Room schema 位于 `schemas/`。
- `services/backend/`：Ktor 后端、PostgreSQL 持久化实现及后端测试。
- `shared/api/`：客户端与服务端共享的 Kotlin API 契约。
- `docs/`：产品、架构、合规、发布、ADR 与阶段规划文档。

功能代码应放入所属模块；测试目录应镜像生产代码的包路径。

## 构建、测试与本地开发

在 PowerShell 中使用仓库自带的 Gradle Wrapper：

- `.\gradlew.bat :apps:android:testDebugUnitTest`：运行 Android JVM、Compose、Room 与 Robolectric 测试。
- `.\gradlew.bat :services:backend:test`：运行后端单元测试及 Ktor 集成测试。
- `.\gradlew.bat :apps:android:assembleDebug`：构建调试版 APK。
- `.\gradlew.bat :apps:android:assembleRelease`：构建 Release APK；只有本地 keystore 与三项签名凭据齐全时才会生成可分发的已签名产物。
- `.\gradlew.bat :services:backend:run`：本地启动后端。
- `.\gradlew.bat build`：编译并测试全部模块。

先运行与改动最相关的最窄任务；跨模块或影响较大的改动在提交前再运行完整 `build`。

## 编码风格与命名

遵循 Kotlin 官方代码风格（`kotlin.code.style=official`），使用四空格缩进。类、对象及 Compose 函数使用 `PascalCase`；普通函数和属性使用 `camelCase`；包名使用 `com.autoaccounting` 下的全小写名称。

每个文件应聚焦一个主要职责。优先沿用现有 feature、repository 与 service 组织方式，不为简单问题引入新抽象。ADR 使用下一个四位编号，例如 `docs/adr/0048-describe-decision.md`。

## 测试规范

测试框架为 JUnit 4。Android 测试可使用 Robolectric、Compose UI Test 与 Room Testing；后端测试使用 Ktor Test Host 和 H2。

测试文件以 `*Test.kt` 结尾，并覆盖本次改动涉及的成功、失败、空值及持久化场景。数据库迁移改变 schema 时，必须同步提交更新后的 Room schema JSON。

## 提交与 Pull Request

近期提交通常使用简短、祈使语气的标题，并优先采用 `feat:`、`fix:`、`refactor:`、`docs:`、`config:` 或 `chore:` 等前缀。每个提交只包含一个逻辑变更。

Pull Request 应说明行为变化、列出验证命令并关联对应 issue 或阶段文档。Android 界面发生可见变化时附截图；迁移、配置变更及已知后续工作必须明确标注。

## 安全与配置

禁止提交凭据、令牌、签名材料、`local.properties` 或生产配置。敏感配置应通过环境变量或本地 Gradle 属性提供；输出日志前先脱敏，并保留后端现有的密钥扫描测试。

Android Release 签名使用 `apps/android/release.jks` 与根目录 `local.properties` 中的 `RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`。这些文件和值仅限本机，均已被 Git 忽略；不得在终端输出、文档、提交或 PR 中复制其内容。修改本地签名配置后，以 `:apps:android:assembleRelease` 构建，并用 `apksigner verify --verbose` 校验生成的 APK。
