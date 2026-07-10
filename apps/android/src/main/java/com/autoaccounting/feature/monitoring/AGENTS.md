# Android 自动记账页面观察指南

## 职责

本目录维护自动记账的开关、无障碍权限健康状态、页面观察、防抖和采集决策。用户明确开启后，支付结果页可在无需手动账单同步的情况下进入采集流水线。

## 约束

- 只有用户明确开启自动记账且无障碍权限健康时才能运行；通知监听是独立采集来源，不是前置条件。
- 用户可随时关闭；无障碍权限失效时立即停止并给出明确阻断原因。
- 只允许微信、支付宝支付结果和支付记录页面；无关 Activity、输入内容、聊天和付款发起页面不得采集。
- 状态 reducer 和采集决策保持纯函数；Service 生命周期由 billsync 层处理。
- 监控候选仍需经过解析、去重和待确认队列，不得直接入账。

## 验证

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.monitoring.ContinuousMonitoringStateTest"
```

覆盖首次禁用、正常启用、随时关闭、权限撤销、包名白名单、支付结果页、防抖及页面过滤。
