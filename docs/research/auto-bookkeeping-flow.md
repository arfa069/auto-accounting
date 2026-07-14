# 自动记账端到端流程研究

> 研究日期：2026-07-13
>
> 研究范围：当前仓库的 Android 功能入口、通知与无障碍采集、分类与去重、待确认/入账持久化，以及相关 Shared API、Ktor 与 JDBC 实现。
>
> 来源原则：仅引用本仓库源码与测试；没有把规划文档当作实现事实。

## 结论先行

当前“自动记账”主链路是**本地优先且完全在 Android 端闭环**的：

1. 用户从“我的”进入“自动记账”，开启持久化开关。
2. 微信/支付宝的支付通知，或支付结果页/支付记录页的无障碍事件，进入各自的解析流水线。
3. 候选先应用本地分类规则，再与已确认账本及待确认队列去重。
4. 结果先写入本机 Room 的 `pending_entries`；不会直接写入账本。
5. 用户在“待确认”中确认后，才在同一个 Room 事务中写入 `ledger_entries` 并删除原待确认记录。

**主链路没有 Android → Ktor 的账目上传接口，也没有 PostgreSQL 账本写入。** Android 生产代码装配的是本机 Room 数据库；Manifest 没有声明 `INTERNET`，Android 依赖中也没有 HTTP client。仓库虽有 Ktor `POST /ai/categorize` 和 JDBC `ai_categorization_logs`，但当前 Android 端只装配本地同步返回的 `DemoAiCategorizationGateway`，因此这套后端能力只能视为“已有但客户端未接入的可选 AI 分类能力”，不能画进现行自动记账主链路（[`AndroidManifest.xml:1-2`](../../apps/android/src/main/AndroidManifest.xml#L1-L2)、[`apps/android/build.gradle.kts:91-115`](../../apps/android/build.gradle.kts#L91-L115)、[`MainActivity.kt:616-630`](../../apps/android/src/main/java/com/autoaccounting/MainActivity.kt#L616-L630)）。

## 流程概览

```text
我的 → 自动记账 → 开启开关
                    │
                    ├─ 写入 Room local_settings.continuous_monitoring_enabled
                    │
                    ├─ 支付通知（微信/支付宝）
                    │    → 通知解析 → 本地规则 → 与账本/待确认去重 ─┐
                    │                                             │
                    └─ 无障碍事件（支付结果/支付记录）              │
                         → 节点文本；微信空节点时受限 OCR           │
                         → 页面过滤 → 解析 → 本地规则 → 去重 ──────┤
                                                                  ↓
                                                     Room pending_entries
                                                                  ↓
                                                        用户检查/编辑
                                                   ┌──────────────┴──────────────┐
                                                   ↓                             ↓
                                             确认入账                         忽略
                                  Room 事务：写 ledger_entries          写 ignored_entries
                                             + 删 pending                + 删 pending

不在当前主链路：Android ─X→ Ktor ─X→ PostgreSQL 账本

仓库中存在但未接入：待确认页手动 AI 建议（当前 Demo）
                    ···→ POST /ai/categorize → AiCategorizationService
                    ···→ JDBC ai_categorization_logs（仅分类日志，不是账本）
```

## 分阶段调用链

### 1. 用户可见入口与开关持久化

- “我的”页枚举了“自动记账”目的地，点击后进入对应二级页（[`ProfileScreen.kt:30-68`](../../apps/android/src/main/java/com/autoaccounting/feature/profile/ProfileScreen.kt#L30-L68)）。
- `MainActivity` 把权限状态、持续监控状态和手动账单同步入口注入 `AutomaticBookkeepingScreen`（[`MainActivity.kt:513-559`](../../apps/android/src/main/java/com/autoaccounting/MainActivity.kt#L513-L559)）。
- 开启按钮调用 `reduceContinuousMonitoringState(Enable)`；只有无障碍权限已授予且服务连接健康时，`enabled` 才会变为 `true`。关闭按钮直接派发 `Disable`（[`AutomaticBookkeepingScreen.kt:148-177`](../../apps/android/src/main/java/com/autoaccounting/feature/monitoring/AutomaticBookkeepingScreen.kt#L148-L177)、[`ContinuousMonitoringState.kt:104-128`](../../apps/android/src/main/java/com/autoaccounting/feature/monitoring/ContinuousMonitoringState.kt#L104-L128)）。
- 状态变化由 `MainActivity.persistContinuousMonitoringState` 写回 `LocalPreferencesRepository`；后者把开关持久化到 Room `local_settings.continuous_monitoring_enabled`。应用启动后又从同一 Flow 恢复状态（[`MainActivity.kt:337-341`](../../apps/android/src/main/java/com/autoaccounting/MainActivity.kt#L337-L341)、[`MainActivity.kt:408-412`](../../apps/android/src/main/java/com/autoaccounting/MainActivity.kt#L408-L412)、[`LocalPreferencesRepository.kt:52-60`](../../apps/android/src/main/java/com/autoaccounting/data/local/LocalPreferencesRepository.kt#L52-L60)、[`LedgerEntities.kt:179-186`](../../apps/android/src/main/java/com/autoaccounting/data/local/LedgerEntities.kt#L179-L186)）。
- Android 13 及以上在成功开启后按需请求“记账结果通知”权限，但这个权限只控制结果通知展示，不参与 `enabled` 的计算（[`AutomaticBookkeepingScreen.kt:66-75`](../../apps/android/src/main/java/com/autoaccounting/feature/monitoring/AutomaticBookkeepingScreen.kt#L66-L75)、[`BookkeepingResultNotifier.kt:18-29`](../../apps/android/src/main/java/com/autoaccounting/feature/capture/BookkeepingResultNotifier.kt#L18-L29)）。

这里有一个容易混淆的边界：页面的“已就绪”摘要要求通知监听和无障碍都健康，但真正启用无障碍自动捕获只以无障碍健康为硬门槛。源码测试明确把它描述为“自动捕获需要无障碍，但不要求先完成手动账单同步或开启通知监听”（[`ContinuousMonitoringState.kt:44-64`](../../apps/android/src/main/java/com/autoaccounting/feature/monitoring/ContinuousMonitoringState.kt#L44-L64)、[`ContinuousMonitoringStateTest.kt:91-118`](../../apps/android/src/test/java/com/autoaccounting/feature/monitoring/ContinuousMonitoringStateTest.kt#L91-L118)）。因此：

- 通知监听是第二个独立采集来源；缺失时页面显示“需要处理”。
- 它不是无障碍支付结果捕获的前置条件。

### 2. 自动分支 A：支付通知

1. Manifest 注册了不可导出的 `PaymentNotificationListenerService`，由系统通过 `BIND_NOTIFICATION_LISTENER_SERVICE` 绑定（[`AndroidManifest.xml:19-27`](../../apps/android/src/main/AndroidManifest.xml#L19-L27)）。
2. 微信或支付宝发出通知后，Service 提取标题、正文、包名和发布时间；处理前从 Room 读取持久化开关，`enabled=false` 时立即返回。服务重连时只回放一小时内的微信/支付宝活动通知（[`PaymentNotificationListenerService.kt:40-83`](../../apps/android/src/main/java/com/autoaccounting/feature/capture/PaymentNotificationListenerService.kt#L40-L83)、[`PaymentNotificationListenerService.kt:96-129`](../../apps/android/src/main/java/com/autoaccounting/feature/capture/PaymentNotificationListenerService.kt#L96-L129)）。
3. `PaymentNotificationParser` 只接受微信和支付宝包名，并要求同时解析出金额与交易类型；再提取商户/交易对方、资金账户和最小货币单位金额（[`PaymentNotificationParser.kt:23-48`](../../apps/android/src/main/java/com/autoaccounting/feature/capture/PaymentNotificationParser.kt#L23-L48)、[`PaymentNotificationParser.kt:61-85`](../../apps/android/src/main/java/com/autoaccounting/feature/capture/PaymentNotificationParser.kt#L61-L85)）。
4. `NotificationCapturePipeline` 把解析结果变为 `ReviewQueueEntry`，来源标为“通知捕获”，默认置信度为 `NEEDS_REVIEW`（[`NotificationCapturePipeline.kt:6-29`](../../apps/android/src/main/java/com/autoaccounting/feature/capture/NotificationCapturePipeline.kt#L6-L29)）。
5. `PaymentNotificationCaptureProcessor` 在共享互斥锁中执行：初始化系统分类、应用本地分类规则、先与已确认账本去重，再与待确认队列去重，最后调用 `ReviewQueuePersistence.persistTransition`（[`PaymentNotificationCaptureProcessor.kt:29-83`](../../apps/android/src/main/java/com/autoaccounting/feature/capture/PaymentNotificationCaptureProcessor.kt#L29-L83)、[`ReviewQueueCaptureCoordinator.kt:6-15`](../../apps/android/src/main/java/com/autoaccounting/feature/review/ReviewQueueCaptureCoordinator.kt#L6-L15)）。

### 3. 自动分支 B：无障碍支付结果/记录页

1. Manifest 注册了不可导出的 `BillSyncAccessibilityService`；服务配置只监听微信、支付宝的窗口内容/状态变化，并允许读取窗口内容和截图（[`AndroidManifest.xml:28-39`](../../apps/android/src/main/AndroidManifest.xml#L28-L39)、[`bill_sync_accessibility_service.xml:1-9`](../../apps/android/src/main/res/xml/bill_sync_accessibility_service.xml#L1-L9)）。
2. Service 连接后持续从 Room 收集 `ContinuousMonitoringState`，并维护连接心跳。收到事件时，先判断是否存在手动同步会话；如果没有，则要求开关开启、无障碍权限健康且包名在白名单内（[`BillSyncAccessibilityService.kt:76-105`](../../apps/android/src/main/java/com/autoaccounting/feature/billsync/BillSyncAccessibilityService.kt#L76-L105)）。
3. Service 收集当前节点树的可见文本。自动分支通过 `decideContinuousMonitoringCapture` 排除聊天、普通消息、收银台、确认付款等页面，只放行明确的支付完成或支付记录表面（[`BillSyncAccessibilityService.kt:107-133`](../../apps/android/src/main/java/com/autoaccounting/feature/billsync/BillSyncAccessibilityService.kt#L107-L133)、[`ContinuousMonitoringState.kt:149-180`](../../apps/android/src/main/java/com/autoaccounting/feature/monitoring/ContinuousMonitoringState.kt#L149-L180)）。
4. 页面先等待 500ms 稳定，再重新读取节点文本、复查权限和页面，最后做同页防抖；通过后调用 `BillSyncCaptureProcessor.processAutomatic`（[`BillSyncAccessibilityService.kt:301-345`](../../apps/android/src/main/java/com/autoaccounting/feature/billsync/BillSyncAccessibilityService.kt#L301-L345)）。
5. `BillPageParser` 解析金额、时间、交易类型、商户/交易对方和资金账户；若支付完成页没有显式交易时间，可使用本次捕获时间作为回退（[`BillPageParser.kt:67-80`](../../apps/android/src/main/java/com/autoaccounting/feature/billsync/BillPageParser.kt#L67-L80)、[`BillPageParser.kt:119-208`](../../apps/android/src/main/java/com/autoaccounting/feature/billsync/BillPageParser.kt#L119-L208)）。
6. `BillSyncPipeline` 把解析记录转为待确认候选，先查已确认账本，再查现有待确认项。高置信重复被跳过或合并；低置信重复保留为 `DUPLICATE_SUSPECT`（[`BillSyncPipeline.kt:38-147`](../../apps/android/src/main/java/com/autoaccounting/feature/billsync/BillSyncPipeline.kt#L38-L147)、[`DedupeEngine.kt:21-56`](../../apps/android/src/main/java/com/autoaccounting/feature/dedupe/DedupeEngine.kt#L21-L56)）。
7. `BillSyncCaptureProcessor` 对新增/合并结果应用本地分类规则，再把状态变化写入待确认持久化层；`processAutomatic` 仅改变采集原因，不绕过待确认（[`BillSyncCaptureProcessor.kt:19-69`](../../apps/android/src/main/java/com/autoaccounting/feature/billsync/BillSyncCaptureProcessor.kt#L19-L69)、[`CategorizationRules.kt:28-60`](../../apps/android/src/main/java/com/autoaccounting/feature/categorization/CategorizationRules.kt#L28-L60)）。

#### 微信空节点 OCR 兜底

- 仅当包名是微信、节点文本为空且 Android 11 以上时才考虑 OCR；正式截屏前还要求屏幕亮起且未锁屏，并再次检查开关、权限和当前窗口（[`BillSyncAccessibilityService.kt:109-117`](../../apps/android/src/main/java/com/autoaccounting/feature/billsync/BillSyncAccessibilityService.kt#L109-L117)、[`BillSyncAccessibilityService.kt:158-218`](../../apps/android/src/main/java/com/autoaccounting/feature/billsync/BillSyncAccessibilityService.kt#L158-L218)、[`BillSyncAccessibilityService.kt:378-389`](../../apps/android/src/main/java/com/autoaccounting/feature/billsync/BillSyncAccessibilityService.kt#L378-L389)）。
- Android 14 以上截当前窗口，Android 11-13 截默认显示器；Bitmap 在识别后立即回收（[`BillSyncAccessibilityService.kt:269-299`](../../apps/android/src/main/java/com/autoaccounting/feature/billsync/BillSyncAccessibilityService.kt#L269-L299)）。
- OCR 结果仍走相同的页面判定、解析、去重和待确认流程，但调用 `processAutomatic(..., retainRawEvidence=false)`，不会把 OCR 原文写入待确认记录（[`BillSyncAccessibilityService.kt:236-266`](../../apps/android/src/main/java/com/autoaccounting/feature/billsync/BillSyncAccessibilityService.kt#L236-L266)、[`BillSyncPipelineTest.kt:133-146`](../../apps/android/src/test/java/com/autoaccounting/feature/billsync/BillSyncPipelineTest.kt#L133-L146)）。

### 4. 独立分支：手动账单同步

手动同步是历史补录/故障兜底，不是自动记账前置步骤：

- “自动记账”页单列“手动账单同步”，用户主动选择微信或支付宝后才启动（[`AutomaticBookkeepingScreen.kt:138-143`](../../apps/android/src/main/java/com/autoaccounting/feature/monitoring/AutomaticBookkeepingScreen.kt#L138-L143)、[`AutomaticBookkeepingScreen.kt:225-246`](../../apps/android/src/main/java/com/autoaccounting/feature/monitoring/AutomaticBookkeepingScreen.kt#L225-L246)）。
- `startManualBillSync` 建立只接受目标包名的会话并拉起来源应用（[`BillSyncSession.kt:40-48`](../../apps/android/src/main/java/com/autoaccounting/feature/billsync/BillSyncSession.kt#L40-L48)、[`BillSyncSession.kt:132-145`](../../apps/android/src/main/java/com/autoaccounting/feature/billsync/BillSyncSession.kt#L132-L145)）。
- 无障碍 Service 优先把匹配事件交给手动会话；它复用同一个 `BillSyncCaptureProcessor`、去重和待确认持久化，只把采集原因标成“账单同步”（[`BillSyncAccessibilityService.kt:93-149`](../../apps/android/src/main/java/com/autoaccounting/feature/billsync/BillSyncAccessibilityService.kt#L93-L149)、[`BillSyncCaptureProcessor.kt:19-40`](../../apps/android/src/main/java/com/autoaccounting/feature/billsync/BillSyncCaptureProcessor.kt#L19-L40)）。

### 5. Room 待确认落库

两条自动采集分支和手动同步最终都进入 `ReviewQueuePersistence.persistTransition`：

- 新待确认项经 mapper 转成 `PendingEntryEntity`，保留来源、采集原因、置信度、金额、商户、时间、分类建议、资金账户标签和必要证据（[`ReviewQueuePersistenceMappers.kt:56-75`](../../apps/android/src/main/java/com/autoaccounting/feature/review/ReviewQueuePersistenceMappers.kt#L56-L75)）。
- `persistTransition` 对下一状态中的每个待确认项调用 `repository.upsertPending`；被忽略的项写入 `ignored_entries` 后删除原 `pending_entries`（[`ReviewQueuePersistence.kt:40-89`](../../apps/android/src/main/java/com/autoaccounting/feature/review/ReviewQueuePersistence.kt#L40-L89)）。
- Room DAO 对待确认和账本都使用 `OnConflictStrategy.REPLACE`；`pending_entries` 按复核优先级与捕获时间提供 Flow（[`LedgerDaos.kt:57-90`](../../apps/android/src/main/java/com/autoaccounting/data/local/LedgerDaos.kt#L57-L90)、[`LedgerDaos.kt:92-154`](../../apps/android/src/main/java/com/autoaccounting/data/local/LedgerDaos.kt#L92-L154)）。
- `PendingEntryEntity` 和 `LedgerEntryEntity` 是两张不同表，体现“候选”和“已入账”两个生命周期（[`LedgerEntities.kt:38-80`](../../apps/android/src/main/java/com/autoaccounting/data/local/LedgerEntities.kt#L38-L80)、[`LedgerEntities.kt:82-131`](../../apps/android/src/main/java/com/autoaccounting/data/local/LedgerEntities.kt#L82-L131)）。

实际数据库文件是应用私有目录中的 `auto-accounting.db`，由 Room 单例创建并注册连续迁移（[`AutoAccountingDatabaseProvider.kt:8-28`](../../apps/android/src/main/java/com/autoaccounting/data/local/AutoAccountingDatabaseProvider.kt#L8-L28)）。

### 6. 用户确认后进入账本

1. 待确认界面的确认动作派发 `ReviewQueueAction.Confirm`；reducer 从 `pendingEntries` 移除目标，并建立带 `originPendingId` 的确认对象（[`ReviewQueueScreen.kt:253-275`](../../apps/android/src/main/java/com/autoaccounting/feature/review/ReviewQueueScreen.kt#L253-L275)、[`ReviewQueueState.kt:169-184`](../../apps/android/src/main/java/com/autoaccounting/feature/review/ReviewQueueState.kt#L169-L184)）。
2. `ReviewQueuePersistence` 发现新增确认项后调用 `LocalLedgerRepository.confirmPending`（[`ReviewQueuePersistence.kt:40-53`](../../apps/android/src/main/java/com/autoaccounting/feature/review/ReviewQueuePersistence.kt#L40-L53)、[`ReviewQueuePersistence.kt:91-106`](../../apps/android/src/main/java/com/autoaccounting/feature/review/ReviewQueuePersistence.kt#L91-L106)）。
3. `confirmPending` 在 Room 事务中读取待确认项，构造 `LedgerEntryEntity`，写入 `ledger_entries`，随后删除原 `pending_entries`；来源、原始采集来源、采集起点和原待确认 ID 均保留（[`LocalLedgerRepository.kt:161-195`](../../apps/android/src/main/java/com/autoaccounting/data/local/LocalLedgerRepository.kt#L161-L195)）。
4. `MainActivity` 观察 `ledger_entries` Flow，把已确认记录映射到“账本”和“报表”界面（[`MainActivity.kt:363-381`](../../apps/android/src/main/java/com/autoaccounting/MainActivity.kt#L363-L381)）。

因此，“自动记账”准确说是“自动采集并生成待确认项”；**最终入账仍是用户确认行为**。

### 7. 结果通知与回流

- 待确认持久化/去重完成后，两类 Service 才尝试发结果通知。通知权限未授予时，`BookkeepingResultNotifier.notify` 直接返回，不会回滚或阻断前面的 Room 写入（[`PaymentNotificationListenerService.kt:72-79`](../../apps/android/src/main/java/com/autoaccounting/feature/capture/PaymentNotificationListenerService.kt#L72-L79)、[`BillSyncAccessibilityService.kt:337-344`](../../apps/android/src/main/java/com/autoaccounting/feature/billsync/BillSyncAccessibilityService.kt#L337-L344)、[`BookkeepingResultNotifier.kt:104-145`](../../apps/android/src/main/java/com/autoaccounting/feature/capture/BookkeepingResultNotifier.kt#L104-L145)）。
- 单条新增待确认通知携带待确认 ID；点击后 `MainActivity` 切到“待确认”并打开对应项目。锁屏公开版本只显示泛化文案（[`BookkeepingResultNotifier.kt:77-101`](../../apps/android/src/main/java/com/autoaccounting/feature/capture/BookkeepingResultNotifier.kt#L77-L101)、[`BookkeepingResultNotifier.kt:115-143`](../../apps/android/src/main/java/com/autoaccounting/feature/capture/BookkeepingResultNotifier.kt#L115-L143)、[`MainActivity.kt:220-224`](../../apps/android/src/main/java/com/autoaccounting/MainActivity.kt#L220-L224)）。

## 后端、共享契约与 PostgreSQL：现有能力，但不在主链路

### 当前真实连接状态

仓库级搜索结果：

```powershell
rg -n "/ai/categorize|HttpClient|OkHttp|Retrofit" apps/android/src/main apps/android/build.gradle.kts
```

Android 生产代码没有 `/ai/categorize` 调用或 HTTP client 实现；Manifest 也没有 `android.permission.INTERNET`。在待确认编辑弹窗点击 AI 建议时，UI 会调用注入的 `AiCategorizationGateway`，但 `MainActivity` 注入的是 `DemoAiCategorizationGateway`，在进程内按标题同步返回分类（[`ReviewQueueScreen.kt:253-275`](../../apps/android/src/main/java/com/autoaccounting/feature/review/ReviewQueueScreen.kt#L253-L275)、[`MainActivity.kt:458-479`](../../apps/android/src/main/java/com/autoaccounting/MainActivity.kt#L458-L479)、[`MainActivity.kt:616-630`](../../apps/android/src/main/java/com/autoaccounting/MainActivity.kt#L616-L630)）。

### 如果未来接入，后端已有的调用链

```text
POST /ai/categorize（表单参数）
  → AiCategorizationRoutes
  → AiCategorizationService.suggest
  → AiProvider.suggest
  → AiCategorizationLogStore.insertLog
  → JdbcAiCategorizationLogStore
  → PostgreSQL ai_categorization_logs
```

- `Application.module` 注册 `/ai/categorize` 路由（[`Application.kt:32-50`](../../services/backend/src/main/kotlin/com/autoaccounting/backend/Application.kt#L32-L50)、[`Application.kt:66-76`](../../services/backend/src/main/kotlin/com/autoaccounting/backend/Application.kt#L66-L76)）。
- 路由接收表单参数、检查待注销账户的云写入状态，然后调用 `AiCategorizationService.suggest` 并按共享响应契约编码 JSON（[`AiCategorizationRoutes.kt:17-69`](../../services/backend/src/main/kotlin/com/autoaccounting/backend/ai/AiCategorizationRoutes.kt#L17-L69)）。
- Service 最小化 payload、调用 Provider，并只持久化分类日志字段；增强上下文中的 note/raw evidence 不进入日志（[`AiCategorizationService.kt:21-64`](../../services/backend/src/main/kotlin/com/autoaccounting/backend/ai/AiCategorizationService.kt#L21-L64)）。
- 生产环境通过 `AUTO_ACCOUNTING_DATABASE_URL` 等变量创建 JDBC Store；Backend 引入 PostgreSQL 驱动，测试使用 H2（[`AiCategorizationService.kt:81-93`](../../services/backend/src/main/kotlin/com/autoaccounting/backend/ai/AiCategorizationService.kt#L81-L93)、[`JdbcAccountStore.kt:398-412`](../../services/backend/src/main/kotlin/com/autoaccounting/backend/account/JdbcAccountStore.kt#L398-L412)、[`services/backend/build.gradle.kts:14-24`](../../services/backend/build.gradle.kts#L14-L24)）。
- JDBC migration 3 创建 `ai_categorization_logs`；`insertLog` 执行参数化 `INSERT`。这张表保存的是分类调用日志，不包含 `pending_entries` 或 `ledger_entries`（[`JdbcAiCategorizationLogStore.kt:16-44`](../../services/backend/src/main/kotlin/com/autoaccounting/backend/ai/JdbcAiCategorizationLogStore.kt#L16-L44)、[`JdbcAiCategorizationLogStore.kt:115-139`](../../services/backend/src/main/kotlin/com/autoaccounting/backend/ai/JdbcAiCategorizationLogStore.kt#L115-L139)）。

### Shared API 的当前边界和缺口

`shared/api` 只定义 AI 请求/响应与云配置合同，没有待确认或账本上传合同（[`CloudAiContracts.kt:13-35`](../../shared/api/src/main/kotlin/com/autoaccounting/api/CloudAiContracts.kt#L13-L35)）。而且当前请求合同与 Ktor 路由并未真正对齐：

- `AiCategorizationRequestContract` 有 `amountRangeLabel`，没有 `token`、`accountPhone`、`amountMinor` 或 `enhancedContext`。
- Ktor 路由实际读取的是表单字段 `token/accountPhone/amountMinor/enhancedContext`，并没有消费 `AiCategorizationRequestContract`（[`CloudAiContracts.kt:13-21`](../../shared/api/src/main/kotlin/com/autoaccounting/api/CloudAiContracts.kt#L13-L21)、[`AiCategorizationRoutes.kt:21-55`](../../services/backend/src/main/kotlin/com/autoaccounting/backend/ai/AiCategorizationRoutes.kt#L21-L55)）。
- Android 的 `CloudAiContractTest` 只验证本地模型可手工映射到请求合同，以及响应 JSON 可映射回 Android 模型；它没有通过真实 Android 网络 client 调用 Ktor 路由（[`CloudAiContractTest.kt:11-59`](../../apps/android/src/test/java/com/autoaccounting/feature/categorization/CloudAiContractTest.kt#L11-L59)、[`CloudAiContractTest.kt:85-95`](../../apps/android/src/test/java/com/autoaccounting/feature/categorization/CloudAiContractTest.kt#L85-L95)）。

这意味着未来接入前至少需要统一传输格式、字段语义、鉴权字段和 base URL/client 装配；当前不能把共享请求类型视为已经落地的线上契约。

## 关键文件索引

| 阶段 | 关键文件 | 作用 |
|---|---|---|
| 功能入口 | `feature/profile/ProfileScreen.kt` | “我的”页入口与目的地 |
| 页面装配 | `MainActivity.kt` | Room/repository 装配、状态恢复、二级页导航 |
| 开关与健康 | `feature/monitoring/AutomaticBookkeepingScreen.kt`、`ContinuousMonitoringState.kt` | 开启/关闭、权限健康、采集判定 |
| 通知入口 | `feature/capture/PaymentNotificationListenerService.kt` | 系统通知 Service、开关门控、结果通知 |
| 通知解析 | `PaymentNotificationParser.kt`、`NotificationCapturePipeline.kt` | 支付字段解析并生成待确认候选 |
| 无障碍入口 | `feature/billsync/BillSyncAccessibilityService.kt` | 节点读取、页面复查、防抖、OCR 兜底 |
| 页面解析 | `BillPageParser.kt`、`BillSyncPipeline.kt` | 支付结果/记录解析、账本与队列去重 |
| 分类 | `feature/categorization/CategorizationRules.kt` | 自动应用本地分类规则 |
| 并发与去重 | `ReviewQueueCaptureCoordinator.kt`、`DedupeEngine.kt` | 串行化双来源写入、重复合并/标疑 |
| 待确认持久化 | `ReviewQueuePersistence.kt`、`ReviewQueuePersistenceMappers.kt` | 状态差异映射为 Room 写操作 |
| Room 数据层 | `data/local/LedgerEntities.kt`、`LedgerDaos.kt`、`LocalLedgerRepository.kt` | `pending_entries`、`ignored_entries`、`ledger_entries` 与事务 |
| 可选后端 AI | `backend/ai/AiCategorizationRoutes.kt`、`AiCategorizationService.kt`、`JdbcAiCategorizationLogStore.kt` | 当前未接入 Android 的 AI 建议与 PostgreSQL 日志 |
| 共享合同 | `shared/api/.../CloudAiContracts.kt` | AI 响应/请求模型；当前请求字段与路由有缺口 |

## 重要分支与边界

- **永不自动直写账本**：通知、无障碍、OCR 和手动账单同步均只创建/合并待确认项；用户确认才迁移到 `ledger_entries`。
- **两种自动采集来源并行但共享去重边界**：通知和无障碍可能观察到同一交易；共享 `Mutex` 串行处理，跨来源高置信匹配合并证据（[`CrossSourceCaptureConcurrencyTest.kt:53-105`](../../apps/android/src/test/java/com/autoaccounting/feature/review/CrossSourceCaptureConcurrencyTest.kt#L53-L105)）。
- **通知权限和通知监听不是同一件事**：通知监听读取来源应用通知；`POST_NOTIFICATIONS` 只负责本应用显示处理结果。拒绝后者不会阻断 Room 持久化。
- **无障碍自动捕获和手动同步共用 Service，但入口和会话不同**：自动分支看持久化开关与权限健康；手动分支看用户启动的 `BillSyncSession`。
- **页面白名单与安全排除**：仅微信/支付宝，且付款发起、收银台、聊天输入等页面被拒绝；解析失败不创建待确认项。
- **OCR 是受限兜底**：仅微信空节点、Android 11 以上、解锁亮屏；图片立即释放，OCR 原文不落库。
- **分类分两层**：自动采集只应用本地规则；云 AI 是待确认编辑时的可选建议，且当前仍是 Demo gateway，不是自动流程的一部分。
- **账本是设备本地事实源**：登录与后端账户能力不改变 `pending_entries`/`ledger_entries` 的 Room 存储位置。

## 测试证据

| 测试 | 证明的行为 |
|---|---|
| [`ContinuousMonitoringStateTest.kt:91-159`](../../apps/android/src/test/java/com/autoaccounting/feature/monitoring/ContinuousMonitoringStateTest.kt#L91-L159) | 自动捕获硬门槛是无障碍健康；无需手动同步或通知监听；受支持支付页可放行 |
| [`PaymentNotificationCaptureProcessorTest.kt:61-97`](../../apps/android/src/test/java/com/autoaccounting/feature/capture/PaymentNotificationCaptureProcessorTest.kt#L61-L97) | 有效通知写入待确认并合并重复；无关通知不会落库 |
| [`BillSyncCaptureProcessorTest.kt:99-152`](../../apps/android/src/test/java/com/autoaccounting/feature/billsync/BillSyncCaptureProcessorTest.kt#L99-L152) | 解析失败不改变队列/账本；自动支付结果应用本地规则且仍停留在待确认 |
| [`CrossSourceCaptureConcurrencyTest.kt:53-105`](../../apps/android/src/test/java/com/autoaccounting/feature/review/CrossSourceCaptureConcurrencyTest.kt#L53-L105) | 通知与无障碍并发捕获只持久化一条合并记录 |
| [`ReviewQueuePersistenceTest.kt:133-150`](../../apps/android/src/test/java/com/autoaccounting/feature/review/ReviewQueuePersistenceTest.kt#L133-L150) | 用户确认把 pending 移到 ledger；撤销会删除 ledger 并恢复 pending |
| [`LocalLedgerRepositoryTest.kt:66-82`](../../apps/android/src/test/java/com/autoaccounting/data/local/LocalLedgerRepositoryTest.kt#L66-L82) | `confirmPending` 删除待确认并写入账本 |
| [`LocalLedgerRepositoryTest.kt:256-294`](../../apps/android/src/test/java/com/autoaccounting/data/local/LocalLedgerRepositoryTest.kt#L256-L294) | 已确认记录在数据库重开后仍存在，证明不是 Compose 临时状态 |
| [`AiCategorizationRoutesTest.kt:17-46`](../../services/backend/src/test/kotlin/com/autoaccounting/backend/ai/AiCategorizationRoutesTest.kt#L17-L46) | Ktor AI 路由能处理表单并记录最小分类上下文 |
| [`AiCategorizationPersistenceTest.kt:11-41`](../../services/backend/src/test/kotlin/com/autoaccounting/backend/ai/AiCategorizationPersistenceTest.kt#L11-L41) | JDBC AI 日志可跨 Store 实例读取；这是分类日志证据，不是 Android 接入证据 |

## 已发现但当前无法确认或尚未落地的点

1. **Android 如何连接 Ktor**：当前没有生产网络 client、base URL、请求编码或 `INTERNET` 权限，无法从源码确认部署地址、失败重试和 TLS 行为。
2. **AI 请求合同最终形态**：Shared request contract 与 Ktor 表单字段不一致；现有测试没有端到端覆盖 Android → Ktor。
3. **PostgreSQL 账本同步**：仓库没有对应路由、合同、Store 或表；目前能确认的 PostgreSQL 写入仅包括账户、云配置和 AI 分类日志，不能推断账本会上云。
4. **“已就绪”摘要与分支独立性的产品语义**：实现把缺少通知监听显示为“需要处理”，同时允许无障碍自动捕获独立运行。这可能是“完整覆盖状态”与“单一来源可运行”的刻意区分；源码没有更进一步的运行时分级说明。
5. **真实 AI Provider 的生产行为**：Backend 可从环境装配 Provider 和 PostgreSQL Store，但当前 Android 端没有调用它，因此无法从客户端流程确认真实网络调用是否可用。

## 验证方法

- 逐条追踪 Android 入口、Service、parser/pipeline、repository/DAO、Shared API、Ktor route/service/store。
- 用 `rg` 反向搜索 `/ai/categorize`、HTTP client、Room 表名和各合同调用方，确认主链路边界。
- 用现有单元/Robolectric/H2 测试交叉验证开关门控、双来源去重、待确认落库、确认入账和后端 AI 日志行为。
