# 性能测试源码与配置索引

以下文件是本次审计的可执行测试与数据准备入口。它们保留在正确的模块目录，本文件只提供稳定索引。

## Macrobenchmark 与 Baseline Profile

- [`CriticalUserJourneysBenchmark.kt`](../../benchmarks/macrobenchmark/src/main/java/com/autoaccounting/macrobenchmark/CriticalUserJourneysBenchmark.kt)：冷启动、账本滚动与详情、自动记账设置三条路径；每条路径同时提供 `CompilationMode.None()` 与 `BaselineProfileMode.Require` 对照。
- [`BenchmarkApp.kt`](../../benchmarks/macrobenchmark/src/main/java/com/autoaccounting/macrobenchmark/BenchmarkApp.kt)：设备操作、固定方向、数据重置与路径等待；当前每组执行 10 次。
- [`BaselineProfileGenerator.kt`](../../benchmarks/macrobenchmark/src/main/java/com/autoaccounting/macrobenchmark/BaselineProfileGenerator.kt)：Baseline Profile 生成入口。
- [`LargeDatasetReportsBenchmark.kt`](../../benchmarks/macrobenchmark/src/main/java/com/autoaccounting/macrobenchmark/LargeDatasetReportsBenchmark.kt)：1k/10k 合成账目下的报表进入路径；各规模执行 5 次。
- [`BenchmarkDataProvider.kt`](../../apps/android/src/benchmark/java/com/autoaccounting/benchmark/BenchmarkDataProvider.kt)：独立 benchmark 包的固定 40、1k 与 10k 测试数据。
- [`benchmarks/macrobenchmark/build.gradle.kts`](../../benchmarks/macrobenchmark/build.gradle.kts)：Macrobenchmark 测试模块配置。
- [`apps/android/build.gradle.kts`](../../apps/android/build.gradle.kts)：benchmark/non-minified 变体及 Baseline Profile 接线。

## PERF-A03 回归测试

- [`AndroidDiagnosticLogRepositoryTest.kt`](../../apps/android/src/test/java/com/autoaccounting/feature/diagnostics/AndroidDiagnosticLogRepositoryTest.kt)：验证构造/开关不解密历史、显式刷新、线程与日志语义。
- [`DiagnosticEncryptedStoreTest.kt`](../../apps/android/src/test/java/com/autoaccounting/feature/diagnostics/DiagnosticEncryptedStoreTest.kt)：验证最新窗口读取、解密次数、轮转与密钥丢失行为。

## 证据入口

- 批次 8 报表规模验证：[`evidence/device-results-20260720-batch8/README.md`](./evidence/device-results-20260720-batch8/README.md)，含 1k/10k 聚合 JSON 和最差迭代 SQL scratchpad。
- 批次 7 账本数据流与报表派生回归：[`evidence/device-results-20260720-batch7/README.md`](./evidence/device-results-20260720-batch7/README.md)，含 7/7 instrumentation、60 个 Trace 的聚合 JSON 和代表性 SQL scratchpad。
- 批次 6 Baseline Profile 对照：[`evidence/device-results-20260720-batch6/README.md`](./evidence/device-results-20260720-batch6/README.md)，含 6 组 × 10 次结果、60 份逐 Trace scratchpad 和聚合 JSON。
- 最终 benchmark JSON：[`evidence/device-results-20260719-batch4-final-ok-2333/com.autoaccounting.macrobenchmark/com.autoaccounting.macrobenchmark-benchmarkData.json`](./evidence/device-results-20260719-batch4-final-ok-2333/com.autoaccounting.macrobenchmark/com.autoaccounting.macrobenchmark-benchmarkData.json)
- 修复后生产 Trace 分析：[`aa-batch4-production-patched-20260720-01.perfetto-trace_analysis.md`](./evidence/device-results-20260720-production-validated/aa-batch4-production-patched-20260720-01.perfetto-trace_analysis.md)
- Perfetto 配置：[`aa-perf-config.pbtxt`](./evidence/aa-perf-config.pbtxt)
