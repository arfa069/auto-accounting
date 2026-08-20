# 已完成：支持微信转账与退款账单手动 OCR 补录

## 目标

在“待确认 → 补录账单 → 微信”的用户主动会话中，继续只使用本机瞬时 OCR，并在现有商户支付账单之外安全识别已完成的微信转账和退款账单。所有结果仍须先进入待确认队列，不直接写入账本。

## 范围

- 将微信手动 OCR 的单一关键词 AND 条件改为按账单类型匹配完整完成签名：
  - 商户支付：`当前状态` + `支付成功`。
  - 已完成转账：`当前状态` + `对方已收`。
  - 已完成退款：`退款状态` + `已退款`。
- 任一签名命中后仍必须只有一个无歧义交易金额，并继续优先应用现有付款发起、待支付、处理中、失败和取消拒绝词。
- 让下游解析器接受 `对方已收` 与 `已退款` 作为微信完成状态。
- 已完成的对外转账识别为支出；退款识别为退款，并读取现有页面可用的时间、资金账户和单号字段。
- 识别成功后沿用现有待确认持久化、去重及“补录识别成功”通知。

## 非目标

- 不把单个“成功”“转账”或“退款”作为充分条件。
- 不识别待对方收款、退款处理中、退款申请、失败或取消页面。
- 不放宽微信包名、用户主动会话、Android 版本、当前应用窗口、锁屏或截图留存边界。
- 不读取聊天内容，不自动点击、滚动、打开原订单或发起任何支付、转账、退款操作。
- 不修改支付宝路径、自动 OCR Activity 白名单或通知监听解析规则。

## 目标文件或模块

- `apps/android/src/main/java/com/bks/feature/billsync/WechatOcrCaptureDecision.kt`
  - 用“签名列表中任一组全部命中”替代当前单一 `MANUAL_OCR_REQUIRED_KEYWORDS.all(...)`。
- `apps/android/src/main/java/com/bks/feature/billsync/BillPageParser.kt`
  - 补充完成状态、转账方向及退款字段标签解析。
- `apps/android/src/test/java/com/bks/feature/billsync/PaymentScreenOcrFallbackTest.kt`
- `apps/android/src/test/java/com/bks/feature/billsync/BillPageParserTest.kt`
- `apps/android/src/test/java/com/bks/feature/billsync/BillSyncCaptureProcessorTest.kt`
- 行为落地后同步更新 `feature/billsync/AGENTS.md`、`docs/PRD.md`、`docs/ARCHITECTURE.md`、`docs/UI-DESIGN.md`、`docs/COMPLIANCE.md` 和 `docs/research/auto-bookkeeping-flow.md`。

建议的最小判断形态：

```kotlin
private val MANUAL_OCR_ACCEPTED_SIGNATURES = listOf(
    listOf("当前状态", "支付成功"),
    listOf("当前状态", "对方已收"),
    listOf("退款状态", "已退款")
)

if (
    MANUAL_OCR_ACCEPTED_SIGNATURES.none { signature ->
        signature.all(compactText::contains)
    }
) return null
```

不要把所有词追加到同一个 `listOf` 后继续使用 `all(...)`，否则会错误要求支付、转账和退款状态同时出现在同一页面。

## 验收标准

- [x] `当前状态 + 支付成功 + 唯一金额` 继续创建一条商户支付待确认项。
- [x] `当前状态 + 对方已收 + 唯一金额` 创建一条支出待确认项。
- [x] `退款状态 + 已退款 + 唯一金额` 创建一条退款待确认项。
- [x] 转账和退款均提取正确交易时间；退款可读取 `退款方式` 和 `退款单号` 时不得退回默认值或遗漏。
- [x] 只有状态值而没有对应状态标签、只有金额、金额不唯一、待确认收款、退款处理中、失败或取消页面均不创建记录。
- [x] 同一账单重复进入或与已有通知证据重合时不创建第二条待确认项，也不重复发布成功通知。
- [x] 截图与 OCR 原文不进入待确认、账本、普通日志或仓库；诊断日志边界保持不变。

## 验收测试

```powershell
.\gradlew.bat :apps:android:testDebugUnitTest --tests "com.bks.feature.billsync.PaymentScreenOcrFallbackTest" --tests "com.bks.feature.billsync.BillPageParserTest" --tests "com.bks.feature.billsync.BillSyncCaptureProcessorTest"
.\gradlew.bat :apps:android:testDebugUnitTest
.\gradlew.bat :apps:android:assembleRelease
```

测试数据使用脱敏的真实页面结构，至少覆盖：

- 三种接受签名及每组缺失任一关键词。
- 转账为支出、退款为退款。
- 正负金额符号归一化与金额唯一性。
- `退款状态`、`退款时间`、`退款方式`、`退款单号` 的结构化字段。
- 所有现有拒绝词和重复候选。

## 手工验证

1. 在 USB 真机从“待确认 → 补录账单 → 微信”分别打开一张已完成商户支付、已完成转账和已完成退款账单。
2. 每张账单只应生成一次“补录识别成功”通知；点击后进入对应待确认项。
3. 核对交易类型、金额、时间、资金账户和对方/标题，不在测试记录中复制真实姓名、账号、单号或完整截图。
4. 打开一张仍待收款或退款处理中的页面，确认无通知、无新增待确认项。
5. 再次打开已识别账单，确认不重复创建或通知。

## 回滚或安全说明

若出现误识别，只回退新增签名与对应解析词，不回退 ML Kit Release 保留规则、通知修复或既有商户支付签名。用户可取消当前补录会话立即停止手动 OCR；不得通过扩大到通用“成功”“转账”“退款”关键词来修复漏识别。

## 验证记录

- 2026-07-27：Xiaomi USB 真机只读复核确认，已完成转账账单使用 `当前状态 + 对方已收钱`，当前代码因缺少 `支付成功` 无法通过手动 OCR 前置条件。
- 2026-07-27：同一真机的已完成退款账单使用 `退款状态 + 已退款`；诊断日志显示手动 OCR 已启动，随后以 `ocr_output_unusable` 安全拒绝并最终超时，没有创建待确认项。
- 2026-07-27：实现三组完整签名 OR、转账支出方向及退款状态/时间/方式/单号解析；定向 JVM 回归测试通过（`BUILD SUCCESSFUL`，35 个 Gradle task）。
- 2026-07-27：Android 全量 JVM 单测通过（`BUILD SUCCESSFUL in 5m 17s`）；签名 Release 构建通过（`BUILD SUCCESSFUL in 2m 37s`），Build Tools 37.0.0 验证 APK Signature Scheme v2 为 `true`。
- 2026-07-27：Release APK 已通过显式序列号覆盖安装到 Xiaomi 真机，ADB 返回 `Success`；安装后通知权限为已授予，无障碍服务仍为启用。未启动应用、未清数据、未修改权限。
- 2026-07-27：真机 ML Kit 将转账状态误识别为 `对方己收线`；转账签名缩短为 `当前状态 + 对方已收`，并仅对已确认的 `已/己`、`钱/线` 状态短语混淆做定向归一化。
- 2026-07-27：缩短后的关键词、真机误识别归一化及支付/退款回归测试通过；Android 全量 JVM 单测 493 项全部通过。签名 Release 构建成功，Build Tools 37.0.0 验证 APK Signature Scheme v2 为 `true`，覆盖安装返回 `Success`。
- 2026-07-27：用户完成真机验收，商户支付、已完成转账和已退款页面识别及成功通知均符合预期；Issue 转为“已完成”。

## 依赖

- 依赖已完成的 [Issue 007](./007-user-started-bill-sync-permission-and-service-path.md) 手动补录会话。
- 复用已完成的 [Issue 016](./016-cover-in-app-payment-message-capture-paths.md) OCR、解析、去重和通知边界。
