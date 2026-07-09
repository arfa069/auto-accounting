# Android Feature 指南

## 职责边界

`feature/<name>/` 按业务能力组织状态、纯逻辑、平台适配和 Compose 界面。`Screen` 负责渲染与事件转发；可复用业务规则放入 reducer、parser、pipeline、repository 或 gateway。

## 全链路不变量

- 采集候选必须先进入待确认队列，经用户确认后才能成为账本记录；通知、账单同步、去重或 AI 都不得直接写入已确认账本。
- Compose `remember` 状态不是持久化真相。影响账目、忽略记录、规则、设置或账号的行为必须检查重启恢复。
- Parser、规则匹配、去重和 reducer 应保持确定性并尽量不依赖 Android UI，便于 JVM 测试。
- 跨 feature 调用只使用明确的数据或行为接口，不把其他 feature 的界面组件当作业务 API，也不要继续扩大现有大型 Screen 的职责。
- 涉及权限、敏感交易文本、账号或删除行为时，继续遵循更深层目录的指南。

## 验证

运行被修改 feature 的 `*Test.kt`；跨 feature 状态流变更还需运行：

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest
```
