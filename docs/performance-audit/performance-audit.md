# Android 项目系统性能审计报告

审计日期：2026-07-19 至 2026-07-21
仓库：`C:\Users\Arfa\Documents\auto-accounting`
Android 模块：`apps/android`
审计阶段：基线调查、补测、报告及批次 1–12 的代码修复与隔离验证已完成；真实支付无障碍事件与长时功耗仍未验证。批次 11 启用 Release R8/资源压缩，将 AndroidX Benchmark/Baseline Profile 升至 `1.5.0-alpha07`，并完成 AGP 9 built-in Kotlin、新 DSL 与 legacy KAPT 的最小迁移。批次 11 在独立 `com.autoaccounting.benchmark` 包执行 6 个 Macrobenchmark 用例、60 条 Trace；未覆盖、清除或修改生产 `com.autoaccounting` 数据与权限。批次 1–3 已提交为 `9f4e8b5`，批次 4 为 `8e497fb`，批次 5 为 `a526443`，批次 6 为 `ecda05d`，批次 7 为 `4ef5204`，批次 8 为 `7ce023c`，批次 9 为 `6ca00b2`，批次 10 为 `663ab7e`，批次 11 为 `37f40ba`。未改写 Git 历史，未 push。

## 证据等级

- 当前批次状态：批次 11 已提交为 `37f40ba`；批次 12 已完成代码与隔离验证，尚未提交。批次 12 仅验证账本内部转场，`PERF-A02` 的冷启动与其他路由仍待补测。

- A：已通过真机、Perfetto、ADB 或本轮构建命令确认。
- B：静态代码中高度可信的风险；当前样本未覆盖触发条件或数据规模。
- C：需要补充数据才能确认；报告明确说明缺少的数据源。

## 1. 项目性能概览

- Application ID：Release `com.autoaccounting`，Debug `com.autoaccounting.debug`，独立性能目标包 `com.autoaccounting.benchmark`；Macrobenchmark 测试包 `com.autoaccounting.macrobenchmark`。
- SDK：minSdk 29，targetSdk/compileSdk 36。
- 工具链：AGP 9.0.1、Kotlin 2.3.20、Java 17、Compose BOM 2024.12.01、Room 2.8.4。
- R8：9.0.32；因 Release `isMinifyEnabled = false`，本项目未实际执行 R8 shrinking/optimization/obfuscation。
- 设备：Xiaomi 24117RK2CC，Android API 36，逻辑分辨率 1080×2400，serial `2a9ea4bd`。
- 初始 Git 状态：`master...origin/master`，无已有 diff；审计期间未覆盖用户工作。
- 测试设施：原有 66 个本地测试文件、1 个 Room 真机测试、JUnit 4/Robolectric/Compose UI Test/Room Testing/JaCoCo；第二批新增独立 `benchmarks/macrobenchmark` 模块，覆盖冷启动、账本滚动→详情、我的→自动记账及 Baseline Profile 生成。
- 第二批产物：三条路径各 5 次，共 15 个正式 Perfetto Trace；1 份 Macrobenchmark JSON；Baseline Profile Generator 成功输出 20,017 行 profile。第三、第四、第五批分别完成对应回归。批次 6 重新生成 20,143 行、2,149,642 bytes 的生产 source profile，并完成三条路径各 10 次 None/Profile 对照，共 60 个 Trace、60 份逐 Trace scratchpad 和 1 份 JSON。批次 7 完成同设备 7/7 instrumentation（Generator 加 6 个用例），另生成 60 个 Trace、1 份 JSON 和 6 份代表性 SQL scratchpad。批次 8 重新生成 20,000 行、2,143,362 bytes 的 source profile（1,798 条项目规则、0 条 benchmark-only 规则），完成 1k/10k 报表路径各 5 次、共 10 个 Trace、1 份 JSON 和 2 份代表性 SQL scratchpad。批次 9 新增 12 个隔离环境 Trace 与 12 份逐 Trace evidence scratchpad。批次 10 新增 3 条有效隔离策略 Trace 与 3 份逐 Trace evidence scratchpad，路径为 `%LOCALAPPDATA%\Temp\auto-accounting-perf-audit-20260719\device-results-20260720-batch10`。项目仍没有截图测试或完整通用 E2E 套件。
- 总体结论：第三批消除目标主线程 bitmap decode；第四批消除诊断历史启动读取和 Keystore Binder 风暴；第五批消除账本点击 `CircleOp → shader_compile`。批次 6 已使生产 Release 包含关键路径 Profile 规则。批次 7 消除根 UI 的全库账目订阅、把账本统计下推到 Room、以 v7 复合索引支持 scoped 查询，并让报表只消费预计算模型。批次 8 证实 1k/10k 报表进入的长尾均主要是主线程组合、布局和绘制，而非数据量线性放大。批次 9 证实会话恢复可在隔离登录态进入主页、受控请求可在约 206 ms 取消、1k/10k CSV 与加密备份重活位于后台线程。批次 10 已使非手动、非支付相关事件在读取根节点前退出，并将连续自动记账相同窗口事件合并；隔离 1,000 事件 admission 的 trace section 为 0.336–0.406 ms，2 分钟受控心跳仅持久化 3 次。该结论不代表真实微信/支付宝节点树、遗漏率或功耗已验证。`PERF-A02` 仍未关闭；R8 未启用及 103.49 MB Release APK 仍是高优先级问题。
- 批次 12 补充结论：账本内部转场取消旧页离场动画后，None/Profile frame CPU P99 为 32.40/25.27 ms，overrun P99 为 24.57/14.11 ms；代表 Trace 的 `Recomposer:recompose` 从 47.023 ms 降至 9.514 ms。RenderThread 全屏绘制仍有 27.606 ms 长尾，冷启动和其他路由尚未验证，因此 `PERF-A02` 保持待修复。

## 2. 启动性能结果

### PERF-A02 — High — 启动首帧根布局与组合过重

- 证据等级：A。
- 类别：启动、Compose、主线程。
- 文件与行号：`apps/android/src/main/java/com/autoaccounting/MainActivity.kt:390-410,616,878`；`data/local/LocalLedgerRepository.kt:32-74`；`feature/ledger/LedgerScreen.kt:104-164,257-309`。
- 用户流程：应用启动到主页稳定。
- 静态证据：第三批已用 `remember` 缓存 tab/底部导航、当前账本列表、删除列表、账本统计、权限健康状态和根路由；账本页也缓存选中项、页面状态、编辑初始值、月份、汇总及筛选结果。批次 7 将账本、当前账本 active/deleted entries、分类和资金账户的五个根级 collector 合并为 `LocalLedgerRepository.state`，其账目只查询当前账本；账本统计改为 Room 聚合。根页面仍承担跨页面状态和转场组合，因此这不是完整根布局拆分。
- Trace/SQL：生产包后台服务重启样本 `startup-force-stop-bg-restarted-01.perfetto-trace` 为 warm 324.684 ms。第三批最终五次 `android_startup` TTID 为 516.516、524.360、571.608、558.376、567.506 ms；intent→first-frame 为 443.864、425.877、497.068、466.157、478.806 ms。首帧前主线程 Running 236.197–250.085 ms、Runnable 4.038–6.572 ms、I/O D-state 34.308–113.333 ms；最长单段 I/O D-state 16.541 ms，Trace 缺少 `blocked_function` 和 `waker_utid`，无法继续归因具体内核函数/文件。代表首帧仍有约 146.590 ms `measure` 和 36.457 ms `Compose:recompose`；5 次均无 GC。
- 实际影响：首屏延迟主要消耗在应用主线程自身工作，而不是等 CPU；批次 8 的 1k/10k 报表路径未观察到数据量线性放大，但跨页面状态和转场仍会造成可见长帧。
- 实测确认：部分修复已确认；最终 Trace 仍确认首帧根布局/组合过重。现有 Trace 不能把各个 `remember` 的单独收益从样本波动中剥离，根派生计算对 `measure/recompose` 的精确占比仍需 Compose compiler metrics 或自定义 trace section。
- 修复建议：批次 7 已完成 scoped Flow、稳定报表模型和 Room 聚合/索引的低风险部分；批次 8 已排除 1k/10k 报表派生为当前主因。后续仅在基准确认仍有收益时再拆 Route 级状态订阅或引入 ViewModel。
- 修复风险：中到高，涉及状态刷新、导航返回和列表滚动语义。
- 验证方式：使用现有 `coldStartup` Macrobenchmark 各跑至少 10 次；输出 Compose stability/recomposition metrics；修复前后比较 TTID、首帧 `measure`、`Compose:recompose`，并补 `reportFullyDrawn()` 后再比较 TTFD。

### PERF-B05 — Medium — 组合阶段同步读取 SharedPreferences/Keystore（批次 9 已完成）

- 证据等级：A（隔离 benchmark 登录会话真机与 Trace）。
- 类别：启动、主线程 I/O。
- 文件与行号：`MainActivity.kt:351-362,411-432,624-635`；`feature/account/SecureAccountSessionStore.kt:28-55,105-131`。
- 用户流程：已登录账号的启动与账号状态恢复。
- 静态证据：修复前 `remember { secureAccountSessionStore.restore() }` 在组合线程同步执行。现改为受 `LaunchedEffect` 管理的 `Dispatchers.IO` 恢复；恢复期间显示 `account-session-restoring`，成功恢复后仍进入原有 `Validating` 链，损坏会话仍确认本地模式。
- Trace/SQL：独立 benchmark 包写入合成加密会话后强停并冷启动，3 次最终登录态 cold startup 为 165.720、171.965、232.814 ms，中位数 171.965 ms；每次均进入主页。启动主线程 Running 为 61.296–76.183 ms，D-state 0.236–2.369 ms 且 `io_wait=0`；每次观察到 2 帧、0 janky frame。最终启动窗主线程指向 keystore 进程的 Binder 事务均为 0；目标进程 max RSS 223.820–225.098 MiB，正持续时间 GC 为 0。
- 实际影响：修复后会话读取和 Keystore 解密不再发生在组合调用栈；隔离登录态启动没有观察到主线程 Keystore Binder 或帧卡顿。
- 实测确认：已确认隔离合成登录会话的恢复、主页到达、主线程状态、Binder、帧、RSS 与 GC。该验证不等同于真实生产账号、线上 token 校验或真实后端时延。
- 修复建议：代码与隔离环境验证已完成；只有在需要评估线上账号校验时再使用专用测试账号补采。
- 修复风险：中，恢复态 UI 可能影响登录/本地模式闪屏与请求时序；当前回归未发现状态机变化。
- 验证方式：回归保留合成登录会话 cold startup；线上环境另比较真实账号 token 校验与首屏稳定时间。

启动测量：

- 第二批独立 benchmark 冷启动 TtID 5/5：481.977、466.552、434.509、537.461、473.371 ms；median 473.371 ms。第三批最终 5/5：439.407、422.685、493.636、462.860、475.248 ms；min/median/max 为 422.685/462.860/493.636 ms。median 较第二批下降 10.511 ms（2.22%），仍只视为本设备五次样本改善，不能外推为稳定收益。
- 第四批最终 `CriticalUserJourneysBenchmark` 明确 `OK (3 tests)`；Macrobenchmark `StartupTimingMetric` 冷启动 5/5 为 439.483、447.554、395.046、393.245、383.913 ms，min/median/max 为 383.913/395.046/447.554 ms。该指标与 Perfetto `android_startup` TTID 仍是不同口径。
- 批次 6 同设备各 10 次对照：`CompilationMode.None()` TtID min/median/max 为 396.095/415.372/467.699 ms；`BaselineProfileMode.Require` 为 375.973/420.970/486.765 ms。中位数变化 +1.3%，未证明 Baseline Profile 改善冷启动；Trace 显示 Profile 样本 JIT duration 明显下降，但主线程 Running 与 D-state I/O 仍主导启动。
- 批次 7 同设备各 10 次对照：None/Profile TtID 中位数为 422.501/406.404 ms。Profile 代表 Trace 的主线程 `measure` 为 128.324 ms、Running 220.534 ms、D-state 72.543 ms；None 代表为 152.528/255.793/65.753 ms。两者都仍由首帧布局、主线程工作和 I/O 主导；不同批次间设备状态不可控，不把这一组数值宣称为 scoped Flow 的单独收益。
- 第四批代表性 cold Trace 的 `android_startup` metrics-first 结果为 TTID 470.118 ms、intent→first-frame 401.107 ms；首帧前主线程 Running 224.019 ms、Runnable 8.915 ms、uninterruptible I/O sleep 38.460 ms。该 Trace 含 `power_rail_empty_packet` 导入错误。
- Perfetto `android_startup` 的 TTID 口径为 516.516–571.608 ms，与 Macrobenchmark `StartupTimingMetric` 数值口径不同，报告不混算。五次首帧前主线程 Running 236.197–250.085 ms，Runnable 仅 4.038–6.572 ms；当前不是调度饥饿。
- 冷启动 I/O D-state 累计 34.308–113.333 ms，最长单段 16.541 ms；缺少 `blocked_function`/`waker_utid`，不能猜测具体文件、页错误或内核函数。五次均无 GC。
- 生产包强停后后台服务重启样本仍为 warm 324.684 ms；温启动 154.191 ms，热启动 67.681 ms。生产包真实 cold 仍因已授权的 `PaymentNotificationListenerService` 立即重启进程而无法在不改权限/组件的前提下测得；报告不以 benchmark 包数值冒充生产包 cold。
- 项目未调用 `reportFullyDrawn()`，所以没有可用 TTFD。

## 3. 帧与卡顿结果

### PERF-A01 — High — 全屏与部分装饰 PNG 在主线程解码（第三批安全候选已修复）

- 证据等级：A。
- 类别：Compose、图片、帧、内存。
- 文件与行号：`ui/visual/AppVisuals.kt:27-135`；`feature/profile/ProfileScreen.kt:116-124`；`ui/components/HomeReturnButton.kt:21-30`；`ui/components/PageTransitions.kt:14-34`。PNG 资源为二进制文件，无源码行号。
- 用户流程：启动、进入账本、详情切换、我的 → 自动记账。
- 静态证据：修复前页面背景、“我的”入口插图和返回主页图通过 `painterResource` 同步加载。修复后 `BitmapFactory.decodeResource` 在 `Dispatchers.IO` 执行，`produceState` 异步接收；全屏背景 LRU 上限 21 MiB，装饰图 LRU 上限 2 MiB，均按解码字节计费，不缓存 Context/Resources，淘汰时不主动 `recycle`。背景占位为 `#FEF8ED`，仍使用 `ContentScale.Crop`；300 ms `AnimatedContent` 的视觉与旧/新页面共存语义未改。
- Trace/SQL：
  - 原生产包账本样本最差帧 101.379 ms；主线程 `Compose:recompose` 74.622 ms，其中 `Decoding 1080x2400 bitmap` 55.933 ms，Running 98.980 ms、Runnable 0.170 ms。
  - benchmark 冷启动最差 Trace 的首帧中，1080×2400 解码 46.259 ms，切片内 Running 37.350 ms、I/O D-state 8.888 ms。
  - benchmark 自动记账最差 Trace 的两个独立大帧为 232.791 ms 和 161.770 ms，分别同步解码两张 1080×2400 PNG 111.182 ms 和 72.415 ms；最差帧 UI Running 226.306 ms、Runnable 0.061 ms、无 D-state。
  - 自动记账 Trace 的 Bitmap Memory 从 30.255 MiB 上升到 41.643 MiB；最长 Binder 0.271 ms，无 GC，排除 Binder/GC 为该帧主因。
  - 第三批最终自动记账 5/5 主线程 bitmap decode 为 0；后台 decode 最大值为 52.737、52.244、61.965、52.512、52.975 ms。五次最大帧仍为 143.130、90.279、67.176、66.448、121.146 ms，每次 2 个 >50 ms 帧；这些长帧由布局/首次 shader 等其他工作主导，不再归因于图片同步解码。
  - 最终 15 个 Trace 均无 GC。自动记账 RSS 峰值约 230.672–248.684 MiB、anonymous RSS 峰值约 94.637–99.297 MiB；账本 RSS 峰值约 238.125–241.156 MiB。当前只确认主线程解码消失，不宣称 RSS 稳定下降。
- 实际影响：修复前页面第一次进入或资源未缓存时出现明显掉帧；修复后目标图片解码不再阻塞 UI，页面会短暂显示占位并在本轮约 52–62 ms 后显示完整图片。剩余可见长帧来自 Compose/RenderThread，不应继续归因于这些 PNG 的同步解码。
- 实测确认：已确认；自动记账 5/5 无主线程 bitmap decode，账本 5/5 无全屏 decode，截图确认主页、我的、自动记账背景资源与裁剪正常。冷启动仍可见 4 个小图 `ImageDecoder_nDecodeBitmap` slice、合计约 12.793 ms，未证明属于本轮已迁移的装饰图，因此不把它误报为已消失。
- 修复建议：第三批安全候选已完成。若后续要求进一步降低转场 Bitmap 峰值，需要改变转场期间双页同时展示、图片像素格式或资源尺寸，均会影响视觉/转场，应作为独立视觉性能批次。
- 修复风险：当前实现风险低中；主要风险是低端设备短暂占位和进程级缓存驻留，已用 21 MiB 上限、应用 Resources 和不主动回收正在绘制的 Bitmap 控制。
- 验证方式：本轮已完成三条路径各 5 次、SQL 主线程/后台线程归因、RSS/GC 查询及截图。主线程目标图片解码验收通过；“全部 >50 ms 帧归零”和“RSS 峰值下降”不是本项完成条件。`PERF-A05` 已在第五批独立完成，当前剩余长帧继续归入 `PERF-A02`。

### PERF-A05 — High — 账本点击 ripple 离场触发 RenderThread CircleOp shader 首用（已完成）

- 证据等级：A。
- 类别：Compose、布局、RenderThread、GPU/JIT。
- 文件与行号：`feature/ledger/LedgerScreen.kt:469-549`；`ui/components/PageTransitions.kt:14-34`。shader 编译为运行时 RenderThread 证据，无直接 Kotlin 源码行。
- 用户流程：账本 → 滚动固定 40 条账目 → 打开详情。
- 静态证据：修复前账目行使用 Material 圆形 ripple；修复后改为 `IndicationNodeFactory` + `DrawModifierNode`，节点直接收集 Press/Release，仅在按压状态边界调用 `invalidateDraw()` 并绘制 8% `onSurface` 覆盖层，detach 时清空未完成按压。按压状态不进入 Compose State，不触发按压重组或测量；`Modifier.clickable` 的语义、点击回调和详情导航保持不变。
- Trace/SQL：修复前第三批最终账本 5/5 出现 33.767–56.824 ms 的 `CircleOp → shader_compile`。修复后两组正常态 120 Hz 样本和一组系统 60 Hz 限制压力样本共 15/15 次 `CircleOp → shader_compile = 0`，GC 15/15 为 0。剩余 shader 最大约 12–25 ms，父节点为 `AAStrokeRect`、`CircularRRectOp` 或 `FillRectOp`；最差 Trace 的主线程 `Recomposer:recompose` 为 99.482 ms、全程 Running，其中 `AndroidOwner:measureAndLayout` 为 66.655 ms，应用 D-state 合计仅 0.310 ms，已排除 ripple shader、I/O 和锁等待为该剩余长尾的根因。
- 实际影响：已消除首次点击账目离场时 33.767–56.824 ms 的圆形 ripple shader 编译停顿。仍可见的导航长尾属于既有根级重组和测量问题，继续归入 `PERF-A02`，不再归因于本项。
- 实测确认：已确认完成。最终节点方案 15/15 Trace 未复现 `CircleOp`；解锁前后均完成受控视觉与交互回归，按压时整张卡片出现轻微灰色反馈且被圆角正确裁剪，释放后进入对应“编辑账目”页。生产包 `ceDataInode=1648470`、`deDataInode=1833109` 保持不变，未清数据或卸载。
- 修复建议：批次 5 已完成，保留节点式 indication。不要改回依赖 `collectIsPressedAsState()` 的卡片颜色或 `graphicsLayer` 方案；前者在被拒绝实验中造成 66.070–100.254 ms 重组和 41.429–63.476 ms 测量。
- 修复风险：中。自定义 indication 改变了 Material 默认 ripple 的视觉形式，但保留 clickable 语义和导航行为；后续主题、圆角或卡片容器变化时需要复查覆盖层颜色与裁剪。
- 验证方式：已完成账本定向测试、整个 ledger 测试包、Android 全量单元测试、Benchmark/Release 构建、Release Lint、APK v2 签名验证、15 次 Trace SQL 和解锁后视觉/导航回归。后续回归继续检查 `CircleOp` 计数、按压反馈、详情打开、返回和列表状态保持。

帧汇总：

- 第二批 Macrobenchmark 账本 5/5：frame CPU P50/P90/P95/P99 为 4.886/11.822/17.005/85.170 ms，overrun P50/P90/P95/P99 为 -4.953/2.581/12.462/143.888 ms；最差 Trace 94 app frames、8 missed、5 app-missed、3 dropped，最大 247.047 ms。
- 第二批 Macrobenchmark 自动记账 5/5：frame CPU P50/P90/P95/P99 为 4.856/9.936/14.628/160.883 ms，overrun P50/P90/P95/P99 为 -4.560/4.183/40.866/150.639 ms；最差 Trace 83 app frames、8 missed、2 app-missed、6 dropped，最大 232.791 ms。
- 第三批最终 Macrobenchmark 账本 5/5：frame CPU P50/P90/P95/P99 为 3.803/9.345/13.163/60.655 ms，overrun为 -7.570/-0.147/10.566/68.366 ms；各迭代最大帧 62.566–97.440 ms。该历史基线 5/5 有 `CircleOp` shader 编译，当时本项尚未修复。
- 第三批最终 Macrobenchmark 自动记账 5/5：frame CPU P50/P90/P95/P99 为 3.718/9.238/13.858/65.849 ms，overrun为 -4.785/2.249/34.170/88.680 ms；各迭代最大帧 66.448–143.130 ms，每次 2 个 >50 ms 帧。P99 CPU 较第二批 160.883 ms 下降 95.034 ms（59.1%），但仍不能宣称长帧已消失。
- 第四批最终 Macrobenchmark 账本 5/5：frame CPU P50/P90/P95/P99 为 4.205/10.641/16.568/81.387 ms，overrun 为 -5.915/2.414/9.270/112.667 ms；frameCount median 89。自动记账 5/5：frame CPU P50/P90/P95/P99 为 3.665/11.250/15.692/89.983 ms，overrun 为 -4.810/2.632/36.429/81.131 ms；frameCount median 73。两条路径均 0 主线程 bitmap decode、0 Keystore Binder、0 GC；该批次未改变账本 ripple，修复结果以第五批证据为准。
- 第五批最终节点方案正常态第一组账本 5/5：frame CPU P99 37.044 ms，overrun P99 29.112 ms，frameCount median 96；正常态第二组 5/5：frame CPU P99 69.169 ms，overrun P99 111.497 ms，frameCount median 91。另有 5 次系统 60 Hz 限制压力样本，仅用于确认 `CircleOp` 未回归，不与 120 Hz 基线比较帧时序。全部 15 次均为 0 `CircleOp`、0 GC。
- 批次 6 同设备各 10 次 None/Profile 对照：账本 frame CPU P99 113.690→36.830 ms（-67.6%），overrun P99 114.414→31.323 ms（-72.6%）；自动记账 frame CPU P99 84.781→51.121 ms（-39.7%），overrun P99 78.573→42.805 ms（-45.5%）。账本 JIT count/duration 中位数 110/128.779 ms→8/13.424 ms，自动记账 70.5/74.971 ms→1/0.853 ms，确认交互收益主要来自减少 JIT；Profile 样本仍可独立出现 shader 和 Compose 布局长帧。
- 批次 7 同设备各 10 次 None/Profile 对照：账本 frame CPU P99 146.987→46.362 ms，自动记账 117.752→37.241 ms。账本 None 最差 Trace 出现 329.926 ms 帧，`measureAndLayout` 188.987 ms、`Recomposer:recompose` 82.786 ms、JIT 140 次/179.975 ms、GC 5 次/476.436 ms；Profile 代表为 7 次/17.458 ms JIT 且无 >10 ms 主线程 slice。自动记账 None 最差 110.958 ms 帧全程 Running，含 50.745 ms recompose、49.861 ms measure、86 次/113.959 ms JIT；Profile 代表为 3 次/3.449 ms JIT、0 GC。长尾仍存在于 None 对照，不能作为批次 7 完全解决根布局问题的证据。
- 批次 8 报表进入（生成后的 Profile、各 5 次）：1k frame CPU P50/P90/P95/P99 为 3.761/14.297/20.173/56.011 ms、overrun 为 -4.439/4.404/12.457/43.722 ms；10k 为 8.589/13.097/17.039/55.663 ms、overrun 为 3.731/5.005/15.065/44.744 ms。最差 1k/10k Trace 主线程帧分别为 62.306/65.503 ms，均几乎全程 Running，主要嵌套 `Recomposer:recompose`、`AndroidOwner:measureAndLayout` 和 `Record View#draw()`；未记录目标进程 JIT/GC slice。10k 最差帧在 CPU 5 的 1,190,400 kHz 上运行，Runnable 仅 0.070 ms，不能归因为调度饥饿。`android_jank` metric 在当前 trace_processor 不可用，故高层卡顿口径使用 FrameTimingMetric。
- 自动记账最终五次 `measureAndLayout` 最大值为 47.808、27.689、27.963、26.801、50.832 ms；shader 首用在前两次为 29.812/27.057 ms，后三次约 0.945–3.160 ms。将权限项改为 `LazyColumn` 的实验 Trace 出现 66.986 ms `measureAndLayout` 且全程 Running；当前高屏一次可容纳全部项目，Lazy 子组合成本反而集中进入首帧，因此该实验与对应测试改动已完整回退。
- 账本强停后后台重启：306 帧，7 missed、3 app-missed、4 dropped；最大 101.379 ms。
- 账本温：221 帧，4 missed、2 app-missed、1 dropped；最大 91.289 ms。
- 账本热：201 帧，3 missed、3 app-missed、0 dropped；最大 38.404 ms。
- 自动记账强停后后台重启：298 帧，5 missed、4 app-missed、1 dropped；最大 91.281 ms。
- 自动记账温：296 帧，4 missed、4 app-missed、0 dropped；最大 86.972 ms。
- 自动记账热：292 帧，6 missed、4 app-missed、1 dropped；最大 30.363 ms。
- 原生产包六个交互样本的 SQL >16.67 ms 计数：账本 3/3/3，自动记账 3/3/11；>50 ms：账本 1/2/0，自动记账 2/2/0。
- 原六个交互样本、第二批 10 个交互迭代与第三批最终 10 个交互迭代均无 >700 ms frozen frame。

### PERF-B03 — Medium — 账本与报表重复派生（批次 7 修复、批次 8 已完成规模验证）

- 证据等级：A（代码、迁移测试和 1k/10k 真机报表路径）。
- 类别：Compose、重组、对象分配。
- 文件与行号：`LedgerScreen.kt:257-309`；`feature/ledger/ReportsScreen.kt:69-150`；`feature/ledger/LedgerModels.kt:71-90,129-217`；`MainActivity.kt:390-410,878`。
- 用户流程：账本搜索/筛选/月份切换、报表切换、任意触发根重组的状态变化。
- 静态证据：第三批已围绕输入缓存账本月份、汇总、当前月份判断、筛选结果，以及根级账目/删除列表和账本统计；批次 7 新增 `LedgerReportUiModel`，仅在账目输入变化时完成 `groupBy/sumOf`、比例与现金流派生，`ReportsScreen` 只渲染稳定模型。Repository 主 UI 状态不再订阅全库实体集合；备份兼容 API 仍保留全库读取，但不参与日常页面组合。
- Trace/SQL：批次 8 的 1k/10k 报表进入各 5 次，P99 CPU 为 56.011/55.663 ms。最差主线程帧为 62.306/65.503 ms，分别包含约 28.448/29.332 ms `Recomposer:recompose`、25.263/26.622 ms `AndroidOwner:measureAndLayout` 和 33.050/35.321 ms `Record View#draw()`，两者均几乎全程 Running；没有目标进程 JIT/GC slice。数据量提升 10 倍没有使报表长尾线性恶化。
- 实际影响：数据量增长后搜索输入和根状态变化可能把 O(N log N) 工作带入每帧。
- 实测确认：已确认。1k/10k 报表路径均通过，当前预计算模型没有表现出按账目数线性恶化的帧长尾。
- 修复建议：保持现有 stable report model；仅在新增更重统计或 100k 级数据需求时再评估按月投影。
- 修复风险：中，需保持筛选、排序和金额舍入完全一致。
- 验证方式：构造 1k/10k 条非用户基准数据的 benchmark；记录 recomposition count、frame CPU time 与分配。

检查未发现的 Compose 异常：

- 所有生产 Lazy 列表均提供稳定 key：Review `entry.id`、Ledger `it.id`、Report `category`、Diagnostics 复合 key。
- 未发现错误使用 `snapshotFlow` 或 `derivedStateOf`；生产代码中未使用这两个 API。
- 未发现明显嵌套 Lazy 滚动或列表项共用同一个行级 `remember` 状态。

## 4. CPU、内存和 GC 结果

### PERF-A03 — High — 进程重启时诊断日志逐条 Keystore 解密，形成 Binder 风暴（代码修复已完成）

- 证据等级：A（修复后的 benchmark 与生产 Release 均已证实；旧生产安装包保留为修复前基线）。
- 类别：后台 CPU、Binder、加密、内存、GC。
- 文件与行号：`MainActivity.kt:329-331`；`feature/diagnostics/AndroidDiagnosticLogRepository.kt:51-56,103-108,193-200`；`feature/diagnostics/DiagnosticEncryptedStore.kt:21-70,138-158`；`feature/diagnostics/DiagnosticLogsScreen.kt:91,142`；`feature/capture/PaymentNotificationListenerService.kt:31-53`。
- 用户流程：进程因应用启动、通知监听服务重连或系统回收后重建。
- 修复后静态证据：Repository 构造和 `setEnabled()` 均不读取历史正文；诊断页面进入/手动刷新才调用 `refresh()`；刷新使用 `readLatest(limit)`，统计只读行数，不逐条解密；`AndroidKeystoreDiagnosticCipher` 在进程内缓存 `SecretKey`。`readAll()` 仅保留给用户主动导出路径，不再是启动或后台服务重连路径。
- 修复后 Trace/SQL：第四批最终独立 benchmark APK 的 15/15 Trace（目录 `device-results-20260719-batch4-final-ok-2333/com.autoaccounting.macrobenchmark`）明确 `OK (3 tests)`，均无 app→keystore2 Binder 事务，均无 Android GC；冷启动 TtID min/median/max 为 383.913/395.046/447.554 ms，账本/自动记账 frame CPU P99 为 81.387/89.983 ms，frame overrun P99 为 112.667/81.131 ms。
- 修复前生产基线 Trace/SQL：`aa-batch4-production-02.perfetto-trace` 窗口 `577963951033471..577978859965080` ns；app upid `920` 对 `/system/bin/keystore2` 发起 4,766 次同步 Binder，累计 client wall `12,108.493266 ms`，最大单次 `15.307240 ms`；6 次 GC 合计 `304.907916 ms`、最大 `75.188489 ms`；RSS 峰值 `123.515625 MiB`。
- 修复后生产 Trace/SQL：`aa-batch4-production-patched-20260720-01.perfetto-trace` 窗口 `614932735011631..614947500882511` ns；强停后 PID `23406→无进程→23966`，新进程 start_ts 为 `614935188576005` ns。app upid `1042` 对 `/system/bin/keystore2` 仅发起 13 次同步 Binder，累计 client wall `38.840574 ms`，最大单次 `10.091250 ms`；全部 app Binder 88 次、累计 `86.763595 ms`；Android GC 为 0。未发现 `readAll`、`readLatest`、历史 `decode` 或历史加载 slice；诊断切片只包含类加载、`recordNow` 与 `DiagnosticEventCodec`，与一次生命周期事件写入一致。RSS `39.914062..113.625000 MiB`，主线程 Running `92.437336 ms`、Runnable `15.192140 ms`、D-state `6.007868 ms`。
- 实际影响：旧产物在后台重启窗口持续占用 Keystore Binder、后台 CPU、堆和 GC；修复后关闭诊断的启动路径不再承担这项成本。主线程不是主要阻塞点，但与服务重启并发时会放大系统争用。
- 实测确认：已完成。修复代码、启用状态下“构造不解密、显式刷新才读取”的单元测试、benchmark APK 和生产 Release 均已确认。
- 修复建议：当前代码保持，不应再次把历史日志读取移回 Repository 构造、开关或后台服务重连路径。
- 修复风险：中，需继续保证诊断开关、清除日志、密钥删除和损坏行兼容语义不变。
- 验证方式：本轮已完成同签名 Release 覆盖安装、数据 inode/权限保持检查和强停后 15 秒 Trace；后续回归继续要求 keystore2 Binder 保持常数级、无历史读取和 GC 风暴。

内存与 GC：

- 旧生产基线重启 Trace：RSS 在采集窗口从 `82.179688` 增至峰值 `123.515625 MiB`；6 次 GC 共 `304.907916 ms`。该 Trace 还显示 swap 样本，不能把 RSS 变化单独归因于诊断日志。
- 第四批 benchmark 15 个 Trace 均无 Android GC；自动记账路径的主线程 bitmap decode 仍为 0。benchmark 与生产基线的 APK、数据和进程状态不同，不混算为生产包内存收益。
- 批次 6 的 60 份 scratchpad 均记录 JIT、最大 recompose/measure 和 GC。代表性账本 None 样本含 131 次 JIT、166.745 ms JIT 和 218.803 ms GC；Profile 代表样本为 8 次 JIT、9.562 ms。自动记账 None/Profile 代表样本为 71 次、76.534 ms 与 1 次、0.744 ms；Baseline Profile 明显降低 JIT 压力，但不等于消除所有 GC 或内存风险。
- 全局长切片/D-state 复核未发现新的应用级阻塞：生产进程主线程 D-state 仅 `0.237186 ms`，最长应用切片为 GC `75.188489 ms` 和 `bindApplication 30.764115 ms`；约 5.1 秒的 D-state 属于 `xiaomi_touch_temp_thread`，且缺少符号化 `blocked_function`，不归因于本项目。
- 未采集 Java heap dump 或 allocation profile，因此不能断言具体对象泄漏。

内存泄漏检查未发现明确异常：

- MainActivity 在 `MainActivity.kt:192-198` 注销 SharedPreferences listener 并移除 Handler callback。
- Notification/Accessibility Service 都在 `onDestroy` 取消自有 scope；OCR Bitmap/HardwareBuffer 有关闭路径。
- 诊断日志磁盘缓存有 10 MiB 总上限，但初始化会读全量，这是性能问题而非无界缓存。

## 5. ANR 和主线程阻塞风险

### PERF-B01 — High — CSV 导出和加密备份存在主线程重活（批次 9 已完成）

- 证据等级：A（隔离 1k/10k 合成数据真机与 Trace）。
- 类别：磁盘 I/O、序列化、加密、ANR。
- 文件与行号：`feature/settings/DataAndBackupScreen.kt:118-145,182-205`；`feature/settings/PersistedLocalDataBackup.kt:49-88`。
- 用户流程：数据与备份 → 导出 CSV；导出加密备份。
- 静态证据：修复前 `rememberCoroutineScope().launch` 默认 Main，CSV 构造整份字符串；备份在 Room 查询后回到调用上下文执行全量序列化/PBKDF/AES/Base64。现 CSV 内容拼装切到 `Dispatchers.Default`，Downloads 写入沿用既有 IO；加密备份的 Room 快照、序列化、PBKDF/AES/Base64 切到 `Dispatchers.Default`，恢复完成解密校验后的 Room 替换事务切到 `Dispatchers.IO`。
- Trace/SQL：独立 benchmark 包分别生成 1k/10k 非生产合成账目，并在内存中执行 CSV 与加密备份，各 3 次。1k CSV 为 14.318、15.602、19.720 ms，中位数 15.602 ms；1k 备份为 175.284、183.838、169.957 ms，中位数 175.284 ms。10k CSV 为 75.908、72.407、68.030 ms，中位数 72.407 ms；10k 备份为 289.149、272.865、283.792 ms，中位数 283.792 ms。主要 Running 位于 `DefaultDispatch`，10k 备份另有磁盘 I/O worker；所有 6 条 Trace 均无正持续时间 GC。目标进程 max RSS：1k 为 232.172–241.805 MiB，10k 为 309.492–318.566 MiB。
- 实际影响：修复后主线程不再承担 CSV 拼装、Room 快照、序列化或加密；10k 路径显示明显但受控的后台 CPU/RSS 增长，没有 GC 停顿。
- 实测确认：已确认 1k/10k 内存 CSV 与加密备份路径。未写用户 Downloads，也未覆盖外部 MediaStore/文件系统写入阶段。
- 修复建议：当前问题已完成。若产品目标超过 10k 或备份体积继续增长，再评估流式 CSV、分块序列化与峰值内存预算。
- 修复风险：低到中，当前实现保留 CSV/备份格式、Snackbar 回主线程和恢复原子性；大文件半成品清理仍应在动态验证中覆盖。
- 验证方式：保留 1k/10k 合成数据 Trace 回归；单独验证真实 Downloads/MediaStore 写入失败、取消和半成品清理。

### PERF-B02 — High — 无障碍事件回调同步遍历节点树并重复查询设置（批次 10 已完成隔离策略验证）

- 证据等级：A（代码、JVM、隔离 instrumentation 与 Trace）；真实支付页面影响仍为 C。
- 类别：Accessibility、Binder、主线程、耗电。
- 文件与行号：`feature/billsync/AccessibilityEventAdmissionGate.kt:7-46`；`feature/billsync/BillSyncAccessibilityService.kt:83-175,989-1005,1198-1242`。
- 用户流程：微信/支付宝支付结果页、连续自动记账。
- 静态证据：手动补录保持原事件路径；连续自动记账在读取 `rootInActiveWindow` 前先拒绝未启用、非微信/支付宝、非窗口状态/内容/变化事件、settle job 期间事件和 250 ms 内相同包/类型/窗口事件。服务连接时缓存健康状态，连接销毁时失效，不再在每个候选事件读取 `Settings.Secure`/SharedPreferences；`windows` 仅在微信 OCR 判定时访问。可见文本收集改为有序去重并限制 512 节点、24 层、16 KiB。
- Trace/SQL：独立 benchmark 包以 1,000 个同窗口内容事件模拟 event storm，Batch10 admission 3 次为 0.389、0.406、0.336 ms；Macrobenchmark instrumentation 三次均断言仅放行 4 个检查点。三个 section 都在目标包 Binder provider 线程；admission 全程 Running 0.336–0.406 ms。真实支付节点树未生成，因此该 Trace 不测量 `rootInActiveWindow`、`windows` 或 OCR。
- 实际影响：常见无关/重复事件不再触发设置读取、根节点 Binder 或整树遍历；同窗口 event storm 在 settle 前被合并。预算限制阻止异常节点树无限递归和持续分配。
- 实测确认：已确认 admission、去重、服务健康缓存和预算代码路径；未确认真实微信/支付宝页面的事件率、节点数、遗漏率和端到端解析结果。
- 修复建议：当前代码修复已完成；在专用设备补齐真实支付页面回归后，仅在发现遗漏时调整预算或 admission 窗口。
- 修复风险：中，极端超过预算的页面可能遗漏深层文本；自动路径在 250 ms 内合并相同窗口事件。手动补录和不同 event type/window 均未合并。
- 验证方式：保留隔离 1,000 event storm 回归；在授权测试设备按真实微信/支付宝流程采集独立 Trace，记录事件率、节点数、服务主线程 Binder/Running 时间与遗漏率。

实测主线程/ANR结论：

- 原六个交互样本、第二批 10 个 Macrobenchmark 交互迭代及第三批最终 10 个交互迭代均无 >700 ms frozen frame；最终 benchmark 包 logcat 未发现 ANR、FATAL 或 OOM。
- 第二批账本 247.047 ms 帧中 UI Running 211.551 ms、Runnable 0.286 ms；自动记账 232.791 ms 帧中 UI Running 226.306 ms、Runnable 0.061 ms。两者都是线程自身工作，不是调度等待。
- 启动主线程锁竞争 metric 0.410 ms；监控到的主线程 monitor contention 最大 0.376 ms，没有当前锁型 ANR。
- 热启动有一次 16.541 ms D-state I/O，位于 `performResume`；isolated cold 首帧窗累计 I/O D-state 129.721 ms，最长单段 35.854 ms。后者可确认由 `kworker/7:7H` 唤醒，但 `blocked_function` 为空且 raw caller 未符号化，无法确认具体文件、页错误或内核函数。
- 第三批最终交互 Binder 最大 0.876 ms，冷启动 Binder 最大 6.856–15.676 ms，均不是当前长帧主因。

## 6. 后台与耗电结果

### PERF-A04 — Medium — 通知监听服务让进程在强停后立即重启（已接受架构约束）

- 证据等级：A。
- 类别：后台生命周期、启动基线、耗电。
- 文件与行号：`feature/capture/PaymentNotificationListenerService.kt:31-53`；合并 Manifest `apps/android/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml:48-57`。
- 用户流程：通知捕获、系统重连、应用启动。
- 静态证据：已授权的 NotificationListenerService 与主 Activity 共用 `com.autoaccounting` 进程，并在连接时初始化 diagnostics、读取 activeNotifications。
- ADB/Trace：修复后生产 Trace `aa-batch4-production-patched-20260720-01.perfetto-trace` 在 14.766 秒窗口内先开始采集再执行 `am force-stop`；PID 由 `23406` 变为暂时无进程，4 秒后恢复为 `23966` 并保持至 Trace 结束。Perfetto 新进程 start_ts 为 `614935188576005` ns，`bindApplication` 为 `62.638281 ms`，`PaymentNotificationListenerService.serviceCreate` 为 `3.230521 ms`。PERF-A03 已不再随重连触发，但进程自动恢复本身仍存在。
- 实际影响：进程会在用户未主动打开界面时恢复，改变真实 cold-start 语义、常驻内存和后台功耗基线；后续任何 Application/Service 初始化都会随之执行。
- 实测确认：确认。用户于 2026-07-20 选择方案 1，将该行为接受为 NotificationListener 常驻能力的架构约束，不再作为待修复缺陷。
- 处理决定：保持 NotificationListenerService 与主应用同进程，不追求“强停后永不拉起”；以重连性能预算管理风险：不得读取历史诊断、GC 为 0、Keystore Binder 保持常数级、RSS 峰值基线为本轮 `113.625 MiB`。本轮已满足前三项。
- 未采用方案：暂不拆分 `:capture` 独立进程，也不动态启停 NotificationListener 组件；前者会引入 Room 多进程一致性和额外常驻内存，后者可能改变授权/重连时序并漏通知。
- 决策风险：低。保留的风险是主 UI 进程无法取得真实 cold-start 基线、后台存在常驻内存；收益是保持通知重放和自动记账可靠性，不引入跨进程状态。
- 后续验证：将强停重连 Trace 作为回归预算，并补 30 分钟稳态功耗；若 Binder、GC、RSS 或漏通知指标恶化，再重新评估进程隔离。

### PERF-B06 — Low — 30 秒心跳持续唤醒并写 SharedPreferences（批次 10 已完成代码与隔离验证）

- 证据等级：A（代码、JVM、隔离 instrumentation 与 Trace）；长期功耗仍为 C。
- 类别：后台、轮询、存储。
- 文件与行号：`BillSyncAccessibilityService.kt:98-116,1000-1005`；`feature/monitoring/ContinuousMonitoringServiceHealth.kt:22-47,78-79`；`MainActivity.kt:129-141`。
- 用户流程：自动记账开启且无障碍服务长时间连接；Activity 前台。
- 静态证据：服务连接时只立即写一次，循环先等待再写；间隔由 30 秒改为 60 秒。`markServiceConnected` 在已连接且上次写入未满 60 秒时不写；时钟回拨会写入新时间，销毁仍立即写断开。连接过期阈值相应由 90 秒调整为 150 秒，Activity 前台轮询随常量改为 60 秒。
- Trace/SQL：独立 benchmark 包按 0、30、60、90、120 秒的受控时间线执行健康写入，三个 instrumentation 均断言只持久化 3 次。Batch10 heartbeat section 为 2.247、0.485、0.452 ms；其中 Running 为 0.392、0.316、0.289 ms，其余为 Sleeping/Runnable，不是 CPU 忙等。目标进程每条 Trace 有 2 个 GC 命名 slice，总计 0.008–0.011 ms；没有 Frame Timeline 样本，不能从此推断帧表现。
- 实际影响：正常服务运行的周期性唤醒/写入由每小时约 120 次降为约 60 次，并消除连接时循环的重复写入；仍保留跨进程存活检测。
- 实测确认：已确认写入选择逻辑、时钟回拨、隔离 2 分钟写入次数和短窗口线程状态；未确认 30 分钟以上的电量、唤醒锁、异常杀服务后的 UI 误判时长。
- 修复建议：当前代码修复已完成；只有在可获得长期功耗数据时再调整 60 秒/150 秒预算。
- 修复风险：中，异常终止但未回调 `onDestroy` 时，最坏健康过期窗口由 90 秒增至 150 秒；正常断开仍即时标记。
- 验证方式：保留 2 分钟受控写入回归；1–2 小时 Battery Historian/Perfetto 长 Trace 比较唤醒次数、I/O 与服务健康误判。

未发现明确异常：

- 生产代码未使用 WakeLock、AlarmManager、WorkManager、`Thread.sleep`、`GlobalScope` 或无界重试循环。
- 两个 Service 均 `exported=false` 且 scope 在 `onDestroy` 取消。
- 第三批最终 Trace 有 CPU frequency 样本，但 power rail 样本为 0、battery wake slice 为 0；不能据此给出真实 mAh、功率或“无耗电”结论。

## 7. 网络与数据库结果

### PERF-C01 — Medium — 网络取消和分阶段可观测性不足（批次 9 已完成受控网络验证）

- 证据等级：A（ADB reverse 回环 HTTP、真机与 Trace）；公网 DNS/TLS 仍属未验证边界。
- 类别：网络、取消、观测。
- 文件与行号：`feature/account/HttpAccountRepository.kt:27-96,230-245`。
- 用户流程：短信验证码、登录、账户验证/删除。
- 静态证据：请求保持在 `Dispatchers.IO`、10 s connect 与 15 s read timeout。修复后使用协程取消回调立即 `disconnect()`；repository 显式重抛 `CancellationException`，不会把生命周期取消误映射成普通网络失败，并已有定向 JVM 回归。内部无敏感数据的 observer 依次公开 `RequestStarted`、`RequestBodyWritten`、`ResponseHeadersReceived`、`ResponseBodyRead` 和 `Cancelled`。未接入线上日志 sink，避免无意记录账户或响应敏感数据；`readText()` 仍没有响应大小上限。
- Trace/SQL：Windows 回环服务通过 `adb reverse tcp:8091 tcp:8091` 提供延迟响应头、分段响应体和 5 秒延迟端点。完整慢响应 3 次为 368.513、361.041、364.266 ms，中位数 364.266 ms；请求开始后约 200 ms 取消，Trace 为 204.800、208.250、206.325 ms，中位数 206.325 ms。取消 observer 在真机断言通过。Binder provider 线程在完整响应与取消窗口均几乎全部为 `S`，Running 不超过 0.589 ms，未形成 CPU 忙等；目标进程 max RSS 221.219–222.098 MiB，正持续时间 GC 为 0。
- 实际影响：页面退出或调用方取消时，阻塞中的 `HttpURLConnection` 会主动断开并在约 206 ms 总窗口内结束；阶段 observer 能确认请求写入、响应头、响应体和取消顺序。该回环 HTTP 不包含 DNS、TLS 或公网服务端等待。
- 实测确认：已确认受控明文回环环境下的阶段顺序、慢响应与取消延迟；真实账号、真实后端、公网 DNS/connect/TLS/TTFB 未验证。
- 修复建议：当前取消与内部阶段观测已完成；后续只需在确有线上诊断需求时增加脱敏 sink、响应体上限和 DNS/TLS/TTFB 数据源。
- 修复风险：低到中，仍需保持既有账号错误映射和服务器兼容。
- 验证方式：保留回环慢响应/取消回归；线上诊断另由可控代理分别注入 DNS、connect、TLS、TTFB 和慢 body。

### PERF-B04 — Medium — 全库常驻与索引不匹配账本查询（批次 7 修复、批次 8 已完成报表规模验证）

- 证据等级：A（迁移、查询计划和 1k/10k 真机报表路径）。
- 类别：Room、数据库、内存、扩展性。
- 文件与行号：`data/local/LedgerDaos.kt:68-99,221-269`；`data/local/LedgerEntities.kt:95-128`；`data/local/AutoAccountingDatabase.kt:268-280`；`data/local/LocalLedgerRepository.kt:32-74`；`MainActivity.kt:390-410,616`。
- 用户流程：启动、账本列表、账本管理、报表。
- 静态证据：修复前根状态观察 `SELECT * FROM ledger_entries ORDER BY ...` 并常驻所有大文本字段；常用账本查询按 `ledger_book_id + deleted_at_epoch_millis + transaction_time_epoch_millis` 过滤/排序，但只有单列索引。批次 7 迁移 v6→v7 将该单列索引替换为同顺序复合索引，`EXPLAIN QUERY PLAN` 回归测试确认当前账本活动条目查询使用该索引。
- Trace/SQL：批次 8 的合成 1k/10k 单账本均通过 Provider 返回条数校验，并完成报表路径各 5 次。10k 最差帧 65.503 ms、1k 为 62.306 ms，均由主线程组合/布局/绘制主导，不存在数据量 10 倍带来的同等帧时间增长。该 Trace 没有单独 Room 查询时长或 RSS 计数，故不把它表述为 100k 级数据库容量证明。
- 实际影响：数据增长时查询排序、Flow invalidation、映射和根级内存线性增加。
- 实测确认：已确认 v6→v7 迁移、复合索引、`EXPLAIN QUERY PLAN` 和 1k/10k 报表可用性；未验证 100k、独立查询时长或 RSS。
- 修复建议：保持当前账本 active/deleted scoped Flow、Room 账本聚合与复合索引；不要仅为当前 10k 报表路径增加按月投影。
- 修复风险：中，索引增加写放大，scoped Flow 会改变刷新边界。
- 验证方式：1k/10k/100k 合成库，记录 query plan、Room 查询时间、Flow 发射与 RSS；验证迁移。

数据库检查未发现明确异常：

- 未启用 `allowMainThreadQueries`。
- 未发现代码级 N+1 查询循环；备份的一组全表查询在单个事务内，但其后加密线程选择有问题。
- 当前 Trace 无 >2 ms 的 SQLiteConnectionPool 单次竞争导致主线程阻塞。

## 8. R8 和包体优化结果

### PERF-B07 — High — Release R8/资源压缩已启用并完成隔离回归（批次 11 已完成）

- 证据等级：A（Release 构建、mapping/usage、签名、隔离 R8 Macrobenchmark）。
- 类别：R8、包体、安装/更新、冷加载。
- 文件与行号：`apps/android/build.gradle.kts:71-96`；`apps/android/proguard-rules.pro:1`。
- 用户流程：下载安装、更新、首次/冷启动。
- 静态与产物证据：`isMinifyEnabled = false`，未设置 `isShrinkResources = true`；项目 ProGuard 文件只有注释，无过宽、重复或无效自定义 keep 规则。没有 mapping/seeds/usage/configuration 输出。
- R8 Skill 结论：R8 9.0.32 低于 9.3.7-dev，按 heuristic 路径分析；当前阻止 shrinking/optimization/obfuscation 的根因是 R8 完全未运行，而不是 keep 规则。
- APK 证据：
  - 批次 9 最终 Release 103,493,619 bytes（约 98.70 MiB）；Debug 117,619,398 bytes（约 112.17 MiB）。
  - native libs 39.17 MiB、`res/` 33.52 MiB、DEX 23.23 MiB、assets 1.85 MiB。
  - ML Kit OCR pipeline 同时包含 x86_64 11.09 MiB、x86 11.03 MiB、arm64 10.55 MiB、armeabi-v7a 6.47 MiB。
  - `xiaolai_regular.ttf` 源文件 21.19 MiB，APK 压缩后 14.04 MiB。
  - DEX method references：classes.dex 65,217、classes2.dex 64,504、classes3.dex 2,861；Compose package约 5.44 MiB DEX。
- Trace/SQL：未将包体直接映射为启动耗时；生产 warm startup metric 中 dex open 3.404 ms，隔离 cold 为 11.282 ms，当前首帧主因仍是 UI。
- Trace/命令证据：Release R8 前后 APK 为 103,493,619 / 80,529,815 bytes，减少 22,963,804 bytes（21.90 MiB，22.19%）；`mapping.txt`、`usage.txt` 已生成，未生成 `missing_rules.txt`，Release、R8 benchmark 与 Macrobenchmark APK 均通过 v2 签名。隔离 R8 benchmark 的 6 个 Macrobenchmark 用例全部通过，共 60 条 Trace。
- 实际影响：下载、安装与更新载荷降低约 22.2%，并启用代码缩减、优化和混淆。
- 实测确认：已确认。未将包体缩减直接归因于首帧改善；隔离 benchmark 冷启动 None/Profile TtID 中位数为 250.225/237.486 ms。
- 修复建议：当前修复完成；后续只在 CI 增加 Release 产物大小预算、mapping 保存和 R8 smoke。
- 修复风险：中。真实生产账号、通知、无障碍/OCR、Room 历史数据尚未用 R8 生产包端到端覆盖。
- 验证方式：保留 6 用例 × 10 次隔离 Macrobenchmark；发布前在专用生产数据副本补真实流程 smoke。

### PERF-C02 — Medium — Baseline Profile 正确进入生产 Release（批次 6 已完成）

- 证据等级：A（接线、产物及同设备 Trace 对照均已确认）。
- 类别：启动、AOT、构建产物。
- 文件与行号：`apps/android/build.gradle.kts:126-131,198`；`benchmarks/macrobenchmark/src/main/java/com/autoaccounting/macrobenchmark/CriticalUserJourneysBenchmark.kt:40-111`；`apps/android/src/release/generated/baselineProfiles/baseline-prof.txt:1`。
- 用户流程：冷启动、账本滚动→详情、报表进入、我的→自动记账。
- 静态证据：批次 8 重新生成生产 source profile，为 20,000 行、2,143,362 bytes，含 1,798 条 `com/autoaccounting/` 规则、0 条 benchmark-only 规则；Gradle filter 同时排除 `com.autoaccounting.benchmark.**`。
- Trace/命令证据：Release APK 内 `baseline.prof`/`baseline.profm` 为 14,736/1,747 bytes。6 个测试 × 10 次均通过，形成 60 Trace；账本与自动记账 frame CPU P99 分别下降 67.6%/39.7%，JIT count/duration 显著下降。冷启动 TtID 中位数 415.372→420.970 ms，没有可测收益。
- 实际影响：生产 Release 已具备关键路径预编译规则，两条交互路径的 JIT 和长帧成本显著降低；启动仍受主线程工作与 D-state I/O 主导。Baseline Profile 不解决 shader、bitmap decode、GC 或根级 Compose 工作。
- 实测确认：确认。生产接入、APK 内容和同设备交互收益均已验证；不宣称冷启动改善。
- 修复建议：批次 6 已完成接入，批次 8 已把报表加入生成路径。后续修改关键启动/交互路径时重新生成 profile，并把 None/Profile 对照作为回归预算。
- 修复风险：低；主要风险是 profile 过期或误包含 benchmark-only 规则，当前两项均已排除。
- 验证方式：Release 构建后检查合并 profile 与 APK `baseline.prof/.profm`；三条路径各执行 10 次 None/Profile 对照，持续比较 TtID、frame CPU、overrun 和 JIT。

### BUILD-A01 — High — Lint 与 Release 构建绿线（第一批已修复）

- 证据等级：A。
- 类别：构建验证、Debug/Release 差异。
- 文件与行号：生成代码 `apps/android/build/generated/source/kapt/release/com/autoaccounting/data/local/*_Impl.java`；首个 Lint 错误 `BillSyncAccessibilityService.kt:801`。
- 用户流程：Release 发布、API 29 自动记账截图。
- 静态/命令证据：首次及补测首次执行 `:apps:android:assembleRelease` 都在 `compileReleaseJavaWithJavac` 失败，Room KAPT 生成实现无法找到 Kotlin DAO/Entity/Database 类型，报告 100 个符号错误。补测使用 `:apps:android:compileReleaseKotlin --rerun-tasks` 重新生成 Kotlin/KAPT 产物后恢复构建。第一批将全版本安全入口与 `@RequiresApi(30)` 截图实现分离；`:apps:android:lintDebug --rerun-tasks` 从 5 errors、65 warnings 改为 0 errors、65 warnings。随后 `assembleRelease --rerun-tasks --no-build-cache` 55 个任务全部执行成功，Room KAPT/Javac 错误未复现。
- Trace/SQL：不适用。
- 实际影响：第一批修复后 Lint 绿线已恢复，API 29 会在进入任何 API 30 类型前安全返回 `null`；API 30–33 与 API 34+ 的截图分支保持原行为。Release 可在禁用构建缓存并强制重跑全部任务时生成。R8 仍因 minify 关闭而不能验证。
- 实测确认：已确认修复；定向 OCR/Service 测试、412 项 JVM 测试、Lint、无构建缓存 Release 和 APK v2 签名均通过。未执行 `clean`，因此保留对早期增量产物不一致的历史记录，不再列为当前未完成项。
- 修复建议：第一批已完成。后续 CI 保留至少一次 `--rerun-tasks --no-build-cache` Release 验证，并继续观察 AGP/KAPT 增量状态是否复现。
- 修复风险：中，涉及 AGP 9/Kotlin/KAPT 构建链与 API 29 路径。
- 验证方式：干净环境执行 assembleRelease、lintDebug、API 29 设备截图回退、apksigner。

批次 9 最终代码生成的 Release APK 为 103,493,619 bytes，`apksigner verify --verbose` 显示 v2 签名有效、1 个 signer，本批次未覆盖安装生产包。此前 2026-07-20 经用户授权安装的同签名 Release 在安装前后保持相同数据 inode，通知权限和 NotificationListener 授权保持，未清除用户数据。

### BUILD-C02 — Low — Baseline Profile Gradle Plugin 与 AGP 9.0.1 兼容性已验证（批次 11 已完成）

- 证据等级：A（构建命令确认）。
- 类别：构建、Benchmark/Baseline Profile 兼容性。
- 文件与行号：`gradle/libs.versions.toml:13,27`；`apps/android/build.gradle.kts:126-128,195`；`benchmarks/macrobenchmark/build.gradle.kts:24-26`。
- 用户流程：开发/CI 构建 benchmark 变体、生成与接入 Baseline Profile。
- 静态证据：AndroidX Benchmark/Baseline Profile 升至 `1.5.0-alpha07`；移除 `android.builtInKotlin=false` 与 `android.newDsl=false`，Android 模块改用 AGP built-in Kotlin、`com.android.legacy-kapt`，benchmark Kotlin 源目录迁至 `AndroidSourceSet.kotlin`。
- Trace/命令证据：`help`、全项目 `build --dry-run`、`kaptGenerateStubsBenchmarkReleaseKotlin`、Debug/Release 构建、Release Lint、422 项 JVM 测试、R8 benchmark/Macrobenchmark 构建均通过；原兼容性上限警告不再出现。真机 6/6 Macrobenchmark 通过。
- 实际影响：Baseline Profile 构建链可在 AGP 9 默认 DSL 下稳定配置、编译、签名并执行隔离真机用例。
- 实测确认：已确认；因 `1.5.0-alpha07` 为预发布构建工具，仍需 CI 持续监测。
- 修复建议：不要 suppress；跟踪支持 AGP 9 的稳定插件版本，在独立依赖升级批次验证后升级。升级前保留当前真机生成、APK `.dm`/profile 内容和启动对照作为验收。
- 修复风险：低（保持现状）；升级风险中，可能改变生成任务和变体命名。
- 验证方式：执行 benchmark assemble、三项 device benchmark、profile generation、app Debug/Release 和 benchmark variant Lint；比较生成 profile 行数/有效性与启动前后数据。

## 9. 当前待修复项

当前总有 1 个待修复项：

1. [High][PERF-A02] 启动根布局仍组合过重；账本相关 Flow 已合并且 scoped，但跨页面状态和转场仍使首帧主线程持续工作。

## 10. 建议修复批次

- 批次 1 [BUILD-A01]：已完成。修复 API 版本隔离，Lint、Release 构建和签名验证通过。
- 批次 2 [测试基础设施]：已完成。新增独立 Macrobenchmark/Baseline Profile 测试模块，三条路径各 5 次 Trace 已完成。
- 批次 3 [PERF-A01]：已完成。全屏和装饰图片后台解码；21 MiB/2 MiB 缓存；主线程目标 bitmap decode 已验证为 0。
- 批次 4 [PERF-A03][PERF-A04]：已完成。`PERF-A03` 已通过单元测试、benchmark 15/15 Trace 和修复后生产 Trace；`PERF-A04` 已按方案 1 接受为 NotificationListener 常驻架构约束，并转为性能预算管理。
- 批次 5 [PERF-A05]：已完成。节点式 indication 消除 `CircleOp` shader 首用；15/15 Trace、全量单元测试、构建、Lint、签名及解锁后视觉/交互回归通过。
- 批次 6 [PERF-C02]：已完成。生产 profile 过滤和 Release 接入已验证；三条路径各完成 10 次 None/Profile 对照，确认账本和自动记账交互收益，冷启动未测得收益。
- 批次 7 [PERF-A02][PERF-B03][PERF-B04]：已完成 scoped state、稳定报表模型和 Room v7 复合索引；`PERF-A02` 的根布局长尾仍待后续批次处理。
- 批次 8 [PERF-B03][PERF-B04][PERF-C02]：已完成。新增 1k/10k 非用户合成账目与报表路径、重新生成 Profile；数据规模验证未显示报表帧长尾线性恶化。
- 批次 9 [PERF-B01][PERF-B05][PERF-C01]：已完成。代码、JVM、构建及隔离真机验证均通过；合成登录会话、受控回环网络、1k/10k CSV 与加密备份各完成 3 次 Trace。真实生产账号、公网 DNS/TLS 和用户 Downloads 写入不属于本批次已验证边界。
- 批次 10 [PERF-B02][PERF-B06]：已完成。自动路径增加 event type/package/window admission、250 ms 同窗口去重、settle job 早退、服务生命周期健康缓存与 512 节点/24 层/16 KiB 预算；心跳改为最长每 60 秒写一次、150 秒过期。JVM、隔离 instrumentation 与 3 条策略 Trace 通过。真实支付节点树、遗漏率和长期功耗仍属于第 11 节边界。
- 批次 11 [PERF-B07][BUILD-C02]：已完成。启用 R8/资源压缩，Release APK 减少 22.19%；完成 AGP 9 built-in Kotlin、新 DSL 与 legacy KAPT 迁移，升级 Baseline Profile 插件并通过 6/6 隔离 R8 Macrobenchmark。
- 批次 12 [PERF-A02]：已完成账本内部转场实验与 2/2 真机隔离回归；frame CPU/overrun P99 明显下降，但冷启动、其他路由和 RenderThread 全屏绘制长尾仍待验证，`PERF-A02` 暂不关闭。

## 11. 未验证部分与需要的帮助

以下均为尚未完成的动态验证或环境能力缺口，不代表前面的构建、测试或 Macrobenchmark 全部失败。

1. 生产包真实 cold startup 未验证。通知监听服务在强停后会立即重启生产进程，因此无法取得纯 cold 样本。
   - 需要帮助：提供专用测试设备，或明确授权临时关闭通知监听服务/使用独立测试权限后重新采集。

2. Gradle UTP 入口未验证成功。设备测试入口因 Google Maven 的 `gradle-work-action-32.0.1.jar` 下载超时失败，但相同 Macrobenchmark 已通过手动 instrumentation 完成。
   - 需要帮助：提供可稳定访问 Google Maven 的网络/缓存环境；否则继续使用已通过的手动 instrumentation 后备路径。

3. Macrobenchmark 测试模块没有独立 Lint 报告。原因是 `com.android.test` 不提供 `:benchmarks:macrobenchmark:lintBenchmarkRelease` 任务；app benchmark source set 的 Lint 已通过。
   - 需要帮助：不需要新增代码；如必须有独立报告，需要接受该模块类型的任务限制或改用 CI 统一 Lint 入口。

4. 功耗数值未验证。批次 10 三条策略 Trace 仍返回 `power_rail_empty_packet`，没有有效 power rail、battery current、WakeLock/battery_stats 样本；短 Trace 也不能替代长期耗电结论。
   - 需要帮助：提供支持功耗轨道采样的设备/固件，或允许使用 Battery Historian/长时功耗采集。

5. 冷启动内核 I/O 的具体根因未验证。已确认 D-state、`io_wait=1` 和相关 kworker，但缺少符号化 caller、`blocked_function` 和 `waker_utid`。
   - 需要帮助：提供带内核符号或更完整 ftrace 数据的采集环境。

6. 真实生产账号、线上 token 校验和真实后端启动时延未验证。批次 9 已完成加密合成会话的登录态启动，但没有使用用户账号、生产 token 或真实后端。
   - 需要帮助：如需线上口径，提供专用测试账号、隔离后端和允许采集脱敏账号阶段数据的环境。

7. 真实微信/支付宝无障碍事件未验证。当前设备 `accessibility_enabled=0`；批次 10 未改变无障碍权限、未模拟支付，仅验证隔离 event admission 和心跳策略。
   - 需要帮助：提供专用测试账号/设备，并明确授权真实支付流程回归；采集事件率、节点数、遗漏率和服务主线程 Trace。

8. 真实 Downloads/MediaStore 写入阶段未验证。批次 9 已完成 1k/10k 内存 CSV 和加密备份 Trace，但没有向用户 Downloads 写文件，也没有覆盖存储空间不足、取消和半成品清理。
   - 需要帮助：提供专用测试用户或明确授权写入测试目录，并允许测试失败、取消与清理路径。

9. 长时内存泄漏和稳态耗电未验证。现有正式 Trace 约 15–30 秒，批次 10 的受控时间线不等于真实 2 分钟等待，更不能代表 30 分钟以上的稳态行为。
   - 需要帮助：允许在专用设备上运行 30 分钟以上场景，并采集 heap dump/allocation profile。

10. Compose stability/recomposition metrics 未验证。项目没有启用 Compose compiler stability/recomposition 报告。
    - 需要帮助：授权单独的构建配置变更，或接受仅使用 Perfetto 的总量证据。

11. 公网 DNS、连接、TLS、真实服务端等待和下载阶段未验证。批次 9 的回环 HTTP 已验证响应头、响应体和取消，但回环链路不经过 DNS/TLS，也不能表示真实服务端 TTFB。
    - 需要帮助：提供可控代理、隔离 HTTPS 后端和服务端观测能力，补采 DNS、连接、TLS、TTFB 与下载阶段。

12. R8 开启后的体积和运行时收益未验证。当前 Release `minify` 和资源 shrink 关闭，没有 mapping/seeds/usage 输出。
    - 需要帮助：授权独立 Release 配置批次，并安排账号、Room、通知、无障碍/OCR 回归。

13. 100k 数据和独立 Room 查询时长未验证。批次 8 已完成 1k/10k 报表路径；批次 9 已补 1k/10k 导出 RSS，但仍不能外推为 100k 数据容量证明。
    - 需要帮助：仅在产品需要 100k 级账本时，提供专用 benchmark 设备或授权补充进程内查询计时与 RSS 采样。

当前已确认但尚未修复的性能问题只有第 9 节列出的 1 项：PERF-A02。PERF-B01、PERF-B02、PERF-B05、PERF-B06、PERF-B07、PERF-C01、BUILD-C02 已完成代码、回归和隔离环境动态验证，不再列为未完成项；真实支付事件与长期功耗仍按上文列为未验证边界。

本轮已检查且未发现明确异常：生产 Lazy 列表均有稳定 key；生产网络 transport 在 `Dispatchers.IO`；未使用 `allowMainThreadQueries`、`runBlocking`、`Thread.sleep`、`GlobalScope`、WakeLock、AlarmManager 或 WorkManager 高频任务；生命周期解绑和 Service scope cancel 路径存在；日志无 ANR/FATAL/OOM，所有已执行交互样本均无 >700 ms frozen frame；项目 ProGuard 文件没有过宽、重复或无效自定义 keep 规则。

## Skill 适用性说明

- `perfetto-trace-analysis`：适用；批次 6 的 60 个 Trace 及批次 10 的 3 条策略 Trace 均保留 evidence scratchpad；批次 10 先尝试 v2 高层 metrics，再以 section、线程状态、GC、Frame Timeline 和全局 D-state SQL 复核。设备 power rail 空包已明确记录为限制。
- `perfetto-sql`：适用；每个使用的表/视图先在 `perfetto-stdlib.md` 确认 schema，查询使用 upid/utid、GLOB、重叠时间窗和 `dur=-1` 处理。批次 10 的 `android_frames` 为 0，未被误报为无卡顿。
- `r8-analyzer`：适用，但只能走 R8 9.0.32 heuristic；因 minify 关闭不能做 mapping/usage 实证。
- `testing-setup`：适用于盘点和第二批补测；在用户授权后复用官方 AndroidX Benchmark/UIAutomator 依赖新增独立测试模块，未改变生产运行时依赖。批次 10 沿用该模块新增隔离 environment validation 和 JVM 纯策略测试，未新增依赖或测试框架；第三批未再新增测试框架或依赖，复用现有 412 项 JVM 测试和手动 instrumentation 后备路径完成回归；测试前锁定 natural orientation、结束后恢复自动旋转，消除了方向漂移干扰。

原最小测试补充方案已经落实：独立 Macrobenchmark/Baseline Profile 模块覆盖本报告三条关键路径，shader 与 Baseline Profile 批次均已完成。后续最小增量是继续用现有框架验证根布局、R8 和剩余业务风险；不需要引入新的测试框架。
