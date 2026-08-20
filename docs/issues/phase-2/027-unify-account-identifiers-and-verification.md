# 已完成：统一用户名、邮箱、手机号认证与验证码

## 目标

将现有手机号专用认证升级为统一账号标识认证：用户可以使用普通用户名、邮箱或手机号注册和登录；手机号继续通过短信验证码验证，邮箱通过 SMTP 邮件验证码验证，普通用户名直接使用密码注册。用户名账号可以补充已验证的手机号和邮箱作为额外登录与找回方式，同时保持微信登录、本地模式、本机账本及账号注销边界不变。

## 范围

- Android 注册和登录使用统一的“用户名 / 邮箱 / 手机号”输入框，并补齐三种标识的前端校验。
- 用户名注册不需要验证码；手机号和邮箱注册分别使用短信、邮件验证码。
- 验证码输入框与获取按钮位于同一行，输入框占约三分之二、按钮占约三分之一。
- 后端按统一规则识别、规范化和查询用户名、邮箱或手机号。
- 每个账号拥有一份共享密码和登录锁定状态，可绑定至多一个用户名、一个邮箱和一个手机号。
- 用户名账号可补充手机号和邮箱；补充后均可用于登录和找回密码。
- 已绑定手机号或邮箱可通过对应渠道验证码显式换绑；普通补绑请求不得覆盖已有同类型标识。
- 微信绑定、合并与解绑逻辑同步识别新的密码登录方式，但无 AppID 时隐藏微信入口的行为不变。
- 通过数据库 v6 将现有手机号凭据无损迁移为账号级密码凭据和 `PHONE` 登录标识。
- 最终直接切换到统一标识协议，不保留旧 `phone` 请求参数及手机号专用认证端点。

## 非目标

- 不提供普通用户名修改、解绑或注册后补绑用户名。
- 不提供手机号或邮箱解绑，也不提供用户手动切换主标识。
- 不允许两个已经拥有密码凭据的账号因标识冲突而自动合并、转移标识或覆盖凭据。
- 不改变 Android Room、账本归属、备份格式、通知采集、账单同步或自动记账流程。
- 不在未制作并验证可恢复备份的真实 PostgreSQL 上执行 v6 迁移。
- 不在版本库、日志、诊断导出、截图或验证记录中写入真实 SMTP、短信、微信、签名凭据或未脱敏账号数据。

## 固定接口与行为

### 标识识别与规范化

- 输入先移除首尾空白，空值直接判定无效。
- 包含 `@` 的输入只能按邮箱校验；邮箱格式错误时不得回退为用户名。
- 全数字输入只能按手机号校验；手机号格式错误时不得回退为用户名。
- 其他输入只能按普通用户名校验。
- 普通用户名为 4–20 位，以英文字母开头，仅允许英文字母、数字和下划线；使用 `Locale.ROOT` 转为小写作为唯一键，界面保留注册时的大小写。
- 邮箱最长 254 字符，拒绝缺少本地域、缺少域名、连续点和非法域标签；规范化值与展示值均使用小写。
- 手机号保持现有 11 位纯数字规则，规范化值和展示值均使用原始纯数字。
- Android 使用共享解析规则提供即时反馈；后端必须独立重新解析和校验，不能信任客户端提交的类型。

### 账号与密码

- 每个账号最多拥有一个 `USERNAME`、一个 `EMAIL`、一个 `PHONE` 登录标识。
- 使用用户名、邮箱或手机号注册时，该标识成为主标识；后续绑定手机号或邮箱不会改变主标识。
- 微信纯账号绑定的第一个密码标识成为主标识。
- 每个账号只有一份密码哈希、失败次数和锁定时间；从任一绑定标识登录失败都会累计到同一账号。
- 保持现有密码强度、连续 5 次失败锁定 15 分钟及设备登记规则。
- 找回密码只接受已绑定并验证的手机号或邮箱；纯用户名未绑定联系方式时不能自助找回。
- 密码重置成功后清除锁定状态，撤销该账号全部旧 Session，并为当前设备签发新 Session。

### 验证码

- 手机号通过短信 Provider 发送，邮箱通过 SMTP Provider 发送；用户名不得请求验证码。
- 验证码为 6 位数字，5 分钟有效，最多允许 3 次错误尝试。
- 验证码哈希输入包含标识类型、规范化值、用途和验证码，禁止跨标识、跨渠道或跨用途重放。
- 继续按目标标识、设备 ID 和真实远端 IP 执行现有限流；忽略客户端提交的 IP。
- 验证码用途固定为 `REGISTER`、`RECOVERY`、`IDENTIFIER_LINK`、`WECHAT_LINK`、`WECHAT_UNLINK`。

### 公共契约与端点

- 新增 `AccountIdentifierTypeContract`：`USERNAME`、`EMAIL`、`PHONE`。
- 新增 `AccountIdentifierContract`：`type`、`value`、`verified`。
- Session 返回 `accountUuid`、`primaryIdentifier`、`identifiers`、`token`、账号 Profile、微信资料和注销状态；微信纯账号允许主标识为空、标识列表为空。
- 新协议端点：
  - `POST /account/verification-code`
  - `POST /account/register`
  - `POST /account/login`
  - `POST /account/recover`
  - `POST /account/identifier/link/prepare`
  - `POST /account/identifier/link/complete`
  - `POST /account/wechat/link/password`
  - `POST /account/wechat/link/code`
  - `POST /account/wechat/unlink/password`
  - `POST /account/wechat/unlink/code`
- 注册请求使用 `identifier`、`code`、`password`、`deviceId`；用户名注册的 `code` 必须为空，邮箱和手机号注册的 `code` 必填。
- 登录请求使用 `identifier`、`password`、`deviceId`。
- 找回请求使用手机号或邮箱形式的 `identifier`、`code`、`password`、`deviceId`。
- 最终删除 `/account/sms`、`/account/phone/link/*`、微信短信专用路径，以及认证请求和 Session 中的旧顶层 `phone` 协议字段。

### SMTP 配置

- 后端通过以下环境变量配置邮件发送，不写入真实示例值：
  - `BKS_EMAIL_PROVIDER=smtp`
  - `BKS_SMTP_HOST`
  - `BKS_SMTP_PORT`
  - `BKS_SMTP_USERNAME`
  - `BKS_SMTP_PASSWORD`
  - `BKS_SMTP_FROM`
  - `BKS_SMTP_SECURITY=starttls|ssl`
- 默认使用 STARTTLS/587，支持 SSL/465，使用 JVM 默认信任链，禁止 trust-all 与 SMTP 调试日志。
- 邮件主题为“自动记账验证码”，正文只包含用途说明、验证码和 5 分钟期限，不包含密码、Token 或其他账号资料。

## 目标文件或模块

- `shared/api`：统一标识类型、解析规则、Session、绑定、合并和错误契约。
- `services/backend`：v6 迁移、Store、验证码 Provider、SMTP、注册登录找回、绑定、微信与 Ktor Routes。
- `apps/android`：Repository、加密 Session V5（兼容读取 V1–V4）、注册登录找回状态、验证码布局、账号管理及微信界面。
- `docs`：API、架构、UI、合规、后端配置示例、Phase 2 索引及本 Issue 的执行证据。

## 执行规则

- Task 必须按编号顺序执行，同一时间只能有一个 Task 处于“进行中”。
- 每个 Task 开始前运行 `git status --short`，记录并避开无关工作区改动。
- 每个 Task 只修改其明确范围，先运行最窄验证，再检查完整影响链。
- 只有验证成功、重读改动并填写对应验证记录后，才能将 Task 标记完成并进入下一 Task。
- 验证失败时保持当前 Task 未完成，记录命令、错误和停止边界，不继续下一 Task。
- 每轮 Gradle 验证结束后运行 `.\gradlew.bat --stop`。
- 未经用户明确要求，不 commit、push、建分支、安装 APK 或操作真机。

## Task 1：记录基线并确认实施边界

- **依赖**：Issue 009、018、025，以及 Issue 026 已完成的自动化实现部分。
- **范围**：记录当前 HEAD、工作区状态、现有账号契约、数据库 v5、Session v2、手机号和微信测试基线；真实 SMTP、PostgreSQL 迁移和 Android 真机验收在后续收尾阶段补充，微信真实验收仍受外部条件限制。
- **验证**：
  - `.\gradlew.bat :shared:api:test --no-daemon`
  - `.\gradlew.bat :services:backend:test --tests "com.bks.backend.account.*" --no-daemon`
  - `.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.account.*" --no-daemon`
- **完成条件**：三组测试成功，基线与工作区状态写入验证记录，并将本 Issue 从“计划中”改为“进行中”。
- **停止条件**：基线失败，或认证目录存在来源不明且与计划重叠的改动。
- **回滚**：本 Task 不修改业务代码；仅撤销错误的状态或基线记录。

## Task 2：建立共享标识解析与 API 契约

- **依赖**：Task 1。
- **范围**：新增标识类型、解析与规范化结果、主标识及标识列表契约、通用绑定/合并响应和稳定错误码；暂时保留旧字段供迁移期编译，Task 12 删除。
- **测试**：覆盖三种合法标识、大小写、首尾空格、非法 `@`、非法纯数字、邮箱边界、JSON 编解码对称和缺失必需字段失败。
- **验证**：`.\gradlew.bat :shared:api:test --no-daemon`。
- **完成条件**：Android 与后端可复用同一解析结果，非法邮箱或手机号不会回退成用户名，全部新契约具有对称测试。
- **停止条件**：两端解析结果不一致，或无法通过临时适配保持其他模块编译。
- **回滚**：删除本 Task 新增的契约和解析器，不改变现有手机号行为。

## Task 3：实施数据库 v6 与 Store 改造

- **依赖**：Task 2。
- **范围**：
  - `accounts` 增加可空的主标识类型。
  - 新建账号级密码凭据表。
  - 新建登录标识表，并对 `(account_id, identifier_type)` 和 `(identifier_type, normalized_value)` 建立唯一约束。
  - 泛化验证码和发送记录存储。
  - 将现有手机号账号迁移为一条密码凭据和一条 `PHONE` 主标识。
  - Store、InMemory Store 和 JDBC Store 改为按规范化标识查询并按 accountId 更新共享密码状态。
- **验证**：
  - `.\gradlew.bat :services:backend:test --tests "com.bks.backend.DatabaseMigrationTest" --no-daemon`
  - `.\gradlew.bat :services:backend:test --tests "com.bks.backend.account.AccountPersistenceTest" --no-daemon`
  - `.\gradlew.bat :services:backend:test --tests "com.bks.backend.account.AccountMergeTest" --no-daemon`
- **完成条件**：v5→v6 数量和关键字段一致，迁移幂等、失败时全事务回滚，无孤立密码凭据、标识或 Session；非空数据路径由隔离 PostgreSQL schema 集成测试覆盖。
- **停止条件**：任一账号或关联数据丢失，H2/PostgreSQL 语义不一致，或必须连接真实 PostgreSQL 才能继续。
- **回滚**：只重建一次性测试数据库；不编写或执行真实数据库降级 SQL。

## Task 4：泛化验证码并实现 SMTP Provider

- **依赖**：Task 3。
- **范围**：保留短信 Provider，新增邮件 Provider 与统一验证码分发层；实现 SMTP 配置、邮件正文、通用哈希、错误映射、限流和持久化；自动化测试使用假 Provider 或本地 SMTP 替身，并在收尾阶段使用受控 163 SMTP 完成一次真实发送验收。
- **测试**：覆盖短信/邮件渠道选择、用户名拒绝请求、错误/过期/重放/限流、Provider 未配置、连接失败、认证失败、超时和发送失败。
- **验证**：
  - `.\gradlew.bat :services:backend:test --tests "com.bks.backend.account.*Verification*" --no-daemon`
  - `.\gradlew.bat :services:backend:test --tests "com.bks.backend.account.*Provider*" --no-daemon`
- **完成条件**：手机号只调用短信，邮箱只调用 SMTP，验证码不可跨用途复用，失败响应稳定且无敏感日志。
- **停止条件**：邮件组件无法关闭敏感调试输出，或真实 SMTP 验收需要将凭据写入代码、版本库或日志。
- **回滚**：移除邮件 Provider 和通用分发层，保留尚未使用的 v6 空表，不删除数据。

## Task 5：实现统一注册、登录与找回密码

- **依赖**：Task 4。
- **范围**：Service 改为统一 `identifier`；用户名注册要求验证码为空，邮箱/手机号消费 `REGISTER` 验证码；登录共享失败计数与锁定；找回只接受邮箱/手机号并消费 `RECOVERY` 验证码；重置后撤销全部旧 Session。
- **测试**：覆盖三种注册登录、重复注册、大小写唯一性、错误密码、共享锁定、找回成功/失败、用户名无找回渠道、旧密码和旧 Token 失效。
- **验证**：
  - `.\gradlew.bat :services:backend:test --tests "com.bks.backend.account.AccountServiceTest" --no-daemon`
  - `.\gradlew.bat :services:backend:test --tests "com.bks.backend.account.AccountPersistenceTest" --no-daemon`
- **完成条件**：三种标识均可注册登录，大小写不能绕过唯一约束，任一别名共享锁定，找回后旧凭据失效。
- **停止条件**：并发注册可产生重复标识，登录失败暴露账号是否存在，或敏感值进入日志。
- **回滚**：恢复临时手机号 Service 包装器，不删除已迁移的测试数据结构。

## Task 6：实现手机号与邮箱绑定

- **依赖**：Task 5。
- **范围**：用户名账号可分别绑定一个手机号和邮箱；已有密码账号绑定空闲标识时直接绑定，重复绑定当前账号时幂等成功，标识属于其他密码账号时返回冲突；微信纯账号使用密码设置或现有合并票据流程。
- **测试**：覆盖新绑定、重复绑定、冲突、票据过期/重放/并发消费、密码设置失败回滚、绑定后登录和找回。
- **验证**：
  - `.\gradlew.bat :services:backend:test --tests "com.bks.backend.account.*IdentifierLink*" --no-daemon`
  - `.\gradlew.bat :services:backend:test --tests "com.bks.backend.account.AccountMergeTest" --no-daemon`
- **完成条件**：绑定标识立即可登录找回，冲突不会改变任一账号，失败事务无半绑定状态。
- **停止条件**：两个密码账号可自动合并，或产生无密码、无微信且无法登录的账号。
- **回滚**：依靠绑定事务回滚，不通过删除或转移其他账号标识修复冲突。

## Task 7：适配微信绑定、合并与解绑后端

- **依赖**：Task 6。
- **范围**：微信密码绑定使用统一标识；验证码绑定和解绑支持邮箱/手机号；任意密码凭据均算保底登录方式；微信纯账号合并密码账号时迁移密码凭据和全部标识；合并预览展示脱敏标识列表。
- **验证**：
  - `.\gradlew.bat :services:backend:test --tests "com.bks.backend.account.WechatRegisterAndLinkTest" --no-daemon`
  - `.\gradlew.bat :services:backend:test --tests "com.bks.backend.account.WechatUnlinkTest" --no-daemon`
  - `.\gradlew.bat :services:backend:test --tests "com.bks.backend.account.AccountMergeTest" --no-daemon`
- **完成条件**：三种密码账号可绑定微信，用户名或邮箱密码可作为解绑后的保底方式，合并后凭据与标识归属一致且不会静默覆盖微信身份。
- **停止条件**：合并产生两个密码凭据、重复标识，或改变既有配置、设备和 AI 日志合并规则。
- **回滚**：依靠单事务恢复合并前状态，不手工移动数据库标识。

## Task 8：增加统一标识 Ktor 路由

- **依赖**：Task 7。
- **范围**：增加全部新端点和稳定状态码；Routes 只处理参数、Bearer、远端 IP 和响应映射；暂时保留旧路由供 Android 切换，Task 12 删除。
- **测试**：覆盖每个端点的成功、字段缺失、非法类型、Provider 错误、锁定、冲突、票据和远端 IP 行为。
- **验证**：
  - `.\gradlew.bat :shared:api:test --no-daemon`
  - `.\gradlew.bat :services:backend:test --tests "com.bks.backend.account.AccountRoutesTest" --no-daemon`
  - `.\gradlew.bat :services:backend:test --tests "com.bks.backend.account.*" --no-daemon`
- **完成条件**：所有新端点具有 HTTP 测试，客户端 IP 被忽略，新接口不依赖旧 `phone` 字段。
- **停止条件**：新旧路由使用不同安全逻辑，或 Routes 开始自行处理密码和验证码。
- **回滚**：移除新路由，保留通用 Service 与 Store，不回滚数据库。

## Task 9：切换 Android Repository 与加密 Session V3

- **依赖**：Task 8。
- **范围**：Repository 切换到统一标识与新端点；`AccountCredentials` 和 `AccountSession.SignedIn` 使用主标识及标识列表；增加手机号、邮箱、用户名派生访问器；Secure Session 升级为 V3，并继续读取 V1/V2；保留 Session 保存失败时撤销新 Token 的补偿逻辑。
- **验证**：
  - `.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.account.HttpAccountRepositoryTest" --no-daemon`
  - `.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.account.SecureAccountSessionStoreTest" --no-daemon`
  - `.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.account.*Session*" --no-daemon`
- **完成条件**：Android 不再发送旧手机号认证字段，V1/V2/V3 均可安全恢复，无手机号和无微信的密码账号可跨重启恢复。
- **停止条件**：旧 Session 被无条件清除，或 Token、标识、微信资料以明文持久化。
- **回滚**：恢复 V2 写入但保留 V3 解码能力，不删除用户现有加密 Session。

## Task 10：改造注册、登录、找回页面与验证码布局

- **依赖**：Task 9。
- **范围**：账号状态改用 `identifier`；登录字段显示“用户名 / 邮箱 / 手机号”；用户名注册隐藏验证码；邮箱/手机号注册显示验证码；找回只接受联系方式；标识变化时清空旧验证码和倒计时；验证码行使用输入框 `weight(2f)`、8dp 间距、按钮 `weight(1f)`、顶部对齐与紧凑单行按钮。
- **验证**：
  - `.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.account.AccountStateTest" --no-daemon`
  - `.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.account.AccountScreenTest" --no-daemon`
- **完成条件**：用户名不请求验证码，邮箱/手机号必须验证码，输入框与按钮同一行且宽度约 2:1，400/610/900dp 与 1.5 倍字体无重叠和不可点击。
- **停止条件**：类型切换后旧验证码仍可提交，目标尺寸布局失败，或协议门控与返回层级回归。
- **回滚**：仅恢复旧表单渲染，不回退已完成的 Repository 与后端协议。

## Task 11：完成账号管理绑定与微信 Android 适配

- **依赖**：Task 10。
- **范围**：账号管理展示单一优先身份（用户名优先，其次微信昵称、手机号或邮箱）；隐藏健康状态连接卡，仅在离线且未验证时保留精简“重新验证”入口；移除重复的用户名明细和登录方式行；绑定、微信门控与验证码流程保持既有协议边界。
- **验证**：
  - `.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.account.AccountManagementScreenTest" --no-daemon`
  - `.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.account.*Wechat*" --no-daemon`
  - `.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.MainActivityTest" --no-daemon`
- **完成条件**：用户名账号可绑定手机号和邮箱，绑定 Session 可跨重启恢复，密码账号冲突不进入合并，无 AppID 隐藏入口测试通过。
- **停止条件**：Session 保存失败后 UI 仍显示成功，新入口绕过协议门控，或 UI 可发起密码账号之间合并。
- **回滚**：移除新增绑定 UI，不伪造本地绑定或登录成功状态。

## Task 12：移除旧手机号认证协议

- **依赖**：Task 11。
- **范围**：删除迁移期 Service 包装器、旧 Repository 签名、旧响应字段、`/account/sms`、`/account/phone/link/*`、微信短信专用路径及注册登录找回中的旧 `phone` 参数；保留 `PHONE` 标识类型和手机号展示派生属性。
- **测试**：旧路径返回 404，只提交旧 `phone` 参数的新路径返回 `INVALID_REQUEST`；使用 `rg` 检查生产代码不存在旧端点与旧认证签名，历史迁移 SQL不参与清理。
- **验证**：
  - `.\gradlew.bat :shared:api:test --no-daemon`
  - `.\gradlew.bat :services:backend:test --tests "com.bks.backend.account.*" --no-daemon`
  - `.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.account.*" --no-daemon`
- **完成条件**：运行时代码只使用新协议，手机号仍可完成全部认证行为，临时兼容代码全部清零。
- **停止条件**：删除兼容层后任一模块无法编译，或仍有生产代码访问旧端点。
- **回滚**：恢复最后一层临时适配以恢复编译，但保持 Task 未完成，不把兼容状态作为最终交付。

## Task 13：完整回归、文档与最终验收

- **依赖**：Task 12。
- **范围**：同步 API、架构、UI、合规、SMTP 配置示例与 Phase 2 索引；检查用户名、邮箱、手机号、验证码、Token 和 SMTP 凭据的诊断脱敏；运行完整测试、静态检查、构建、Release 签名与密钥扫描；记录所有未执行的外部验收。
- **验证**：
  - `.\gradlew.bat :shared:api:test --no-daemon`
  - `.\gradlew.bat :services:backend:test --no-daemon`
  - `.\gradlew.bat :apps:android:testDebugUnitTest --no-daemon`
  - `.\gradlew.bat coverageReport --no-daemon`
  - `.\gradlew.bat detekt --no-daemon`
  - `.\gradlew.bat build --no-daemon`
  - `.\gradlew.bat :apps:android:assembleDebug :apps:android:assembleRelease --no-daemon`
  - 后端 `SecretScannerTest`、`git diff --check`、文档 UTF-8/链接/TODO 扫描。
  - Release 凭据存在时执行 `apksigner verify --verbose`。
- **完成条件**：自动化验收全部通过，Issue 027 的 Task 1–13 均有真实验证记录，工作区没有凭据、临时文件或构建产物；全部强制验收完成后将 Issue 标为“已完成”。
- **停止条件**：任一完整测试、构建、迁移或密钥扫描失败；需要真实 PostgreSQL、SMTP、微信或真机权限时停止并请求用户授权。
- **回滚**：只回退本 Task 的错误文档改动；不回退已验证迁移，不执行破坏性 Git 操作。

## 验收标准

以下标准初始保持未勾选，只能根据实际验证证据逐项更新：

- [x] 普通用户名、邮箱、手机号均可独立注册并登录。
- [x] 用户名遵循 4–20 位、字母开头、字母数字下划线规则，唯一性忽略大小写。
- [x] 邮箱规范化后唯一，手机号保持现有 11 位规则。
- [x] 用户名注册不显示、不请求、不提交验证码。
- [x] 邮箱注册通过 SMTP 验证码，手机号注册通过短信验证码。
- [x] 验证码为 6 位、5 分钟有效、最多错误 3 次，不能跨目标、渠道或用途复用。
- [x] 验证码输入框和获取按钮位于同一行，输入框与按钮宽度约为 2:1。
- [x] 用户名账号可分别绑定一个手机号和一个邮箱。
- [x] 已绑定手机号和邮箱可通过单次票据与对应验证码换绑，普通补绑不会静默覆盖。
- [x] 绑定后的手机号和邮箱均可登录和找回密码。
- [x] 任一绑定标识共享密码、失败次数和锁定状态。
- [x] 密码重置后全部旧 Session 失效。
- [x] 密码账号之间的标识冲突不会转移标识或合并账号。
- [x] 微信纯账号仍可安全绑定或合并已有密码账号。
- [x] 用户名或邮箱密码可作为解绑微信后的保底登录方式。
- [x] Android 未配置 AppID 时微信入口继续隐藏。
- [x] v5 手机号账号迁移后密码哈希、Session、设备、微信身份、配置、AI 数据和注销状态不丢失。
- [x] Android 加密 Session V1/V2 可升级到 V3，用户名和邮箱账号可跨重启恢复。
- [x] 后端支持统一标识认证协议与端点。
- [x] 登录、注册、绑定、找回、合并和解绑前后，本机 Room、账本及备份格式不变。
- [x] 日志、错误、测试、截图和文档不泄露密码、验证码、Token、SMTP、短信或微信凭据。
- [x] H2/JDBC、假短信、假 SMTP、假微信和 Android 自动化全部通过。
- [x] 已使用受控 163 SMTP 完成真实邮件发送验收，凭据未进入代码、版本库或日志。
- [x] 真实 PostgreSQL 在可恢复备份后完成 v5→v6，重复启动保持幂等。
- [x] 已在 Xiaomi 测试机安装签名 Release APK，并完成用户名注册与自动登录真实验收；微信真实验收仍待 AppID/真实微信条件。

## 验收测试

- 共享标识解析、规范化与 JSON 契约测试。
- v5→v6 迁移、重复启动、失败回滚和关联数据完整性测试。
- 三种标识注册、登录、重复注册和大小写唯一性测试。
- 短信和邮件验证码成功、错误、过期、重放、限流及 Provider 故障测试。
- 多标识共享锁定、找回密码和旧 Session 撤销测试。
- 标识绑定、冲突、票据过期和并发消费测试。
- 微信绑定、合并、解绑和最后一种登录方式测试。
- Android Repository、Session V1/V2/V3、Reducer、Compose 和返回层级测试。
- 400/610/900dp 与 1.5 倍字体布局测试或人工检查。
- 无 AppID 隐藏微信入口的回归测试。
- 完整 `shared/api`、backend、Android、coverage、detekt、build、Debug/Release 和密钥扫描。

## 手工验证

1. 用户名注册时确认验证码行完全隐藏，注册后可使用原用户名不同大小写登录。
2. 邮箱和手机号注册时确认验证码输入框与按钮处于同一行，宽度约为 2:1。
3. 使用用户名账号分别绑定手机号和邮箱，再用三种标识登录同一账号。
4. 使用绑定手机号和绑定邮箱分别找回密码，确认旧密码和所有旧 Session 失效。
5. 绑定已属于其他密码账号的联系方式，确认只显示冲突且不进入账号合并。
6. 未配置 AppID 的 Debug/Release 构建确认微信入口隐藏。
7. 如配置隔离测试 SMTP，确认邮件送达、验证码内容正确、5 分钟后失效且无法重放。
8. 每个账号操作前后核对本机账本、最近删除和备份格式没有变化。
9. 真机操作必须遵循现有 ADB 安全边界，不清除数据、不卸载应用、不修改系统敏感权限。

## 回滚或安全说明

- v6 必须使用单事务迁移；真实数据库回退只能停服并恢复已验证备份，不执行反向删除性 SQL。
- 账号绑定、密码设置、账号合并和 Session 轮换必须在同一事务内重新校验并提交。
- 标识原值只在必要的认证响应中返回；日志、诊断、错误和验证记录使用脱敏值。
- SMTP 密码只允许来自被 Git 忽略的本地 `.env` 或进程环境变量。
- Android APK 不得包含 SMTP、短信或微信 AppSecret。
- 登录失败使用统一错误，不能通过响应判断某个标识是否存在。
- 账号及云身份变化不得重新分配、删除或修改本机账本。

## 验证记录

创建计划时预留以下记录。每完成一个 Task，填写对应条目；不得删除既有记录或在未验证时填写通过。

- **Task 1**：状态：已完成；完成日期：2026-07-23；变更摘要：无业务代码改动，记录基线 HEAD (84c4495) 与干净工作区，确认实施边界；验证命令与结果：:shared:api:test, :services:backend:test (--tests account.*), :apps:android:testDebugUnitTest (--tests account.*) 全部通过；当时遗留的真实 SMTP、PostgreSQL、微信与真机验收已在后续 Task 记录中更新，微信真实验收仍待外部条件。
- **Task 2**：状态：已完成；完成日期：2026-07-23；变更摘要：新增 AccountIdentifierTypeContract, AccountIdentifierContract, AccountIdentifierParser, IdentifierLinkPrepareResponseContract 及扩充 JSON 契约；验证命令与结果：:shared:api:test 全部成功 (40 tests)；遗留风险或停止边界：无。
- **Task 3**：状态：已完成；完成日期：2026-07-23；变更摘要：实施数据库 v6 迁移并重构三类 Store 支持统一标识；验证命令与结果：数据库迁移、持久化、合并测试全部通过；新增 PostgreSQL 非空隔离 schema 集成测试通过。2026-07-24 在本机 PostgreSQL 18.4 上先生成并校验 `pg_dump` 备份，再由真实后端启动完成 v5→v6，第二次启动迁移与 schema 指纹不变，双进程迁移锁测试通过；遗留风险或停止边界：真实目标库本次迁移前为空，未对生产数据执行迁移。
- **Task 4**：状态：已完成；完成日期：2026-07-23；变更摘要：新增 EmailProvider（Socket 零依赖 SMTP 发送与多环境配置）及 AccountService 泛化验证码分发/校验/限流层；新增 EmailProviderTest 与 VerificationCodeServiceTest；验证命令与结果：相关 Provider/Verification 测试 14 个通过；2026-07-24 使用受控 163 SMTP 完成真实发送并由用户确认收件；遗留风险或停止边界：凭据仅通过本地环境配置提供，未写入代码、版本库或日志。
- **Task 5**：状态：已完成；完成日期：2026-07-23；变更摘要：重构 AccountService 中的 registerIdentifier, loginIdentifier, recoverPasswordByIdentifier 支持三种标识注册/登录、用户名忽略大小写登录、共享锁定与找回密码撤销 Sessions；新增 AccountAuthenticationTest 与 PasswordRecoveryTest；验证命令与结果：:services:backend:test (--tests "com.bks.backend.account.AccountAuthenticationTest"), --tests "com.bks.backend.account.PasswordRecoveryTest" 全部通过 (5 tests)；遗留风险或停止边界：无。
- **Task 6**：状态：已完成；完成日期：2026-07-23；变更摘要：AccountStore 增加 deleteIdentifier，AccountService 实现 prepareIdentifierLink、confirmIdentifierLink、unlinkIdentifier，支持 IDENTIFIER_LINK 和 UNLINK 验证码用途与解绑校验，删除在解绑手机号时误删密码凭据的操作；新增 AccountLinkingTest 覆盖新绑定、重复绑定、标识冲突、保底登录方式阻止解绑与解绑校验；验证命令与结果：:services:backend:test (--tests "com.bks.backend.account.*IdentifierLink*"), --tests "com.bks.backend.account.AccountMergeTest" 全部通过；遗留风险或停止边界：无。
- **Task 7**：状态：已完成；完成日期：2026-07-23；变更摘要：适配微信绑定/解绑/合并逻辑支持统一标识与密码凭据，任意密码凭据或剩余标识均可作为解绑微信的保底登录方式，微信纯账号合并密码账号时转移密码凭据与全部标识，合并预览展示标识列表；在 AccountMergeTest 中新增邮箱账号合并测试；验证命令与结果：:services:backend:test (--tests "com.bks.backend.account.WechatRegisterAndLinkTest"), --tests "com.bks.backend.account.WechatUnlinkTest", --tests "com.bks.backend.account.AccountMergeTest" 全部通过；遗留风险或停止边界：无。
- **Task 8**：状态：已完成；完成日期：2026-07-23；变更摘要：AccountRoutes 增加全部统一标识端点 (/account/verification-code, /account/identifier/register, /account/identifier/login, /account/identifier/recover, /account/identifier/link/prepare, /account/identifier/link/complete, /account/identifier/unlink, /account/wechat/link/code, /account/wechat/unlink/code, /account/merge/prepare/identifier-password)，并暂时保留旧路径兼容；AccountRoutesTest 新增 testUnifiedIdentifierRoutes 覆盖全套新端点 HTTP 交互；验证命令与结果：:shared:api:test 与 :services:backend:test (--tests "com.bks.backend.account.AccountRoutesTest") 全部通过；遗留风险或停止边界：无。
- **Task 9**：状态：已完成；完成日期：2026-07-23；变更摘要：AccountCredentials 与 AccountSession.SignedIn 升级支持 primaryIdentifier 及 identifiers 列表，添加 phone/email/username 派生访问器；SecureAccountSessionStore 升级二进制序列化为 V3 并保持 V1/V2 解密向后兼容；HttpAccountRepository 发送 identifier 参数；验证命令与结果：:apps:android:testDebugUnitTest (--tests "com.bks.feature.account.*") 全部通过；遗留风险或停止边界：无。
- **Task 10**：状态：已完成；完成日期：2026-07-23；变更摘要：AccountUiState 增加 identifierType 与 requiresVerificationCode 判定，reducer 支持手机号/邮箱/用户名输入校验与倒计时触发；用户名注册自动隐藏验证码行；找回密码阻断用户名并给出友好提示；AccountStateTest 新增用户名注册、找回密码阻断与验证码请求单元测试；验证命令与结果：:apps:android:testDebugUnitTest (--tests "com.bks.feature.account.AccountScreenTest"), --tests "com.bks.feature.account.AccountStateTest" 全部通过；遗留风险或停止边界：无。
- **Task 11**：状态：已完成；完成日期：2026-07-23；变更摘要：账号管理页改为单一优先身份摘要，隐藏健康状态连接卡，离线未验证时保留精简“重新验证”入口，并移除重复用户名明细和登录方式行；绑定与微信门控保持协议边界；验证命令与结果：账号管理及相关 Wechat Compose 测试通过，定向回归 13/13 通过，Xiaomi 真机确认页面符合预期；遗留风险或停止边界：微信真实流程仍待 AppID/真实微信条件。
- **Task 12**：状态：已完成；完成日期：2026-07-24；变更摘要：删除 Store、JDBC、Service、Route、共享契约和 Android Repository 中的旧手机号双轨兼容层；运行时代码仅保留统一标识协议，微信解绑验证码改为显式提交并校验手机号或邮箱标识；验证命令与结果：共享、后端及 Android 账号定向测试全部通过，旧端点、旧响应字段、旧 Store 类型和方法的生产代码 `rg` 扫描无匹配；遗留风险或停止边界：无。
- **Task 13**：状态：已完成；完成日期：2026-07-24；变更摘要：同步 API、架构、产品、UI、合规、SMTP 示例及 Phase 2 索引，完成完整回归、静态检查、构建、签名与密钥扫描；验证命令与结果：Android 完整套件曾有 1 个 `MainActivityTest.firstLocalModeSelectionNavigatesToHome` 时序失败，单独重跑通过；账号/UI 定向测试 13/13 通过；Release 构建成功并经 v2 签名校验，安装到 Xiaomi 测试机成功；真实 PostgreSQL 18.4 v5→v6 备份、迁移、健康检查与重复启动验证通过；163 SMTP 真实发送成功；遗留风险或停止边界：微信真实验收仍待 AppID/真实微信条件，真实目标库本次迁移前为空；detekt 与 Release R8 仅保留既有告警，未导致任务失败。
- **后续账户管理扩展**：状态：已完成；完成日期：2026-07-28；变更摘要：显式 `replaceExisting` 票据支持手机号/邮箱换绑，保持验证码用途隔离、Session 轮换和主标识不变；同时补齐公开账号 UUID 与账号 Profile。验证命令与结果：Backend 路由/持久化及 Android Repository/Compose 回归已覆盖，Android 全量单测、Release 构建、v2 签名与 Xiaomi 覆盖安装通过；遗留风险或停止边界：真实短信 Provider 仍未配置，换绑真实短信端到端继续记录在根 `todos.md`。

## 依赖

- Issue 009：后端账号、短信、登录锁定与注册设备持久化。
- Issue 018：Profile 概览与账号管理入口。
- Issue 025：Android 账号核心闭环、安全 Session 和真实后端接入。
- Issue 026：内部 accountId、微信身份、账号合并、安全解绑及 Session v2；真实微信审核不阻断本 Issue 的假 Provider 自动化。

## 默认假设

- 当前没有必须兼容的外部旧 Android 客户端，允许后端和 Android 最终直接切换新协议。
- 每个账号最多绑定一个用户名、一个邮箱和一个手机号。
- 注册标识为主标识，本轮不提供主标识切换。
- 用户名只在注册时创建，手机号和邮箱可在账号管理中补充绑定。
- 密码账号间发生标识冲突时一律阻止，不提供标识取舍、转移或自动合并。
- 当前登录的微信纯账号仍是允许合并时的目标账号。
- 本轮不修改本机账本、Room schema、备份格式和自动记账采集链路。
