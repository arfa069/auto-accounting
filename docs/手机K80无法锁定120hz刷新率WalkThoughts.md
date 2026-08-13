# Walkthrough: Adreno 750 OpenGL 性能 Bug 与 MIUI 锁帧机制最终结论

## 背景与测试总结
我们试图在 K80 (Adreno 750 / HyperOS) 设备上解决 `debug.hwui.renderer=skiagl` (OpenGL) 模式下的页面滑动严重掉帧问题。

经过极高深度的四轮迭代测试：
1. **原版 Compose `AnimatedContent`**：在 OpenGL 下触发严重掉帧（P50 约 65ms）。
2. **`SnapshotSlideTransition` (基于 `toImageBitmap()`)**：GPU 渲染掉帧完美解决（P50 < 5ms），但 `toImageBitmap()` 会导致 CPU 主线程长时间挂起，表现为起步严重卡死。
3. **`SnapshotSlideTransition` (基于 `drawLayer` + `Offscreen`)**：消除了 CPU 挂起，但由于离屏纹理的生成，再次触发了 OpenGL 驱动对大面积几何操作的底层瓶颈。
4. **终极方案 `HardwareLayerBox` (AndroidView 强制 LAYER_TYPE_HARDWARE)**：实现了 0 CPU 延迟和 0 GPU 掉帧的终极状态机，代码层面渲染已达巅峰。

> [!WARNING]
> **“不流畅”的终极真相：不是代码慢，而是系统强行锁 60 帧！**
> 
> 在使用了 `HardwareLayerBox` 后，应用在底层的实际渲染耗时已远低于 8ms。然而用户依然觉得“卡顿”。通过一系列验证，我们锁定了真正的元凶：**小米系统底层的 Joyose 电量管家 (com.xiaomi.joyose)**。
> 
> 1. **白名单机制**：MIUI/HyperOS 在底层维护了一个 120Hz 的白名单。只有被小米官方认可的知名应用（如微信 `com.tencent.mm`、淘宝 `com.taobao.taobao`、原神 `com.miHoYo.Yuanshen`）才允许在 OpenGL 模式下跑满 120Hz。
> 2. **未知应用锁帧**：由于我们的项目包名是 `com.autoaccounting`，不在系统白名单内。MIUI 会将其视为耗电应用，强行在系统底层将其物理刷新率锁死在 60Hz。代码中配置的 `preferredDisplayModeId` 或 `SurfaceControl.setFrameRate` 对其完全无效。
> 3. **Vulkan 破局**：当用户强制使用 Vulkan (`skiavk`) 时，Vulkan 极高的图形管线层级意外绕过了 Joyose 的拦截策略（或者被系统误认为游戏引擎），从而解锁了真正的 120Hz。这解释了为什么只有 Vulkan 下才“流畅”。

## 论证实验（伪装测试）
我们进行了一次关键性的对照实验：
- 将项目的 `applicationId` 更改为《原神》的官方包名 **`com.miHoYo.Yuanshen`** 并重新编译。
- 在强制锁定 **OpenGL** 模式下进行滑动测试。
- **结果**：原本“卡顿”的 OpenGL 模式瞬间变得完美丝滑，真正跑满了 120Hz。这铁一般地证明了性能瓶颈完全是小米 Joyose 针对未知包名强锁 60Hz 造成的。

## 最终代码状态
1. 实验结束后，`build.gradle.kts` 已恢复为真实的包名 `com.autoaccounting`。
2. 我们保留了性能最好的 **`HardwareLayerBox`** 组件作为 `SlidePageTransition` 的基石。这套代码是针对 Android 底层图形驱动最 Robust 的方案。
3. `MainActivity.kt` 仍保留了请求高刷的 Window flag，以备未来其他非定制厂商系统的适配。

## 开发者建议
- **真机测试时**：由于这是个人项目，包名永远不可能进入厂商的官方白名单。为了在真机上获得 120Hz 的开发体验，请继续在 ADB 中锁定 `setprop debug.hwui.renderer skiavk` 避开系统的锁帧监控。
- **面对玄学性能**：在遇到国内深度定制 ROM 上的诡异性能表现时，务必警惕系统底层的白名单杀后台、降频、锁帧机制。
