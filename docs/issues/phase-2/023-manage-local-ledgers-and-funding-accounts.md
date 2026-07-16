# 管理本地多账本、资金账户并隔离账目调试信息

## 目标

把单一本地账本扩展为可创建、切换和安全删除的命名账本，提供独立的资金账户管理，并保证日常 Release 界面不显示账目来源与生命周期调试信息。所有能力继续只使用本机 Room，不新增后端或共享 API。

## 范围

### 多账本与作用域

- Room 升级至 v6，新增带稳定 ID、名称和创建时间的账本表，并为每条活动或最近删除账目保存非空账本归属。
- v5 升级创建使用固定 ID 的“默认账本”，将全部旧账目无损归入该账本；当前账本 ID 持久化到本地设置。
- 新建账本后自动切换；点击已有账本切换并返回其账目列表。
- 手动新增和待确认入账在触发写入前捕获当前账本 ID，避免异步期间切换账本导致写错目标。
- 账本列表、搜索、报表、CSV 和最近删除只读取当前账本；待确认、忽略记录、重复检测、分类和资金账户保持全局共享。
- 仅允许删除没有活动或最近删除账目的非最后账本；删除当前空账本时，在同一事务中切换到最早创建的剩余账本。

### 资金账户管理

- 在账本“更多”菜单提供独立资金账户页，支持查看、新增、编辑和删除。
- 名称去除首尾空白且不能为空；同一支付来源下规范化名称不可重复，不同来源可同名，支付来源可以为空。
- 编辑使用更新语义并保留账户 ID 和创建时间；修改账户支付来源不批量改写历史账目。
- 仅在活动/最近删除账目、待确认和忽略记录均无引用时硬删除；失败结果按四类记录返回引用数量。
- 自动确认优先保留已有账户 ID；没有 ID 时只按支付来源和规范化名称精确复用，不自动创建资金账户。
- 账目表单继续保留内联新增，并与独立管理页复用相同规范化和重复校验。

### 备份、清理与调试信息

- 加密备份升级为 V4，保存全部账本、账目归属、共享数据和当前账本；V2/V3 导入时创建“默认账本”并归入全部旧账目。
- 恢复前完整校验账本唯一性、账目归属、当前账本和其他外键引用；校验失败不得修改现有数据。
- 清除本机数据后重新创建唯一的“默认账本”并设为当前账本。
- 账目来源与生命周期元数据继续持久化。Debug 构建在对应账目详情显示录入方式、创建或首次确认时间、最后修改、原始来源、原待确认 ID 和采集证据；Release 不组合该区域。

## 非目标

- 不包含账本重命名、整本回收站或既有账目跨账本移动。
- 不为每个账本复制分类或资金账户。
- 不增加余额、对账、账户间转账、多币种或汇率换算。
- 不修改待确认、忽略记录和重复检测的全局作用域。
- 不新增云端账本同步、后端接口或 `shared/api` 契约。
- 不把真实交易来源元数据迁入集中开发者工具。

## 目标文件或模块

- `apps/android/src/main/java/com/autoaccounting/data/local`
- `apps/android/src/main/java/com/autoaccounting/feature/ledger`
- `apps/android/src/main/java/com/autoaccounting/feature/review`
- `apps/android/src/main/java/com/autoaccounting/feature/settings`
- `apps/android/src/main/java/com/autoaccounting/MainActivity.kt`
- `apps/android/schemas/com.autoaccounting.data.local.AutoAccountingDatabase`
- `apps/android/src/test/java/com/autoaccounting`

## 验收标准

### 数据与迁移

- [x] v5→v6 迁移创建唯一固定 ID 的“默认账本”，保留全部活动和最近删除账目及其字段，并为账本外键建立索引和 `ON DELETE RESTRICT` 约束。
- [x] 当前账本跨进程重启恢复；新建后自动选中，删除当前空账本时原子切换到最早创建的剩余账本。
- [x] 最后一个账本、含活动账目的账本和仅含最近删除账目的账本均不能删除，失败不改变当前账本或账目。
- [x] 手动新增和待确认确认分别写入操作开始时捕获的目标账本；切换账本不会重定向已开始的写入。
- [x] 报表、CSV、搜索和最近删除仅包含当前账本数据；分类、资金账户、待确认、忽略记录和重复检测仍跨账本共享。

### 资金账户

- [x] 独立管理页可新增、编辑和删除资金账户；空名称和同来源重名给出明确错误，不同来源同名可保存。
- [x] 编辑保留 ID 和创建时间，且修改账户支付来源不会重写历史账目的支付来源。
- [x] 无引用账户可删除；活动账目、最近删除账目、待确认或忽略记录任一引用都能原子阻止删除并返回对应数量。
- [x] 自动确认优先保留已有账户 ID，否则只精确复用现有账户，不自动创建；账目表单内联新增与管理页使用相同规则。

### 备份与界面

- [x] V4 加密备份往返保留全部账本、账目归属、共享数据和当前账本；V2/V3 备份安全导入到“默认账本”。
- [x] 备份中的重复账本、缺失账本归属或非法当前账本引用会在替换事务前失败，现有本地数据保持不变。
- [x] 清除本机数据后只存在一个空“默认账本”，且它是当前账本。
- [x] 账本标题显示当前名称；“更多”菜单依次显示“账本管理”“资金账户”“最近删除”，首页中央按钮仍是唯一新增一笔入口。
- [x] 待确认详情显示确认目标账本；账本管理页可创建、切换和看到明确删除阻断；资金账户页可完成完整 CRUD。
- [x] `showDebugMetadata=false` 时来源与生命周期区域完全不存在，`true` 时完整显示；两种模式下普通详情、编辑和删除均可用，`MainActivity` 仅在 Debug 构建启用该参数。

## 验收测试

- [x] `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.autoaccounting.data.local.LocalLedgerRepositoryTest"`
- [x] `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.settings.LocalDataBackupRepositoryTest"`
- [x] `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.ledger.*"`
- [x] `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.review.*"`
- [x] `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.autoaccounting.MainActivityTest"`
- [x] `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest`
- [x] `.\gradlew.bat --no-daemon :apps:android:assembleDebug :apps:android:assembleRelease`
- [x] 对已签名 Release APK 执行 `apksigner verify --verbose`，并运行 `git diff --check`。

自动化测试至少覆盖：v5→v6 迁移、当前账本恢复、并发切换时的写入目标、三类账本删除阻断、当前账本查询隔离、V4 往返及 V2/V3 兼容、非法引用导入回滚、资金账户新增/编辑/重复/四类引用删除、自动确认精确复用，以及 Debug/Release 调试元数据可见性。

## 手工验证

1. 从包含既有账目的 v5 数据升级，确认全部记录进入“默认账本”，最近删除记录仍可恢复。
2. 创建两个账本并分别手动新增和确认待确认记录，切换后检查列表、报表、CSV 和最近删除互不串账；强制停止并重启确认当前账本保持。
3. 验证最后账本、活动非空账本和仅最近删除非空账本的删除阻断，再删除当前空账本并确认自动切换目标正确。
4. 从独立页面新增、编辑和删除资金账户；用四类引用各验证一次删除失败提示，并确认历史账目支付来源未被批量修改。
5. 使用脱敏数据完成 V4 全量备份往返，并用 V2/V3 测试备份验证归入“默认账本”；损坏引用的备份不得改变当前数据。
6. 分别检查 Debug 与 Release 账目详情：Debug 显示来源和生命周期元数据，Release 无该区域但仍可查看、编辑和删除账目。

## 回滚或安全说明

- Room 迁移不得使用破坏性迁移；上线 v6 后旧 APK 不能直接打开新 schema，回滚前必须使用兼容备份迁移数据。
- 删除账本和资金账户必须在事务中重新检查引用，不能只依赖 UI 计数或先查后删。
- 备份恢复必须先完整解密、解析和校验，再按账本、共享数据、账目的依赖顺序在单个事务中替换。
- Release 隐藏只改变组合层，不得删除来源元数据或削弱备份、去重和审计所需的持久化字段。
- 调试界面、测试输出和日志不得包含真实交易原文、手机号、令牌或签名材料。

## 验证记录

- `2026-07-16`：仅完成任务拆分与架构决策文档；业务代码、Room 迁移、Gradle 测试、APK 构建和真机验收尚未在本 issue 中记录。
- `2026-07-16`：文档相对链接、Issue/ADR 连续编号、占位符与空白错误检查通过；未据此勾选任何业务验收项。
- `2026-07-16`：完成 Room v6、多账本与资金账户 CRUD、V4 备份、当前账本作用域和 Debug 元数据隔离；Android 单元测试共 49 个套件、318 项，0 失败、0 错误、0 跳过。
- `2026-07-16`：Debug/Release APK 构建通过；Release APK 通过 `apksigner verify --verbose`，APK Signature Scheme v2 有效、签名者数量为 1；`git diff --check` 无空白错误，仅报告既有 CRLF 转换提示。
- `2026-07-16`：上述勾选项由自动化测试、构建和静态复审确认；“手工验证”六步仍待在升级数据和真机 Release/Debug 包上执行。

## 依赖

- Issue 17：实现单笔账新增、查看、编辑、删除与恢复。
- Issue 21：迁移数据与备份并保护恢复操作。
- Issue 22：拆分合规与隐私并隔离开发者工具。
