# Auto Accounting

面向 Android 的自动记账 App，用于捕获微信、支付宝支付活动，先进入待确认队列，再由用户确认到账本。

## 项目结构

- `apps/android`: Kotlin + Jetpack Compose + Room Android 客户端。
- `services/backend`: Ktor 后端骨架。
- `shared/api`: 共享 Kotlin API 模型。
- `docs`: PRD、架构、UI、合规、ADR 与迭代切片。

## 本地构建

当前工作区使用 Android SDK: `C:\Users\Arfa\AppData\Local\Android\Sdk`。

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest
.\gradlew.bat :apps:android:assembleDebug
.\gradlew.bat :apps:android:assembleRelease
.\gradlew.bat :services:backend:test
.\gradlew.bat build
```

## 当前进度

- Slice 0: 仓库、Android、后端、共享模块和四 Tab App Shell 已建立。
- Slice 1: 本地账本 Room schema、种子分类、DAO、仓储 API 和基础测试已建立。
- Slice 2: 待确认队列、确认/忽略/撤销、详情编辑、证据区和忽略恢复已建立。
- Slice 3: 已确认账目可进入账本和报表，包含月汇总、搜索筛选、分类排行和 6 个月趋势。
- Slice 4: 本地分类规则、优先级匹配、规则管理 UI 和分类修正存规则确认已建立。
- Slice 5: 账号入口、本地模式说明、登录/注册/找回密码表单、协议拦截和验证码倒计时 UI 已建立。
- Slice 6: 后端账号服务、短信验证码、限流、登录锁定、token 合同和 Android 账号 repository seam 已建立。
- Slice 7: 微信/支付宝通知解析、通知监听服务、捕获证据、权限中心通知项和待确认队列接收入口已建立。
- Slice 8: 手动账单同步入口、微信/支付宝来源选择、账单页解析、同步进度、无障碍服务声明和通知候选去重已建立。
- Slice 9: 去重评分、强匹配自动合并、弱匹配疑似重复入队、合并证据保留和误合并规避测试已建立。
- Slice 10: 云端 AI 分类同意开关、增强上下文开关、待确认详情 AI 建议入口、最小 payload 门禁和后端 AI 代理日志已建立。
- Slice 11: CSV 导出、AES-GCM 加密备份导出/导入、本地数据恢复和带备份提醒的本机数据删除确认已建立。
- Slice 12: 账号注销 7 天冷静期、取消注销、云端写入暂停和到期删除账号/AI 日志任务已建立。
- Slice 13: 隐私政策、个人信息收集清单、第三方服务清单、权限说明、商店审核说明和构建依赖合规扫描已建立。
- Slice 14: 连续监控高级模式、账单同步后提示、可随时关闭的控制项、权限中心状态和支付页面观察边界已建立。
- Slice 15: 内测就绪检查、核心质量指标计算、设备矩阵、已知风险、合规复核清单和源码密钥扫描已建立。

## 当前 Phase 2 状态

Phase 2 已将本地账本、待确认队列、规则与 AI 同意、备份恢复、权限服务和“我的”五个二级页接入当前 Android 主流程。五个页面依次为账户管理、自动记账、分类规则、数据与备份、合规与隐私；开发者工具只在 Debug 构建中显示。独立的“诊断日志”入口在 Debug、Release 均位于“合规与隐私”，Debug 默认开启，Release 默认关闭并要求用户阅读说明后主动开启。

“自动记账”页当前以紧凑清单展示通知监听、无障碍及后台运行、自启动、电池优化、省电模式引导；后四项只用于提升国产 ROM 后台稳定性，不阻断自动记账启用。Android 13 及以上的记账结果通知在开启自动记账时按需申请，拒绝不影响本地采集与持久化。

当前仍不具备向更广泛测试者分发的条件：目标 ROM 设备矩阵尚未完成，云端 AI 仍使用本地 `DemoAiCategorizationGateway`，而 Release 只有在本地提供 keystore 与完整签名凭据时才是可安装的已签名 APK。没有签名凭据时，构建会产出未签名的 `android-release-unsigned.apk`，仅用于编译与 lint 验证。

- 当前执行状态与未完成手工验证见 [Phase 2 Issue Files](docs/issues/phase-2/) 和 [可选真机验证清单](docs/issues/phase-2/OPTIONAL-VALIDATIONS.md)。
- 内测发布、设备矩阵、签名和风险记录见 [Internal Beta Release](docs/INTERNAL-BETA-RELEASE.md)。
- 诊断日志的隐私边界、导出与本机解密方式见 [诊断日志操作手册](docs/DIAGNOSTIC-LOGS.md)。
- [Phase 2 Baseline Audit](docs/PHASE-2-BASELINE-AUDIT.md) 是 `cfa42ec` 时的历史审计，不代表当前工作树状态。
