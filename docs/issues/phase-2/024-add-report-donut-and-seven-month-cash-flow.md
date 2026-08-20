# 新增报表环形图与七个月现金流

> 状态：完成

## 目标

把当前账本的报表首页收敛为两块固定、可直接比较的内容：用手绘感环形图说明最新有效月份的支出分类构成，用七个月收支表同时展示总支出与总收入，并补齐无数据和仅有收入时的明确语义。

## 范围

### 数据作用域与月份锚点

- 所有汇总和报表内容只使用当前账本中的活动账目，不读取其他账本或最近删除记录。
- 将当前账本中至少包含一笔有效流入或流出账目的最新月份定义为 `x`；不计收支账目不参与统计，也不能单独建立 `x`。
- 月份 `x` 的概览分别显示总支出与总收入；有效收支统计继续只覆盖 CNY。

### A：支出分类环形图

- 仅统计月份 `x` 的流出账目，按分类支出金额从高到低排序。
- 使用 Compose `Canvas` 绘制手绘感环形图，展示前四个分类；存在更多分类时合并为“其他”，四类及以下不创建空的“其他”。
- 环形图旁的图例展示分类名称和一位小数占比，中心展示总支出；只读分类排行继续展示分类名称和金额。颜色只作为辅助，不能成为分类与扇区对应关系的唯一表达。

### B：七个月收支表

- 固定展示闭区间 `[x-3, x+3]`，共七个连续自然月，并正确处理跨年。
- 每个月分别汇总总支出和总收入；窗口内没有对应账目的月份以零展示。
- 使用“月份 / 支出 / 收入”三列按月份升序排列，以浅紫色背景突出 `x` 行，支出与收入分别使用珊瑚色和青绿色。
- 同时保留月份、系列名称和可读数值，不提供分类选择，不再保留原“可选分类的最近六个月趋势”。

### 空状态

- 当前账本没有任何有效收入或支出时，不生成 `x`，不伪造全零概览或报表内容，显示“当前账本暂无可分析的收支”。
- 月份 `x` 仅有收入时，收入概览和七个月收支表照常展示，支出为零；A 区与分类排行显示“本月暂无支出分类”，不得回退到更早的有支出月份。
- 不计收支账目始终排除在收入、支出、净额、支出分类和七个月收支之外。

## 非目标

- 不增加月份切换、日期范围选择、分类筛选或图表钻取。
- 不增加余额、预算、同比、环比、预测、账户维度或多币种换算。
- 不修改账目、账本、分类或资金账户的持久化模型，不新增 Room 迁移。
- 不新增后端接口、云端同步或 `shared/api` 契约。

## 目标文件或模块

- `apps/android/src/main/java/com/bks/feature/ledger/LedgerModels.kt`
- `apps/android/src/main/java/com/bks/feature/ledger/ReportsScreen.kt`
- `apps/android/src/test/java/com/bks/feature/ledger/LedgerReportQueriesTest.kt`
- `apps/android/src/test/java/com/bks/feature/ledger/LedgerReportsScreenTest.kt`
- `apps/android/src/test/java/com/bks/feature/ledger/LedgerModelsTest.kt`
- `apps/android/src/test/java/com/bks/MainActivityTest.kt`
- `docs/PRD.md`
- `docs/UI-DESIGN.md`

## 验收标准

### 查询与统计

- [x] `x` 是当前账本中最新的有效收入或支出月份；其他账本、最近删除记录和不计收支账目均不影响锚点。
- [x] 月份 `x` 的总收入、总支出和现有净额语义与流入、流出、不计收支规则一致。
- [x] A 区按月份 `x` 的支出金额降序展示前四类，并把第五类起的金额与占比准确合并到“其他”。
- [x] B 区始终返回 `[x-3, x+3]` 七个连续月份，每月分别给出总支出和总收入，缺失月份补零且跨年顺序正确。

### 界面与状态

- [x] 报表首页先显示月份 `x` 的收支概览，再显示 A 环形图和 B 七个月收支表，不再出现分类选择或六个月分类趋势。
- [x] A 区使用 Compose `Canvas` 呈现手绘感环形图；图例通过分类名称与占比建立非颜色唯一的对应关系，中心显示总支出，分类排行只读显示名称与金额。
- [x] 当前账本无有效收支时只显示账本级空状态，不显示伪造月份、全零概览或报表内容。
- [x] 仅有收入时仍建立 `x` 并显示收入与七个月收支，支出为零，A 区与分类排行显示“本月暂无支出分类”。
- [x] 切换当前账本后，月份锚点、概览、环形图和七个月收支表全部切换到新账本的数据，进程重启后作用域保持。

## 验收测试

- [x] `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.bks.feature.ledger.LedgerReportQueriesTest"`
- [x] `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.bks.feature.ledger.LedgerReportsScreenTest"`
- [x] `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.bks.feature.ledger.LedgerModelsTest"`
- [x] `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.bks.MainActivityTest"`
- [x] `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.bks.feature.ledger.*"`
- [x] `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest`
- [x] `.\gradlew.bat --no-daemon :apps:android:assembleDebug :apps:android:assembleRelease`
- [x] `apksigner verify --verbose apps/android/build/outputs/apk/release/android-release.apk`
- [x] `git diff --check`

自动化测试至少覆盖：当前账本隔离、`x` 对收入/支出/不计收支的选择、四类及以下与五类及以上的“其他”合并、七个月窗口跨年与缺月补零、无数据、仅收入，以及界面不再提供分类选择。

## 手工验证

1. 在两个账本分别准备不同月份的脱敏收支，来回切换并确认 `x`、概览、A 和 B 均只使用当前账本。
2. 在月份 `x` 准备至少五个支出分类，确认环形图为手绘感、前四类与“其他”金额及占比正确，并且不依赖颜色也能对应。
3. 准备跨年的七个月脱敏数据，确认 `[x-3, x+3]` 月份顺序、收入/支出列和缺月零值正确。
4. 分别验证空账本、只有不计收支账目、仅有收入和四类及以下支出，确认各自空状态与“其他”规则正确。
5. 在常用屏幕尺寸和系统字体缩放下检查月份、图例、金额和占比可读，环形图与收支表不遮挡或截断关键信息。

## 回滚或安全说明

- 本任务只调整报表查询聚合与展示，不修改持久化 schema 或原始账目；回滚可恢复旧报表模型与界面，账目数据不需要迁移。
- 图表聚合必须继续使用现有活动账目流，不得为绘图复制或改写持久化数据。
- 自动化测试、截图和日志只使用合成或脱敏数据，不输出真实金额、商户、备注或采集原文。

## 验证记录

- `2026-07-16`：完成 `LedgerReportQueriesTest`、`LedgerModelsTest`、`LedgerReportsScreenTest`、`MainActivityTest` 和 `com.bks.feature.ledger.*` 专项回归。
- `2026-07-16`：Android 全量 `testDebugUnitTest` 最终通过，共 326 项；首次全量运行遇到既有无障碍服务健康测试的偶发时序失败，该测试单独复跑通过，随后全量干净通过。
- `2026-07-16`：Debug 与 Release APK 构建成功；Release 通过 APK Signature Scheme v2 校验，签名者数量为 1。
- `2026-07-16`：在 Xiaomi `24117RK2CC` 的独立 `com.bks.debug` 包中注入脱敏合成数据并完成视觉验收。环形图、前四类加“其他”、只读排行、`2026-04..2026-10` 七行收支、未来空月补零及 `2026-07` 高亮均正确；验收后已执行 `pm clear com.bks.debug`，正式版 `com.bks` 未改动。
- `2026-07-16`：`git diff --check` 退出码为 0；仅出现工作区既有 CRLF→LF 行尾提示，无空白错误。

## 依赖

- Issue 17：提供流入、流出、不计收支及持久化报表语义。
- Issue 23：提供当前账本作用域以及报表查询隔离。
