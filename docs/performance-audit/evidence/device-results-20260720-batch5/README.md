# 批次 5：账目点击反馈验证

本目录保存 `PERF-A05` 修复后的脱敏可版本化证据。

- `normal-120hz-run1-*`：第一组以 120 Hz 为主的 5 次正式 Trace 分析及 Benchmark JSON。
- `normal-120hz-run2-*`：设备冷却并恢复 120 Hz 后补采的 5 次正式 Trace 分析及 Benchmark JSON。
- `system-60hz-limit-*`：系统 `PRIORITY_THERMAL_LIMIT_REFRESH_RATE` 将刷新率限制为 60 Hz 时的 5 次压力样本；仅用于确认 `CircleOp` 未回归，不与 120 Hz 基线比较帧时序。
- [被拒绝的实验方案](./rejected-experiments.md)：记录直接切换卡片颜色和 graphicsLayer 方案未被采用的原因。

15/15 Trace 的 `CircleOp → shader_compile` 计数均为 0，GC 均为 0。解锁后再次确认按压覆盖层、圆角裁剪和释放后详情导航均正常，应用数据 inode 未变化。原始 Perfetto Trace 与按压截图仍保存在仓库外临时目录，未进入 Git。
