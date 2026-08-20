# bks

面向 Android 的记账 App，支持手动录入，以及用户主动发起的微信/支付宝账单补录；补录结果先进入待确认队列，再由用户确认到账本。

## 项目结构

- `apps/android`: Kotlin + Jetpack Compose + Room Android 客户端。
- `services/backend`: Ktor 后端，提供统一账号认证、云配置、AI 代理和账户级账本同步。
- `shared/api`: 共享 Kotlin API 模型。
- `docs`: PRD、架构、UI、合规、ADR 与迭代切片。

## 本地构建

当前工作区使用 Android SDK: `C:\Users\Arfa\AppData\Local\Android\Sdk`。

后端环境变量模板位于 `services/backend/.env.example`。Android 的 `BKS_BACKEND_URL` 可放在被 Git 忽略的根目录 `local.properties` 或进程环境变量中；Debug 未配置时使用 `http://10.0.2.2:8080`，Release 未配置时保持账号网络不可用。局域网 HTTP 只用于受控测试账号，生产环境必须使用 HTTPS。

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest
.\gradlew.bat coverageReport
.\gradlew.bat detekt
.\gradlew.bat :apps:android:assembleDebug
.\gradlew.bat :apps:android:assembleRelease
.\gradlew.bat :services:backend:test
.\gradlew.bat build
```

## 当前进度

- Slice 0: 仓库、Android、后端、共享模块和四 Tab App Shell 已建立。
- Slice 1: 本地账本 Room schema、种子分类、DAO、仓储 API 和基础测试已建立。
- Slice 2: 待确认队列、确认/忽略/撤销、详情编辑、证据区和忽略恢复已建立。
- Slice 3: 已确认账目可进入账本和报表，包含月汇总、搜索筛选、分类排行和 7 个月现金流。
- Slice 4: 本地分类规则、优先级匹配、规则管理 UI 和分类修正存规则确认已建立。
- Slice 5: 账号入口、本地模式说明、登录/注册/找回密码表单、协议拦截和验证码倒计时 UI 已建立。
- Slice 6: 后端账号服务、短信验证码、限流、登录锁定、token 合同和 Android 账号 repository seam 已建立。
- Slice 7-9: 手动账单补录、微信/支付宝来源选择、账单页解析、待确认队列、去重与合并证据已建立。
- Slice 10: 云端 AI 分类同意开关、增强上下文开关、待确认详情 AI 建议入口、最小 payload 门禁和后端 AI 代理日志已建立。
- Slice 11: CSV 导出、AES-GCM 加密备份导出/导入、本地数据恢复和带备份提醒的本机数据删除确认已建立。
- Slice 12: 账号注销 7 天冷静期、取消注销、云端写入暂停和到期删除账号/AI 日志任务已建立。
- Slice 13: 隐私政策、个人信息收集清单、第三方服务清单、权限说明、商店审核说明和构建依赖合规扫描已建立。
- Slice 15: 内测就绪检查、核心质量指标计算、设备矩阵、已知风险、合规复核清单和源码密钥扫描已建立。
- Slice 16: 用户名/邮箱/手机号统一账号、可选微信身份、账户级离线优先账本同步和人工冲突处理已建立。

## 当前 Phase 2 状态

Phase 2 已将本地账本、待确认队列、规则与 AI 同意、备份恢复、手动补录权限服务和“我的”四个二级页接入当前 Android 主流程。四个页面依次为账户管理、分类规则、数据与备份、合规与隐私；开发者工具只在 Debug 构建中显示。独立的“诊断日志”入口在 Debug、Release 均位于“合规与隐私”，Debug 默认开启，Release 默认关闭并要求用户阅读说明后主动开启。

手动补录在“待确认”页面启动，使用无障碍服务读取用户当前打开的微信或支付宝账单页面；微信补录可在本次会话内使用本机 OCR，支付宝补录读取无障碍节点文本。每次只读取当前可见页面，不自动滚动或扫描完整历史。

当前账号入口支持用户名、邮箱或手机号加密码登录；用户名注册和真实 SMTP 邮箱注册已完成验收。短信验证码直接登录不在产品范围内，真实短信 Provider 未配置，因此手机号注册、找回及短信验证类绑定暂不可用。微信代码与假 Provider 自动化已完成，但未配置 Android AppID 和后端 AppID/AppSecret，入口保持隐藏，真实微信登录/注册不可用。

已登录账户管理使用后端生成的公开 UUID 作为稳定账号 ID，界面仅显示脱敏短格式，复制时保留完整 UUID；不得从 Session Token 派生账号 ID。昵称、相册头像和相机头像通过账号 Profile 接口持久化，手机号与邮箱支持经验证码换绑。

账户同步已完成一台 Xiaomi 真机与受控模拟客户端的账号切换、两种冲突处理及局域网 HTTP Release 验收。两台真实设备与生产式 HTTPS Release 验收仍未完成。目标 ROM 设备矩阵也尚未全部覆盖。Android 云端 AI 只调用项目后端；后端使用统一的协议、完整 Endpoint、API Key、模型及能力配置，支持 OpenAI Responses、OpenAI-compatible Chat Completions 和 Anthropic-compatible Messages，默认关闭，未配置、`rule` 或未知协议均失败关闭。DeepSeek Chat Completions 的真实外部协议调用和 Android 到后端的局域网 Release 真机端到端均已通过，生产式 HTTPS 启用仍未执行。

Release 只有在本地提供 keystore 与完整签名凭据时才是可安装的已签名 APK。没有签名凭据时，构建会产出未签名的 `android-release-unsigned.apk`，仅用于编译与 lint 验证。

- 当前执行状态与未完成手工验证见 [Phase 2 Issue Files](docs/issues/phase-2/) 和 [可选真机验证清单](docs/issues/phase-2/OPTIONAL-VALIDATIONS.md)。
- 当前仍需外部服务、真实设备或生产环境完成的事项见 [todos.md](todos.md)。
- 内测发布、设备矩阵、签名和风险记录见 [Internal Beta Release](docs/INTERNAL-BETA-RELEASE.md)。
- 诊断日志的隐私边界、导出与本机解密方式见 [诊断日志操作手册](docs/DIAGNOSTIC-LOGS.md)。
- [Phase 2 Baseline Audit](docs/PHASE-2-BASELINE-AUDIT.md) 是 `cfa42ec` 时的历史审计，不代表当前工作树状态。
