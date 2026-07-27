package com.autoaccounting.feature.billsync

import com.autoaccounting.feature.monitoring.hasWechatMerchantPaymentSuccessSignature
import com.autoaccounting.feature.monitoring.hasWechatReceivedRedPacketSuccessSignature
import com.autoaccounting.feature.monitoring.hasWechatSentRedPacketSuccessSignature
import com.autoaccounting.feature.monitoring.hasWechatTransferCompletionContext
import com.autoaccounting.feature.review.ReviewQueueEntry

internal const val WECHAT_MERCHANT_PAYMENT_ACTIVITY_CLASS =
    "com.tencent.mm.plugin.brandservice.ui.flutter.BizFlutterTLFlutterViewActivity"
internal const val WECHAT_TRANSFER_RESULT_ACTIVITY_CLASS =
    "com.tencent.mm.framework.app.UIPageFragmentActivity"
internal const val WECHAT_RED_PACKET_DETAIL_ACTIVITY_CLASS =
    "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewDetailUI"
internal const val WECHAT_RECENT_NOTIFICATION_WINDOW_MILLIS = 5 * 60_000L

internal data class WechatWindowEvidence(
    val activityClassName: String?,
    val isApplicationWindow: Boolean
)

internal data class WechatOcrCaptureDecision(
    val shouldCapture: Boolean,
    val verification: AutomaticCaptureVerification = AutomaticCaptureVerification.Standard,
    val rejectionReason: WechatOcrRejectionReason? = null
)

internal enum class WechatOcrRejectionReason {
    NoSupportedPaymentSignature
}

internal data class WechatOcrPaymentFingerprint(
    val merchantTitle: String,
    val amountMinor: Long,
    val transactionKindLabel: String,
    val explicitTransactionTimeText: String?,
    val isRedPacket: Boolean
)

internal fun decideWechatOcrCapture(
    pageText: String,
    windowEvidence: WechatWindowEvidence
): WechatOcrCaptureDecision {
    if (
        hasWechatSentRedPacketSuccessSignature(pageText) ||
        hasWechatReceivedRedPacketSuccessSignature(pageText)
    ) {
        return WechatOcrCaptureDecision(
            shouldCapture = true,
            verification = if (isTrustedWechatRedPacketWindow(windowEvidence)) {
                AutomaticCaptureVerification.Standard
            } else {
                AutomaticCaptureVerification.RequireRecentNotification
            }
        )
    }
    if (hasWechatTransferCompletionContext(pageText)) {
        return WechatOcrCaptureDecision(
            shouldCapture = true,
            verification = if (isTrustedWechatTransferResultWindow(windowEvidence)) {
                AutomaticCaptureVerification.Standard
            } else {
                AutomaticCaptureVerification.RequireRecentNotification
            }
        )
    }
    if (!hasWechatMerchantPaymentSuccessSignature(pageText)) {
        return WechatOcrCaptureDecision(
            shouldCapture = false,
            rejectionReason = WechatOcrRejectionReason.NoSupportedPaymentSignature
        )
    }

    val hasTrustedWindow = isTrustedWechatMerchantPaymentWindow(windowEvidence)
    return WechatOcrCaptureDecision(
        shouldCapture = true,
        verification = if (hasTrustedWindow) {
            AutomaticCaptureVerification.Standard
        } else {
            AutomaticCaptureVerification.RequireRecentNotification
        }
    )
}

internal fun isTrustedWechatMerchantPaymentWindow(
    windowEvidence: WechatWindowEvidence
): Boolean = windowEvidence.isApplicationWindow &&
    windowEvidence.activityClassName == WECHAT_MERCHANT_PAYMENT_ACTIVITY_CLASS

internal fun isVerifiedWechatOcrResultActivity(activityClassName: String?): Boolean =
    activityClassName == WECHAT_MERCHANT_PAYMENT_ACTIVITY_CLASS ||
        activityClassName == WECHAT_TRANSFER_RESULT_ACTIVITY_CLASS ||
        activityClassName == WECHAT_RED_PACKET_DETAIL_ACTIVITY_CLASS

internal fun prepareManualWechatOcrResultText(pageText: String): String? {
    val lines = pageText.lineSequence()
        .map(String::trim)
        .map(String::normalizeManualWechatOcrText)
        .filter(String::isNotBlank)
        .toList()
    val normalizedText = lines.joinToString("\n")
    if (normalizedText.isBlank()) return null

    val compactText = normalizedText.filterNot(Char::isWhitespace)
    if (MANUAL_OCR_DENY_KEYWORDS.any(compactText::contains)) return null
    if (
        MANUAL_OCR_ACCEPTED_SIGNATURES.none { signature ->
            signature.all(compactText::contains)
        }
    ) return null
    if (!hasUnambiguousTransactionAmount(normalizedText)) return null
    return lines
        .filterNot { line ->
            line.filterNot(Char::isWhitespace).contains(MANUAL_OCR_BILL_SERVICE_KEYWORD)
        }
        .joinToString("\n")
}

internal fun hasCurrentStatusPaymentSuccessPair(pageText: String): Boolean =
    hasCurrentStatusPaymentSuccessPair(
        pageText.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    )

private fun hasCurrentStatusPaymentSuccessPair(lines: List<String>): Boolean {
    val normalizedLines = lines.map { line ->
        line.filterNot(Char::isWhitespace).replace(":", "").replace("：", "")
    }
    return normalizedLines.any { it == "当前状态支付成功" } ||
        normalizedLines.windowed(2).any { pair ->
            pair[0] == "当前状态" && pair[1] == "支付成功"
        }
}

private fun isTrustedWechatTransferResultWindow(
    windowEvidence: WechatWindowEvidence
): Boolean = windowEvidence.isApplicationWindow &&
    windowEvidence.activityClassName == WECHAT_TRANSFER_RESULT_ACTIVITY_CLASS

private fun isTrustedWechatRedPacketWindow(
    windowEvidence: WechatWindowEvidence
): Boolean = windowEvidence.isApplicationWindow &&
    windowEvidence.activityClassName == WECHAT_RED_PACKET_DETAIL_ACTIVITY_CLASS

internal fun wechatOcrPaymentFingerprint(pageText: String): WechatOcrPaymentFingerprint? =
    BillPageParser().parse(
        source = BillSyncSource.WeChat,
        pageText = pageText,
        fallbackTransactionTimeText = "1970-01-01 00:00"
    ).firstOrNull { entry -> !entry.merchantTitleFromFallback }
        ?.let { entry ->
            WechatOcrPaymentFingerprint(
                merchantTitle = entry.merchantTitle,
                amountMinor = entry.amountMinor,
                transactionKindLabel = entry.transactionKindLabel,
                explicitTransactionTimeText = entry.transactionTimeText
                    .takeUnless { entry.transactionTimeFromFallback },
                isRedPacket = hasWechatSentRedPacketSuccessSignature(pageText) ||
                    hasWechatReceivedRedPacketSuccessSignature(pageText)
            )
        }

internal val ReviewQueueEntry.hasNotificationCaptureEvidence: Boolean
    get() = captureReasonLabel == "通知捕获" ||
        parsedFields.contains("证据来源=通知捕获")

internal val ReviewQueueEntry.hasAutomaticOcrCaptureEvidence: Boolean
    get() = captureReasonLabel == "支付结果自动捕获" ||
        parsedFields.contains("证据来源=支付结果自动捕获")

internal fun ReviewQueueEntry.matchesUnlinkedRecentWechatNotification(
    fingerprint: WechatOcrPaymentFingerprint,
    capturedAtEpochMillis: Long
): Boolean =
    sourceLabel == BillSyncSource.WeChat.label &&
        hasNotificationCaptureEvidence &&
        !hasAutomaticOcrCaptureEvidence &&
        title.trim().equals(fingerprint.merchantTitle.trim(), ignoreCase = true) &&
        amountMinor == fingerprint.amountMinor &&
        kindLabel == fingerprint.transactionKindLabel &&
        wasCapturedWithinWechatNotificationWindow(capturedAtEpochMillis)

internal fun ReviewQueueEntry.wasCapturedWithinWechatNotificationWindow(
    capturedAtEpochMillis: Long
): Boolean {
    val notificationAgeMillis = capturedAtEpochMillis - this.capturedAtEpochMillis
    return this.capturedAtEpochMillis > 0 &&
        notificationAgeMillis in 0..WECHAT_RECENT_NOTIFICATION_WINDOW_MILLIS
}

private val MANUAL_OCR_DENY_KEYWORDS = listOf(
    "确认支付",
    "立即支付",
    "收银台",
    "支付密码",
    "待支付",
    "处理中",
    "支付失败",
    "已取消"
)

private val MANUAL_OCR_ACCEPTED_SIGNATURES = listOf(
    listOf("当前状态", "支付成功"),
    listOf("当前状态", "对方已收"),
    listOf("退款状态", "已退款")
)

private fun String.normalizeManualWechatOcrText(): String =
    replace("对方己收", "对方已收")
        .replace("对方已收线", "对方已收钱")

private const val MANUAL_OCR_BILL_SERVICE_KEYWORD = "账单服务"
