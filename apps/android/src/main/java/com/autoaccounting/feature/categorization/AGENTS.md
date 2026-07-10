# Android 分类功能指南

## 职责

本目录维护本地分类规则、规则管理界面和可选的云端 AI 分类客户端。

## 约束

- 本地规则应保持确定性；优先级、启用状态和匹配字段变化必须有边界测试。
- 首次安装提供的初始规则与用户规则使用同一张表和同一编辑界面；初始规则可以编辑、停用或删除。
- AI 分类默认关闭，只在用户明确同意、已登录且配置允许时调用项目 Backend。客户端不得直接调用外部 AI Provider。
- 默认发送最小上下文；只有增强上下文另行授权后才能加入原始证据。不得把 token、完整证据或敏感 payload 写入日志。
- AI 和本地规则只提供建议，不能绕过待确认队列或自动确认入账。
- JSON 字段变化必须同步检查 `shared/api`、Backend AI 路由和 Android 契约测试。
- `CategorizationRulesScreen` 已承担较多组装职责；新增非分类业务应放回对应 feature，而不是继续集中到该 Screen。

## 验证

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.categorization.*"
```
