# 批次 8：1k/10k 报表规模验证

- 设备：Xiaomi 24117RK2CC，Android 16，1080×2400。
- 目标包：`com.autoaccounting.benchmark`；测试包：`com.autoaccounting.macrobenchmark`。生产包未覆盖、未清数据。
- 测试：`LargeDatasetReportsBenchmark`，1k 和 10k 合成账目各 5 次，`CompilationMode.Partial(BaselineProfileMode.Require)`。
- 1k：frame CPU P50/P90/P95/P99 为 3.761/14.297/20.173/56.011 ms；overrun 为 -4.439/4.404/12.457/43.722 ms。
- 10k：frame CPU P50/P90/P95/P99 为 8.589/13.097/17.039/55.663 ms；overrun 为 3.731/5.005/15.065/44.744 ms。
- 两份 scratchpad 是最差迭代的 Perfetto SQL 证据。`android_jank` metric 在本机 trace_processor 不可用，采用 Macrobenchmark FrameTimingMetric 作为高层帧指标。
- 原始 10 份 Trace 位于仓库外的 `C:\Users\Arfa\AppData\Local\Temp\auto-accounting-perf-audit-20260719\device-results-20260720-batch8`；不复制到仓库。
