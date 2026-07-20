# 批次 11 R8 与 AGP 9 验证证据

- 目标：验证 Release R8/资源压缩、AGP 9 built-in Kotlin 迁移，以及 AndroidX Benchmark/Baseline Profile `1.5.0-alpha07`。
- 真机：`2a9ea4bd`；仅安装和清理隔离 `com.autoaccounting.benchmark` 与 Macrobenchmark 测试包，未覆盖生产 `com.autoaccounting`。
- 测试：`CriticalUserJourneysBenchmark` 的 6 个用例全部通过；三条路径各执行 None/Profile 对照，每个用例 10 次，共 60 条 Trace。
- 聚合结果：`com.autoaccounting.macrobenchmark-benchmarkData.json`。
  - 冷启动 TtID None/Profile 中位数：250.225 / 237.486 ms。
  - 账本 None/Profile frame CPU P99：65.059 / 32.706 ms；overrun P99：73.184 / 23.078 ms。
  - 自动记账 None/Profile frame CPU P99：65.019 / 39.722 ms；overrun P99：74.873 / 37.148 ms。
- Release APK：R8 前 103,493,619 bytes；R8 后 80,529,815 bytes；减少 22,963,804 bytes（21.90 MiB，22.19%）。Release、benchmark 与 Macrobenchmark APK 均通过 v2 签名校验；未生成 `missing_rules.txt`。
- 限制：该验证覆盖 R8 的隔离 benchmark 包，不替代真实生产账号、通知、无障碍/OCR、Room 历史数据或用户生产安装包的端到端回归。
