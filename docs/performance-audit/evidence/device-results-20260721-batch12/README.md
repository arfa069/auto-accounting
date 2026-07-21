# 批次 12 账本转场性能证据

- 改动：账本内部 `SlidePageTransition` 保留新页滑入，取消旧页离场动画；全局主页、账户、报表转场不变。
- 真机：`2a9ea4bd`；仅运行隔离 `com.autoaccounting.benchmark`，未覆盖生产包。
- 回归：`ledgerScrollAndDetail` 与 `ledgerScrollAndDetailBaselineProfile` 各 10 次，2/2 通过。
- None frame CPU P99：65.06 → 32.40 ms；frame overrun P99：73.18 → 24.57 ms。
- Baseline Profile frame CPU P99：32.71 → 25.27 ms；frame overrun P99：23.08 → 14.11 ms。
- 代表 Trace SQL：最长 `Recomposer:recompose` 47.023 → 9.514 ms；主线程 Running 805.435 → 444.681 ms；D-state 0.018 → 0.108 ms。RenderThread 仍有 27.606 ms 全屏绘制长尾。
- 限制：本批只验证账本滚动→详情；冷启动、主页→其他路由、真实生产账号与生产包安装仍未验证。Trace 仍有 `power_rail_empty_packet`。
