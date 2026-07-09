# Backend 云配置指南

## 职责

本目录维护账号级 AI 同意状态、增强上下文设置和 Feature Flag 的读取、合并、持久化与删除。

## 约束

- 所有读写路由先验证 token；待注销账号不得继续写入云配置。
- 部分更新只覆盖请求中明确提供的字段，缺失字段必须保留现值，不能误清空同意状态或 Feature Flag。
- Feature Flag 值保持布尔类型和确定性序列化；无效 JSON 返回稳定的客户端错误。
- 内存 Store 与 JDBC Store 保持同一读写和删除语义。账号最终删除必须清理配置，数据库外键行为与 Service 删除结果一致。
- 环境变量只用于连接配置；凭据、token 和完整配置内容不得进入日志或响应。
- 字段变化同步更新 Shared API 和 Android 客户端。

## 验证

```powershell
.\gradlew.bat :services:backend:test --tests "com.autoaccounting.backend.config.*"
```

覆盖默认值、完整写入、部分合并、未认证、待注销阻断、无效 Flag、重启持久化和级联删除。
