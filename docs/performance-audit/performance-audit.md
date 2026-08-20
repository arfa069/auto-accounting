# Android 项目系统性能审计报告

审计日期：2026-07-19 至 2026-07-22
仓库：`C:\Users\Arfa\Documents\bks`
Android 模块：`apps/android`
审计阶段：基线调查、补测、报告及批次 1–13 的代码修复与隔离验证已完成；批次 14–19 为定位或受控否定性验证，未保留新的生产代码改动。批次 19 在隔离 `benchmarkRelease` 包为冷启动、账本滚动→详情、首次自动记账导航各采集 10 条开启 Compose runtime tracing 的 Trace（共 30 条），3/3 Macrobenchmark 用例通过。批次 15 与批次 18 的临时生产代码候选均已完整回退；批次 16 仅增加默认关闭的构建诊断开关；批次 17 与批次 19 仅给隔离 benchmark 变体加入/复用 tracing runtime 与 Perfetto helper，默认 Debug/Release classpath 不含这些诊断依赖，未改变生产运行行为、权限或用户数据。真实支付无障碍事件与长时功耗仍未验证。批次 11 启用 Release R8/资源压缩，将 AndroidX Benchmark/Baseline Profile 升至 `1.5.0-alpha07`，并完成 AGP 9 built-in Kotlin、新 DSL 与 legacy KAPT 的最小迁移。批次 11 在独立 `com.bks.benchmark` 包执行 6 个 Macrobenchmark 用例、60 条 Trace；批次 13 与批次 14 各追加 4 个用例、40 条 Trace；批次 15 复跑 4 个自动记账导航用例；批次 17 新增 1 个运行时 tracing 用例、10 条 Trace；批次 19 复用 3 个既有用例、30 条 Trace。批次 14、17 与 19 只修改过隔离测试或诊断配置；批次 15 与 18 的实验源码均已回退；所有这些批次均未覆盖、清除或修改生产 `com.bks` 数据与权限。批次 1–3 已提交为 `9f4e8b5`，批次 4 为 `8e497fb`，批次 5 为 `a526443`，批次 6 为 `ecda05d`，批次 7 为 `4ef5204`，批次 8 为 `7ce023c`，批次 9 为 `6ca00b2`，批次 10 为 `663ab7e`，批次 11 为 `37f40ba`，批次 12 为 `4ade499`，批次 13 为 `f1a9406`。未改写 Git 历史，未 push。

批次 18 已完成根路由最小候选的受控否定性验证：隔离 `com.bks.benchmark` 以同进程首次/第二次自动记账导航的 None/Profile 四组各运行 10 次（共 80 条 Trace）；测试候选已在验证后完整回退，当前工作区不保留该生产源码改动。候选虽在三组聚合 P99 改善，但“第二次导航 + Baseline Profile”的 frame CPU P99 由基线 `13.285 ms` 升至 `14.546 ms`，不满足预先设定的“四组均不得退化”接纳标准，因此不作为修复。

批次 19 已完成 `PERF-A02` 的隔离包 runtime-tracing 覆盖：冷启动 TtID min/median/max 为 `195.6/220.0/318.0 ms`；首次自动记账导航和账本滚动→详情的 frame CPU P99 分别为 `62.3/65.5 ms`、overrun P99 分别为 `64.4/59.8 ms`。该模式会增加观测开销，以上聚合数只用于本批路径内定位，不能与批次 14–18 未开启 tracing 的 P99 横向比较。

证据保留方式：自 2026-07-21 起，`docs/performance-audit/evidence/` 中的 Trace、JSON、SQL scratchpad 与截图仅保留在执行验证的本机，已由 Git 忽略；本报告保留复现命令、Trace 名称、SQL 结论和汇总数值。

批次 20 的图片异步解码候选未完成基线/候选对照，且仅针对约 3–6 ms 的 `painterResource` 子项，不能解决 `PERF-A02` 的主线程 Compose→measure/draw 长尾或 `PERF-A08` 的首次 GPU shader cache 成本。该候选及其测试已撤回、未提交；本审计停止继续 Trace、安装重试或同类定位。

## 证据等级

- 当前批次状态：批次 13 已提交为 `f1a9406`；批次 14 的隔离真机对照、报告和测试源更新已完成。批次 15 已完成 `animateOutgoingContent = false` 的根路由离场动画实验及 4/4 真机 Macrobenchmark：仅“第二次导航 None”改善，其余三组 P99 变差，因此实验已回退且不作为修复。批次 16 新增默认关闭的 `-PcomposeCompilerReports=true` 诊断开关；Debug 编译成功输出稳定性、可跳过性与模块指标，自动记账页的全部入口参数均为 stable。批次 17 在独立包运行 `automaticBookkeepingSecondNavigation` 10 次并开启 full tracing；批次 19 又以相同方式覆盖冷启动、账本滚动→详情、首次自动记账导航各 10 次。runtime tracing 具名记录了 `SlidePageTransition`、各路径首建页面、状态卡、账本表单和卡片组合；该 tracing 有额外开销，不能与批次 14–18 的未开启 tracing P99 横向比较。批次 15、17、18 与 19 的原始 Trace、JSON 和 scratchpad 均仅保留在本机 Git 忽略 evidence 目录；批次 16 报告位于 Git 忽略的 `apps/android/build/reports/compose-compiler/`。`PERF-A02` 已在隔离包补齐三条关键路径的逐 Composable 运行时耗时，真实生产包仍未以该 tracing 方式覆盖；`PERF-A08` 已确认是进程内首次进入关键路由的 GPU shader 首用成本，不是同一会话每次重复导航的持续成本。

- 批次 18：四组同进程导航矩阵均在 Xiaomi `2a9ea4bd` 的隔离 R8 benchmark 包完成，原始产物位于 Git 忽略的 `docs/performance-audit/evidence/device-results-20260721-batch18/`。基线/候选的首次 None CPU/overrun P99 为 `60.389/52.652 → 49.085/51.241 ms`，首次 Profile 为 `45.174/39.071 → 36.135/31.995 ms`，第二次 None 为 `52.042/48.223 → 47.705/46.543 ms`，第二次 Profile 为 `13.285/8.435 → 14.546/4.203 ms`。因最后一组 CPU P99 退化 `1.261 ms`，候选已完整回退。代表候选 Trace 的主线程长帧仍由组合、测量与 View 绘制构成，而非 RenderThread、Binder、I/O 或调度等待。

- 批次 19：3/3 隔离 Macrobenchmark 用例通过，30 条开启 `androidx.benchmark.perfettoSdkTracing.enable=true` 的 Trace 位于 Git 忽略的 `docs/performance-audit/evidence/device-results-20260722-batch19/runtime-tracing/`。三个代表帧都把主因定位在主线程 Compose 组合及后续 measure/draw；首次路由另有独立 RenderThread shader cache miss。精确时间窗、线程状态、Binder、锁、GC、RSS、CPU frequency 与 power 轨道限制见 `PERF-A02`、`PERF-A08` 和第 11 节。

- 批次 20：图片异步解码候选在完成前已取消并回退，未提交。它不构成 `PERF-A02` 或 `PERF-A08` 的修复，也不再安排真机安装、Macrobenchmark 或 Perfetto 复测。

- A：已通过真机、Perfetto、ADB 或本轮构建命令确认。
- B：静态代码中高度可信的风险；当前样本未覆盖触发条件或数据规模。
- C：需要补充数据才能确认；报告明确说明缺少的数据源。

## 1. 项目性能概览

- Application ID：Release `com.bks`，Debug `com.bks.debug`，独立性能目标包 `com.bks.benchmark`；Macrobenchmark 测试包 `com.bks.macrobenchmark`。
- SDK：minSdk 29，targetSdk/compileSdk 36。
- 工具链：AGP 9.0.1、Kotlin 2.3.20、Java 17、Compose BOM 2024.12.01、Room 2.8.4。
- R8：9.0.32；批次 11 已启用 Release shrinking/optimization/obfuscation 与资源压缩，生成 `mapping.txt`、`usage.txt`，Release APK 从 103,493,619 降至 80,529,815 bytes（-22.19%）。
- 设备：Xiaomi 24117RK2CC，Android API 36，逻辑分辨率 1080×2400，serial `2a9ea4bd`。
- 初始 Git 状态：`master...origin/master`，无已有 diff；审计期间未覆盖用户工作。
- 测试设施：原有 66 个本地测试文件、1 个 Room 真机测试、JUnit 4/Robolectric/Compose UI Test/Room Testing/JaCoCo；第二批新增独立 `benchmarks/macrobenchmark` 模块，覆盖冷启动、账本滚动→详情、我的→自动记账及 Baseline Profile 生成。
- 第二批产物：三条路径各 5 次，共 15 个正式 Perfetto Trace；1 份 Macrobenchmark JSON；Baseline Profile Generator 成功输出 20,017 行 profile。第三、第四、第五批分别完成对应回归。批次 6 重新生成 20,143 行、2,149,642 bytes 的生产 source profile，并完成三条路径各 10 次 None/Profile 对照，共 60 个 Trace、60 份逐 Trace scratchpad 和 1 份 JSON。批次 7 完成同设备 7/7 instrumentation（Generator 加 6 个用例），另生成 60 个 Trace、1 份 JSON 和 6 份代表性 SQL scratchpad。批次 8 重新生成 20,000 行、2,143,362 bytes 的 source profile（1,798 条项目规则、0 条 benchmark-only 规则），完成 1k/10k 报表路径各 5 次、共 10 个 Trace、1 份 JSON 和 2 份代表性 SQL scratchpad。批次 9 新增 12 个隔离环境 Trace 与 12 份逐 Trace evidence scratchpad。批次 10 新增 3 条有效隔离策略 Trace 与 3 份逐 Trace evidence scratchpad，路径为 `%LOCALAPPDATA%\Temp\bks-perf-audit-20260719\device-results-20260720-batch10`。批次 13 新增 40 条隔离 R8 Trace、1 份聚合 JSON、4 份新 scratchpad，并为批次 12 账本 Trace 追加 1 份 RenderThread SQL scratchpad。批次 14 新增 40 条隔离 R8 Trace、1 份聚合 JSON、4 份代表性 scratchpad 与首次/第二次导航对照 README。批次 15 在同一隔离模块重跑 4 个导航用例；45 个原始产物与 3 份 Perfetto scratchpad 仅保留在 Git 忽略 evidence 目录。批次 17 新增 10 条 full-tracing Trace、1 份聚合 JSON 和 1 份代表性 SQL scratchpad，目录为 `docs/performance-audit/evidence/device-results-20260721-batch17/`。批次 19 复用 3 个既有用例完成 3/3 runtime-tracing Macrobenchmark，新增 30 条 Trace 与 3 份代表性 SQL scratchpad，目录为 `docs/performance-audit/evidence/device-results-20260722-batch19/runtime-tracing/`。项目仍没有截图测试或完整通用 E2E 套件。

- 批次 18 新增 80 条隔离 R8 Trace、2 份聚合 JSON 和 1 份代表性 SQL scratchpad：`baseline-matrix/` 与 `candidate-matrix/` 分别包含首次/第二次导航的 None/Profile 四组各 10 次；另有一次仅用于预检的首次 None 基线，不参与接纳对照。候选源码已完整回退，evidence 仅保留测试结果，不保存为 Git 受控产物。
- 总体结论：第三批消除目标主线程 bitmap decode；第四批消除诊断历史启动读取和 Keystore Binder 风暴；第五批消除账本点击的 Material ripple 特定 shader 首用；批次 6 已使生产 Release 包含关键路径 Profile 规则；批次 7 消除根 UI 的全库账目订阅、把账本统计下推到 Room、以 v7 复合索引支持 scoped 查询，并让报表只消费预计算模型。批次 8 证实 1k/10k 报表进入的长尾均主要是主线程组合、布局和绘制，而非数据量线性放大。批次 9 证实会话恢复可在隔离登录态进入主页、受控请求可在约 206 ms 取消、1k/10k CSV 与加密备份重活位于后台线程。批次 10 已使非手动、非支付相关事件在读取根节点前退出，并将连续自动记账相同窗口事件合并；隔离 1,000 事件 admission 的 trace section 为 0.336–0.406 ms，2 分钟受控心跳仅持久化 3 次。批次 11 已启用 R8/资源压缩。批次 13 进一步确认冷启动仍有主线程 I/O 与生命周期长尾，关键路由还存在独立的 GPU shader cache miss；批次 14 确认该 shader 成本发生于进程内首次进入，第二次导航不再出现，残余长尾主要回到主线程 Compose 组合、测量和绘制；批次 15 进一步排除“仅关闭根路由离场动画”这一低侵入候选，因收益未跨首次/第二次与 None/Profile 四组稳定复现；批次 16 证明自动记账页及根转场已具备编译器层面的可跳过性和稳定输入；批次 17 将重复导航中两条相邻最慢帧分别定位到 `ProfileOverviewScreen` 与 `AutomaticBookkeepingScreen` 的 `SlidePageTransition` 组合。批次 19 扩展到冷启动、账本详情和首次自动记账导航，三条最慢帧均显示 UI 线程持续 Running 的 Compose→measure/draw 长尾；首次路由同时出现独立 RenderThread shader cache miss。当前没有可由这些证据单独确认的低风险生产修复候选。该结论不代表真实微信/支付宝节点树、遗漏率或功耗已验证。

- 批次 18 补充结论：根路由最小候选的 4×10 对照完成后，首次 None/Profile 与第二次 None 聚合 P99 均改善，但第二次 Profile 的 frame CPU P99 `13.285 → 14.546 ms` 退化；单次候选 Trace 的最大 CPU/overrun 也达到 `64.252/66.842 ms`，高于相同基线矩阵的 `55.976/51.497 ms`。候选代表 Trace `CriticalUserJourneysBenchmark_automaticBookkeepingSecondNavigationBaselineProfile_iter007_2026-07-21-12-34-10.perfetto-trace` 有 57 个 app frame、1 条 deadline miss、0 dropped frame；最长主线程帧为 `17.736 ms`，其中 `traversal/draw/Record View#draw()/measureAndLayout/Recomposer` 为 `13.065/12.991/12.511/8.684/4.362 ms`。主线程在该帧 Running `17.447 ms`，RenderThread 仅 Running `0.458 ms`、Sleeping `17.152 ms`；重叠 Binder 均为 `0.000 ms`，无 shader/cache miss。`Background concurrent copying GC` 与该帧重叠 `10.905 ms`，但此单条 Trace 不能把 GC 外推为四组回归的唯一原因。候选已回退，不作为生产修复。
- 批次 19 补充结论：开启 runtime tracing 后，冷启动 TtID min/median/max 为 `195.6/220.0/318.0 ms`；首次自动记账导航 frame CPU/overrun P99 为 `62.3/64.4 ms`；账本滚动→详情为 `65.5/59.8 ms`。冷启动代表 `doFrame` 的 `measure` 为 `145.880 ms`，但它仅在 first-frame 前开始 `4.066 ms`、其余延续到首帧后，故记录为首屏稳定长尾而非把全部计入 TtID；首次自动记账和账本详情的最慢 UI 帧分别为 `100.660/86.656 ms`、`109.970/95.800 ms`（CPU/overrun）。这些数值含 tracing 开销，只用于逐组件归因，不构成与未开启 tracing 批次的回归对比。
- 批次 12 补充结论：账本内部转场取消旧页离场动画后，None/Profile frame CPU P99 为 32.40/25.27 ms，overrun P99 为 24.57/14.11 ms；代表 Trace 的 `Recomposer:recompose` 从 47.023 ms 降至 9.514 ms。当时 RenderThread 全屏绘制仍有 27.606 ms 长尾，冷启动和其他路由尚未验证，因此没有关闭 `PERF-A02`；这些缺口已由批次 13 补齐。
- 批次 13 补充结论：冷启动 None/Profile 的 `StartupTimingMetric` 中位数为 260.308/244.419 ms；主页→自动记账 frame CPU P99 为 48.124/41.302 ms，overrun P99 为 52.228/37.947 ms。三条代表 Trace 的 RenderThread 全屏 `Drawing` 均由 `shader_compile → ShaderCache::cache_miss → driver_compile_shader/driver_link_program` 主导，单段约 23.8–30.7 ms；该 GPU 长尾独立登记为 `PERF-A08`。
- 批次 14 补充结论：同进程第二次自动记账导航的 None/Profile CPU P99 为 35.072/13.120 ms，overrun P99 为 35.793/1.886 ms；首次导航为 43.789/40.828 ms 和 52.072/36.985 ms。全部 20 条第二次导航 Trace 的目标 RenderThread 均为 0 次 `shader_compile`/`cache_miss`；首次导航 20 条 Trace 均记录 shader 编译。首次 None 的 cache miss 为 3/10，首次 Profile 为 10/10，但测试执行顺序与驱动缓存状态并未随机化，不能据此比较 CompilationMode 的 GPU 回归。
- 批次 15 补充结论：根路由临时设置 `SlidePageTransition(..., animateOutgoingContent = false)` 后，第二次导航 None/Profile 的 CPU/overrun P99 分别为 31.532/23.849 ms 与 15.650/6.946 ms；首次导航为 None 63.932/57.336 ms、Profile 46.993/38.974 ms。相对批次 14，仅第二次 None 改善；第二次 Profile、首次 None 和首次 Profile 均变差。代表第二次 None 长帧的 UI 线程连续 Running 47.194 ms，包含 `Recomposer` 17.117 ms、`measureAndLayout` 21.531 ms、`Record View#draw()` 28.949 ms，而 RenderThread 仅 Running 5.918 ms；首次 None 仍有 28.919 ms `shader_compile` 与 28.422 ms `cache_miss`。因此该实验已完整回退，不作为生产修复。

## 2. 启动性能结果

### PERF-A02 — High — 启动首帧与关键路由 UI 组合/测量长尾

- 证据等级：A。
- 类别：启动、Compose、主线程、Frame Timeline。
- 文件与行号：`apps/android/src/main/java/com/bks/MainActivity.kt:390-410,616,878`；`data/local/LocalLedgerRepository.kt:32-74`；`feature/ledger/LedgerScreen.kt:104-164,257-309`；`feature/ledger/LedgerEntryEditorScreens.kt:118,639`；`feature/monitoring/AutomaticBookkeepingScreen.kt:31-172`；`ui/components/PageTransitions.kt:15-39`。
- 用户流程：应用启动到主页稳定；主页 → 我的 → 自动记账；账本滚动 → 详情。
- 静态证据：第三批已用 `remember` 缓存 tab/底部导航、当前账本列表、删除列表、账本统计、权限健康状态和根路由；账本页也缓存选中项、页面状态、编辑初始值、月份、汇总及筛选结果。批次 7 将账本、当前账本 active/deleted entries、分类和资金账户的五个根级 collector 合并为 `LocalLedgerRepository.state`，其账目只查询当前账本；账本统计改为 Room 聚合。根页面仍承担跨页面状态和转场组合，因此这不是完整根布局拆分。
- Trace/SQL：生产包后台服务重启样本 `startup-force-stop-bg-restarted-01.perfetto-trace` 为 warm 324.684 ms。第三批最终五次 `android_startup` TTID 为 516.516、524.360、571.608、558.376、567.506 ms；intent→first-frame 为 443.864、425.877、497.068、466.157、478.806 ms。首帧前主线程 Running 236.197–250.085 ms、Runnable 4.038–6.572 ms、I/O D-state 34.308–113.333 ms；最长单段 I/O D-state 16.541 ms，Trace 缺少 `blocked_function` 和 `waker_utid`，无法继续归因具体内核函数/文件。代表首帧仍有约 146.590 ms `measure` 和 36.457 ms `Compose:recompose`；5 次均无 GC。批次 13 的隔离 R8 冷启动 None/Profile 各 10 次中位数为 260.308/244.419 ms；最慢代表 Trace intent→first-frame 为 338.444/314.718 ms。None 样本主线程 uninterruptible I/O sleep 为 100.048 ms，最长 D-state 39.840 ms，仍无 `blocked_function`/`waker_utid`；Profile 样本为 26.762 ms I/O sleep，但 `installd` 正在运行且 `activityResume` 为 104.997 ms，不能把该单样本当作纯应用回归。批次 14 的同进程第二次自动记账 None 代表帧为 CPU `53.610 ms`、overrun `40.775 ms`，其中 UI `Recomposer:recompose` `16.960 ms`、`AndroidOwner:measureAndLayout` `21.628 ms`、`Record View#draw()` `28.575 ms`，RenderThread `DrawFrame` 仅 `3.247 ms`；UI 线程持续 Running `49.458 ms`，无 D-state/I/O wait/blocked function。第二次 Profile 代表帧为 CPU `18.192 ms`、overrun `5.304 ms`，UI `measureAndLayout` `7.983 ms`。两条代表 Trace 的相关 Binder 事务最长仅 `322.188 µs`，均无 GC slice。
- 批次 15 Trace/SQL：临时关闭根路由离场动画的 4/4 用例均通过，证据位于 Git 忽略目录。`CriticalUserJourneysBenchmark_automaticBookkeepingSecondNavigation_iter006_2026-07-21-04-50-34.perfetto-trace` 的最慢帧为 CPU `53.604 ms`、overrun `40.313 ms`，UI 连续 Running `47.194 ms`，含 `Recomposer` `17.117 ms`、`measureAndLayout` `21.531 ms`、`Record View#draw()` `28.949 ms`，RenderThread 仅 Running `5.918 ms`；无 I/O wait、blocked function 或可测 Binder 主因。对应 Profile Trace 的最慢帧为 CPU `21.450 ms`、overrun `7.934 ms`，`Recomposer`/`measureAndLayout`/`Record View#draw()` 为 `3.162/8.759/12.391 ms`。两条第二次导航代表 Trace 均为 `shader_compile=0`、`cache_miss=0`。首次 None Trace 的最慢帧为 CPU `100.370 ms`、overrun `86.226 ms`，`Choreographer#doFrame` `67.737 ms`，RenderThread `DrawFrames` `31.852 ms`，其中 `shader_compile`/`cache_miss` 为 `28.919/28.422 ms`，仍有 GPU shader 首用。
- 批次 16 编译器报告：使用 `.\gradlew.bat :apps:android:compileDebugKotlin -PcomposeCompilerReports=true --rerun-tasks --console=plain` 成功生成 `android-classes.txt`、`android-composables.txt/.csv/.log` 与 `debug/android-module.json`。Debug 模块有 612 个 Composable、611 个 restartable、453 个 skippable；`knownUnstableArguments=148`、推断稳定/不稳定类为 `129/60`。`BksApp`、`AutomaticBookkeepingScreen` 与 `SlidePageTransition` 均为 restartable skippable，`AutomaticBookkeepingScreen` 的权限、状态、回调和 `Modifier` 参数均为 stable。`LedgerRepositoryState` 及其 `List` 字段仍为 unstable，但它不传入自动记账页；不能据此给全局状态或集合强加 `@Stable`/`@Immutable`。该报告是编译期静态资格，不记录实际运行时重组次数或单个组件耗时。
- 批次 17 Trace/SQL：隔离 `benchmarkRelease` 的 `automaticBookkeepingSecondNavigation` 开启 full tracing 并完成 10 次。共 `538` 帧，frame CPU P50/P90/P95/P99 为 `2.605/4.322/8.403/17.141 ms`，overrun 为 `-8.353/-3.830/-1.119/7.705 ms`；正 overrun `20/538`，最大 `39.159 ms`。代表 Trace `CriticalUserJourneysBenchmark_automaticBookkeepingSecondNavigation_iter000_2026-07-21-06-21-31.perfetto-trace` 的两条相邻最慢帧为 `34.934/33.382 ms`：前者 `Compose:recompose 7.046 ms → SlidePageTransition 6.913 ms → AppWallpaper 5.021 ms → ProfileOverviewScreen 4.788 ms`；后者 `Recomposer:recompose 13.473 ms → Compose:recompose 8.324 ms → SlidePageTransition 8.157 ms → AutomaticBookkeepingScreen 5.777 ms`，其状态卡/卡片为 `1.361/1.339 ms`。后者还含 `AndroidOwner:measureAndLayout 11.772 ms` 与 `Record View#draw() 16.317 ms`。UI `utid=24` 在该帧 Running `33.178 ms`（CPU 3），RenderThread `utid=12169` 仅 Running `4.689 ms`、Sleeping `28.394 ms`；10 条重叠 Binder 事务均为 `0.000 ms`，无 monitor contention，app D-state 最大 `0.030 ms`。同帧 GC 重叠 `16.332 ms`，但 UI 未被挂起；CPU 3 随后升至 `2,323,200 kHz`，不支持 CPU 降频归因。Trace 的 `android_startup` 为空、`android_jank` metric 在本机 trace_processor 不可用，且有 `power_rail_empty_packet`，故分别以 Macrobenchmark FrameTiming 和 SQL 明确记录边界。
- 批次 18 Trace/SQL：未开启 runtime tracing 的基线/候选均执行首次/第二次 None/Profile 各 10 次。候选相对同批基线的 frame CPU/overrun P99 为：首次 None `60.389/52.652 → 49.085/51.241 ms`、首次 Profile `45.174/39.071 → 36.135/31.995 ms`、第二次 None `52.042/48.223 → 47.705/46.543 ms`、第二次 Profile `13.285/8.435 → 14.546/4.203 ms`。最后一组 CPU P99 退化 `1.261 ms`，未通过“四组均不退化”的接纳门槛。代表候选 Trace `CriticalUserJourneysBenchmark_automaticBookkeepingSecondNavigationBaselineProfile_iter007_2026-07-21-12-34-10.perfetto-trace` 的 app FrameTimeline 为 57 帧、1 条 App Deadline Missed、0 dropped；最长 `Choreographer#doFrame` `17.736 ms` 含 `traversal 13.065 ms`、`Record View#draw() 12.511 ms`、`measureAndLayout 8.684 ms`、`Recomposer 4.362 ms`。该帧 UI `utid=17` Running `17.447 ms`，RenderThread `utid=9071` Running `0.458 ms`、Sleeping `17.152 ms`；重叠异步 Binder 均为 `0.000 ms`、无 shader/cache miss。`HeapTaskDaemon` 的 concurrent-copying GC 与该帧重叠 `10.905 ms`，但样本不足以把它归为矩阵回归的唯一原因；Trace 仍有 `power_rail_empty_packet`。
- 批次 19 Trace/SQL：隔离 `benchmarkRelease` 为冷启动、首次自动记账导航、账本滚动→详情分别开启 `androidx.benchmark.perfettoSdkTracing.enable=true` 并各完成 10 次。冷启动代表 `CriticalUserJourneysBenchmark_coldStartup_iter006_2026-07-22-04-51-55.perfetto-trace` 的 `android_startups` cold intent→first-frame 为 `322.421 ms`（该次 Macrobenchmark TtID `317.951 ms`）；主线程 `utid=968` 在首帧前 Running/Runnable/I/O D-state/Sleeping 为 `85.839/3.592/3.686/139.313 ms`。末尾 `Choreographer#doFrame` 为 `157.298 ms`，`traversal/measure/AndroidOwner:onMeasure` 为 `149.903/145.880/145.820 ms`；其中 `AppBottomNavigationBar` 为 `19.833 ms`，首个导航项的 `painterResource` 解码 `res/dh.png` 为 `6.006 ms`。该 doFrame 在 first-frame 前仅开始 `4.066 ms`、大部分延续到首帧后，故不能把其全部 145.880 ms measure 归因成 TtID，只能确认首屏稳定阶段的布局长尾。同帧 UI 在 CPU 7（`3,052,800 kHz`）Running `131.971 ms`，不支持降频或调度饥饿归因。最长 I/O D-state `5.913 ms` 与 `AssetManager::OpenNonAssetFd(res/Ap.ttf)` `6.473 ms` 重叠，但 `blocked_function`/`waker_utid` 缺失。首次自动记账代表 `...automaticBookkeepingFirstNavigation_iter005_2026-07-22-04-52-55.perfetto-trace` 的最慢帧 CPU/overrun/UI 为 `100.660/86.656/61.894 ms`：`Recomposer:recompose` `28.508 ms` → `Compose:recompose` `17.013 ms` → `SlidePageTransition` `16.746 ms` → `AutomaticBookkeepingScreen` `13.000 ms`，随后 `measureAndLayout`/`Record View#draw()` 为 `25.724/32.628 ms`；UI `utid=29` 在 CPU 6 连续 Running `61.600 ms`，无 D-state/I/O wait，Binder 最长 `0.409 ms`、无锁竞争、无重叠 GC。账本代表 `...ledgerScrollAndDetail_iter000_2026-07-22-04-53-35.perfetto-trace` 的最慢帧 CPU/overrun/UI 为 `109.970/95.800/102.467 ms`：`Recomposer` `59.435 ms` → `LedgerScreen` `45.107 ms` → `SlidePageTransition` `44.723 ms` → `LedgerEntryForm` `41.323 ms`，随后 `traversal/measureAndLayout/Record View#draw()` 为 `42.884/32.587/42.000 ms`。主线程 `utid=7` 在 CPU 6 连续 Running `102.007 ms`，Runnable `0.084 ms`、无 D-state、无重叠 GC 或同步 Binder/锁竞争；`CategoryArtwork` 的主线程 `painterResource` 解码额外占 `3.824 ms`。三条路径的逐 Trace 原始证据与 SQL scratchpad 位于 Git 忽略目录 `docs/performance-audit/evidence/device-results-20260722-batch19/runtime-tracing/`。
- 实际影响：首屏与首次关键路由仍主要消耗在应用主线程 UI 工作和启动 I/O，而不是调度饥饿；批次 14 证明 GPU shader 不是重复导航的持续主因。批次 15 进一步表明根路由离场页是否同时绘制不是这条链的稳定低风险根因：它没有同时改善首次/第二次与 None/Profile 四组。批次 17 与批次 19 进一步确认：`SlidePageTransition` 两侧的自动记账页和账本详情首建会把组合、测量和 View 记录绘制叠加到同一帧，三个代表长帧均主要为 UI 线程自身 Running。批次 8 的 1k/10k 报表路径未观察到数据量线性放大。Baseline Profile 在本轮冷启动中位数降低 6.10%，并降低主页路由的组合/测量长尾，但没有使该问题归零。
- 批次 18 进一步说明：即使同一候选在三组 P99 聚合结果改善，只要另一组的 CPU P99 退化，就不能把它当成稳定收益。当前应保留已回退的基线行为，不把单次或单指标改善作为生产优化结论。
- 实测确认：部分修复已确认；冷启动、主页路由、账本路径和同进程第二次路由均已补测。批次 15 的根路由实验是已实测的否定结果，代码已回退；批次 16 的编译器 stability/skippability 报告也已生成；批次 17 与批次 19 已实测确认隔离包四种导航状态以及冷启动、账本详情、首次自动记账的逐 Composable 运行时耗时。runtime tracing 覆盖全部三条关键隔离路径，但会增加观测开销，不能作为批次 14–18 P99 的横向回归数据；GPU shader 长尾已从本项分离到 `PERF-A08`。
- 批次 18 是第二次已实测的根路由候选否定结果；候选代码已回退，当前只保留其基线/候选矩阵、代表 Trace 和 SQL scratchpad，不能作为已修复项计入。
- 修复建议：批次 7 已完成 scoped Flow、稳定报表模型和 Room 聚合/索引的低风险部分；批次 8、14、15、16、18、19 已分别排除数据规模、重复 shader 编译、仅关闭根路由离场动画、入口参数不稳定和根路由最小候选等低风险解释。当前不存在能精确落到单一 Kotlin 输入、又不改变交互或视觉设计的安全生产代码候选。
- 后续边界：`PERF-A02` 保留为待决的设计级问题。只有产品明确接受页面切换视觉、首建内容密度或布局方案的调整时，才另行立项；本审计不再进行同类 Trace 定位、安装重试或小候选试验。
- 修复风险：中到高，涉及状态刷新、导航返回、转场视觉和列表滚动语义。
- 验证方式：本审计已完成冷启动、账本详情、首次/第二次自动记账导航的隔离 Macrobenchmark 与 tracing 对照，且两个低风险生产候选均已回退。若未来经产品决策开展 UI 重设计，应从当前已回退代码重新建立同批基线，再以同进程首次/第二次 None/Profile 对照验证视觉、状态语义和帧 P99；这不是当前审计的待执行工作。

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
- 验证方式：本轮已完成三条路径各 5 次、SQL 主线程/后台线程归因、RSS/GC 查询及截图。主线程目标图片解码验收通过；“全部 >50 ms 帧归零”和“RSS 峰值下降”不是本项完成条件。`PERF-A05` 已在第五批独立完成，当前剩余 UI 长帧归入 `PERF-A02`，GPU shader cache miss 归入 `PERF-A08`。

### PERF-A05 — High — 账本点击 ripple 离场触发 RenderThread CircleOp shader 首用（已完成）

- 证据等级：A。
- 类别：Compose、布局、RenderThread、GPU/JIT。
- 文件与行号：`feature/ledger/LedgerScreen.kt:469-549`；`ui/components/PageTransitions.kt:14-34`。shader 编译为运行时 RenderThread 证据，无直接 Kotlin 源码行。
- 用户流程：账本 → 滚动固定 40 条账目 → 打开详情。
- 静态证据：修复前账目行使用 Material 圆形 ripple；修复后改为 `IndicationNodeFactory` + `DrawModifierNode`，节点直接收集 Press/Release，仅在按压状态边界调用 `invalidateDraw()` 并绘制 8% `onSurface` 覆盖层，detach 时清空未完成按压。按压状态不进入 Compose State，不触发按压重组或测量；`Modifier.clickable` 的语义、点击回调和详情导航保持不变。
- Trace/SQL：修复前第三批最终账本 5/5 出现 33.767–56.824 ms 的 `CircleOp → shader_compile`。修复后两组正常态 120 Hz 样本和一组系统 60 Hz 限制压力样本共 15/15 次未复现该按压 ripple 路径，GC 15/15 为 0。批次 13 的新账本 Trace 仍有 `CircleOp` 与 `shader_compile`，但父链为全屏 `DrawFrames → flush commands`，且 Perfetto 无法映射到单一 Kotlin composable；当前 `LedgerEntryRow` 仍使用自定义矩形按压 indication，因此该新证据不能证明 Material ripple 回归，另以 `PERF-A08` 跟踪。
- 实际影响：已消除首次点击账目离场时原有 33.767–56.824 ms 的圆形 ripple shader 编译停顿。当前仍可见不同 GPU shader cache miss 和 UI 长尾，分别归入 `PERF-A08` 与 `PERF-A02`，不再把“所有 CircleOp 为零”作为本项完成条件。
- 实测确认：已确认原始 ripple 修复完成。解锁前后均完成受控视觉与交互回归，按压时整张卡片出现轻微灰色反馈且被圆角正确裁剪，释放后进入对应“编辑账目”页。生产包 `ceDataInode=1648470`、`deDataInode=1833109` 保持不变，未清数据或卸载。
- 修复建议：批次 5 已完成，保留节点式 indication。不要改回依赖 `collectIsPressedAsState()` 的卡片颜色或 `graphicsLayer` 方案；前者在被拒绝实验中造成 66.070–100.254 ms 重组和 41.429–63.476 ms 测量。
- 修复风险：中。自定义 indication 改变了 Material 默认 ripple 的视觉形式，但保留 clickable 语义和导航行为；后续主题、圆角或卡片容器变化时需要复查覆盖层颜色与裁剪。
- 验证方式：已完成账本定向测试、整个 ledger 测试包、Android 全量单元测试、Benchmark/Release 构建、Release Lint、APK v2 签名验证、15 次 Trace SQL 和解锁后视觉/导航回归。后续回归检查自定义按压覆盖层、详情打开、返回和列表状态保持，不以全局 `CircleOp` 计数作为 ripple 回归判据。

### PERF-A08 — High — 关键路由首次全屏绘制触发 GPU shader cache miss（已验证、已接受）

- 证据等级：A（隔离 R8 Macrobenchmark、Frame Timeline、RenderThread thread-state 和 SQL）。
- 类别：RenderThread、GPU、关键路由、帧卡顿。
- 文件与行号：`ui/components/PageTransitions.kt:15-39`；`feature/ledger/LedgerScreen.kt:147-169,469-514`；`feature/monitoring/AutomaticBookkeepingScreen.kt:31-172`。这些文件证实了路径和全屏 Compose 路由；Perfetto 的 Skia `CircleOp`/`CircularRRectOp` 不能精确映射到其中某一行，当前没有已证实的单一 Kotlin 根因。
- 用户流程：账本滚动 → 详情；主页 → 我的 → 自动记账。
- 静态证据：两条路径都在 `MainActivity` View 树内完成全屏 Compose 组合与路由切换；账本项使用 Category artwork、Card 和圆角/边框，自动记账页使用 Card、按钮、文本与全屏滚动布局。上述静态证据只能证明绘制输入存在，不能单独归因某个形状或组件。
- Trace/SQL：账本代表 Trace `703094593899927..703094621506125 ns` 中 RenderThread `utid=5328` 的 `DrawFrames 159843497` 为 27.606 ms，`Drawing 1080×2400` 为 27.161 ms，包含 14.933 + 8.133 ms 的 `shader_compile`、14.686 + 7.999 ms `ShaderCache::cache_miss`。自动记账批次 13 None/Profile 代表绘制分别包含 29.530/28.646 ms 与 29.257/28.581 ms 的 shader/cache-miss 链。批次 14 的同进程对照中，首次 None 代表 `DrawFrames 160690342` 的 `28.645 ms` 内有 `shader_compile 25.517 ms → cache_miss 25.044 ms → driver_link_program 13.549 ms`；首次 Profile 代表 `DrawFrames 160659462` 的 `31.934 ms` 内有 `29.068/28.401/13.556 ms`。首次 None/Profile 的 20 条 Trace 均有 `shader_compile`；cache miss 分别为 3/10 与 10/10。全部 20 条同进程第二次导航 Trace 均为 0 次 shader 编译和 cache miss，代表 RenderThread `DrawFrame` 为 3.247/2.116 ms。首次长尾 RenderThread 主要为 Running，无 D-state/I/O wait/blocked function；相关 Binder 事务最长 322.188 µs。
- 批次 19 Trace/SQL：同一 runtime-tracing 采集明确显示该问题与 `PERF-A02` 的主线程组合长尾独立存在。冷启动代表 Trace 的首个 RenderThread `DrawFrames` 为 `28.485 ms`，含 7 个 `shader_compile/cache_miss`（各 `2.433–3.727 ms`）与一次 1080×2400 texture upload `3.296 ms`。首次自动记账的 `DrawFrames 188500900` 延后于 UI 帧启动 `61.467 ms`，其 `Drawing/shader_compile/cache_miss` 为 `34.963/32.240/31.738 ms`，`driver_compile_shader/driver_link_program` 为 `10.395/17.962 ms`。账本详情最慢 UI 帧后，独立 RenderThread 绘制含两组 `shader_compile/cache_miss` `14.176/13.893 ms`、`11.347/11.178 ms`，以及 `driver_link_program` `8.573/6.639 ms`。三条代表 Trace 的相应 UI 长帧均无同步 Binder、monitor contention 或重叠 GC，故不把 shader 编译、Binder、锁或 GC 混写为同一根因。
- 实际影响：批次 14 两条代表 Trace 的 FrameTimeline 相邻帧间隔为约 `7.54–8.99 ms`，且测试后设备 active display mode 为 120 Hz；因此首次路由中 RenderThread 的约 25–35 ms 单段绘制相当于约 3–4 个 120 Hz 帧预算。批次 19 在冷启动、首次自动记账和账本详情也再次捕获 shader 首用链，但它与同帧/相邻 UI 的 Compose→measure/draw 长尾是独立成本。它不是用户在同一会话每次返回并重进该页都会承受的成本。自动记账首次导航的 frame CPU/overrun P99 为 None `43.789/52.072 ms`、Profile `40.828/36.985 ms`；第二次为 None `35.072/35.793 ms`、Profile `13.120/1.886 ms`。Baseline Profile 降低 UI 线程工作，但不预热 GPU shader cache。
- 实测确认：已确认隔离 benchmark 包的进程内发生边界：同一进程首用存在 shader 长尾，第二次导航不复现。批次 19 将该观测扩展到冷启动、账本详情和首次自动记账；此项已作为 GPU 驱动的每进程首用成本接受，不再列为待修复项。真实生产包、长期用户会话以及跨设备 GPU 驱动的发生频率仍未验证；Profile/None cache-miss 次数受测试执行顺序与驱动缓存状态影响，未作为两种 CompilationMode 的回归比较。
- 修复建议：不再为该项进行生产代码优化或继续采集。Perfetto 不能把 Skia primitive 精确映射到单一 Kotlin 组件，且第二次导航 20/20 不复现；除非产品明确把“每进程第一次进入关键路由”设为严格帧预算，否则保留当前视觉设计。若未来提出该产品要求，另开视觉重设计任务并比较独特 Skia primitive/阴影组合。
- 修复风险：当前无改动风险；未来视觉重设计风险中到高，可能改变主题、一致性、内存和首屏时序。
- 验证方式：已完成批次 14 首次/第二次各 10 次 None/Profile 对照、40 条 Trace、全量 shader/cache-miss 统计和 4 条代表 Trace SQL 证据；批次 19 又完成冷启动、账本详情、首次自动记账 3/3 runtime-tracing Macrobenchmark、30 条 Trace。除非启动新的产品级视觉重设计任务，否则本项没有待执行验证。

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
- 批次 13（R8、各 10 次）：冷启动 None/Profile `StartupTimingMetric` min/median/max 为 232.123/260.308/335.149 ms 与 229.246/244.419/311.236 ms。主页→自动记账 None/Profile 共 693/730 帧，frame CPU P50/P90/P95/P99 为 3.779/10.243/12.930/48.124 ms 与 2.939/7.606/11.971/41.302 ms，overrun 为 -4.787/3.907/17.587/52.228 ms 与 -4.874/-2.350/1.597/37.947 ms；正 overrun 帧为 141/51。代表长帧同时含 UI 组合/测量与 RenderThread shader cache miss，详见 `PERF-A02`/`PERF-A08`。
- 批次 14（同进程首次/第二次、各 10 次）：首次 None/Profile 共 730/662 帧，frame CPU P50/P90/P95/P99 为 3.519/8.708/11.647/43.789 ms 与 2.986/8.028/12.992/40.828 ms，overrun P99 为 52.072/36.985 ms；第二次 None/Profile 共 492/567 帧，CPU P99 为 35.072/13.120 ms，overrun P99 为 35.793/1.886 ms。第二次两组 20/20 Trace 均无目标 RenderThread shader 编译或 cache miss；None 的残余最差帧仍为 UI `Recomposer:recompose`、`measureAndLayout` 和 `Record View#draw()`。
- 批次 15（临时关闭根路由离场动画、各 10 次）：第二次 None/Profile 的 frame CPU/overrun P99 为 `31.532/23.849 ms` 与 `15.650/6.946 ms`；首次 None/Profile 为 `63.932/57.336 ms` 与 `46.993/38.974 ms`。相对批次 14，只有第二次 None 改善，另三组均变差；不能把单组波动当作收益，临时生产代码已回退。第二次导航的代表 Trace 仍无 `shader_compile`/`cache_miss`，残余长帧是 UI `Recomposer`、`measureAndLayout` 与 `Record View#draw()`；首次 None 仍出现 GPU shader 首用。
- 批次 19（开启 runtime tracing、各 10 次）：冷启动 TtID min/median/max 为 `195.6/220.0/318.0 ms`；首次自动记账导航 frame CPU/overrun P99 为 `62.3/64.4 ms`，代表 Trace 65 帧、8 条正 overrun、5 条慢帧、2 条 big-jank，最大 `100.660/86.656 ms`；账本滚动→详情为 `65.5/59.8 ms`，代表 Trace 95 帧、12 条正 overrun、8 条慢帧、3 条 big-jank，最大 `109.970/95.800 ms`。tracing 会增加观测开销，故不与批次 14–18 未开启 tracing 的 P99 对比。
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
- 修复后 Trace/SQL：第四批最终独立 benchmark APK 的 15/15 Trace（目录 `device-results-20260719-batch4-final-ok-2333/com.bks.macrobenchmark`）明确 `OK (3 tests)`，均无 app→keystore2 Binder 事务，均无 Android GC；冷启动 TtID min/median/max 为 383.913/395.046/447.554 ms，账本/自动记账 frame CPU P99 为 81.387/89.983 ms，frame overrun P99 为 112.667/81.131 ms。
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
- 批次 19 runtime-tracing：冷启动 startup→first-draw、首次自动记账最慢帧、账本详情最慢帧均无目标 GC 重叠；首次自动记账的下一次 `Background concurrent copying GC` 在目标帧后开始并持续 `67.191 ms`。首次自动记账代表窗口 RSS 为 `210.613–219.176 MiB`、swap `75.305–75.398 MiB`；账本详情为 `226.512–228.418 MiB`。这些是隔离包、短窗口和 tracing 开销下的瞬时样本，不能据此推断长期泄漏或生产包峰值。

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
- 批次 19：最慢首次自动记账/账本详情 UI 帧为 `100.660/109.970 ms` CPU，主线程分别连续 Running `61.600/102.007 ms`；无 D-state、同步 Binder 或 monitor contention。冷启动代表帧的同步 `IWindowSession::relayout` 为 `4.297 ms`，后台 `skip_verifyclas` 为 `23.323/32.502 ms`，没有证据表明它们造成该 157 ms UI 帧。Trace 中 966.205、181.663、182.143 ms 的全局 `Choreographer#doFrame` 父 slice 主要是 Sleeping，不能误报为同等时长的主线程计算卡顿。所有这些样本仍远低于 frozen-frame/ANR 口径，未发现新的 ANR 证据。

## 6. 后台与耗电结果

### PERF-A04 — Medium — 通知监听服务让进程在强停后立即重启（已接受架构约束）

- 证据等级：A。
- 类别：后台生命周期、启动基线、耗电。
- 文件与行号：`feature/capture/PaymentNotificationListenerService.kt:31-53`；合并 Manifest `apps/android/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml:48-57`。
- 用户流程：通知捕获、系统重连、应用启动。
- 静态证据：已授权的 NotificationListenerService 与主 Activity 共用 `com.bks` 进程，并在连接时初始化 diagnostics、读取 activeNotifications。
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
- 批次 19 三条 runtime-tracing Trace 仍为 `power_rail_empty_packet`，`android_power_rails_counters` 均为 0。WakeLocks 轨道只有泛系统名称：冷启动 `*launch*` 最长 `497.010 ms`、首次自动记账 `MSF:WakeLock:Alarm` 最长 `140.643 ms`、账本 `*alarm*` 最长 `24.765 ms`；没有可归因到 `com.bks.benchmark` 或生产包的持锁记录，不能据此计算功耗或判断长期耗电。

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
- 文件与行号：`data/local/LedgerDaos.kt:68-99,221-269`；`data/local/LedgerEntities.kt:95-128`；`data/local/BksDatabase.kt:268-280`；`data/local/LocalLedgerRepository.kt:32-74`；`MainActivity.kt:390-410,616`。
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
- 静态与产物证据：批次 11 前的基线为 `isMinifyEnabled = false`、未设置资源压缩、无 mapping/seeds/usage/configuration 输出；当前 Release 已启用 minify 与资源压缩，生成 `mapping.txt`、`usage.txt`，且仍未发现过宽、重复或无效自定义 keep 规则。
- R8 Skill 结论：R8 9.0.32 按 heuristic 路径分析；基线阶段阻止 shrinking/optimization/obfuscation 的根因是 R8 完全未运行，而不是 keep 规则。该根因已在批次 11 消除。
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
- 文件与行号：`apps/android/build.gradle.kts:126-131,198`；`benchmarks/macrobenchmark/src/main/java/com/bks/macrobenchmark/CriticalUserJourneysBenchmark.kt:40-111`；`apps/android/src/release/generated/baselineProfiles/baseline-prof.txt:1`。
- 用户流程：冷启动、账本滚动→详情、报表进入、我的→自动记账。
- 静态证据：批次 8 重新生成生产 source profile，为 20,000 行、2,143,362 bytes，含 1,798 条 `com/bks/` 规则、0 条 benchmark-only 规则；Gradle filter 同时排除 `com.bks.benchmark.**`。
- Trace/命令证据：Release APK 内 `baseline.prof`/`baseline.profm` 为 14,736/1,747 bytes。6 个测试 × 10 次均通过，形成 60 Trace；账本与自动记账 frame CPU P99 分别下降 67.6%/39.7%，JIT count/duration 显著下降。冷启动 TtID 中位数 415.372→420.970 ms，没有可测收益。
- 实际影响：生产 Release 已具备关键路径预编译规则，两条交互路径的 JIT 和长帧成本显著降低；启动仍受主线程工作与 D-state I/O 主导。Baseline Profile 不解决 shader、bitmap decode、GC 或根级 Compose 工作。
- 实测确认：确认。生产接入、APK 内容和同设备交互收益均已验证；不宣称冷启动改善。
- 修复建议：批次 6 已完成接入，批次 8 已把报表加入生成路径。后续修改关键启动/交互路径时重新生成 profile，并把 None/Profile 对照作为回归预算。
- 修复风险：低；主要风险是 profile 过期或误包含 benchmark-only 规则，当前两项均已排除。
- 验证方式：Release 构建后检查合并 profile 与 APK `baseline.prof/.profm`；三条路径各执行 10 次 None/Profile 对照，持续比较 TtID、frame CPU、overrun 和 JIT。

### BUILD-A01 — High — Lint 与 Release 构建绿线（第一批已修复）

- 证据等级：A。
- 类别：构建验证、Debug/Release 差异。
- 文件与行号：生成代码 `apps/android/build/generated/source/kapt/release/com/bks/data/local/*_Impl.java`；首个 Lint 错误 `BillSyncAccessibilityService.kt:801`。
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

## 9. 当前待决项与已接受项

当前总有 1 个待决项：

1. [High][PERF-A02] 冷启动首屏稳定、账本详情与主页→首次自动记账仍有可测的主线程 Compose→measure/draw 长尾。批次 19 runtime tracing 的代表样本分别为：冷启动末尾 doFrame 的 `measure 145.880 ms`（它大部分发生在 first-frame 后，不把它全部计入 TtID）；首次自动记账 `Recomposer/measureAndLayout/Record View#draw()` `28.508/25.724/32.628 ms`、最慢帧 CPU/overrun `100.660/86.656 ms`；账本详情 `Recomposer/LedgerEntryForm/measureAndLayout/Record View#draw()` `59.435/41.323/32.587/42.000 ms`、最慢帧 `109.970/95.800 ms`。三者主线程都主要为 Running，缺少 Binder、锁、I/O wait 或调度饥饿主因。批次 18 的根路由最小候选虽改善三组 P99，但第二次 Profile CPU P99 `13.285 → 14.546 ms` 退化，已回退；当前没有通过 4×10 接纳门槛、且能精确落到单一 Kotlin 输入的低风险生产修复。它是需要产品/UI 设计决策后才可继续的待决项，不再安排同类 Trace 定位或小候选试验。

已验证、已接受项：

- [High][PERF-A08] 冷启动、账本详情与主页→首次自动记账的首次全屏绘制会发生 GPU shader cache miss；批次 14 已在同进程第二次导航 20/20 Trace 未复现，批次 19 又在三条路径确认其为每进程首用成本。当前接受该平台成本，不再作为待修复项或继续优化目标；只有产品提出严格的首次进入帧预算时，才另开视觉重设计任务。

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
- 批次 12 [PERF-A02]：已完成并提交为 `4ade499`。账本内部转场取消旧页离场动画，2/2 隔离真机回归通过；frame CPU/overrun P99 明显下降。
- 批次 13 [PERF-A02][PERF-A08]：已完成验证，未改生产代码。4/4 R8 Macrobenchmark、40 条 Trace、冷启动/主页路由补测和账本 RenderThread 复核完成；确认 UI 长尾与独立 GPU shader cache miss。
- 批次 14 [PERF-A02][PERF-A08]：已完成验证，未改生产代码。新增同进程首次/第二次导航对照，4/4 R8 Macrobenchmark 通过、40 条 Trace 已归档。确认第二次导航不再有 shader 编译/cache miss；残余 None 长帧是主线程 Compose 组合、测量和 View 绘制。未找到能精确归因到单一 Kotlin 图形输入的低风险视觉修复候选。
- 批次 15 [PERF-A02]：已完成否定性验证，未保留生产代码改动。临时关闭根 `SlidePageTransition` 的离场内容绘制后完成 4/4 真机 Macrobenchmark；仅第二次 None 改善，首次 None/Profile 与第二次 Profile 均变差。实验已完整回退，结论是当前主瓶颈仍在自动记账页自身的 Compose 组合、测量和绘制链，而非离场 Profile 页叠加。
- 批次 16 [PERF-A02]：已完成诊断基础设施。新增默认关闭的 `composeCompilerReports` Gradle 开关；Debug 编译、定向 JVM 测试和默认 Debug 构建通过。报告证实自动记账页和根转场均可跳过，自动记账页入口稳定；没有发现可由编译器静态报告单独证实的低风险生产代码修复候选。
- 批次 17 [PERF-A02]：已完成运行时定位，未改生产运行时依赖。隔离 `benchmarkRelease` 的 Compose runtime tracing 通过 1/1 Macrobenchmark、10 条 Trace 具名确认转场两侧的 Profile/自动记账页面组合，以及自动记账帧的测量和 View 绘制长尾；排除了该代表帧的 Binder、锁、I/O、RenderThread 和 CPU 降频共同主因。
- 批次 18 [PERF-A02]：已完成否定性验证，未保留生产代码改动。根路由最小候选完成同进程首次/第二次 None/Profile 四组各 10 次真机矩阵；三组聚合 P99 改善，但第二次 Profile CPU P99 `13.285 → 14.546 ms` 退化，未通过“四组均不退化”接纳标准，候选已完整回退。代表 Trace 仍以主线程组合、测量与 `Record View#draw()` 为主，未得到新的低风险单点根因。
- 批次 19 [PERF-A02][PERF-A08]：已完成 runtime-tracing 验证，未改生产代码。冷启动、账本详情、首次自动记账 3/3 隔离 Macrobenchmark 通过，共 30 条 Trace；逐 Composable 证实三条路径的主线程 Compose→measure/draw 长尾，并把首次路线的独立 RenderThread shader cache miss 划归 `PERF-A08`。观测结果含 tracing 开销，不作为 P99 回归比较；未发现可安全落地的单点生产修复。
- 批次 20：已取消。图片异步解码候选未完成基线/候选对照，且不解决 `PERF-A02`/`PERF-A08` 主因，已完整回退、未提交；不再进行真机安装、Macrobenchmark 或 Perfetto 重试。
- 后续批次：当前无。`PERF-A02` 需先由产品/UI 决定可接受的页面切换视觉、首建内容密度或布局调整范围，才能定义新的修复批次。

## 11. 未验证部分与需要的帮助

以下均为尚未完成的动态验证或环境能力缺口，不代表前面的构建、测试或 Macrobenchmark 全部失败。

1. 生产包真实 cold startup 未验证。通知监听服务在强停后会立即重启生产进程，因此无法取得纯 cold 样本。
   - 需要帮助：提供专用测试设备，或明确授权临时关闭通知监听服务/使用独立测试权限后重新采集。

2. Macrobenchmark 测试模块没有独立 Lint 报告。原因是 `com.android.test` 不提供 `:benchmarks:macrobenchmark:lintBenchmarkRelease` 任务；app benchmark source set 的 Lint 已通过。
   - 需要帮助：不需要新增代码；如必须有独立报告，需要接受该模块类型的任务限制或改用 CI 统一 Lint 入口。

3. 功耗数值未验证。批次 10、批次 13、批次 14、批次 15、批次 17 和批次 19 Trace 均返回 `power_rail_empty_packet`，没有有效 power rail、battery current 或 battery_stats 样本。批次 19 虽有 `*launch*`、`MSF:WakeLock:Alarm`、`*alarm*` 等 WakeLocks 轨道，但均不能归因到目标应用；短 Trace 也不能替代长期耗电结论。
   - 需要帮助：提供支持功耗轨道采样的设备/固件，或允许使用 Battery Historian/长时功耗采集。

4. 冷启动内核 I/O 的具体根因未验证。批次 13 None 代表样本确认主线程 `100.048 ms` uninterruptible I/O sleep，最长单段 `39.840 ms`；批次 19 的 runtime-tracing 冷启动代表样本仅确认 `AssetManager::OpenNonAssetFd(res/Ap.ttf)` 与 `5.913 ms` D-state 重叠。两批 Trace 均缺少可用的符号化 caller、`blocked_function` 和 `waker_utid`，不能把 I/O 归因到具体内核函数或文件。
   - 需要帮助：提供带内核符号或更完整 ftrace 数据的采集环境。

5. 真实生产账号、线上 token 校验和真实后端启动时延未验证。批次 9 已完成加密合成会话的登录态启动，但没有使用用户账号、生产 token 或真实后端。
   - 需要帮助：如需线上口径，提供专用测试账号、隔离后端和允许采集脱敏账号阶段数据的环境。

6. 真实微信/支付宝无障碍事件未验证。当前设备 `accessibility_enabled=0`；批次 10 未改变无障碍权限、未模拟支付，仅验证隔离 event admission 和心跳策略。
   - 需要帮助：提供专用测试账号/设备，并明确授权真实支付流程回归；采集事件率、节点数、遗漏率和服务主线程 Trace。

7. 真实 Downloads/MediaStore 写入阶段未验证。批次 9 已完成 1k/10k 内存 CSV 和加密备份 Trace，但没有向用户 Downloads 写文件，也没有覆盖存储空间不足、取消和半成品清理。
   - 需要帮助：提供专用测试用户或明确授权写入测试目录，并允许测试失败、取消与清理路径。

8. 长时内存泄漏和稳态耗电未验证。现有正式 Trace 约 15–30 秒，批次 10 的受控时间线不等于真实 2 分钟等待，更不能代表 30 分钟以上的稳态行为。
   - 需要帮助：允许在专用设备上运行 30 分钟以上场景，并采集 heap dump/allocation profile。

9. 公网 DNS、连接、TLS、真实服务端等待和下载阶段未验证。批次 9 的回环 HTTP 已验证响应头、响应体和取消，但回环链路不经过 DNS/TLS，也不能表示真实服务端 TTFB。
    - 需要帮助：提供可控代理、隔离 HTTPS 后端和服务端观测能力，补采 DNS、连接、TLS、TTFB 与下载阶段。

10. 100k 数据和独立 Room 查询时长未验证。批次 8 已完成 1k/10k 报表路径；批次 9 已补 1k/10k 导出 RSS，但仍不能外推为 100k 数据容量证明。
    - 需要帮助：仅在产品需要 100k 级账本时，提供专用 benchmark 设备或授权补充进程内查询计时与 RSS 采样。

当前仅 `PERF-A02` 为已确认但待决的设计级问题；`PERF-A08` 已验证并接受为每进程首次 GPU shader cache 成本，不再列为待修复项。PERF-B01、PERF-B02、PERF-B05、PERF-B06、PERF-B07、PERF-C01、BUILD-C02 已完成代码、回归和隔离环境动态验证，不再列为未完成项；批次 19 已完成三条隔离关键路径的 runtime tracing，真实生产包、真实支付事件与长期功耗仍按上文列为未验证边界。

本轮已检查且未发现明确异常：生产 Lazy 列表均有稳定 key；生产网络 transport 在 `Dispatchers.IO`；未使用 `allowMainThreadQueries`、`runBlocking`、`Thread.sleep`、`GlobalScope`、WakeLock、AlarmManager 或 WorkManager 高频任务；生命周期解绑和 Service scope cancel 路径存在；日志无 ANR/FATAL/OOM，所有已执行交互样本均无 >700 ms frozen frame；项目 ProGuard 文件没有过宽、重复或无效自定义 keep 规则。

## Skill 适用性说明

- `perfetto-trace-analysis`：适用；批次 6 的 60 个 Trace、批次 10 的 3 条策略 Trace、批次 13 的 40 条 Trace、批次 14 的 40 条 Trace、批次 15 的 4 组导航用例、批次 17 的 10 条 runtime-tracing Trace、批次 18 的 80 条基线/候选矩阵 Trace，以及批次 19 的 30 条 runtime-tracing Trace 均保留 evidence scratchpad 或聚合证据。批次 14–15、17、18 与 19 先使用 Macrobenchmark FrameTiming 高层汇总；当前 trace_processor 的 `android_jank` metric 为空，`frame_times` metric 又因当前 SQL 的 `arg_set_id` 歧义失败，随后以 Frame Timeline、线程状态、CPU frequency、Binder、memory/GC、power rail 和全局 D-state SQL 复核。设备 power rail 空包已明确记录为限制。
- `perfetto-sql`：适用；每个使用的表/视图先在 `perfetto-stdlib.md` 确认 schema，查询使用 upid/utid、GLOB、重叠时间窗和 `dur=-1` 处理。批次 14–15、17、18 与 19 使用 `android_frames`、`thread_slice`、`thread_state`、`android_binder_txns`、`android_monitor_contention_chain`、`android_garbage_collection_events`、`cpu_frequency_counters`、`memory_rss_and_swap_per_process`、`android_gpu_memory_per_process` 和 `android_power_rails_counters` 确认同进程首/次导航与三条关键路径差异。批次 19 分别排除 Binder、锁、主线程 I/O wait 或调度阻塞是代表 UI 长帧的共同主因，并把 RenderThread shader cache miss 与主线程 Compose→measure/draw 记录为独立成本；GC 仅作为重叠事实记录，未外推为唯一根因。
- `r8-analyzer`：适用；R8 9.0.32 仍按 heuristic 分析 keep 规则，批次 11 已启用 minify 并产生 mapping/usage 产物，已可做 Release 产物实证。批次 14–15、17–19 未改 R8、keep 规则或包体，因此未重复执行该分析。
- `testing-setup`：适用于盘点和第二批补测；在用户授权后复用官方 AndroidX Benchmark/UIAutomator 依赖新增独立测试模块，未改变生产运行时依赖。批次 14 复用该模块新增首次/第二次导航用例及返回主页 helper，未新增依赖或测试框架；批次 16 继续复用既有 JVM 回归，仅增加默认关闭的编译诊断开关；批次 19 复用既有三条 Macrobenchmark 用例及 direct-ADB instrumentation，规避 Xiaomi UTP 安装确认导致的 `INSTALL_FAILED_USER_RESTRICTED`，不改生产包或用户数据。测试前锁定 natural orientation、结束后恢复自动旋转，消除了方向漂移干扰。

原最小测试补充方案已经落实：独立 Macrobenchmark/Baseline Profile 模块覆盖本报告三条关键路径，shader 与 Baseline Profile 批次均已完成。后续最小增量是继续用现有框架验证根布局、R8 和剩余业务风险；不需要引入新的测试框架。
