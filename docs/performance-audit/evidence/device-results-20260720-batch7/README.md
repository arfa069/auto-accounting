# 批次 7 账本数据流与报表派生回归证据

本目录保存批次 7 的可版本化证据：1 份聚合 Macrobenchmark JSON 和 6 份代表性 Perfetto scratchpad。原始 60 个 Trace、生成 profile 与临时脚本未进入仓库，保存在：

`C:\Users\Arfa\AppData\Local\Temp\auto-accounting-perf-audit-20260719\device-results-20260720-batch7`

## 测试矩阵

- 设备：Redmi/Xiaomi `24117RK2CC`，Android 16，序列号 `2a9ea4bd`。
- 测试：Baseline Profile Generator 加 6 个 Macrobenchmark 用例；冷启动、账本滚动→详情、我的→自动记账各执行 10 次 None/Profile 对照。
- Instrumentation：`OK (7 tests)`，共 60 个 Perfetto Trace。
- 生产包未覆盖或清除数据；测试前后 `com.autoaccounting` 的 CE/DE inode 均为 `1648470` / `1833109`。

## 聚合结果

- 冷启动 TtID 中位数：None 422.501 ms，Profile 406.404 ms。
- 账本 frame CPU P99：None 146.987 ms，Profile 46.362 ms；overrun P99：143.571 ms / 36.104 ms。
- 自动记账 frame CPU P99：None 117.752 ms，Profile 37.241 ms；overrun P99：111.723 ms / 27.908 ms。

这些是同一批次的 None/Profile 对照，不应直接和历史不同批次的 P99 相减来宣称代码优化收益。代表性 None Trace 的长帧仍由主线程 Compose 布局/重组、JIT 与 GC 主导；Profile 代表样本显著减少 JIT，且无超过 10 ms 的主线程 slice。

## 代码验证范围

- `LocalLedgerRepository.state` 使用当前账本的 active/deleted scoped Flow，并把账本统计交给 Room 聚合查询。
- Room v7 将单列 `ledger_book_id` 索引替换为 `(ledger_book_id, deleted_at_epoch_millis, transaction_time_epoch_millis)`；迁移测试用 `EXPLAIN QUERY PLAN` 确认活动账本查询使用该索引。
- `LedgerReportUiModel` 只在账目输入变化时构建，避免报表页面的重复 `groupBy`、`sumOf` 和现金流派生。

现有 Macrobenchmark 没有报表专用路径，也没有 1k/10k 合成数据规模；该部分仍是后续验证缺口。
