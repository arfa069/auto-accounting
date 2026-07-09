# Android 连续监控指南

## 职责

本目录维护连续监控的开关、权限健康状态、阻断原因、页面观察和采集决策。连续监控是高级可选能力，不是默认采集模式。

## 约束

- 只有用户主动完成过账单同步、明确开启监控且通知与无障碍权限健康时才能运行。
- 用户可随时关闭；任一权限失效时立即停止并给出明确阻断原因。
- 只允许微信、支付宝等明确支付应用及支付历史页面；无关 Activity、输入内容和聊天页面不得采集。
- 状态 reducer 和采集决策保持纯函数；Service 生命周期由 billsync 层处理。
- 监控候选仍需经过解析、去重和待确认队列，不得直接入账。

## 验证

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.monitoring.ContinuousMonitoringStateTest"
```

覆盖首次禁用、正常启用、随时关闭、权限撤销、包名白名单及页面过滤。
