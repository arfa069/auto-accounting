# Android 账单同步指南

## 职责

本目录负责基于 Assists 的通用自动记账窗口读取、交易候选解析、去重及待确认队列交接。

## 约束

- 不能把 Manifest 声明当作已授权。持久化开关只表示用户意图；权限或服务断开只改变运行状态，不反写关闭。
- 无障碍 Service 只被动读取第三方应用当前活动窗口的可见、非密码、非可编辑文字；排除 BKS 自身，不截图、不点击、不滚动、不启动应用。
- 通用识别必须同时具备完成状态、唯一金额、无冲突方向和交易上下文；付款发起、密码、待支付、处理中、失败、取消及冲突页面全部拒绝。
- 原始页面文字只在内存中短暂解析。未命中内容、原始文字和无关页面信息不得写入 Room、诊断日志或上传。
- 新候选固定使用 `PaymentSource.OTHER`、`CaptureReason.ACCESSIBILITY_AUTO`、`ConfidenceState.NEEDS_REVIEW`，不自动选择资金账户；随后复用现有分类、去重和待确认持久化，绝不直接生成账本记录。

## 验证

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.billsync.*"
```

覆盖开关/权限分离、事件过滤、稳定等待、防抖、节点与文本上限、解析拒绝、重复候选、待确认持久化及确认入账。
