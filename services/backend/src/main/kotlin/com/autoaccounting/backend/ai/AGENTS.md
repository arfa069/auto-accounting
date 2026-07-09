# Backend AI 服务指南

## 职责

本目录负责 AI 分类代理、Provider 边界和内测日志，不负责保存或同步完整账本。

## 隐私与行为约束

- 只接受分类所需的最小 payload；增强上下文必须来自明确授权。Provider 凭据只从环境配置读取。
- 路由先验证 token 和账号状态。待注销账号不得新增 AI 日志或调用 Provider。
- 持久化日志保持最小化；增强原始证据不得落库，也不得进入普通日志或错误响应。
- Provider 缺失或失败时返回安全、稳定的失败结果，不泄露配置、上游响应或堆栈。
- 账号最终删除必须清理该账号的全部 AI 日志；保留策略变化要同步合规文档。
- 请求和响应字段变化同步更新 Shared API 与 Android 客户端测试。

## 验证

```powershell
.\gradlew.bat :services:backend:test --tests "com.autoaccounting.backend.ai.*"
```

重点覆盖最小 payload、增强上下文不落库、待注销写入阻断、Provider 缺失和日志删除。
