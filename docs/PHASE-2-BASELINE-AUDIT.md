# 阶段 2 基线审计 (Phase 2 Baseline Audit)

基线 Commit: `cfa42ec chore: establish phase 1 baseline`

来源 Issue: [Phase 2 Issue 1](./issues/phase-2/001-baseline-audit-and-risk-map.md)

> **历史快照说明**：本审计文档描述的是 Phase 2 完成之前的 Commit `cfa42ec` 状态。请勿将其中的 Mock、内存存贮、签名或 Profile 页面布局的审计结论当作当前的最新状态。当前的最新实现与剩余验证项记录在 [Phase 2 Issue 跟踪文件](./issues/phase-2/) 以及 [内测发布与 QA](./INTERNAL-BETA-RELEASE.md) 中。

## 审计结论

Phase 1 是一个功能完备的框架，涵盖了 PRD Slice 0-15 所规划的产品功能界面。但它尚不具备直接在真机上进行测试的内测条件，因为若干核心流程仍停留在内存（In-memory）、Mock 伪造数据或无持久化集成的 Android/后端边界阶段。

Phase 2 应将本代码仓库视为一个功能自恰的基线，而非直接具备生产就绪特性的状态。

本审计并不打算在同一个 Commit 中彻底清空所有 Mock/Demo 缝隙，而是通过精准命名各缝隙、归属模块及对应的后续 Issue 来对其进行隔离与排期。阻断真实内测的缝隙已排入下文的后续映射表中。

## 审计审查范围

对标文件：
- [产品需求文档 (PRD)](./PRD.md)
- [系统架构设计草案](./ARCHITECTURE.md)
- [领域语言与术语表](../CONTEXT.md)
- [开发切片规划](./DEVELOPMENT-SLICES.md)
- [阶段 2 切片规划](./PHASE-2-SLICES.md)
- [阶段 2 Issue 规划](./PHASE-2-ISSUES.md)

审查的实现区域：
- Android 应用 Shell 与 Compose 状态组装。
- Room Schema、DAO 与本地账本仓储。
- 待确认队列、账本、报表、分类规则、备份/导出、账号、权限、捕获、账单同步、AI、合规及内测就绪 UI。
- 后端账号、短信、注销及 AI 分类服务/路由。
- 单元测试与构建配置。

## 基线现状诊断

**已真实实现且有效的部分**：
- Android 应用、后端及共享 API 的 Kotlin/Gradle 多模块工程已建立。
- Room Schema、DAO、实体、类型转换器、仓储 API 及 DAO 级别单元测试已具备。
- 待确认队列、账本、报表、分类、账号、合规、备份/导出及内测就绪产品界面已建立。
- 通知解析、账单页解析、去重、分类规则逻辑、本地备份加密、账号状态、注销状态、合规检查及内测指标具备单元测试覆盖。
- Android 通知监听服务与无障碍服务声明/类已建立。
- 后端账号、短信验证码、密码哈希、登录锁定、注销冷静期及 AI 代理/日志模型均具备路由/服务测试。

**仍处于 Mock、内存中或仅限本地的部分**：
- 主应用状态大部分保存在 `MainActivity` 的 Compose `remember` 状态中：账号 Session、待确认队列、已确认条目、分类规则、AI 设置、连续监控及注销状态。归属模块：Android 应用组装/本地状态。后续追踪：Issue 2, 3, 4, 8。
- `LocalLedgerRepository` 与 Room 已存在但未接入主 UI 流程。归属模块：Android 本地数据。后续追踪：Issue 2, 3。
- 应用启动时默认加载 `sampleReviewQueueEntries()` 示例数据，因此待确认/账本/报表数据默认处于演示状态。归属模块：Android 待确认队列。后续追踪：Issue 2。
- Android 账号 UI 默认使用 `FakeAccountRepository`，在登录、注册和找回密码时返回 `mock-token`。归属模块：Android 账号/后端认证集成。后续追踪：Issue 9。
- `MainActivity` 中的 `DemoAiCategorizationGateway` 返回本地建议而非调用后端 AI 路由。归属模块：Android AI/后端 AI 集成。后续追踪：Issue 10。
- 后端 `AccountService` 在进程内存中存储用户、短信验证码、发送时间、注销状态及 Token。归属模块：后端认证/短信/注销持久化。后续追踪：Issue 9, 11。
- 后端 `AiCategorizationService` 在进程内存中存储 AI 日志，并使用本地启发式建议而非环境变量配置的服务商。归属模块：后端 AI 提供商/持久化。后续追踪：Issue 10。
- 备份/导出 UI 使用固定的 `DEMO_BACKUP_PASSPHRASE`，并对从当前 Compose 状态组装的 `LocalDataSnapshot` 进行操作。归属模块：Android 备份/导出。后续追踪：Issue 5。
- 通知监听器将通知文本转发给进程内捕获总线；权限状态检测、设置深层链接（Deep-link）、服务生命周期及持久化捕获对接尚未闭环。归属模块：Android 通知捕获/权限。后续追踪：Issue 6。
- 无障碍服务已声明但目前缺少账单同步事件处理；账单同步在待确认 UI 中使用示例页面文本。归属模块：Android 无障碍账单同步。后续追踪：Issue 7。
- 连续监控仅为一个 UI/状态 Reducer，而非真正的 Android 服务边界。归属模块：Android 监控服务。后续追踪：Issue 8。
- 权限中心文案已存在，但权限健康度缺少真实的 Android 权限检查支撑。归属模块：Android 权限中心。后续追踪：Issue 6, 7, 8。
- Kotlin 源码中若干面向用户的 Android 字符串字面量存在乱码（Mojibake），应在向测试人员分发构建前予以修正。归属模块：Android UI/文案。后续追踪：Issue 13。

## 后续 Issue 映射路线图 (Follow-Up Issue Map)

- **Issue 2**：用持久化的待确认队列及已忽略条目状态替换示例/默认待确认状态。
- **Issue 3**：将已确认账本条目、报表及本机数据删除接入持久化本地数据。
- **Issue 4**：持久化分类规则、AI 同意、增强上下文及监控设置状态。
- **Issue 5**：用基于持久化应用数据的用户输入口令替换 Demo 备份口令及临时快照备份。
- **Issue 6**：闭环通知监听权限、设置深层链接、过滤及持久化待确认条目捕获。
- **Issue 7**：闭环无障碍账单同步权限、用户发起的同步会话、服务事件处理及持久化待确认条目捕获。
- **Issue 8**：用真正的可选服务边界与安全护栏替换仅 UI 的连续监控状态。
- **Issue 9**：用持久化后端集成替换 Android 伪造账号仓储及后端内存认证/短信/设备状态。
- **Issue 10**：用持久化云端配置与服务商驱动的 AI 代理替换 Demo AI 门面及后端启发式/服务商占位符。
- **Issue 11**：将账号注销 UI/后端状态接入持久化的定时云端清理任务。
- **Issue 12**：在 Issue 2-11 与 Issue 13 完成后打包已验证的内测 QA 与发布产物。
- **Issue 13**：在面向内部测试人员构建前修正 Android 字符串乱码问题。

## 风险映射图 (Risk Map)

### P0 级（真实内测前必须解决）

- 在依赖任何 UX 行为前，必须通过 Room/Repositories 持久化应用状态。
  - 归属模块：Android 本地数据与主应用组装。
  - 后续追踪：Phase 2 Issues 2, 3, 4, 5。
- 用后端客户端与安全的提供商缝隙替换伪造的 Android 账号与 AI 门面。
  - 归属模块：Android 账号/AI 客户端与后端服务。
  - 后续追踪：Phase 2 Issues 9, 10。
- 闭环通知捕获、账单同步及连续监控的真实 Android 权限与服务流程。
  - 归属模块：Android 权限、通知监听、无障碍服务、监控。
  - 后续追踪：Phase 2 Issues 6, 7, 8。
- 用用户输入的口令处理及持久化备份数据替换 Demo 备份口令。
  - 归属模块：Android 备份/导出。
  - 后续追踪：Phase 2 Issue 5。

### P1 级（更广泛测试者前必须解决）

- 将后端账号、短信、注销、已注册设备、云端配置及 AI 日志状态迁移至 PostgreSQL。
  - 归属模块：后端持久化与数据库迁移。
  - 后续追踪：Phase 2 Issues 9, 10, 11。
- 修正面向用户的 Kotlin 字符串字面量乱码，并在真机上验证应用文案。
  - 归属模块：Android UI/文案。
  - 后续追踪：Phase 2 Issue 13。
- 添加真实的权限健康度与 ROM 引导，切忌过度承诺后台行为。
  - 归属模块：Android 权限中心。
  - 后续追踪：Phase 2 Issues 6-8。
- 确认后端在支持 AI 日志和账号/设备配置的同时，不存储完整的账本数据。
  - 归属模块：后端 AI/配置契约。
  - 后续追踪：Phase 2 Issue 10。

### P2 级（重构与可维护性提升）

- `MainActivity` 当前充当了应用状态容器、捕获处理器、导航宿主及 GateWay 选择器。
  - 归属模块：Android 应用组装。
  - 后续追踪：引入基于仓储的应用状态，保持功能页面聚焦。
- 待确认队列状态与 Room 仓储模型存在概念重复但尚未连接。
  - 归属模块：Android 本地数据模型。
  - 后续追踪：Phase 2 Issues 2-3。
- 账号注销状态存在于 Android UI 和后端服务中，但尚未在契约层面上连通。
  - 归属模块：Android/后端账号契约。
  - 后续追踪：Phase 2 Issue 11。
- AI 设置存在于本地且后端 AI 日志存在于服务端，但同意/配置同步尚不可靠。
  - 归属模块：Android/后端 AI 与云端配置。
  - 后续追踪：Phase 2 Issue 10。

## 切片覆盖率备注 (Slice Coverage Notes)

- **Slice 0-4**：产品界面和本地模型已存在；主 UI 在大部分用户可见状态上仍绕过 Room。
- **Slice 5-6**：账号 UI 和后端账号服务已存在；Android 使用 Fake 仓储，后端状态在内存中。
- **Slice 7-8**：通知和账单同步解析已存在；真正的权限/会话/服务闭环仍为 Phase 2 工作。
- **Slice 9**：去重逻辑已存在并用于 Review Reducer；持久化交接仍未闭环。
- **Slice 10**：AI 同意和后端 AI 路由已存在；服务商集成和持久化配置/日志仍未闭环。
- **Slice 11**：CSV 和加密备份辅助类已存在；UI 使用 Demo 口令与内存快照。
- **Slice 12**：注销状态机已存在；持久化后端注销及客户端/后端契约仍未闭环。
- **Slice 13**：合规材料已存在；商店包在公开发布前需要真实的权限/服务商证据。
- **Slice 14**：连续监控 UI 状态已存在；真实服务边界仍未闭环。
- **Slice 15**：内测就绪界面已存在；真实设备 QA 包与发布产物仍未闭环。

## 架构与术语规范校验 (Architecture And Glossary Notes)

- **架构约定**：Android 应用拥有账本数据的真实源，首版后端不得同步或存储用户的完整账本。本基线将账本数据保留在本地，但主 UI 当前使用临时状态而非可靠的 Room 驱动的本地真实源。
- **架构约定**：捕获流水线绝对不直接写入已确认的账本条目。基线在 UI 状态中遵循了这一点：通知/账单候选转化为待确认条目，随后由审核操作确认进入面向账本的状态。
- **架构约定**：架构将通知监听、无障碍账单同步、去重、分类、待确认条目、待确认队列、账本、报表、CSV/导出、后端、短信及 AI 列为独立职责。基线在文件/类层面上具备这些职责，但主应用组装仍将多个职责集中在 `MainActivity` 中。
- **术语规范**：本审计中使用的术语严格遵循项目词汇表：待确认条目 (pending entry)、账本条目 (ledger entry)、待确认队列 (review queue)、已忽略条目 (ignored entry)、重复候选对象 (duplicate candidate)、分类规则 (categorization rule)、AI 分类 (AI categorization)、账单同步 (bill sync)、连续监控 (continuous monitoring)、本地模式 (local mode)、账号注销 (account deletion)、本机数据删除 (local data deletion)、内测发布 (internal beta) 以及敏感交易信息 (sensitive transaction information)。
- **禁用术语**：本审计绝不将待确认条目称为“原始交易”、将已忽略条目称为“已删除条目”、将账单同步称为“抓取/爬虫”、将本地模式称为“游客账号”。

## 验证执行记录 (Validation Record)

本审计期间运行的终端命令：
- `rg -n -i "mock|demo|sample|placeholder|todo|fixme|in[- ]?memory|fake|stub|hardcoded|passphrase|password|token|secret|local mode|local-only" apps services shared docs README.md CONTEXT.md`
- `.\gradlew.bat --no-daemon test` - 通过。
- `.\gradlew.bat --no-daemon build` - 通过。

根目录的 `test` 与 `build` 任务通过 Gradle 任务依赖运行了文档中记录的较窄测试/构建任务，包括 Android Debug 单元测试和后端测试。在根目录 `build` 通过后，未重复调用 `:apps:android:testDebugUnitTest`、`:apps:android:assembleDebug` 和 `:services:backend:test`。
