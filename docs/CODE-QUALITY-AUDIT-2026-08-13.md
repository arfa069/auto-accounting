# 代码质量与性能审计报告（2026-08-13）

- 审计日期：2026-08-13
- 整改验证日期：2026-08-14
- 审计范围：全部 4 个模块（Android 175 个生产文件、后端 70 个、共享 API 9 个）
- 方法：4 个专项子代理深度审查（数据层 / feature 层 / UI 层 / 后端）+ 关键发现逐条复核（git 历史、schema JSON、编译产物、lint/测试报告）
- 基线提交：49a99ec（master）

## 一、总体评价

| 模块 | 评分 | 一句话结论 |
|---|---|---|
| 数据层 (Room/Repository) | 7.5/10 原始基线 | 撤销软删除、同步墓碑、索引、并发持久化和账本计数查询已修复；Room 文件迁移 4/4 通过 |
| Feature 层 (采集/账单/去重) | 7.5/10 原始基线 | 竞态、replay、热路径正则、时间窗去重和无障碍线程边界已修复；专项 164/164 通过 |
| UI 层 (Compose) | 7/10 原始基线 | App 导航、查询筛选及未提交表单草稿可恢复，HardwareLayerBox 已删除；敏感及瞬时状态不保存 |
| 后端 (Ktor/JDBC) | 7/10 原始基线 | 连接池、Netty call group、删除 claim、Session TTL、请求体上限和 Provider 超时已落地 |
| 构建/工具链 | 6/10 原始基线 | JaCoCo 路径及非空报告、PostgreSQL 驱动、forkEvery 和 Detekt 均已验证；大版本迁移仍需独立决策 |

**原始审计基线：7.2/10。** 历史“Android 590 例 + 后端 216 例全绿”不再作为整改证据。本轮最终实测 Android 601/601 通过；后端 219 条总计，216 通过、3 条 PostgreSQL 环境测试跳过、0 失败。高严重度问题均已完成代码修复与仓库内可运行验证；仍需产品或架构决策的项目见第六节。

## 二、高严重度问题（建议优先修复）

### 后端

**B-H1. 完全无数据库连接池：属实，已修复**
账号域现由单一 JdbcAccountStoreContext 初始化 HikariDataSource，四个 Store 共享连接池；迁移仍使用一次性连接，避免初始化递归。verifyToken 的重复查询也从约 8 次降至 5 次。后端全量测试 219 条总计，216 通过、3 条环境相关跳过、0 失败。

**B-H2. 同步阻塞占用 Netty I/O 事件循环：核心风险已修复**
生产 Netty engine 已显式配置独立 callGroupSize（至少 8，按 CPU 扩展），路由中的 JDBC、PBKDF2 和同步 Provider 不再占用 connection/worker I/O event loop。SMS 已补齐 10 秒连接与单请求超时；SMTP、微信 OAuth 和 AI Provider 的既有连接/读取或单请求超时已逐项复核。

**B-H3. 账号删除链路 TOCTOU：属实，已修复**
简单“先删账号并靠 FK CASCADE”不适用，因为 AI 日志外键是 ON DELETE SET NULL。现通过 accounts.deletion_claimed_at_millis 和 Store 原子 claim/cancel API 互斥取消与清理；claim 后失败可重试，finalize 只允许删除已 claim 账号。

**B-H4. 会话永不过期：属实，已修复固定 TTL**
account_sessions 已增加 expires_at_millis，第 12 版迁移为旧会话回填 30 天期限；签发、账号合并和校验路径均持久化并强制检查到期时间，到期 token 会被删除并拒绝。当前采用固定 30 天 TTL；是否增加滑动续期属于产品/安全策略决策。

### Android 数据层

**A-H1. 撤销确认硬删除并绕过同步墓碑：属实，已修复**
撤销确认现按 origin_pending_entry_id 查找账本 ID，再调用 moveLedgerEntryToDeleted；业务软删除与同步删除墓碑处于同一 Room 事务，避免服务器保留已撤销账目或残留 upsert。

**A-H2. 全局类型转换无防御：属实，已修复**
LedgerTypeConverters 对未知枚举字符串现返回 null，避免服务器推送、数据损坏或未来枚举演进导致 Room 读取直接崩溃。

### Android Feature 层

**A-H3. 采集与 UI 确认读-改-写竞态：属实，已修复**
ReviewQueuePersistence.persistTransition 现统一在自身 Mutex 内重读 Room 的 pending、ignored 和已确认账本来源 ID，再合并调用方转换与持久化期间新增状态；旧 UI 快照不会覆盖后续采集条目。并发回归测试及 Android 全量 601/601 均已通过。

**A-H4. 无障碍 API 线程模型与失效节点异常：属实，已修复**
Accessibility Service scope、rootInActiveWindow 和 AccessibilityNodeInfo 树访问保留在 Main；只把不可变 String 快照的页面判定切到 Default，parser/Room 工作切到 IO。失效节点读取增加异常回退，截图 host按 API级别安全委托，不再从 IO线程访问节点对象。

**A-H5. 通知 replay 复活已忽略条目：属实，已修复**
通知处理现对当前有效 ignored entries执行同一 DedupeEngine高置信度匹配，命中后直接跳过；权限重授、监听服务重连或 force-stop后的活跃通知 replay不会重新进入待确认队列，并已新增回归测试源码。

### UI 层

**A-H6. 旋转屏幕状态恢复**：部分属实，已完成必要修复。Runtime 的 review/ledger/settings 是 Room/Session Flow 的可恢复业务快照，不保存副本；LazyListState 自带 Saver。AppState 导航、账本查询筛选、诊断查询筛选、分类规则筛选与草稿、账本创建草稿、资金账户编辑草稿、Ledger 当前子页/选中条目及 Review 编辑入口现保存必要状态，并由 StateRestorationTester 验证。实体只保存稳定 ID 后从当前列表重解析；密码、验证码、执行中状态、删除/放弃确认弹窗等敏感或瞬时状态继续不保存。Manifest 不声明 configChanges 是 Compose 正常重建策略，不是缺陷。

**A-H7. HardwareLayerBox 常驻 GPU 纹理与重复 setContent**：属实，已修复。已删除整层 AndroidView/ComposeView 包装、常驻 LAYER_TYPE_HARDWARE 和 update 中 setContent；AnimatedContent 直接渲染页面，保留原 slide transition，由 Compose animation 自身按需管理图层，消除嵌套 composition 和全屏常驻 GPU texture。

## 三、中严重度问题（精选）

### 性能类
- 通知解析热路径正则和格式器反复构造：属实，但报告的 20+ 数量略有夸大；实际确认 14 个静态正则及 5 类标签正则在解析路径构造。现全部提升为文件级只读实例/列表，标签正则也仅初始化一次；formatCaptureTime 复用单一 DateTimeFormatter。
- 每条通知全量加载整本账本去重：属实，已修复。DedupeEngine 的最大匹配窗口实际为 10 分钟（报告未注明），现通过 Room 查询通知时间前后 10 分钟的活跃账本记录，不再全表加载，且不改变既有匹配边界。
- 主状态流账本计数查询原为 ledger_books 与全表 ledger_entries 的 JOIN+GROUP BY，现改为按 ledger_book_id 相关计数子查询，利用既有复合索引，避免构造整表聚合中间结果。state 的多流组合仍保留，因为这些字段共同构成单一 UI 快照。
- ignored_entries 缺 funding_account_id 索引：属实，已完成代码、schema 和迁移验证。IgnoredEntryEntity 新增索引，SCHEMA_VERSION 为 10，MIGRATION_9_10 已注册，Room compiler 正式生成 schema 10；Room 文件迁移测试 4/4 通过。
- purge/reconcileAll 批量操作逐条 DAO：属实但报告的统一 5N 估算不准确，已部分修复。purge 原为一次预读后逐条业务 DELETE + 独立同步墓碑，现业务删除改为单条批量 DELETE，并以批量入口仅检查一次 sync enabled；每实体墓碑/outbox/metadata仍保留以维持同步语义。reconcileAll 现只加载资金账户一次（仅分配缺失 syncId 后复读），构建本地 ID→syncId 映射，消除每条账目的 funding account 查询；缺失实体墓碑按类型批处理。outbox/metadata 的逐实体判定决定 mutationId 复用、冲突屏蔽和无需上传，未机械删除。
- 搜索无防抖、ReviewQueue 排序重复计算：部分属实。ReviewQueue 排序现由 remember(pendingEntries) 缓存，仅队列变化时重排。账本搜索已使用 remember、Sequence 和过滤后排序；没有慢测或数据规模证据支持新增 debounce 状态机，因此复核后不实施。
- 无障碍事件主线程整页解析：部分属实，已修复。onAccessibilityEvent 直接执行的是必须留在主线程的 AccessibilityNodeInfo 文本快照；完整 parser 虽在 coordinator 协程中，但该 scope 继承 Main，settle 后解析/Room 写入仍占主线程，手动路径的页面判定也在回调栈。现不可变 String 快照之后的页面判定切到 Dispatchers.Default，自动/手动 parser 与持久化切到 Dispatchers.IO；rootInActiveWindow/节点树访问、诊断和通知继续留在 Main。
- ledger_sync_records.business_key 无索引：属实，已修复。第 14 版迁移新增 (account_id, entity_type, business_key, entity_id) 复合索引，覆盖业务键查询与 ORDER BY entity_id。

### 正确性/健壮性
- 竞态被映射成 500：部分属实。标识补绑/替换票据路径已使用 SELECT ... FOR UPDATE、条件消费 UPDATE 和唯一冲突映射，报告对该路径不成立；账号 merge 票据此前未锁行，且事务唯一约束异常会直接抛出。现 merge 查询增加 FOR UPDATE，同票据并发确认被序列化，后到事务稳定返回 TICKET_ALREADY_USED；SQLState 23505 映射为 MERGE_BLOCKED（HTTP 409），其他 SQL 异常仍保留为服务错误。既有测试覆盖成功后重复消费；3 条需外部 PostgreSQL 的测试本机跳过，因此真实 PostgreSQL merge 并发仍是明确未运行覆盖，不能等同于已验证。
- 鉴权路径 N+1 与头像负载：查询数属实，头像描述部分准确。verifyToken 原执行 8 次 Store 查询，其中 account 查询重复 2 次、identifiers 重复 3 次；现复用首次 account 和单次 identifiers 结果派生手机号/主标识，降为 5 次查询。头像随 AccountToken 响应返回，用户上传 data URL 可接近上限，但微信头像通常只是短 URL；彻底拆分轻量鉴权 principal 与 profile 响应需要契约调整，尚未实施。
- 全局明文流量与账号 URL 校验：属实，已修复。main Manifest 禁止 cleartext；debug/benchmark overlay 仍可按现有配置支持局域网开发。HttpAccountRepository 复用 sync/AI 的策略：默认仅 HTTPS，显式 allowHttp 时也只允许 loopback/RFC1918 私网地址。
- 请求体普遍无上限、HTTP 响应无界读取：部分属实，已修复。账号客户端响应解码前限制为 256KB。后端统一 form 请求体上限为 384KB，ledger sync 保留 1MB 合同；Content-Length 和未知长度流式 body 均在解析前受限，超限返回稳定 413，且不回显敏感正文或 token。
- SecureAccountSessionStore 等 7 处 SharedPreferences.commit() 同步磁盘写：属实但跳过。7 处均位于账号 Session、LocalMode 确认、安装 ID 生成或损坏凭据清理路径；其中 6 处返回 Boolean/依赖写入成功后再切换状态，安装 ID 还被 WorkManager 同步 Worker 读取。改成 apply() 会丢失现有同步成功语义并引入进程终止/并发读取竞态；应先改 API 为挂起 IO 写入并补状态机测试，不在本轮机械替换。
- 通知候选 ID 可能碰撞：属实，已修复。ID 现在使用包名、标题、正文和通知时间的 SHA-256 稳定短摘要；同通知 replay 保持同 ID，同毫秒同金额但证据不同的交易不会碰撞，并新增回归测试。
- clearLocalData 双实现不一致：属实，已修复。LocalLedgerRepository.clearLocalData 现在也清理 default_funding_account_cache，与 LocalPreferencesRepository 一致。
- 生产代码混入假账单默认数据：属实，已修复。ReviewQueueScreen 的默认 initialState 改为空状态，样例仅由测试显式传入。
- 硬编码中文文案（0 处 stringResource）+ 同一主色在多文件值不同（0xFF202A44 vs 0xFF5B5BD6）+ 无暗色主题。

## 四、构建/工具链专项（已亲自验证）

1. **Android JaCoCo 覆盖率报告失效**：属实，已修复并验证。jacocoDebugTestReport 指向 AGP 9 的 build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes；XML 为 2,735,948 字节、HTML index 为 21,048 字节，包含 24 个 package、1,030 个 class。
2. **配置缓存反复失效**：历史报告数量与抽样结论无法从源码证明。当前 Android compile 已出现 configuration cache entry reused，说明并非所有构建都失效；IDE 注入的 android.injected.* 属性属于外部输入。detekt 版本升级仍需兼容性验证，本轮不改。
3. **依赖整体偏旧**：部分属实。PostgreSQL JDBC 已在同一 42.7.x 兼容线升级至 42.7.13，并通过后端编译/测试；Compose/Ktor/Kotlin/AGP/Robolectric 大版本升级涉及 API、编译器和测试框架联动，留作独立任务。报告中的“最新版本”和 25 项 lint 数量不作为当前可验证事实。
4. **kapt 而非 KSP**：属实但跳过。迁移需新增 KSP 插件、改 processor 配置并重新生成/核验 schema；当前 kapt 路径、schema 10 和 4 条迁移测试均正常，没有为工具替换扩大整改范围。
5. **forkEvery = 5**：属实，已修复。已移除该配置，Android JVM 测试恢复 Gradle 默认的测试进程复用；保留 1GB maxHeapSize 和 JaCoCo 设置。
6. **detekt 配置过松**：部分属实。MagicNumber、MaxLineLength 等规则确实关闭，阈值也较宽；仓库不存在 detekt-baseline.xml，报告称‘各模块 baseline 吸收 findings’不实。当前 Detekt 全量通过；是否收紧规则属于团队规范决策，直接开启会产生大规模非行为改动，故跳过。
7. **lint 96 个问题**：属实；现存报告结尾为 0 errors、95 warnings、1 hint。列举类别也与报告一致，但多数是风格/资源建议，不等同运行时缺陷：ApplySharedPref 已逐项复核并因同步成功语义跳过；UseKtx 是等价风格替换；ModifierParameter 会扩散调用点；UnusedResources 需排除动态引用。无可运行 lint 回归时不做大规模机械清理。
8. **生产目录残留**：文件存在且未跟踪，属实；但‘无法编译’不实。该文件声明合法 package 并只有两个未使用 Compose import，Kotlin 允许未使用 import。它是用户在本任务前已有的未跟踪文件，所有权不明，本轮不擅自删除；建议由所有者确认后移除。
9. **构建目录与重复迁移**：当前 apps/android/build 实测约 2.59GB，体积描述属实，但‘无定期清理’是本地/CI缓存策略建议，不是应用代码缺陷；自动清理会牺牲增量构建，本轮不删除用户产物。后端账号 Store 重复迁移检查原先属实，已随 B-H1 修复：JdbcAccountStoreDelegate 只创建一个 JdbcAccountStoreContext，该 context 运行一次 migration 后共享 HikariDataSource，不再由四个 Store 各自迁移。

## 五、值得肯定（防止过度整改）

- **事务纪律**：所有多表写均 withTransaction，同步 outbox 与业务写同事务；ensureFundingAccount 的 insertIgnore + 回查正确处理去重竞态。
- **密码学与安全**：PBKDF2 120k 迭代 + 每用户盐、token 仅存 SHA-256 哈希、验证码 HMAC + 3 次作废、未知用户 dummy 等时、MessageDigest.isEqual 比较；全后端零字符串拼接 SQL。
- **迁移工程**：PG advisory lock + 进程锁防并发迁移、H2 快照回滚、10 个 Room schema JSON 与实体一致、无破坏性迁移；schema 10 的 Robolectric 文件迁移测试 4/4 通过。
- **敏感数据处理**：诊断日志 Keystore AES-256-GCM 逐事件加密、1MB 分段/10MB 上限、锁屏通知脱敏、Logcat 只输出元数据——是同类项目少见的高标准。
- **性能意识**：列表 key 规范、Coil3 磁盘缓存头像、壁纸 LruCache + IO 解码 + inSampleSize、Modifier.Node 自绘行指示器、macrobenchmark（1k/10k 条报表基准 + Baseline Profile）、ExistingWorkPolicy.KEEP 同步去重。
- **测试基线**：本轮实测 Android 601/601 通过；后端 219 条总计，216 通过、3 条 PostgreSQL 环境测试跳过、0 失败；Shared API 47/47 通过；Detekt 通过，JaCoCo 报告非空。

## 六、行动建议（按优先级）

| 状态 | 行动 | 当前结论 |
|---|---|---|
| 已完成 | 并发持久化、撤销墓碑、HikariCP、Netty call group、删除 claim、Session TTL、通知/无障碍链路、账本计数、请求上限、Provider 超时、状态恢复、JaCoCo、HardwareLayerBox 移除 | 代码、专项及模块全量测试已完成 |
| 复核后不实施 | 账本搜索 debounce | 现有 remember + Sequence + 过滤后排序足够；无慢测证据，不新增状态机 |
| 待产品/架构决策 | 鉴权 principal/profile 拆分、主题/暗色与文案资源化 | 涉及 API 契约或全局 UI 设计，不在质量修复中推断 |
| 待决定 | Kapt→KSP、detekt规则收紧、Compose/Ktor/Kotlin/AGP/Robolectric升级、SharedPreferences commit重构、Session滑动续期 | 涉及工具链、产品安全策略或API契约，不进行机械修改 |

## 七、逐项复核记录（2026-08-13）

### 已确认并修复

- A-H1 撤销确认硬删除：属实。ReviewQueuePersistence 原先按 origin_pending_entry_id 物理删除账本行；现改为按来源查找账本 ID，再调用已有 moveLedgerEntryToDeleted，由同步记录器写入删除墓碑。新增 LedgerEntryDao.findIdByOriginPendingEntryId。
- A-H2 枚举转换无防御：属实。LedgerTypeConverters 已改为未知字符串返回 null，避免读取异常直接崩溃。
- A-H3 采集与 UI 状态竞态：`ReviewQueuePersistence.persistTransition` 在自身 Mutex 内重读 Room，只写调用方 previous → next 真正变化的 pending，避免旧 UI 快照覆盖并发采集字段。`ReviewQueuePersistenceTest` 9/9 通过。
- A-H4 无障碍线程模型：报告中的目录/文件名部分不准确（实际实现位于 feature/billsync）；BillSyncAccessibilityService 的生命周期 scope 使用 Dispatchers.Main.immediate，rootInActiveWindow/AccessibilityNodeInfo 留在主线程，不可变判定切 Default、解析与持久化切 IO；失效节点回退到事件快照。capture/billsync 专项 164/164 通过。
- A-H5 通知 replay 复活已忽略条目：属实。通知路径此前未查询 ignoredEntries，现对当前有效忽略项使用 DedupeEngine 高置信度匹配后直接跳过，并新增 replay 回归测试。
- B-H1 数据库连接池缺失：属实。账号域的 JdbcAccountStoreContext 已改为初始化一次 HikariDataSource，四个账号 Store 通过同一上下文共享连接池；迁移阶段仍使用一次性连接，避免初始化递归。同步确认账号域此前虽已声明 HikariCP 依赖，但没有实际使用。
- B-H2 同步阻塞调用：属实，已修复。生产 Netty engine 使用 Ktor 3.2.3 的 applicationEnvironment/configure API 配置 connector 和独立 callGroupSize（至少 8，按 CPU 扩展），JDBC、PBKDF2 和同步 Provider 不占用 connection/worker I/O event loop。SMS connect/request timeout 均为 10 秒；SMTP、微信 OAuth 和 AI Provider 已复核，原有连接/读取或单请求超时有效。
- B-H3 账号删除 TOCTOU：新增 accounts.deletion_claimed_at_millis 第 13 版迁移和 Store 原子 claim/cancel API；后台任务先 claim，取消与清理互斥，失败可重试，finalize 只删除已 claim 账号。后端全量回归通过，无失败。
- B-H4 Session 永不过期：新增 30 天固定 TTL、`expires_at_millis` 第 12 版迁移、JDBC/账号合并写入与到期拒绝逻辑；TTL 边界测试随后端全量回归通过。


### 代码已修复并通过回归
- 请求体上限：新增共享读取边界，通用 form 384KB、ledger sync 1MB，同时限制 Content-Length 和未知长度流式 body；稳定 413 合同由路由测试覆盖。
- 构建专项 1 JaCoCo 路径：属实。class 路径切至 AGP 9 built-in Kotlin 输出，XML/HTML 实测非空，包含 24 个 package、1,030 个 class。

### 已复核但本轮跳过

- 依赖大版本升级、KSP、detekt 规则收紧和 lint 批量清理需要兼容性/规范决策，当前跳过。SharedPreferences commit API、Session 滑动续期、principal/profile 拆分、主题与字符串资源化也不做机械扩展。
- 报告中的 AccountDeletionJob.kt 路径：报告写在 backend/account/AccountDeletionJob.kt，实际文件位于 backend/AccountDeletionJob.kt；路径定位不准确，但删除 claim/finalize 行为已完成代码复核并随后端全量测试通过。

### 验证记录

- Android 全量：`:apps:android:testDebugUnitTest`，601/601 通过，0 跳过、0 失败。
- Android 专项：Room 文件迁移 4/4、capture/billsync 164/164、`PaymentNotificationCaptureProcessorTest` 14/14、`ReviewQueuePersistenceTest` 9/9 通过。
- 状态恢复：AppState、账本查询筛选、诊断筛选、分类规则筛选/草稿、账本创建、资金账户编辑、Ledger 编辑入口及 Review 编辑草稿均通过 `StateRestorationTester`；放弃确认弹窗不会恢复。
- Backend 全量：`:services:backend:test`，219 条总计；216 通过、3 条需外部 PostgreSQL 的集成测试跳过、0 失败。未知长度 form 超限 413 已实测；真实 PostgreSQL merge 并发仍未运行。
- 静态与覆盖率：`detekt` 通过；JaCoCo XML 2,744,655 字节、HTML index 21,046 字节；`git diff --check` 通过。
- 跨模块总门禁：`build --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process` 通过，286 个任务中 7 个执行、279 个 up-to-date；覆盖 Android 各构建类型、Lint、Backend、Shared API 与 benchmark 模块。首次调用的工具等待窗口先于 Gradle 进程结束，确认进程自然退出后用同一命令增量复跑取得明确成功退出码；未删除缓存或构建产物。
