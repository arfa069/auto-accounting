# 进行中：增加微信登录、注册与账号身份管理

## 目标

在不影响手机号认证、本地模式及本机账本的前提下，接入微信原生 OAuth 登录与注册，并把后端账号身份从手机号主键迁移为内部账号 ID，支持微信资料、手机号补充、互补凭据账号合并及安全解绑。

## 范围

- 微信授权后登录已有微信账号，或一键创建纯微信账号。
- 未注册微信可选择创建微信账号，或通过密码、短信绑定已有手机号账号。
- 纯微信账号可新增手机号并设置密码。
- 支持互补凭据账号合并、安全解绑微信、微信昵称和头像展示。
- 账号、Session、设备、云配置和 AI 日志从手机号关联键迁移到内部账号 ID。
- 缺少微信开放平台配置时安全隐藏微信入口，手机号认证与本地模式继续可用。
- 使用假微信 SDK 和假 OAuth Provider 完成自动化验证；资质审核通过后再执行真实微信真机验收。

## 非目标

- 不修改 Android Room、账本 schema、账本归属、备份格式或自动记账采集链路。
- 不把 AppSecret、微信 code、access token、refresh token、OpenID 或 UnionID 放入 APK、客户端 Session、日志或诊断导出。
- 不在后端下载或长期托管微信头像图片，只保存 HTTPS 头像 URL。
- 不允许一个账号绑定多个手机号或多个微信身份，也不提供冲突凭据的人工取舍。
- 不在尚未取得微信开放平台资质时宣称真实微信端到端验证通过。

## 固定接口与行为

- `AccountSessionResponseContract` 调整为：
  - `phone: String?`
  - `wechatLinked: Boolean = false`
  - `nickname: String? = null`
  - `avatarUrl: String? = null`
  - 保留 `token` 与注销状态；手机号账号继续返回原有 `phone` 字符串。
- 微信认证结果使用 `SIGNED_IN`、`REGISTRATION_REQUIRED`、`MERGE_REQUIRED` 三种稳定状态。
- API 分组：
  - 微信：`/account/wechat/exchange`、`/account/wechat/register`、`/account/wechat/link/password`、`/account/wechat/link/sms`。
  - 手机凭据：`/account/phone/link/prepare`、`/account/phone/link/complete`。
  - 合并：`/account/merge/prepare/phone-password`、`/account/merge/confirm`。
  - 解绑：`/account/wechat/unlink/password`、`/account/wechat/unlink/sms`。
- `/account/sms` 增加可选 `purpose` 和绑定上下文；未传时默认为现有账号用途，保证旧客户端兼容。
- 一次性微信、手机号及合并票据有效期统一为 5 分钟；客户端只持有原始值，数据库只保存 SHA-256 哈希，票据只能消费一次。
- 微信身份优先以 `unionid` 识别，缺失时使用唯一 `(appId, openid)`；后端不持久化微信 access token 或 refresh token。
- 微信资料接口失败不阻断身份认证：已有账号保留旧资料，新账号使用“微信用户”和默认头像，后续成功授权时刷新。
- 账号合并始终保留当前登录账号，只允许互补凭据合并；存在不同手机号、不同微信身份或任一账号处于注销冷静期时拒绝。

## 目标文件或模块

- `shared/api/src/main/kotlin/com/autoaccounting/api/AccountContracts.kt`
- `services/backend/src/main/kotlin/com/autoaccounting/backend` 下的迁移、账号、云配置、AI 与注销链路
- `services/backend/.env.example`
- `apps/android/build.gradle.kts`、版本目录、Manifest 与 R8 配置
- `apps/android/src/main/java/com/autoaccounting/feature/account`
- Release/Debug 各自包路径下的 `wxapi/WXEntryActivity.kt`
- `apps/android/src/main/java/com/autoaccounting/MainActivity.kt`
- `docs/adr/0057-use-internal-account-id-for-wechat-identity.md`、`docs/PRD.md`、`docs/ARCHITECTURE.md`、`docs/UI-DESIGN.md`、`docs/COMPLIANCE.md`

## 实施任务

### 0. 已完成：固定工作区与安全边界

- **实施**：以提交 `3bc7786` 为实现基线；提交后 `master` 比远端领先 13 个提交且工作树干净。根 `AGENTS.md` 的既有修改已按用户要求包含在该提交中；后续不建分支、不提交、不推送，除非用户另行明确要求。
- **验证**：共享 API、后端账号和 Android 账号专项测试均成功；测试后 `git status` 没有生成新的跟踪文件，Gradle Daemon 已停止。
- **完成条件**：已达到。基线可复现，且新后端首次连接真实 PostgreSQL 前禁止执行迁移；必须先制作并验证可恢复备份。
- **停止条件**：若既有测试出现与本功能无关的新失败，先定位基线，不进入数据库改造。

### 1. 已完成：扩展共享账号契约

- **实施**：
  - 修改 `AccountContracts.kt`，让手机号可空，并加入微信资料、认证状态、票据期限和稳定错误码。
  - 增加微信交换响应、手机号准备响应和合并预览契约。
  - 现有成功 JSON 保留原字段；新增字段均提供兼容默认值。
- **错误码**：覆盖微信未配置、授权失败、服务不可用、票据过期或已使用、微信或手机号冲突、合并被阻止、最后登录方式不可解绑。
- **验证**：
  - 旧手机号 JSON 仍可解析。
  - 微信账号可解析 `phone=null`。
  - 三种认证状态缺少其必需字段时明确失败。
  - 新后端响应中的附加字段不影响旧手机号客户端。
- **完成条件**：`:shared:api:test` 通过，所有新增契约有编码与解析对称测试。

### 2. 已完成：统一后端迁移入口并建立内部账号 ID

- **实施**：
  - 将目前分散在账号、云配置和 AI Store 中的 1–4 号迁移集中到统一迁移清单；各 JDBC Store 调用同一个幂等入口。
  - 新增事务性 5 号迁移，创建：
    - `accounts`：内部自增 `BIGINT account_id`、注销状态和创建时间。
    - `account_phone_credentials`：唯一手机号、密码哈希、失败次数和锁定状态。
    - `account_wechat_identities`：唯一 `(app_id, openid)`、可空唯一 `unionid`、昵称和头像 URL。
    - 微信授权、手机号绑定和账号合并的一次性票据表。
  - 把 Session、注册设备、云配置和 AI 日志的关联键迁移为 `account_id`。
  - `account_sms_codes` 增加 `purpose` 和上下文键；现有验证码迁移为默认账号用途。
- **迁移规则**：
  - 为每个旧手机号账号生成一个内部账号 ID。
  - 保留密码、锁定状态、Token 哈希、注销期限、设备、配置、AI 日志和创建时间。
  - 不修改 Android Room、账本或备份 schema。
- **验证**：
  - 从手工构造的 v4 H2 数据库执行迁移，逐项比较迁移前后记录。
  - 原 Bearer Token 迁移后仍能校验。
  - 重复启动不重复迁移。
  - 任一迁移语句失败时整个版本回滚。
- **完成条件**：H2 新库、v4 升级库和重复启动测试全部通过，且无孤立外键。
- **停止条件**：任何账号、Session、注销状态或云配置数量不一致时，不继续后端业务改造。

### 3. 已完成：将现有后端账号链路改为 `accountId`

- **实施**：
  - 拆分 `StoredAccount`、`StoredPhoneCredential`、`StoredSession` 和账号资料模型。
  - 手机注册先创建账号，再创建手机号凭据；手机号登录先解析凭据，再取得账号。
  - Token 校验返回内部账号主体，同时查询可选手机号和微信资料。
  - 云配置、AI 路由、注册设备及账号注销任务内部统一使用 `accountId`。
  - 账号最终注销级联删除手机号、微信身份、资料、设备、Session 和票据。
- **兼容要求**：
  - 手机注册、登录、找回、退出、注销和 Bearer 路由的请求格式不变。
  - 手机账号响应继续包含原手机号。
  - 登录失败和手机号枚举防护文案不变。
- **验证**：运行现有账号、云配置、AI、注销任务和持久化测试，并新增迁移后旧 Token、注销任务及 AI/配置隔离测试。
- **完成条件**：尚未加入微信逻辑时，所有既有行为与当前基线一致。

### 4. 实现微信服务端 OAuth 与临时授权票据

- **实施**：
  - 新建可注入的 `WechatOAuthClient` 和专用身份服务，路由只负责参数与响应映射。
  - 使用 Java 17 HTTP 客户端调用微信 code 换票和用户信息接口，不增加后端网络框架。
  - `AUTO_ACCOUNTING_WECHAT_APP_ID`、`AUTO_ACCOUNTING_WECHAT_APP_SECRET` 从后端环境读取，并在 `.env.example` 中仅加入空配置项。
  - code 换票不自动重试；AppSecret、code、access token、refresh token、OpenID、UnionID 和原始响应体不得进入日志或异常信息。
- **交换行为**：
  - 已绑定身份：刷新昵称和头像并签发业务 Session。
  - 未绑定身份：生成微信授权票据，返回资料预览和 `REGISTRATION_REQUIRED`。
  - 携带当前 Bearer 时，未绑定身份直接绑定当前账号；属于另一账号时返回 `MERGE_REQUIRED` 票据，不做任何写入迁移。
  - 用户资料接口失败时，身份认证继续；保留旧资料或使用默认资料。
- **验证**：假微信客户端覆盖成功、无 UnionID、无资料、无效 code、微信错误码、超时、配置缺失和原始响应脱敏。
- **完成条件**：后端在没有微信配置时正常启动，只有微信接口返回稳定的“未配置”错误。

### 5. 已完成：实现微信注册和绑定已有手机号账号


- **实施**：
  - `/account/wechat/register` 消费微信票据，创建纯微信账号、注册设备和 Session。
  - `/account/wechat/link/password` 使用现有密码校验和锁定规则，成功后把未注册微信身份绑定到手机号账号。
  - `/account/wechat/link/sms` 使用绑定到微信票据的专项短信验证码完成绑定。
  - 一个微信身份只能属于一个账号；一个账号最多一个微信身份。
  - 注册或绑定必须在一个数据库事务中消费票据、写入身份和签发 Session。
- **失败语义**：
  - 票据过期、重放或并发消费均失败并要求重新微信授权。
  - 目标手机号账号已有另一微信身份时拒绝，不静默覆盖。
  - 新 Session 无法在 Android 安全保存时，客户端撤销该 Session。
- **验证**：覆盖首次注册、重复微信登录、密码绑定、短信绑定、账号锁定、验证码错误或过期、并发消费及事务回滚。
- **完成条件**：微信注册和两种绑定方式均可通过纯后端集成测试形成可校验 Session。

### 6. 已完成：实现纯微信账号新增手机号

- **实施**：
  - 当前微信账号申请 `PHONE_LINK` 专项验证码。
  - `/account/phone/link/prepare` 验证手机号控制权：
    - 手机号未注册：返回短期手机号凭据票据。
    - 手机号属于另一账号：返回账号合并票据。
  - `/account/phone/link/complete` 使用手机号票据和符合现有强度规则的新密码创建手机号凭据。
- **安全规则**：
  - 在短信验证成功前不透露手机号是否已注册。
  - 纯微信账号新增手机号后轮换 Session。
  - 已有手机号的账号不能再绑定第二个手机号。
- **验证**：覆盖新增未注册手机号、密码不合规、手机号并发占用、已有手机号进入合并，以及票据过期或重放。
- **完成条件**：纯微信账号能够获得手机号和密码凭据，并继续保留原微信身份。

### 7. 实现账号合并

- **准备阶段**：
  - 微信来源账号通过携带当前 Bearer 的微信交换准备。
  - 手机来源账号支持密码准备或 `PHONE_LINK` 短信准备。
  - 返回目标和来源脱敏资料、会发生的迁移和 5 分钟合并票据。
- **确认阶段**：
  - Android 必须展示“本机账本不变、来源云账号将被删除、操作无法自动撤销”，并要求输入“合并账号”。
  - `/account/merge/confirm` 同时校验 Bearer、确认常量和票据。
- **单事务规则**：
  - 按账号 ID 固定顺序锁定两个账号。
  - 任一账号处于注销冷静期时拒绝。
  - 双方存在不同手机号或不同微信身份时拒绝。
  - 当前账号保留；来源的互补凭据移动到当前账号。
  - 当前云配置优先；来源独有 feature flag 补入，当前值覆盖同名键。
  - 设备按安装 ID 合并：保留最早首次时间、最新最后时间及最新记录对应 IP；时间相同时当前账号记录优先。
  - 删除来源 AI 日志，不迁移到目标。
  - 撤销双方全部 Session，给当前设备签发一个新 Session。
  - 删除来源账号并消费合并票据。
- **验证**：覆盖两个合并方向、配置冲突、设备去重、AI 日志删除、凭据冲突、注销阻断、双击确认、并发合并和任意步骤故障回滚。
- **完成条件**：失败时两个账号完全不变；成功时来源账号不可登录，目标新 Session 有效。

### 8. 实现微信安全解绑

- **实施**：
  - 仅同时拥有手机号凭据的账号显示解绑入口。
  - 支持当前密码或 `WECHAT_UNLINK` 专项短信再次验证。
  - 成功后删除微信身份、昵称和头像 URL，撤销全部旧 Session，并签发手机号账号的新 Session。
- **限制**：
  - 纯微信账号返回“最后一种登录方式不可解绑”。
  - 注销冷静期内禁止解绑。
  - 解绑不删除云配置、设备、目标 AI 日志或本机账本。
- **验证**：覆盖密码解绑、短信解绑、无手机号拒绝、错误凭据、注销期阻断、Session 轮换和新 Session 持久化失败。
- **完成条件**：解绑后微信不能再登录原账号，手机号仍可正常登录。

### 9. 接入 Android 微信 SDK 与安全回调

- **依赖与配置**：
  - 在版本目录固定 `com.tencent.mm.opensdk:wechat-sdk-android:6.8.40`。
  - 增加 `BuildConfig.AUTO_ACCOUNTING_WECHAT_APP_ID`，来源为 `local.properties` 或环境变量。
  - 加入微信官方 R8 keep 规则并验证 Release 混淆产物。
- **回调结构**：
  - 共享逻辑放在账号 feature 的 `WechatAuthCoordinator` 或 Gateway 中。
  - Release 使用 `com.autoaccounting.wxapi.WXEntryActivity`。
  - Debug 使用 `com.autoaccounting.debug.wxapi.WXEntryActivity`。
  - Activity 设置 `exported=true`、`singleTask`、正确 `taskAffinity`，不添加 `intent-filter`。
  - 回调通过显式 Intent 和 `CLEAR_TOP | SINGLE_TOP` 交给 `MainActivity.onNewIntent`。
- **安全行为**：
  - 只有用户同意协议并点击微信按钮后才创建和注册 `IWXAPI`。
  - 使用 32 字节 URL-safe 随机 state，私有存储目的、过期时间和请求 state。
  - `WXEntryActivity` 先交给 OpenSDK 校验，再比较 state；不匹配、过期或重复回调全部丢弃。
  - 客户端只把一次性 code 发给自有后端，绝不包含 AppSecret。
- **验证**：使用 SDK 包装层假实现覆盖未安装、版本过低、取消、拒绝、错误回调、state 不符、冷启动和 `onNewIntent`。
- **完成条件**：默认无 AppID 构建正常；注入假 Gateway 时能够完整驱动账号流程。

### 10. 升级 Android Repository 与加密 Session

- **实施**：
  - 扩展 `AccountRepository` 和 `HttpAccountRepository`，实现微信交换、注册、两种绑定、手机号新增、合并及解绑。
  - `AccountCredentials` 和 `AccountSession.SignedIn` 改为可空手机号，并携带微信绑定状态、昵称和头像 URL。
  - `SecureAccountSessionStore` 增加格式 v2；继续读取 v1 的手机号和 Token，并在下一次保存时升级。
  - v2 仍使用 Android Keystore AES-GCM，不写入 Room、备份、可恢复 UI 状态或日志。
- **失败处理**：
  - 网络失败保留已有离线 Session。
  - 明确无效 Session 才清除密文并进入本地模式。
  - 合并、绑定或解绑后新 Token 保存失败时撤销新 Token，清除本机账号 Session 并进入本地模式；本机账本不变。
- **验证**：HTTP 表单和响应测试、v1→v2 恢复、空手机号微信账号、持久化密文不含昵称、头像或 Token 明文、损坏密文安全降级。
- **完成条件**：手机号与微信 Session 均可跨重启恢复并执行后台校验。

### 11. 完成登录页、账号管理及头像 UI

- **登录与注册页**：
  - 登录落地页把“微信登录/注册”放在手机号登录和创建账号之前。
  - 手机号登录、注册页底部提供同一微信入口。
  - 未勾选协议时不启动 SDK，只显示既有协议提示。
  - 未绑定微信授权后展示头像、昵称及“创建微信账号 / 绑定已有账号”。
  - 绑定页面允许选择密码或短信方式，并锁定重复提交。
- **账号管理页**：
  - 展示头像、昵称、脱敏手机号和登录方式标签。
  - 根据状态显示绑定微信、绑定手机号、合并或解绑。
  - 合并页展示目标和来源、配置规则、AI 日志删除及本机账本不变说明。
  - 解绑页提示剩余手机号登录方式，并要求密码或短信确认。
- **头像**：
  - 使用 Coil `3.4.0` 与 `coil-network-okhttp`。
  - 仅加载 HTTPS URL，失败显示默认头像。
  - 使用独立、上限 10 MB 的 `wechat_avatars` 缓存。
  - URL 更新时淘汰旧头像；退出、解绑、Session 失效和本机数据删除时清空头像缓存。
- **结构约束**：微信 reducer、控制器和子页面单独文件，避免继续扩大 `AccountScreen`、`AccountManagementScreen` 和 `MainActivity`。
- **验证**：Compose 测试覆盖所有分支、返回层级、加载或错误状态、头像占位和本地账本隔离。
- **完成条件**：手机号、本地模式和既有系统返回行为不发生回归。

### 12. 合规、诊断与文档同步

- **实施**：
  - 扩展诊断脱敏规则，覆盖 `wechat_code`、`wechat_ticket`、OpenID、UnionID、access token 和 refresh token；账号流程仅记录稳定结果码，不记录资料正文。
  - 更新个人信息清单：OpenID、UnionID、昵称、头像 URL、安装 UUID 及用途、保存期限和注销删除规则。
  - 第三方服务清单加入腾讯微信开放平台和 OpenSDK，明确不向微信发送账本或交易数据。
  - 新增 ADR 0057，说明内部账号 ID、微信 OAuth、服务端换票和合并规则；同步 Phase 2 Issue 026 索引。
  - 更新 PRD、Architecture、UI Design、Compliance 和 `.env.example`。
- **验证**：运行诊断测试、`SecretScannerTest`、文档链接检查、中文编码检查和 `git diff --check -- docs/`。
- **完成条件**：代码、UI、架构与合规材料对微信数据处理和账号生命周期描述一致。

### 13. 分层验证、发布与真实验收

- **定向验证顺序**：
  1. `.\gradlew.bat :shared:api:test`
  2. `.\gradlew.bat :services:backend:test --tests "com.autoaccounting.backend.account.*"`
  3. 后端 config、AI、环境与 `SecretScannerTest`
  4. `.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.account.*"`
  5. Profile、MainActivity、诊断和本机数据删除相关 Android 测试
  6. `.\gradlew.bat detekt`
  7. `.\gradlew.bat build`
  8. Debug/Release APK 构建、Release R8 与 `apksigner verify --verbose`
  9. `.\gradlew.bat --stop`
- **数据库发布**：
  - 在真实 PostgreSQL 上线前制作时间戳备份，并实际验证可恢复。
  - 先部署兼容后端和迁移，观察旧手机号客户端登录、Token 校验、云配置及注销任务。
  - 后端稳定后再配置 Android AppID 并发布客户端。
  - 数据库迁移后的后端回退只能在停服后恢复备份；不得直接反向执行删除性 SQL。
- **微信开放平台后续**：
  - 申请并审核 Android 移动应用及微信登录能力。
  - 登记 Release 包名 `com.autoaccounting` 和实际发布证书签名；Debug 若需真实验证必须单独登记其包名和签名。
  - AppSecret 仅写入后端 `.env`，AppID 写入 Android 本地构建配置。
- **最终停止边界**：开放平台未审核前，只能声明自动化和假 Provider 验证通过，不得声明真实微信端到端完成；全程不清除设备数据、不卸载应用、不修改系统敏感授权。

## 验收标准

- [ ] 旧手机号账号和原 Bearer Session 经 v5 迁移后继续有效，注销期限、设备、云配置和 AI 日志无损保留。
- [ ] 手机号注册、登录、找回、退出、注销和本地模式行为与现有版本兼容。
- [ ] 已绑定微信可直接登录；未绑定微信可创建账号，或通过密码和短信绑定已有手机号账号。
- [ ] 纯微信账号可绑定未注册手机号并设置密码，且微信身份继续有效。
- [ ] 账号合并只允许互补凭据，始终保留当前账号，并严格执行配置、设备、AI 日志和 Session 规则。
- [ ] 仅有微信一种凭据时不能解绑；具备手机号凭据并二次验证后可以解绑且获得新 Session。
- [ ] 微信昵称、HTTPS 头像 URL 在每次成功授权后刷新，头像失败时使用默认占位。
- [ ] AppSecret、微信 Token、code、OpenID、UnionID 和原始 Provider 响应不进入 APK、客户端 Session、日志或诊断导出。
- [ ] Android 无 AppID 时隐藏微信入口；后端无微信配置时仅微信接口返回稳定错误，手机号和本地模式保持可用。
- [ ] 登录、绑定、合并、解绑、注销及退出前后，本机 Room 账本与备份格式完全不变。

## 验收测试

- `.\gradlew.bat --no-daemon :shared:api:test`
- `.\gradlew.bat --no-daemon :services:backend:test --tests "com.autoaccounting.backend.account.*"`
- `.\gradlew.bat --no-daemon :services:backend:test --tests "com.autoaccounting.backend.config.*" --tests "com.autoaccounting.backend.ai.*"`
- `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.account.*" --tests "com.autoaccounting.feature.profile.*" --tests "com.autoaccounting.MainActivityTest"`
- `.\gradlew.bat --no-daemon :services:backend:test`
- `.\gradlew.bat --no-daemon :apps:android:testDebugUnitTest`
- `.\gradlew.bat --no-daemon detekt`
- `.\gradlew.bat --no-daemon build`
- `.\gradlew.bat --no-daemon :apps:android:assembleDebug :apps:android:assembleRelease`
- 对 Release APK 执行 `apksigner verify --verbose`，完成 R8 检查后运行 `.\gradlew.bat --stop`。

自动化测试至少覆盖：共享契约兼容、v4 数据库升级、旧 Session 保留、微信新建和重复登录、密码与短信绑定、手机号新增、票据过期和重放、资料接口降级、配置缺失、合并冲突和事务回滚、配置优先级、设备去重、AI 日志删除、Session 轮换、注销冷静期、无手机号禁止解绑、协议门控、微信取消或拒绝、state 不匹配、冷启动回跳、头像失败占位、旧加密 Session 恢复及本地账本隔离。

## 手工验证

1. 未配置 AppID 和后端微信配置时启动 Debug/Release，确认微信入口隐藏且手机号登录、注册、本地模式均正常；直接调用微信后端接口只返回稳定的未配置错误。
2. 使用假 SDK 和假 Provider 完成微信新账号创建、重复登录、密码绑定、短信绑定、手机号新增、两个方向的互补账号合并、冲突阻止和微信解绑。
3. 每个账号操作前后核对本机账本数量、当前账本、账目、最近删除和备份格式均未变化。
4. 微信开放平台审核通过后，在已登记签名的 Release 包上验证微信取消、拒绝、未安装、正常授权、冷启动回跳和 `onNewIntent` 回跳。
5. 在 Xiaomi 真机完成微信注册、退出后再登录、两种绑定、手机号新增、两个方向合并、解绑、重新绑定、昵称头像刷新和失败占位。
6. 真机验证不得清除应用数据、卸载应用、修改系统敏感权限或查看真实账本内容；日志与截图保存前必须脱敏。

## 回滚或安全说明

- 首次让新后端连接真实 PostgreSQL 前，必须制作时间戳备份并实际验证恢复流程；未满足此条件不得执行 v5 迁移。
- v5 必须以单事务迁移并通过升级测试；迁移后需要回退时停服并恢复已验证备份，不直接执行反向删除性 SQL。
- 先部署兼容后端，确认旧手机号客户端和旧 Session 正常，再启用带 AppID 的 Android 客户端。
- 合并必须在单事务内重新锁定并校验两个账号；不得依赖 UI 预览或事务外的先查后写结果。
- 票据原值、微信 code、Token、OpenID、UnionID、密码和短信验证码不得出现在日志、异常、截图、文档或版本库中。
- 真实 AppSecret 只允许写入被 Git 忽略的后端 `.env`；Android APK 只包含可公开的 AppID。
- 账号合并和注销不提供通过 Android Room 回滚云端身份的机制；本机账本始终保持独立且不得重新分配。

## 验证记录

- `2026-07-22`：完成代码、数据库、Android 账号界面及微信官方接入要求调研，形成前置阶段 0 加实施阶段 1–13 的严格计划；共享 API、后端账号专项和 Android 账号专项基线测试通过。
- `2026-07-22`：仅写入计划与 Phase 2 索引，未修改业务代码、数据库、Android 配置或真机状态；微信开放平台尚未申请，真实端到端验收延期。
- `2026-07-22`：提交全部既有文档改动为 `3bc7786` 后完成 Task 0；`:shared:api:test`、后端 `com.autoaccounting.backend.account.*` 和 Android `com.autoaccounting.feature.account.*` 均成功，工作树在测试后保持干净，未连接或迁移真实 PostgreSQL。
- `2026-07-22`：完成 Task 1。`AccountContracts.kt` 中 `phone` 改为可空、新增 `wechatLinked`/`nickname`/`avatarUrl` 字段（默认值兼容旧客户端）；新增 9 个微信相关错误码、`TICKET_VALIDITY_MILLIS` 常量、`WechatAuthResultContract`（三种认证状态密封类）、`WechatExchangeResponseContract`、`PhoneLinkPrepareResponseContract`、`MergePreviewResponseContract` 及完整的 JSON 编解码方法。测试文件从 4 个扩展到 30 个用例，覆盖旧手机号 JSON 兼容、`phone=null` 微信账号、三种认证状态编解码对称、缺失必需字段明确失败、PhoneLinkPrepare 和 MergePreview 编解码对称、新增字段不影响旧客户端、全部新错误码和票据有效期常量。`:shared:api:test` 零警告零错误通过，Gradle Daemon 已停止。
- `2026-07-22`：完成 Task 2。统一后端 1–5 号数据库迁移入口 `runBackendMigrations` 至 `JdbcMigrations.kt`，并建立事务性 5 号迁移 SQL：新增 `accounts`（自增 `account_id`）、`account_phone_credentials`、`account_wechat_identities`、`account_one_time_tickets`；将 `account_sessions`、`registered_devices`、`cloud_config` 和 `ai_categorization_logs` 的外键从 `phone` 迁移关联为 `account_id`；`account_sms_codes` 扩展 `purpose`（默认 'DEFAULT'）和 `context_key`。适配 `JdbcAccountStore`、`JdbcCloudConfigStore`、`JdbcAiCategorizationLogStore` 以兼容 v5 表结构。新增 `DatabaseMigrationTest.kt`，验证 v4->v5 数据平滑无损迁移、原 Token 哈希与凭据全量保留、重复启动幂等性、孤立外键检测及事务容错。全套 `:services:backend:test`（57 项测试）及 `:services:backend:detekt` 均 100% 成功通过，未连接或修改真实 PostgreSQL，测试后 Gradle Daemon 已停止。
- `2026-07-23`：完成 Task 3。将现有后端账号 Service、Store、Session、设备、Cloud Config、AI 日志及注销定时任务的全链路主标识从手机号迁移为内部 `accountId`。拆分 `StoredUser` 为 `StoredAccount` 与 `StoredPhoneCredential`，重构 `InMemoryAccountStore` 与 `JdbcAccountStore`，直接按 `accountId` 执行持久化与级联删除。CloudConfigStore/Service 与 AiCategorizationLogStore/Service 全量重构为 `accountId` 驱动。保持 HTTP 请求与响应 JSON 完全兼容，旧手机号客户端无需变更。全套单元与集成测试（`AccountServiceTest`, `AccountPersistenceTest`, `CloudConfig*`, `AiCategorization*`）及 `detekt` 静态检查均 100% 成功通过。
- `2026-07-23`：完成 Task 4。实现服务端微信授权 code 换取与一次性票据管理。在 `services/backend/.env.example` 补充 `AUTO_ACCOUNTING_WECHAT_APP_ID` 和 `AUTO_ACCOUNTING_WECHAT_APP_SECRET` 配置项；新增基于 Java 17 标准 HttpClient 的无依赖 `DefaultWechatOAuthClient`，实现超时控制与敏感字段（Access Token, OpenID, UnionID, AppSecret, Code）全脱敏防泄漏处理；扩展 `AccountStore`（内存与 JDBC）支持微信身份、原子认领与一次性票据 CRUD；在 `AccountError` 与 `AccountRoutes` 扩展 9 个微信错误码与 `POST /account/wechat/exchange` 端点；在 `AccountService` 实现 code 换取与 4 种分支处理逻辑（未绑定生成认证票据返回 `REGISTRATION_REQUIRED`、Bearer 下未绑定直接关联返回 `SIGNED_IN`、已绑定当前账号刷新个人信息返回 `SIGNED_IN`、Bearer 下已绑定其他账号生成合并票据返回 `MERGE_REQUIRED`），并拒绝用新微信静默覆盖已有绑定。测试覆盖 Mock HTTP 错误码与超时、完整 Service 分支、路由、已有绑定保护及 H2/JDBC 双线程并发认领。全套后端单元测试通过；`:services:backend:detekt` 任务通过，并保留 `JdbcAccountStore` 的既有 `LargeClass` 提示。
- `2026-07-23`：完成 Task 5。实现微信注册纯账号及绑定已有手机号账号的 3 个 HTTP 路由（`/account/wechat/register`、`/account/wechat/link/password`、`/account/wechat/link/sms`）。扩展 `AccountStore` 与 `JdbcAccountStore` 支持单事务原子消费微信授权票据、创建纯微信账号/绑定微信身份、注册设备及签发 Session；密码绑定继承登录重试与锁定规则，短信绑定验证专项验证码并销毁；在“一个账号最多绑定一个微信身份且一个微信身份只能绑定一个账号”约束下，对已绑定微信的账号拒绝静默覆盖。在 `AccountToken` 与 Ktor 响应透出 `wechatLinked`、`nickname`、`avatarUrl` 资料。新增 `WechatRegisterAndLinkTest.kt` 并扩展 `AccountRoutesTest.kt`，覆盖微信纯账号创建与重复登录、密码绑定、短信绑定、短信发送频率限制、密码错误锁定、验证码错误/过期、票据超时/重复使用/假票据、账号微信身份冲突及 Ktor 端到端 HTTP 交互。全套后端单元与集成测试、`:shared:api:test` 及 `:services:backend:detekt` 均 100% 成功通过，Gradle Daemon 已停止。
- `2026-07-23`：完成 Task 6。实现纯微信账号新增手机号与设置密码流程。支持 `PHONE_LINK` 专项验证码及 HTTP 端点 `/account/phone/link/prepare`（根据手机号注册状态分别返回 `PHONE_TICKET_ISSUED` 或 `MERGE_REQUIRED` 票据）与 `/account/phone/link/complete`（单事务消费手机号票据、创建手机凭据、轮换 Session 并注册设备）。严格执行“短信验证通过前不泄露手机号注册状态”防泄漏规则；修正在纯微信账号（`phone=""`）下 `phoneUserByAccountId` 的判空逻辑；支持内存 `InMemoryAccountStore` 与 `JdbcAccountStore` 事务持久化。新增 `PhoneLinkTest.kt`（9 项测试），覆盖新增未注册手机号、密码强度拒绝、验证码错误防泄漏、手机号已存在触发合并、票据重复使用与过期、并发注册冲突阻断、已绑定手机号拒绝重复绑定、H2/JDBC 持久化及 Ktor 端到端 HTTP 接口测试。全套 `:services:backend:test`、`:shared:api:test` 及 `:services:backend:detekt` 均 100% 成功通过，测试后 Gradle Daemon 已停止。




## 依赖

- Issue 9：后端账号、短信及注册设备持久化。
- Issue 10：后端云配置与 AI 代理状态。
- Issue 11：账号注销与定时云端清理。
- Issue 18：Profile 概览与账户管理导航。
- Issue 22：合规与隐私及第三方服务清单。
- Issue 25：Android 账号核心闭环、安全 Session 与真实后端接入。
- 微信开放平台移动应用和微信登录能力审核仅阻断真实 Provider 与真机验收，不阻断假 Provider 自动化实现。

## 默认假设

- 一个账号最多一个手机号和一个微信身份。
- 双方存在不同同类凭据时阻止合并，不提供逐项凭据取舍。
- 当前登录账号始终是合并目标。
- 来源 AI 日志在合并时删除；当前账号日志保留。
- 当前云配置优先，来源独有 feature flag 补入，同名键保留当前值。
- 微信头像只保存 HTTPS URL 并在授权时刷新，不在后端下载或长期托管图片。
- 本轮实现不修改本机账本、Room schema、备份格式或自动记账采集链路。
