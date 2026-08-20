# ADR 0058: 允许 Android 构建使用显式 HTTP 后端

内部验收需要让真实测试机直接访问同一受控局域网中的开发后端。为该环境部署公开可信的 HTTPS 域名和证书会增加与功能验收无关的运维前置条件，而测试机、网络和测试账号均由开发者控制。

Android Debug 与 Release 构建均接受通过 `BKS_BACKEND_URL` 显式配置的 HTTP 或 HTTPS URL，并允许明文网络流量。Debug 未配置时仍默认使用 `http://10.0.2.2:8080`；Release 不提供默认地址，未配置时继续保持账号网络不可用。

HTTP 仅用于受控测试网络和专用测试账号。账号密码、验证码与 Session Token 在 HTTP 链路上不具备传输加密，因此不得把该配置用于不受信任网络或真实用户账号。面向真实用户部署时应显式配置 HTTPS 后端。

该决定仅替代 [ADR 0056](./0056-secure-and-persist-real-account-sessions.md) 中“Release 仅接受 HTTPS URL”的传输边界；Keystore Session 加密、后端 Token 哈希及受保护路由的身份解析规则保持不变。未采用单独的本地 Release 变体，因为当前验收要求直接覆盖测试机上的 Release 包，且显式构建配置已经提供清晰的启用边界。
