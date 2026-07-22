# ADR 0056: 安全持久化真实账号 Session

Android 账号流程将使用真实的 Ktor 后端，而非生产环境中的 `FakeAccountRepository`。后端 URL 在构建时注入：Debug 默认使用模拟器宿主并可使用明文 HTTP，而 Release 仅接受显式配置的 HTTPS URL。没有该 URL 的 Release 构建保持本地模式可用，并报告账号服务不可用。

登录、注册、找回密码、短信、Session 校验、当前 Session 退出登录及账号注销操作使用共享的稳定 JSON 契约。受保护的路由仅从 `Authorization: Bearer` 解析账号身份；表单手机号和 Token 绝不用于选取受保护账号。后端将短信验证码存储为带密钥的 HMAC 值，将 Session 存储为 SHA-256 Token 哈希。安全迁移会清理旧验证码和 Session，而不是保留明文兼容路径。

Android 在 Android Keystore AES-GCM 下将手机号和 Bearer Token 加密存储在一起，与 Room、账本备份、诊断和 UI 状态恢复相互隔离。随机持久化的安装 UUID 用于限流和设备注册；不读取硬件标识符。

重启后，恢复的 Session 在后台进行校验。网络或配置故障使用户保持在离线未校验模式，且本地账本可用；仅显式的无效 Session 响应会清除加密 Session 并返回持久化本地模式。云端写入和账号注销操作保持暂停，直到校验成功。仅在加密持久化成功后登录状态才变为活动状态，且仅在后端撤销当前 Session 后退出登录才清除本地密文。

服务端是账号注销挂起状态和截止时间的唯一真实源。最终注销首先执行幂等的 AI 日志和云端配置清理，随后删除账号、设备及 Session。清理失败将保留挂起账号以便稍后重试。本地账本删除保持为单独的操作，绝对不清除或重新分配账号 Session。
