# 通用交易识别规则

本文记录当前 Android 客户端基于 `assists-base` 的通用自动记账规则。识别不按微信、支付宝或其他平台分流，也不使用截图或 OCR。

## 1. 采集边界

- 用户必须先开启自动记账；开关默认关闭，与无障碍授权和服务连接状态分别保存与展示。
- `AssistsService` 只接收窗口状态、窗口内容和窗口集合变化事件，等待 500 ms 后复核活动窗口包名与窗口 ID。
- 排除 BKS 自身。只读取当前活动窗口中可见、非密码、非可编辑节点的文字，最多 512 个节点、24 层和 16 KiB。
- 不点击、不滚动、不启动其他应用、不截图、不执行 OCR。
- 相同包名和页面指纹在 30 秒内不重复处理；跨进程和历史记录去重继续由现有 Pipeline 完成。

## 2. 同时满足的准入条件

页面必须同时具备：

1. 明确完成状态，例如`支付成功`、`付款成功`、`收款成功`、`退款到账`或`交易完成`。
2. 唯一交易金额。金额必须带`¥`、`￥`或`元`；同一金额重复展示不算冲突，不同金额同时出现则拒绝。
3. 唯一资金方向。收入/收款/退款属于流入，支付/付款/扣款/支出属于流出；两种方向同时出现则拒绝。
4. 至少一个交易上下文字段，例如商户、收款方、付款方、交易对象、商品、订单、交易单号、支付方式或交易时间。

无法提取商户时使用`其他应用支付`，但仍必须满足其他交易上下文要求。

## 3. 硬性拒绝

以下任一情况出现即拒绝，不因同时存在正面词而放行：

- 付款发起或密码页面：`确认付款`、`立即支付`、`继续支付`、`输入密码`、`支付密码`。
- 未完成状态：`待支付`、`待付款`、`处理中`。
- 失败或取消：`支付失败`、`付款失败`、`交易失败`、`已取消`、`交易取消`。
- 缺少金额、完成状态、明确方向或交易上下文。
- 出现多个不同金额或流入/流出方向冲突。

未命中内容、原始页面文字和无关页面信息只在内存中短暂存在，不写入数据库、诊断日志、备份或网络请求。

## 4. 候选输出

每个新候选固定使用：

- `PaymentSource.OTHER`，界面显示`其他应用`。
- `CaptureReason.ACCESSIBILITY_AUTO`，界面显示`支付结果自动捕获`。
- `ConfidenceState.NEEDS_REVIEW`。
- 资金账户为空，不自动创建或选择账户。
- 原始证据文本为空，仅保存金额、方向、商户/回退标题、时间和规范化解析字段。

候选经现有本地分类、去重和 `ReviewQueuePersistence` 进入待确认队列。用户确认前不会写入账本。

## 5. 实现依据

- 无障碍入口：[`BillSyncAccessibilityService.kt`](../apps/android/src/main/java/com/bks/feature/billsync/BillSyncAccessibilityService.kt)
- 通用解析：[`BillPageParser.kt`](../apps/android/src/main/java/com/bks/feature/billsync/BillPageParser.kt)
- 去重与候选生成：[`BillSyncPipeline.kt`](../apps/android/src/main/java/com/bks/feature/billsync/BillSyncPipeline.kt)
- 待确认写入：[`BillSyncCaptureProcessor.kt`](../apps/android/src/main/java/com/bks/feature/billsync/BillSyncCaptureProcessor.kt)

历史 `PaymentSource.WECHAT`、`PaymentSource.ALIPAY`、通知捕获和账单同步枚举值仅用于读取旧记录，不代表当前识别入口。
