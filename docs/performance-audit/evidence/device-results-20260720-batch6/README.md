# 批次 6 Baseline Profile 对照证据

本目录保存批次 6 的脱敏、可版本化证据：60 份逐 Trace 分析 scratchpad 和 1 份 Macrobenchmark JSON。原始 60 个 Perfetto Trace 未进入仓库，仍保存在：

`C:\Users\Arfa\AppData\Local\Temp\auto-accounting-perf-audit-20260719\device-results-20260720-batch6-profile-comparison`

## 测试矩阵

- 设备：Redmi/Xiaomi `24117RK2CC`，Android 16，序列号 `2a9ea4bd`。
- 三条关键路径：冷启动、账本滚动→详情、我的→自动记账。
- 每条路径分别执行 10 次 `CompilationMode.None()` 和 10 次 `CompilationMode.Partial(BaselineProfileMode.Require)`，共 60 次。
- Instrumentation 结果：`OK (6 tests)`。

## 结果摘要

- 冷启动 TtID 中位数：415.372 ms → 420.970 ms，变化 +1.3%；未测得启动收益。
- 账本 frame CPU P99：113.690 ms → 36.830 ms，下降 67.6%；overrun P99：114.414 ms → 31.323 ms，下降 72.6%。
- 自动记账 frame CPU P99：84.781 ms → 51.121 ms，下降 39.7%；overrun P99：78.573 ms → 42.805 ms，下降 45.5%。
- 账本 JIT count 中位数：110 → 8；JIT duration 中位数：128.779 ms → 13.424 ms。
- 自动记账 JIT count 中位数：70.5 → 1；JIT duration 中位数：74.971 ms → 0.853 ms。
- 冷启动 JIT count 中位数：9.5 → 7；JIT duration 中位数：10.114 ms → 1.597 ms，但主线程 CPU 与 D-state I/O 仍主导 TtID。

## 结论与限制

Baseline Profile 已确认降低两条交互路径的 JIT 和长帧成本，但没有证明冷启动改善。它不解决 shader 编译、后台 bitmap decode、冷启动内核 I/O、GC 或根级 Compose 工作；这些问题仍按各自编号跟踪。

每份 `_analysis.md` 均包含对应 Trace 的 JIT count/duration、最大 recompose/measure、GC 和线程状态证据。`benchmarkData.json` 是 6 组 Macrobenchmark 的聚合结果。
