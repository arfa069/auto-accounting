# ADR 0057: 使用内部账号 ID 承载手机号与微信身份

账号不再以手机号作为内部主键，而使用不可变的后端 `accountId`。手机号密码凭据与微信身份都是账号的可选登录方式；每个账号最多各有一个手机号和一个微信身份，每个凭据也只能属于一个账号。现有手机号 API 与旧 Bearer Session 保持兼容，本机 Room 账本、待确认队列和备份不关联也不迁移到云端账号。

微信授权由 Android 微信 OpenSDK 发起。Android 仅持有可公开 AppID、短期授权 code 和自有后端的一次性票据；AppSecret、微信 access/refresh token、OpenID、UnionID 及原始 Provider 响应只在后端受控边界内处理。后端用 code 调用微信 OAuth，以 UnionID 优先、否则以 `(appId, openid)` 识别身份，只保存身份标识、昵称和 HTTPS 头像 URL。微信资料接口失败不得阻断已确认身份的认证。

微信授权、手机号新增和账号合并使用五分钟、单次消费的随机票据，数据库只保存票据的 SHA-256 哈希。绑定、合并和解绑必须在数据库事务中重新校验凭据归属与注销状态，并轮换 Session。Android 只有在使用 Keystore AES-GCM 成功保存新 Session 后才切换登录状态；保存失败会清除本机账号 Session 并尝试撤销新 Token。

账号合并只允许互补凭据，始终保留当前账号。当前云配置优先，来源独有功能开关补入；设备按安装 UUID 去重；来源 AI 日志删除；双方旧 Session 全部撤销；来源账号删除。本机账本始终不变。仅有微信一种登录方式时禁止解绑；具备手机号凭据并通过密码或专项短信二次验证后才能解绑微信。

该决定扩展 [ADR 0056](./0056-secure-and-persist-real-account-sessions.md) 的 Session 边界。未选择“继续以手机号为主键”，因为纯微信账号没有手机号且手机号变化会把身份和关联数据耦合；未选择客户端直接换取微信 Token，因为这会把 AppSecret 或 Provider Token 暴露到 APK 和客户端存储；未选择自动合并冲突账号，因为凭据归属和云数据删除需要用户明确确认与事务保护。

状态：后续 [ADR 0060](./0060-unify-account-identifiers-and-provider-readiness.md) 将密码登录标识扩展为用户名、邮箱或手机号，并以真实 Provider 配置和端到端证据判定渠道是否就绪；本 ADR 的内部账号 ID、微信身份和事务安全边界继续有效。
