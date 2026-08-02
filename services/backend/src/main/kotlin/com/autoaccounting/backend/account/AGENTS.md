# Backend 账号服务指南

## 职责

本目录负责统一账号标识、公开账号 UUID、账号 Profile、账号级密码凭据、短信/邮件验证码、微信身份、登录锁定、Session、注册设备、账号合并和账号注销持久化。

## 安全与状态约束

- Routes 只解析请求和映射稳定响应；密码策略、限流、锁定、token 校验和注销状态由 Service 决定。
- 只保存带随机盐的密码哈希，不保存或记录明文密码、验证码和 token。比较逻辑不得降级为普通字符串或弱哈希。
- 短信/邮件发送次数、错误尝试、登录锁定、Session 和设备状态必须跨进程重启保持一致；验证码必须按标识、渠道、用途和上下文隔离。
- 短信 Provider 统一由 `SmsProvider.fromEnvironment` 选择；仅支持已显式配置的 `webhook` 或 `aliyun_pnvs`（兼容 `aliyun`）路径，缺少必需参数时必须使用不可发送的安全失败实现，不得静默回退为成功假发送。
- 用户名、邮箱、手机号和微信身份必须归属不可变 `accountId`；密码账号之间不得因标识冲突自动合并或转移凭据。
- 数据库自增 `accountId` 仅用于内部关联；用户可见标识使用唯一 `publicId` UUID。Session Token 及其子串不得用作公开账号 ID。
- `account_profiles` 保存账号级昵称和头像，优先于微信资料返回。Profile 更新、标识换绑和注销状态必须分别保持事务语义，不得因资料修改清除冷静期。
- 同类型标识替换只有在显式 `replaceExisting` 票据流程中允许；普通补绑不得静默覆盖既有手机号或邮箱。
- 注销申请进入七天冷静期；待注销账号暂停云端写入，取消后恢复，最终删除需联动 AI 日志、云配置和账户同步数据。
- Store 接口、内存替身与 JDBC 实现必须保持同一语义；迁移版本不可复用。
- `AccountService` 保持 Routes 使用的公共门面，内部按验证码、标识、微信、Session 与生命周期服务委派；JDBC Store 事务通过共享连接上下文保持原子，微信注册/绑定/解绑与账号合并必须复用一个 `Connection`。
- SMTP 与短信 Provider 的连接/签名行为保持在 Provider 内部；`SmtpEmailProviderConfig` 和 `AliyunPnvsSmsConfig` 只承载不可变配置，环境工厂继续负责既有环境变量与安全失败判断。保留旧的 public Provider 构造器作为兼容入口时，不得改变 TLS、超时、凭据回退或错误映射。

## 验证

```powershell
.\gradlew.bat :services:backend:test --tests "com.autoaccounting.backend.account.*"
```

修改最终删除流程时同时运行 `AccountDeletionJob`、AI、Config 和 Sync 相关测试。
