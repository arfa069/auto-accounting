# PERF-A05 被拒绝的实验方案

## 直接切换 Card 颜色

- 做法：取消 ripple，通过 `collectIsPressedAsState()` 在按压时切换 `CardDefaults.cardColors`。
- 结果：`CircleOp` 在 10/10 Trace 中消失，但最大 `Recomposer:recompose` 为 66.070–100.254 ms，最大 `AndroidOwner:measureAndLayout` 为 41.429–63.476 ms。
- 决定：拒绝。按压状态进入组合阶段，把 RenderThread shader 成本转移成主线程重组和测量成本。

## graphicsLayer alpha

- 做法：按压时通过 `graphicsLayer` 和 `CompositingStrategy.ModulateAlpha` 调整整张卡片透明度。
- 结果：`CircleOp` 消失，但实现仍依赖 Composable `State`；连续 Trace 中页面重组波动较大，无法把按压状态与导航重组的边界完全分离。
- 决定：拒绝。最终改用 `IndicationNodeFactory`，节点直接收集 Press/Release，仅调用 `invalidateDraw()`，不创建按压 Compose State。
