# Android 项目系统性能审计报告

审计日期：2026-07-19 至 2026-07-20
仓库：`C:\Users\Arfa\Documents\auto-accounting`
Android 模块：`apps/android`
审计阶段：基线调查、补测、报告、第一批 API 修复、第二批性能测试基础设施、第三批安全候选修复及第四批诊断日志修复已完成。经用户授权，第一批仅修改 `BillSyncAccessibilityService.kt` 的 API 版本隔离；第二批新增独立 Macrobenchmark/Baseline Profile 模块、benchmark 专用变体与固定测试数据 Provider；第三批把全屏背景及“我的”页面/返回主页装饰图解码移出主线程，分别增加 21 MiB 与 2 MiB 有界缓存，并对根路由、账本派生模型和自动记账状态摘要做输入驱动的 `remember`；第四批把诊断历史读取彻底移出 Repository 构造和开关路径，仅在诊断页面显式刷新时读取最新窗口，并缓存进程内 Keystore `SecretKey`。AnimatedContent 尺寸动画、圆角、账目行点击容器及自动记账 `LazyColumn` 均只做过受控实验，Trace 未证明收益或证实反优化后已回退。独立 benchmark 包完成最终回归后，经用户明确授权，以同签名 Release 覆盖安装生产包 `com.autoaccounting`；未清除数据、未卸载、未修改权限，安装前后 CE/DE 数据 inode 不变。前三批已提交为 `9f4e8b5 perf: add Android performance fixes and benchmarks`；第四批改动尚未提交。未改写 Git 历史，未 push。

## 证据等级

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
- 第二批产物：三条路径各 5 次，共 15 个正式 Perfetto Trace；1 份 Macrobenchmark JSON；Baseline Profile Generator 成功输出 20,017 行 profile。第三批最终回归又生成三条路径各 5 次、共 15 个正式 Trace（133,599,881 bytes）、1 份 JSON 和 15 份逐 Trace evidence scratchpad。第四批最终代码又生成三条路径各 5 次、共 15 个 Trace（115,418,472 bytes）、1 份 JSON 和 15 份逐 Trace evidence scratchpad；冷启动 TtID min/median/max 为 383.913/395.046/447.554 ms，账本与自动记账 frame CPU P99 为 81.387/89.983 ms；另有 3 份生产包后台重启 Trace，其中 1 份为修复后生产确认。项目仍没有截图测试或完整通用 E2E 套件。
- 总体结论：第三批已消除自动记账路径的主线程 bitmap decode；第四批最终代码已把诊断历史读取彻底移出 Repository 构造/开关路径，只保留诊断页面显式 `refresh()`，并缓存进程内 Keystore key。修复后的生产 Release 在相同强停重连场景中把 app→keystore2 Binder 从旧包 4,766 次、12,108.493 ms 降至 13 次、38.841 ms，GC 从 6 次降至 0；未发现 `readAll`、`readLatest` 或历史 decode，PERF-A03 已完成生产确认。第五批以节点式 indication 消除了账本点击 ripple 的 `CircleOp → shader_compile`，15/15 最终 Trace 均未复现。当前主要实测瓶颈收敛为启动/页面切换的大范围组合与布局；R8 未启用、生成的 Baseline Profile 未进入生产 Release，以及 103.48 MB Release APK 仍是高优先级问题。

## 2. 启动性能结果

### PERF-A02 — High — 启动首帧根布局与组合过重

- 证据等级：A。
- 类别：启动、Compose、主线程。
- 文件与行号：`apps/android/src/main/java/com/autoaccounting/MainActivity.kt:336-435,746-760`；`apps/android/src/main/java/com/autoaccounting/feature/ledger/LedgerScreen.kt:104-164,257-309`。
- 用户流程：应用启动到主页稳定。
- 静态证据：第三批已用 `remember` 缓存 tab/底部导航、当前账本列表、删除列表、账本统计、权限健康状态和根路由；账本页也缓存选中项、页面状态、编辑初始值、月份、汇总及筛选结果。剩余七个独立 Flow collector 仍更新根级状态，根页面仍承担跨页面状态和转场组合，因此只是派生分配部分修复，不是根布局拆分完成。
- Trace/SQL：生产包后台服务重启样本 `startup-force-stop-bg-restarted-01.perfetto-trace` 为 warm 324.684 ms。第三批最终五次 `android_startup` TTID 为 516.516、524.360、571.608、558.376、567.506 ms；intent→first-frame 为 443.864、425.877、497.068、466.157、478.806 ms。首帧前主线程 Running 236.197–250.085 ms、Runnable 4.038–6.572 ms、I/O D-state 34.308–113.333 ms；最长单段 I/O D-state 16.541 ms，Trace 缺少 `blocked_function` 和 `waker_utid`，无法继续归因具体内核函数/文件。代表首帧仍有约 146.590 ms `measure` 和 36.457 ms `Compose:recompose`；5 次均无 GC。
- 实际影响：首屏延迟主要消耗在应用主线程自身工作，而不是等 CPU；数据增长会放大根派生计算和大范围重组成本。
- 实测确认：部分修复已确认；最终 Trace 仍确认首帧根布局/组合过重。现有 Trace 不能把各个 `remember` 的单独收益从样本波动中剥离，根派生计算对 `measure/recompose` 的精确占比仍需 Compose compiler metrics 或自定义 trace section。
- 修复建议：保留本轮低风险 memoization；下一批拆分根级状态订阅，只把当前页面需要的状态下放；对报表派生结果使用稳定输入和预计算；数据库直接提供当前账本与月份的 scoped Flow。
- 修复风险：中到高，涉及状态刷新、导航返回和列表滚动语义。
- 验证方式：使用现有 `coldStartup` Macrobenchmark 各跑至少 10 次；输出 Compose stability/recomposition metrics；修复前后比较 TTID、首帧 `measure`、`Compose:recompose`，并补 `reportFullyDrawn()` 后再比较 TTFD。

### PERF-B05 — Medium — 组合阶段同步读取 SharedPreferences/Keystore

- 证据等级：B。
- 类别：启动、主线程 I/O。
- 文件与行号：`MainActivity.kt:313-327`；`feature/account/SecureAccountSessionStore.kt:28-55,105-131`。
- 用户流程：已登录账号的启动与账号状态恢复。
- 静态证据：`remember { secureAccountSessionStore.restore() }` 在组合线程同步执行；有会话时会读取 SharedPreferences、加载 AndroidKeyStore 并做 AES-GCM 解密，错误路径还同步 `commit()`。
- Trace/SQL：本机处于本地模式，启动主线程未出现指向 keystore2 的 session restore Binder；因此本轮不能给出登录态耗时。Trace 中大量 Keystore 调用来自诊断日志的 `DefaultDispatch` 线程，不能冒充此问题的证据。
- 实际影响：登录态启动可能在首帧前增加 Binder/磁盘延迟；损坏会话路径还有同步写。
- 实测确认：未确认登录态影响。
- 修复建议：把 restore 放入受生命周期管理的 IO coroutine，UI 先进入明确的 restoring 状态；保持账号状态机顺序不变。
- 修复风险：中，可能影响登录/本地模式闪屏与请求时序。
- 验证方式：保留登录态采集冷/温启动，各至少 10 次；SQL 检查主线程 keystore Binder 和首帧前 SharedPreferences I/O。

启动测量：

- 第二批独立 benchmark 冷启动 TtID 5/5：481.977、466.552、434.509、537.461、473.371 ms；median 473.371 ms。第三批最终 5/5：439.407、422.685、493.636、462.860、475.248 ms；min/median/max 为 422.685/462.860/493.636 ms。median 较第二批下降 10.511 ms（2.22%），仍只视为本设备五次样本改善，不能外推为稳定收益。
- 第四批最终 `CriticalUserJourneysBenchmark` 明确 `OK (3 tests)`；Macrobenchmark `StartupTimingMetric` 冷启动 5/5 为 439.483、447.554、395.046、393.245、383.913 ms，min/median/max 为 383.913/395.046/447.554 ms。该指标与 Perfetto `android_startup` TTID 仍是不同口径。
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
- 自动记账最终五次 `measureAndLayout` 最大值为 47.808、27.689、27.963、26.801、50.832 ms；shader 首用在前两次为 29.812/27.057 ms，后三次约 0.945–3.160 ms。将权限项改为 `LazyColumn` 的实验 Trace 出现 66.986 ms `measureAndLayout` 且全程 Running；当前高屏一次可容纳全部项目，Lazy 子组合成本反而集中进入首帧，因此该实验与对应测试改动已完整回退。
- 账本强停后后台重启：306 帧，7 missed、3 app-missed、4 dropped；最大 101.379 ms。
- 账本温：221 帧，4 missed、2 app-missed、1 dropped；最大 91.289 ms。
- 账本热：201 帧，3 missed、3 app-missed、0 dropped；最大 38.404 ms。
- 自动记账强停后后台重启：298 帧，5 missed、4 app-missed、1 dropped；最大 91.281 ms。
- 自动记账温：296 帧，4 missed、4 app-missed、0 dropped；最大 86.972 ms。
- 自动记账热：292 帧，6 missed、4 app-missed、1 dropped；最大 30.363 ms。
- 原生产包六个交互样本的 SQL >16.67 ms 计数：账本 3/3/3，自动记账 3/3/11；>50 ms：账本 1/2/0，自动记账 2/2/0。
- 原六个交互样本、第二批 10 个交互迭代与第三批最终 10 个交互迭代均无 >700 ms frozen frame。

### PERF-B03 — Medium — 账本与报表每次重组重复全量聚合和分配

- 证据等级：B。
- 类别：Compose、重组、对象分配。
- 文件与行号：`LedgerScreen.kt:257-309`；`feature/ledger/ReportsScreen.kt:75-133`；`feature/ledger/LedgerModels.kt:108-226`；`MainActivity.kt:397-425`。
- 用户流程：账本搜索/筛选/月份切换、报表切换、任意触发根重组的状态变化。
- 静态证据：第三批已围绕输入缓存账本月份、汇总、当前月份判断、筛选结果，以及根级账目/删除列表和账本统计；重复分配风险已部分修复。报表仍有 `groupBy/sumOf` 与 BigInteger 分配，根级状态订阅仍可能让大列表输入变化，数据库仍提供全库实体集合。
- Trace/SQL：第三批账本 P99 CPU 60.655 ms，但当时 5/5 的主要长帧均有 CircleOp shader 证据，无法单独量化全量聚合成本。第五批消除 `CircleOp` 后仍有最高 99.482 ms 的主线程重组和 66.655 ms 的测量，证明另有 Compose 根级成本；固定 40 条数据仍不足以把账本全量聚合成本单独量化。
- 实际影响：数据量增长后搜索输入和根状态变化可能把 O(N log N) 工作带入每帧。
- 实测确认：memoization 改动已通过 412 项测试及真机路径确认功能未回退；其独立性能收益未从 Trace 波动中分离，当前规模仍未确认剩余聚合是主瓶颈。
- 修复建议：保留本轮 memoization；后续稳定报表 UI model、按月份/账本预索引，并由 ViewModel/Repository 提供 scoped 预计算结果；避免每条记录重复拼接搜索字符串。
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
- 全局长切片/D-state 复核未发现新的应用级阻塞：生产进程主线程 D-state 仅 `0.237186 ms`，最长应用切片为 GC `75.188489 ms` 和 `bindApplication 30.764115 ms`；约 5.1 秒的 D-state 属于 `xiaomi_touch_temp_thread`，且缺少符号化 `blocked_function`，不归因于本项目。
- 未采集 Java heap dump 或 allocation profile，因此不能断言具体对象泄漏。

内存泄漏检查未发现明确异常：

- MainActivity 在 `MainActivity.kt:192-198` 注销 SharedPreferences listener 并移除 Handler callback。
- Notification/Accessibility Service 都在 `onDestroy` 取消自有 scope；OCR Bitmap/HardwareBuffer 有关闭路径。
- 诊断日志磁盘缓存有 10 MiB 总上限，但初始化会读全量，这是性能问题而非无界缓存。

## 5. ANR 和主线程阻塞风险

### PERF-B01 — High — CSV 导出和加密备份存在主线程重活

- 证据等级：B。
- 类别：磁盘 I/O、序列化、加密、ANR。
- 文件与行号：`feature/settings/DataAndBackupScreen.kt:118-145,182-205`；`feature/settings/PersistedLocalDataBackup.kt:49-65`。
- 用户流程：数据与备份 → 导出 CSV；导出加密备份。
- 静态证据：`rememberCoroutineScope().launch` 默认 Main；CSV 在 Main 上创建 MediaStore 项、构造整份字符串并写文件。备份在 Room suspend 查询后回到调用上下文执行全量序列化/PBKDF/AES/Base64；只有最终 `writeDownloadFile` 切到 IO。
- Trace/SQL：本轮未执行导出，避免写入用户 Downloads；无 Trace 时间窗。
- 实际影响：数据量或备份大小增长时会冻结 UI，严重时触发 ANR；PBKDF 和 Base64 还会制造大临时对象。
- 实测确认：未确认，静态高可信。
- 修复建议：整个 CSV 生成/写入置于 IO；加密/序列化置于 Default 或专用 dispatcher；流式写入并限制并发。
- 修复风险：低到中，注意 Snackbar 和状态回主线程、失败时删除半成品。
- 验证方式：在非用户测试库用 1k/10k 条记录导出，Trace 主线程不得出现文件写/PBKDF，监控峰值 RSS 与取消。

### PERF-B02 — High — 无障碍事件回调同步遍历节点树并重复查询设置

- 证据等级：B。
- 类别：Accessibility、Binder、主线程、耗电。
- 文件与行号：`feature/billsync/BillSyncAccessibilityService.kt:112-193,946-962,1167-1181`；`feature/billsync/BillSyncPermission.kt:20-40`。
- 用户流程：微信/支付宝支付结果页、连续自动记账。
- 静态证据：每个候选事件在服务主线程读取 `rootInActiveWindow`、递归整树收集 text/contentDescription、`distinct/joinToString`；每次健康检查读取两项 `Settings.Secure` 并解析服务列表，还访问 `windows`。
- Trace/SQL：普通应用内路径不能安全生成支付/无障碍事件；本轮未模拟支付、未改权限，因此没有对应时间窗。
- 实际影响：复杂页面或事件风暴下产生主线程 Binder、分配和重复解析，可能拖慢目标应用交互并耗电。
- 实测确认：未确认真实支付页面影响。
- 修复建议：按 event type/package/window 做早期过滤和去重；缓存权限健康并由设置/生命周期事件刷新；节点遍历加入节点数/深度预算；重解析移到受控后台 pipeline，注意 AccessibilityNodeInfo 生命周期。
- 修复风险：高，可能漏记账或改变现有防重语义。
- 验证方式：在授权测试设备按真实微信/支付宝流程采集独立 Trace，记录事件率、节点数、服务主线程 Binder/Running 时间与遗漏率。

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

### PERF-B06 — Low — 30 秒心跳持续唤醒并写 SharedPreferences

- 证据等级：B。
- 类别：后台、轮询、存储。
- 文件与行号：`BillSyncAccessibilityService.kt:100-103`；`feature/monitoring/ContinuousMonitoringServiceHealth.kt:18-32,66-67`；`MainActivity.kt:129-137`。
- 用户流程：自动记账开启且无障碍服务长时间连接；Activity 前台。
- 静态证据：服务每 30 秒 `markServiceConnected` 并 `apply()` 两个 preference；Activity 另有同周期 Handler 读取健康状态。
- Trace/SQL：正式 Trace 只有约 15 秒，不能覆盖稳态 30 秒周期。
- 实际影响：每小时约 120 次服务唤醒/异步写；单次轻，但全天累计且服务本已常驻。
- 实测确认：未确认稳态耗电。
- 修复建议：优先用连接/断开事件和单调时间内存状态；若需跨进程存活证明，降低写频率并仅在值变化时写。
- 修复风险：中，心跳承担系统杀服务后的健康判断。
- 验证方式：1–2 小时 Battery Historian/Perfetto 长 Trace，比较唤醒次数与服务健康误判。

未发现明确异常：

- 生产代码未使用 WakeLock、AlarmManager、WorkManager、`Thread.sleep`、`GlobalScope` 或无界重试循环。
- 两个 Service 均 `exported=false` 且 scope 在 `onDestroy` 取消。
- 第三批最终 Trace 有 CPU frequency 样本，但 power rail 样本为 0、battery wake slice 为 0；不能据此给出真实 mAh、功率或“无耗电”结论。

## 7. 网络与数据库结果

### PERF-C01 — Medium — 网络取消和分阶段可观测性不足

- 证据等级：C。
- 类别：网络、取消、观测。
- 文件与行号：`feature/account/HttpAccountRepository.kt:26-65`。
- 用户流程：短信验证码、登录、账户验证/删除。
- 静态证据：请求在 `Dispatchers.IO`，有 10 s connect 和 15 s read timeout，并在 finally disconnect；但阻塞 `HttpURLConnection` 不会因 coroutine cancel 可靠中断，`readText()` 无响应上限，也没有 DNS/connect/TLS/server/download 分阶段事件。
- Trace/SQL：设备处于本地模式，本轮三条安全路径没有账户网络请求。
- 实际影响：页面退出后请求可能继续占 IO 线程；慢请求无法区分 DNS、握手、服务端或下载。
- 实测确认：未确认线上延迟。
- 修复建议：在不急于换依赖的前提下先封装取消时 disconnect、限制 body；后续若采用 OkHttp，加入 EventListener 和 traceId。
- 修复风险：中，涉及账号错误映射和服务器兼容。
- 验证方式：可控代理分别注入 DNS、connect、TLS、TTFB 和慢 body，验证取消和阶段指标。

### PERF-B04 — Medium — 全库常驻与索引不匹配账本查询

- 证据等级：B。
- 类别：Room、数据库、内存、扩展性。
- 文件与行号：`data/local/LedgerDaos.kt:190-237`；`data/local/LedgerEntities.kt:94-128`；`MainActivity.kt:397-420,632-635`。
- 用户流程：启动、账本列表、账本管理、报表。
- 静态证据：根状态观察 `SELECT * FROM ledger_entries ORDER BY ...` 并常驻所有大文本字段；常用账本查询按 `ledger_book_id + deleted_at_epoch_millis + transaction_time_epoch_millis` 过滤/排序，但只有单列索引。
- Trace/SQL：当前样本未见主线程查询；Room 磁盘线程首轮类加载/初始化存在 15–18 ms monitor contention，SQLiteConnectionPool 竞争约 0.4–1.3 ms，未形成当前 UI 主瓶颈。
- 实际影响：数据增长时查询排序、Flow invalidation、映射和根级内存线性增加。
- 实测确认：当前小/中数据集未确认慢查询。
- 修复建议：按当前账本/月份投影所需列；添加与真实 WHERE/ORDER BY 匹配的复合索引前先用 `EXPLAIN QUERY PLAN`；避免同时观察 all/active/deleted 的重叠全集。
- 修复风险：中，索引增加写放大，scoped Flow 会改变刷新边界。
- 验证方式：1k/10k/100k 合成库，记录 query plan、Room 查询时间、Flow 发射与 RSS；验证迁移。

数据库检查未发现明确异常：

- 未启用 `allowMainThreadQueries`。
- 未发现代码级 N+1 查询循环；备份的一组全表查询在单个事务内，但其后加密线程选择有问题。
- 当前 Trace 无 >2 ms 的 SQLiteConnectionPool 单次竞争导致主线程阻塞。

## 8. R8 和包体优化结果

### PERF-B07 — High — Release 完全未启用 R8/资源压缩，通用 APK 体积 103.48 MB

- 证据等级：B（构建配置与 APK 产物已确认）。
- 类别：R8、包体、安装/更新、冷加载。
- 文件与行号：`apps/android/build.gradle.kts:71-96`；`apps/android/proguard-rules.pro:1`。
- 用户流程：下载安装、更新、首次/冷启动。
- 静态与产物证据：`isMinifyEnabled = false`，未设置 `isShrinkResources = true`；项目 ProGuard 文件只有注释，无过宽、重复或无效自定义 keep 规则。没有 mapping/seeds/usage/configuration 输出。
- R8 Skill 结论：R8 9.0.32 低于 9.3.7-dev，按 heuristic 路径分析；当前阻止 shrinking/optimization/obfuscation 的根因是 R8 完全未运行，而不是 keep 规则。
- APK 证据：
  - Release 103,477,235 bytes（约 98.68 MiB）；Debug 116,818,350 bytes。
  - native libs 39.17 MiB、`res/` 33.52 MiB、DEX 23.23 MiB、assets 1.85 MiB。
  - ML Kit OCR pipeline 同时包含 x86_64 11.09 MiB、x86 11.03 MiB、arm64 10.55 MiB、armeabi-v7a 6.47 MiB。
  - `xiaolai_regular.ttf` 源文件 21.19 MiB，APK 压缩后 14.04 MiB。
  - DEX method references：classes.dex 65,217、classes2.dex 64,504、classes3.dex 2,861；Compose package约 5.44 MiB DEX。
- Trace/SQL：未将包体直接映射为启动耗时；生产 warm startup metric 中 dex open 3.404 ms，隔离 cold 为 11.282 ms，当前首帧主因仍是 UI。
- 实际影响：下载/安装更新成本高，未移除未用资源与代码，失去 R8 优化；通用 APK 含四 ABI。
- 实测确认：配置和现有 APK 确认；开启 R8 后的可节省比例未知。
- 修复建议：先在干净 CI 中确认 Release 可重复构建，再在独立小批次启用 `minify` 与资源 shrink，保留 optimized defaults；用 AAB/ABI split 交付；评估离线 OCR需求后再决定 bundled/unbundled ML Kit；对字体做合法子集化。
- 修复风险：中到高，反射/序列化/Room/ML Kit 与离线 OCR需要完整回归。
- 验证方式：生成 mapping/usage/seeds；运行 Release smoke、账号、Room、通知、无障碍/OCR；比较 APK/AAB download size 与启动 Trace。

### PERF-C02 — Medium — Baseline Profile 已接线但生成结果未进入生产 Release

- 证据等级：A（接线与产物已确认）；性能收益为 C。
- 类别：启动、AOT、构建产物。
- 文件与行号：`apps/android/build.gradle.kts:126-128,195`；`benchmarks/macrobenchmark/src/main/java/com/autoaccounting/macrobenchmark/BaselineProfileGenerator.kt:15-34`；`apps/android/build/intermediates/merged_art_profile/release/mergeReleaseArtProfile/baseline-prof.txt:1`。
- 用户流程：冷启动、账本滚动→详情、我的→自动记账。
- 静态证据：app 已应用 Baseline Profile 插件并声明 `baselineProfile(project(":benchmarks:macrobenchmark"))`，但 `automaticGenerationDuringBuild = false`。Generator 真机输出 20,017 行、2,117,709 bytes，其中 `com/autoaccounting/` 规则 1,620 行，并含 7 行 benchmark-only `BenchmarkDataProvider` 描述符；不能原样复制进生产源码。
- Trace/命令证据：当前 Release 合并 profile 为 2,606 行、0 条项目类规则，只有依赖库规则；因此已生成的关键路径 profile 尚未进入 Release。尚未执行 `CompilationMode.Partial(BaselineProfileMode.Require)` 对照，不能给出启动或帧收益。
- 实际影响：Release 目前没有针对本项目启动与关键路径的预编译规则；`CompilationMode.None()` 下观察到的 JIT 成本可能高于正确接入后的 Release，但 shader 编译不会因此消失。
- 实测确认：生产接入缺失已确认；收益未确认。
- 修复建议：通过插件的 `generateReleaseBaselineProfile`/`copyReleaseBaselineProfileIntoSrc` 正确生成或过滤规则，确认生产 source set 不含 benchmark-only 描述符，再验证 Release 合并 profile 中存在项目规则。
- 修复风险：低到中；错误复制可能把 benchmark-only 类带入 profile 或让规则失效，但不应改业务行为。
- 验证方式：检查生成/合并 profile 的项目规则与 benchmark-only 命中；用同设备至少 10 次 `CompilationMode.None` 和 10 次 `Partial(BaselineProfileMode.Require)` 比较 TtID、frame CPU 与 JIT slice。

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

批次 5 最终代码生成的 Release APK 为 103,477,235 bytes，`apksigner verify --verbose` 显示 v2 签名有效、1 个 signer，本轮未再次覆盖安装生产包。此前 2026-07-20 经用户授权安装的同签名 Release 在安装前后保持相同数据 inode，通知权限和 NotificationListener 授权保持，未清除用户数据。

### BUILD-C02 — Low — Baseline Profile Gradle Plugin 对 AGP 9.0.1 发出兼容性上限警告

- 证据等级：A（构建命令确认）。
- 类别：构建、Benchmark/Baseline Profile 兼容性。
- 文件与行号：`gradle/libs.versions.toml:13,27`；`apps/android/build.gradle.kts:126-128,195`；`benchmarks/macrobenchmark/build.gradle.kts:24-26`。
- 用户流程：开发/CI 构建 benchmark 变体、生成与接入 Baseline Profile。
- 静态证据：第二批使用 AndroidX Benchmark/Baseline Profile `1.4.1` 与 AGP `9.0.1`。插件在每次配置时明确提示其测试上限低于 `9.0.0-alpha01`，当前组合“may not work as intended”。
- Trace/命令证据：不适用 Trace；`assembleBenchmarkRelease`、三项 Macrobenchmark、Baseline Profile Generator、`lintBenchmarkRelease` 和 Release 构建均已成功，因此当前不是构建失败。
- 实际影响：已覆盖路径正常，但插件未声明验证当前 AGP 组合；未来接入 profile、升级 AGP 或 CI 环境时可能出现任务/产物差异。
- 实测确认：兼容性警告已确认；尚未观察到功能失败。
- 修复建议：不要 suppress；跟踪支持 AGP 9 的稳定插件版本，在独立依赖升级批次验证后升级。升级前保留当前真机生成、APK `.dm`/profile 内容和启动对照作为验收。
- 修复风险：低（保持现状）；升级风险中，可能改变生成任务和变体命名。
- 验证方式：执行 benchmark assemble、三项 device benchmark、profile generation、app Debug/Release 和 benchmark variant Lint；比较生成 profile 行数/有效性与启动前后数据。

## 9. 当前待修复项

当前总有 11 个待修复项：

1. [High][PERF-A02] 启动根布局仍组合过重；根级多 Flow 订阅和跨页面状态使首帧主线程持续工作。
2. [High][PERF-B01] CSV 导出和加密备份仍可能在 Main 上执行序列化、加密和文件准备，存在 ANR 风险。
3. [High][PERF-B02] 无障碍事件回调同步遍历节点树并重复查询设置，事件风暴时可能拖慢自动记账和耗电。
4. [High][PERF-B07] Release 未启用 R8 shrinking/optimization/obfuscation 和资源压缩，APK 约 103.48 MB。
5. [Medium][PERF-B05] 登录态 `restore()` 在组合阶段同步读取 SharedPreferences/Keystore；本轮未取得登录态动态耗时。
6. [Medium][PERF-B03] 账本/报表仍存在全量派生、排序、聚合和对象分配；本轮 memoization 只完成部分缓解。
7. [Medium][PERF-C01] 网络请求缺少可靠取消和 DNS/连接/TLS/服务端/下载阶段观测。
8. [Medium][PERF-B04] Room 常驻全库实体且索引与常用账本过滤/排序不完全匹配，数据增长后有扩展性风险。
9. [Medium][PERF-C02] Baseline Profile 已生成但尚未正确过滤并进入生产 Release，实际启动收益未验证。
10. [Low][PERF-B06] 30 秒健康心跳持续唤醒并写 SharedPreferences，长期耗电影响未量化。
11. [Low][BUILD-C02] Baseline Profile 插件对 AGP 9.0.1 发出兼容性上限警告，需要后续升级验证。

## 10. 建议修复批次

- 批次 1 [BUILD-A01]：已完成。修复 API 版本隔离，Lint、Release 构建和签名验证通过。
- 批次 2 [测试基础设施]：已完成。新增独立 Macrobenchmark/Baseline Profile 测试模块，三条路径各 5 次 Trace 已完成。
- 批次 3 [PERF-A01]：已完成。全屏和装饰图片后台解码；21 MiB/2 MiB 缓存；主线程目标 bitmap decode 已验证为 0。
- 批次 4 [PERF-A03][PERF-A04]：已完成。`PERF-A03` 已通过单元测试、benchmark 15/15 Trace 和修复后生产 Trace；`PERF-A04` 已按方案 1 接受为 NotificationListener 常驻架构约束，并转为性能预算管理。
- 批次 5 [PERF-A05]：已完成。节点式 indication 消除 `CircleOp` shader 首用；15/15 Trace、全量单元测试、构建、Lint、签名及解锁后视觉/交互回归通过。
- 批次 6 [PERF-C02]：正确生成、过滤并接入 Baseline Profile，再做 `BaselineProfileMode.Require` 对照。
- 批次 7 [PERF-A02][PERF-B03][PERF-B04]：拆根状态订阅、稳定 Compose UI model、报表预计算、Room scoped Flow 和复合索引。
- 批次 8 [PERF-B01][PERF-B05][PERF-C01]：导出/备份移出 Main、账号 restore 异步化、网络取消和阶段观测。
- 批次 9 [PERF-B02][PERF-B06]：无障碍事件早筛/去重/节点预算，并量化健康心跳的长期耗电。
- 批次 10 [PERF-B07][BUILD-C02]：启用 R8/资源压缩并做 Release 回归；随后隔离验证支持 AGP 9 的 Baseline Profile 插件版本。

## 11. 未验证部分、已确认未修复项与需要的帮助

以下内容分为“尚未完成验证”和“已确认但尚未修复”两类，不代表前面的构建、测试或 Macrobenchmark 全部失败。

1. 生产包真实 cold startup 未验证。通知监听服务在强停后会立即重启生产进程，因此无法取得纯 cold 样本。
   - 需要帮助：提供专用测试设备，或明确授权临时关闭通知监听服务/使用独立测试权限后重新采集。

2. Gradle UTP 入口未验证成功。设备测试入口因 Google Maven 的 `gradle-work-action-32.0.1.jar` 下载超时失败，但相同 Macrobenchmark 已通过手动 instrumentation 完成。
   - 需要帮助：提供可稳定访问 Google Maven 的网络/缓存环境；否则继续使用已通过的手动 instrumentation 后备路径。

3. Macrobenchmark 测试模块没有独立 Lint 报告。原因是 `com.android.test` 不提供 `:benchmarks:macrobenchmark:lintBenchmarkRelease` 任务；app benchmark source set 的 Lint 已通过。
   - 需要帮助：不需要新增代码；如必须有独立报告，需要接受该模块类型的任务限制或改用 CI 统一 Lint 入口。

4. 功耗数值未验证。Trace 有 CPU frequency，但设备没有有效 power rail、battery current、WakeLock/battery_stats 样本。
   - 需要帮助：提供支持功耗轨道采样的设备/固件，或允许使用 Battery Historian/长时功耗采集。

5. 冷启动内核 I/O 的具体根因未验证。已确认 D-state、`io_wait=1` 和相关 kworker，但缺少符号化 caller、`blocked_function` 和 `waker_utid`。
   - 需要帮助：提供带内核符号或更完整 ftrace 数据的采集环境。

6. 登录态启动与账号网络未验证。设备处于本地模式，本轮没有登录会话或账号请求。
   - 需要帮助：提供专用测试账号、可用后端和允许采集网络阶段数据的环境。

7. 真实微信/支付宝无障碍事件未验证。未模拟支付，也未改变无障碍权限，避免影响敏感主流程和用户状态。
   - 需要帮助：提供专用测试账号/设备，并明确授权真实支付流程回归。

8. CSV 导出和加密备份的动态耗时未验证。为避免向用户 Downloads 写入审计产物，本轮只完成静态检查。
   - 需要帮助：提供非生产测试库并授权写入临时目录，完成 1k/10k 数据规模的 Trace。

9. 长时内存泄漏和稳态耗电未验证。现有正式 Trace 约 15 秒，不能代表 30 分钟以上的稳态行为。
   - 需要帮助：允许在专用设备上运行 30 分钟以上场景，并采集 heap dump/allocation profile。

10. Compose stability/recomposition metrics 未验证。项目没有启用 Compose compiler stability/recomposition 报告。
    - 需要帮助：授权单独的构建配置变更，或接受仅使用 Perfetto 的总量证据。

11. 网络分阶段耗时未验证。当前没有 OkHttp EventListener/链路追踪，安全测试路径也没有网络请求。
    - 需要帮助：提供可控网络/代理和后端观测能力，补采 DNS、连接、TLS、服务端等待、下载阶段。

12. Baseline Profile 收益未验证。Generator 已输出 20,017 行，但当前 Release 合并 profile 为 0 条项目规则，尚未正确接入生产 Release，也未完成 `BaselineProfileMode.Require` 对照。
    - 需要帮助：授权单独接入/过滤 profile 的修复批次，并提供同设备对照窗口。

13. R8 开启后的体积和运行时收益未验证。当前 Release `minify` 和资源 shrink 关闭，没有 mapping/seeds/usage 输出。
    - 需要帮助：授权独立 Release 配置批次，并安排账号、Room、通知、无障碍/OCR 回归。

14. 已确认但尚未修复的性能问题：PERF-A02、PERF-B03、PERF-B01、PERF-B02、PERF-B07、PERF-B05、PERF-C01、PERF-B04、PERF-C02、PERF-B06、BUILD-C02。
    - 需要帮助：按第 10 节批次逐批授权修复；当前不应把这些项目描述成“测试失败”。

本轮已检查且未发现明确异常：生产 Lazy 列表均有稳定 key；生产网络 transport 在 `Dispatchers.IO`；未使用 `allowMainThreadQueries`、`runBlocking`、`Thread.sleep`、`GlobalScope`、WakeLock、AlarmManager 或 WorkManager 高频任务；生命周期解绑和 Service scope cancel 路径存在；日志无 ANR/FATAL/OOM，所有已执行交互样本均无 >700 ms frozen frame；项目 ProGuard 文件没有过宽、重复或无效自定义 keep 规则。

## Skill 适用性说明

- `perfetto-trace-analysis`：适用，最终 15 个 Trace 均完成 metrics-first、多个瓶颈、线程状态和全局异常复核，并各自保留 evidence scratchpad。
- `perfetto-sql`：适用；每个使用的表/视图先在 `perfetto-stdlib.md` 确认 schema，查询使用 upid/utid、GLOB、重叠时间窗和 `dur=-1` 处理。
- `r8-analyzer`：适用，但只能走 R8 9.0.32 heuristic；因 minify 关闭不能做 mapping/usage 实证。
- `testing-setup`：适用于盘点和第二批补测；在用户授权后复用官方 AndroidX Benchmark/UIAutomator 依赖新增独立测试模块，未改变生产运行时依赖。第三批未再新增测试框架或依赖，复用现有 412 项 JVM 测试和手动 instrumentation 后备路径完成回归；测试前锁定 natural orientation、结束后恢复自动旋转，消除了方向漂移干扰。

原最小测试补充方案已经落实：独立 Macrobenchmark/Baseline Profile 模块覆盖本报告三条关键路径。后续最小增量是把 shader、Baseline Profile、根布局分别作为独立批次验证；不需要引入新的测试框架。
