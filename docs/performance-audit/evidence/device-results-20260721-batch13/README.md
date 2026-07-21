# 批次 13：冷启动、主页路由与 RenderThread 长尾补测

- 真机：`2a9ea4bd`（Redmi 24117RK2CC，Android 16，1080×2400 覆盖分辨率、60 Hz 应用模式）。
- 隔离范围：仅安装、清理和采集 `com.autoaccounting.benchmark`；未清理、卸载、覆盖或修改生产 `com.autoaccounting` 数据、权限和安装。
- 命令：`.\gradlew.bat :benchmarks:macrobenchmark:connectedBenchmarkReleaseAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.autoaccounting.macrobenchmark.CriticalUserJourneysBenchmark#coldStartup,com.autoaccounting.macrobenchmark.CriticalUserJourneysBenchmark#coldStartupBaselineProfile,com.autoaccounting.macrobenchmark.CriticalUserJourneysBenchmark#automaticBookkeepingSettings,com.autoaccounting.macrobenchmark.CriticalUserJourneysBenchmark#automaticBookkeepingSettingsBaselineProfile" --console=plain`。
- 结果：4/4 instrumentation 通过；四个用例各 10 次，共 40 条 Trace。

## 聚合结果

- 冷启动 None/Profile 的 `StartupTimingMetric` 中位数为 `260.308/244.419 ms`，Profile 低 `6.10%`；最大值为 `335.149/311.236 ms`。
- 主页 → 我的 → 自动记账 None/Profile 的 frame CPU P99 为 `48.124/41.302 ms`，frame overrun P99 为 `52.228/37.947 ms`。None/Profile 分别有 `141/51` 个 deadline miss（总帧数 `693/730`）。
- 冷启动代表 Trace 的 intent→first-frame 为 None `338.444 ms`、Profile `314.718 ms`。None 代表样本有 `100.048 ms` 主线程 uninterruptible I/O sleep；Profile 代表样本仅 `26.762 ms`，但同时受 `installd` 运行和 `activityResume` 长尾干扰。

## RenderThread 结论

- 主页路由 None/Profile 代表帧均在 `Drawing 0 0 1080 2400` 内出现约 `32 ms` 全屏绘制；`flush commands` 的约 `30 ms` 主要是 `shader_compile → ShaderCache::cache_miss → driver_compile_shader/driver_link_program`。
- 已复核的账本详情转场也有相同类型的 GPU shader cache miss：`27.606 ms` RenderThread 绘制内包含 `14.933 ms` 和 `8.133 ms` 两次 shader compile。
- 上述 RenderThread 在长尾区间主要为 Running，不是 Binder、锁、磁盘 I/O 或 GPU completion 等待。Baseline Profile 降低 UI 线程组合/测量，但不会预热 GPU shader cache。

## 限制

- 每条 Trace 都有 `power_rail_empty_packet`，不能据此得出功耗结论。
- 这是隔离 benchmark 包的冷进程/首次路由测量，不替代生产包真实 cold startup；生产包的 NotificationListenerService 会自行恢复进程。
- Perfetto 无法把 `CircleOp`、`CircularRRectOp` 等 Skia op 精确映射到单一 Kotlin Composable，因此不能把 shader miss 归因到某一行源码。
