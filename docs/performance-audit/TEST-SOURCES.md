# 性能测试源码与配置索引

以下文件是本次审计的可执行测试与数据准备入口。它们保留在正确的模块目录，本文件只提供稳定索引。

## Macrobenchmark 与 Baseline Profile

- [`CriticalUserJourneysBenchmark.kt`](../../benchmarks/macrobenchmark/src/main/java/com/bks/macrobenchmark/CriticalUserJourneysBenchmark.kt)：冷启动、账本滚动与详情、自动记账设置，以及自动记账同进程首次/第二次导航对照；每条路径同时提供 `CompilationMode.None()` 与 `BaselineProfileMode.Require` 对照。
- [`BenchmarkApp.kt`](../../benchmarks/macrobenchmark/src/main/java/com/bks/macrobenchmark/BenchmarkApp.kt)：设备操作、固定方向、数据重置、路径等待及从自动记账返回主页；当前每组执行 10 次。
- [`BaselineProfileGenerator.kt`](../../benchmarks/macrobenchmark/src/main/java/com/bks/macrobenchmark/BaselineProfileGenerator.kt)：Baseline Profile 生成入口。
- [`LargeDatasetReportsBenchmark.kt`](../../benchmarks/macrobenchmark/src/main/java/com/bks/macrobenchmark/LargeDatasetReportsBenchmark.kt)：1k/10k 合成账目下的报表进入路径；各规模执行 5 次。
- [`BenchmarkDataProvider.kt`](../../apps/android/src/benchmark/java/com/bks/benchmark/BenchmarkDataProvider.kt)：独立 benchmark 包的固定 40、1k 与 10k 测试数据。
- [`benchmarks/macrobenchmark/build.gradle.kts`](../../benchmarks/macrobenchmark/build.gradle.kts)：Macrobenchmark 测试模块配置。
- [`apps/android/build.gradle.kts`](../../apps/android/build.gradle.kts)：benchmark/non-minified 变体及 Baseline Profile 接线。

## PERF-A03 回归测试

- [`AndroidDiagnosticLogRepositoryTest.kt`](../../apps/android/src/test/java/com/bks/feature/diagnostics/AndroidDiagnosticLogRepositoryTest.kt)：验证构造/开关不解密历史、显式刷新、线程与日志语义。
- [`DiagnosticEncryptedStoreTest.kt`](../../apps/android/src/test/java/com/bks/feature/diagnostics/DiagnosticEncryptedStoreTest.kt)：验证最新窗口读取、解密次数、轮转与密钥丢失行为。

## 本机证据

完整测量结论、Trace 名称、SQL 分析摘要和复现命令集中记录在 [性能审计报告](./performance-audit.md)。

原始 Perfetto Trace、Macrobenchmark JSON、SQL/Trace scratchpad、截图和每批次说明保留在开发机的 `docs/performance-audit/evidence/`。该目录自 2026-07-21 起由 Git 忽略，不会随仓库克隆或提交传递；需要复核原始证据时，应向执行该批次的开发机索取副本。
