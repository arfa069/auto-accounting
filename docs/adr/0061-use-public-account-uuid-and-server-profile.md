# ADR 0061: 使用公开账号 UUID 与服务端个人资料

后端数据库自增 `accountId` 继续作为关系表、Session、设备、云配置和账户同步的内部主键，不直接展示给用户。每个账号另持有不可变、全局唯一且非认证秘密的 UUID，作为账户管理页可展示和复制的公开账号 ID。旧账号在数据库迁移中回填 UUID，新账号在创建时生成；Bearer Token 不得再充当或派生用户可见 ID。

昵称与头像从微信身份资料中解耦到账号级 Profile。微信授权成功时仍可提供初始昵称与 HTTPS 头像，用户随后可在账户管理页修改昵称，或从相册、相机选择图片。Android 在 IO 调度器上使用单源图片解码，将头像缩放并压缩为最多 256 KiB 的 JPEG Data URL；后端校验格式、签名和大小后持久化。Session 校验、重启恢复和其他设备登录都返回最新 Profile，资料修改不得清除账号注销冷静期状态。

手机号和邮箱换绑沿用验证码、五分钟单次票据和 Session 轮换边界。只有显式的 `replaceExisting` 请求才能替换同类型标识；普通补绑仍在账号已经拥有该类型时失败。换绑不得改变内部 `accountId`、公开 UUID、主标识、本机 Room 账本或账户同步 Profile。

该决定扩展 [ADR 0057](./0057-use-internal-account-id-for-wechat-identity.md) 与 [ADR 0060](./0060-unify-account-identifiers-and-provider-readiness.md)。未直接展示数据库自增 ID，因为它暴露实现细节且不适合作为跨环境公开标识；未使用 Session Token 的截断值，因为 Token 会轮换、跨设备不同且属于认证秘密；未把头像只保存在 Android 本地，因为资料需要在重新验证和其他设备登录后保持一致。
