# 当前未完成事项

最后更新：2026-07-28

本文件只汇总仍需外部服务、真实设备或生产环境才能完成的事项。详细范围和历史验证证据仍以对应 Phase 2 Issue 为准。

## 当前能力边界

- 用户名注册和密码登录可用，已完成签名 Release 真机验收。
- 邮箱验证码注册可用，当前 SMTP 已配置并完成真实邮件发送验收。
- 已有手机号账号可使用“手机号 + 密码”登录。
- 已接入真实短信 Provider 架构，后端支持阿里云个人开发者“短信认证”（号码认证服务 PNVS / Dypnsapi 2017-05-25）与 Webhook 双方案；未配置私有凭据前维持安全阻断。
- 微信登录/注册代码和假 Provider 自动化已完成；当前 Android AppID、后端 AppID/AppSecret 均未配置，入口隐藏，真实微信能力不可用。

## P0：启用真实短信能力

- [ ] 选择并接入真实短信服务商，通过后端环境变量配置 Provider 及相应凭据（不得写入代码、文档或版本库）：
  - 阿里云短信认证服务（推荐个人开发者）：配置 `BKS_SMS_PROVIDER=aliyun_pnvs`（或 `aliyun`），并配置 `BKS_SMS_ALIYUN_ACCESS_KEY_ID`、`BKS_SMS_ALIYUN_ACCESS_KEY_SECRET`、`BKS_SMS_SIGN_NAME`（预置签名名称）和 `BKS_SMS_TEMPLATE_CODE`（预置模板 Code）。
  - Webhook 短信网关：配置 `BKS_SMS_PROVIDER=webhook`、`BKS_SMS_WEBHOOK_URL` 和 `BKS_SMS_API_KEY`。
- [ ] 使用签名 Release 真机验证手机号注册、找回密码、绑定手机号及其他短信二次验证流程。
- [ ] 验证验证码 5 分钟有效、最多错误 3 次、用途隔离、不可重放、发送限流及 Provider 故障提示。
- [ ] 检查日志、诊断导出、截图和服务端错误均不泄露手机号原文、验证码或短信凭据。

完成条件：真实手机能够收到验证码，所有短信认证流程和失败路径通过，并在 [Issue 027](docs/issues/phase-2/027-unify-account-identifiers-and-verification.md) 记录证据。

## P0：启用真实微信能力

- [ ] 申请并通过微信开放平台 Android 移动应用及微信登录能力审核。
- [ ] 登记 Release 包名 `com.bks` 和实际发布证书签名。
- [ ] Android 本地构建配置写入可公开 AppID，后端私有环境写入 AppID/AppSecret；凭据不得进入 APK、日志或版本库。
- [ ] 使用签名 Release 真机验证微信新账号注册、退出后登录、绑定、合并、解绑和重新绑定。
- [ ] 验证微信未安装、用户取消、拒绝授权、正常授权、冷启动回跳及 `onNewIntent` 回跳。
- [ ] 验证昵称与 HTTPS 头像刷新、头像失败占位，以及账号操作前后本机账本不变。

完成条件：真实微信 Provider 和 Xiaomi 真机端到端通过，并在 [Issue 026](docs/issues/phase-2/026-add-wechat-login-and-registration.md) 记录证据。

## P1：完成账户同步真实环境验收

- [ ] 使用两台真实设备完成新增、编辑、双端离线冲突、删除/恢复、签出恢复和账号切换。
- [ ] 分别验证冲突选择“保留云端”和“保留本机”，确认两台设备与云端最终一致，待上传和冲突均归零。
- [ ] 在生产式 HTTPS 后端使用签名 Release 完成同步；确认 Release 不依赖局域网 HTTP 测试开关。
- [ ] 在真实部署数据库变更前制作可恢复备份，并验证迁移、重复启动、服务重启和恢复路径。

完成条件：[Issue 028](docs/issues/phase-2/028-add-account-ledger-sync.md) 剩余两项验收标准有真实证据后勾选完成。

## 更新规则

- 只有实际完成并留有命令、设备或服务端证据后才能勾选。
- 完成事项时同步更新对应 Issue 的验证记录；本文件只保留尚未完成的汇总。
- 新增生产凭据、修改系统敏感权限、清除设备数据或执行数据库恢复前，必须单独确认影响和回滚方案。
