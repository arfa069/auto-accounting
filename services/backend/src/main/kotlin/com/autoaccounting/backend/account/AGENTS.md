# Backend 账号服务指南

## 职责

本目录负责账号、密码凭据、短信验证码、登录锁定、Session、注册设备和账号注销持久化。

## 安全与状态约束

- Routes 只解析请求和映射稳定响应；密码策略、限流、锁定、token 校验和注销状态由 Service 决定。
- 只保存带随机盐的密码哈希，不保存或记录明文密码、验证码和 token。比较逻辑不得降级为普通字符串或弱哈希。
- 短信发送次数、错误尝试、登录锁定、Session 和设备状态必须跨进程重启保持一致。
- 注销申请进入七天冷静期；待注销账号暂停云端写入，取消后恢复，最终删除需联动 AI 日志和云配置。
- Store 接口、内存替身与 JDBC 实现必须保持同一语义；迁移版本不可复用。

## 验证

```powershell
.\gradlew.bat :services:backend:test --tests "com.autoaccounting.backend.account.*"
```

修改最终删除流程时同时运行 `AccountDeletionJob`、AI 和 Config 相关测试。
