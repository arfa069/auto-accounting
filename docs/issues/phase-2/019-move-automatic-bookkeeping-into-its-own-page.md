# 将自动记账迁移为独立页面

## 目标

让用户在“自动记账”中判断持续自动捕获是否可用、修复具体授权或服务问题，并将手动账单同步保留为独立的补录操作。

## 范围

- 从“我的”总览进入自动记账二级页，按状态、权限与后台设置、持续监控健康摘要、手动账单同步的顺序展示。
- 总览状态仅使用“已就绪”“需要处理（指出具体原因）”“已关闭”。
- 通知监听和无障碍状态参与自动记账可用性判断；记账结果通知只影响回执展示，不阻断捕获；账单同步不是常驻权限。
- 保留通知监听、无障碍与账单同步的真实系统入口；结果通知改为开启自动记账时按需申请，并增加后台运行、自启动、电池优化和省电模式的非阻断设置引导。

## 非目标

- 不改变支付结果采集、去重、待确认入队或无障碍页面白名单。
- 不扩大到聊天、普通消息、支付发起或转账发送页面。

## 目标文件或模块

- `apps/android/src/main/java/com/autoaccounting/MainActivity.kt`
- `apps/android/src/main/java/com/autoaccounting/feature/capture`
- `apps/android/src/main/java/com/autoaccounting/feature/billsync`
- `apps/android/src/main/java/com/autoaccounting/feature/monitoring`
- `apps/android/src/test/java/com/autoaccounting/feature`

## 验收标准

- [x] 自动记账页只包含本 Issue 定义的设置和操作，不再与分类、备份或合规内容混排。
- [x] 已开启但缺少必要权限或服务不健康时，总览显示“需要处理”和具体原因；关闭自动记账时显示“已关闭”。
- [x] 拒绝记账结果通知不会阻断采集或使总览显示“需要处理”。
- [x] 无障碍说明明确其读取范围，不放宽现有隐私边界。

## 验收测试

- [x] `./gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.capture.*"`
- [x] `./gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.monitoring.*"`
- [x] `./gradlew.bat --no-daemon :apps:android:testDebugUnitTest`

## 手工验证

1. 在测试设备上分别授予和撤销通知监听、无障碍和结果通知，确认页面状态与系统状态一致。
2. 开启后再关闭自动记账，确认关闭时不会新增待确认记录。
3. 从页面发起手动账单同步，确认其作为补录操作而非权限项出现。

## 回滚或安全说明

- 权限刷新后须重新检查无障碍服务是否仍启用和绑定；不得通过修改系统设置绕过用户授权。
- 真机验证不检查隐私敏感的微信钱包历史页。

## 验证记录

- 2026-07-12：新增独立“自动记账”页和状态摘要；通知监听、无障碍、结果通知、持续监控健康状态与手动账单同步入口按页面顺序呈现。健康状态同时检查无障碍授权和服务连接心跳，连接心跳超时会显示为需要处理；关闭自动记账会同时停止通知监听处理；手动同步继续复用既有会话、无障碍服务、去重和待确认入队链路。
- 2026-07-12：`./gradlew.bat --no-daemon :apps:android:testDebugUnitTest --tests "com.autoaccounting.feature.capture.*" --tests "com.autoaccounting.feature.monitoring.*" --tests "com.autoaccounting.feature.billsync.BillSyncSessionTest" --tests "com.autoaccounting.feature.categorization.CategorizationRulesScreenTest" --tests "com.autoaccounting.MainActivityTest"` 通过。
- 2026-07-12：`./gradlew.bat --no-daemon :apps:android:testDebugUnitTest :apps:android:assembleDebug` 通过。
- `2026-07-13`：在 Xiaomi `24117RK2CC`（Android 16，`192.168.1.6:40793`）的已签名 Release 包完成非破坏性检查：二级页依次显示自动记账状态、通知监听、自动记账无障碍权限；向下滚动确认持续监控和健康状态、手动账单同步，系统返回回到“我的”总览。未变更权限、开关或服务状态，未发起手动账单同步，也未进入微信钱包历史等敏感页面。
- `2026-07-13`：自动记账页改为紧凑权限清单，新增后台运行、自启动、电池优化和省电模式引导；结果通知不再单列，Android 13 及以上在开启自动记账时按需申请，拒绝仍不阻断采集。新增入口尚待真机复验。

## 依赖

- Issue 18：重构“我的”总览与账户管理。
