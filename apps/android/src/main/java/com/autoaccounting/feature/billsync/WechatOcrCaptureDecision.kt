package com.autoaccounting.feature.billsync

import com.autoaccounting.feature.monitoring.hasWechatMerchantPaymentSuccessSignature
import com.autoaccounting.feature.monitoring.hasWechatTransferCompletionContext
import com.autoaccounting.feature.review.ReviewQueueEntry

internal const val WECHAT_MERCHANT_PAYMENT_ACTIVITY_CLASS =
    "com.tencent.mm.plugin.brandservice.ui.flutter.BizFlutterTLFlutterViewActivity"
internal const val WECHAT_TRANSFER_RESULT_ACTIVITY_CLASS =
    "com.tencent.mm.framework.app.UIPageFragmentActivity"
internal const val WECHAT_RECENT_NOTIFICATION_WINDOW_MILLIS = 5 * 60_000L

internal data class WechatWindowEvidence(
    val activityClassName: String?,
    val isApplicationWindow: Boolean
)

internal data class WechatOcrCaptureDecision(
    val shouldCapture: Boolean,
    val verification: AutomaticCaptureVerification = AutomaticCaptureVerification.Standard
)

internal data class WechatOcrPaymentFingerprint(
    val merchantTitle: String,
    val amountMinor: Long,
    val transactionKindLabel: String,
    val explicitTransactionTimeText: String?
)

internal fun decideWechatOcrCapture(
    pageText: String,
    windowEvidence: WechatWindowEvidence
): WechatOcrCaptureDecision {
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
        return WechatOcrCaptureDecision(shouldCapture = false)
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
        activityClassName == WECHAT_TRANSFER_RESULT_ACTIVITY_CLASS

private fun isTrustedWechatTransferResultWindow(
    windowEvidence: WechatWindowEvidence
): Boolean = windowEvidence.isApplicationWindow &&
    windowEvidence.activityClassName == WECHAT_TRANSFER_RESULT_ACTIVITY_CLASS

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
                    .takeUnless { entry.transactionTimeFromFallback }
            )
        }

internal val ReviewQueueEntry.hasNotificationCaptureEvidence: Boolean
    get() = captureReasonLabel == "通知捕获" ||
        parsedFields.contains("证据来源=通知捕获")

internal fun ReviewQueueEntry.wasCapturedWithinWechatNotificationWindow(
    capturedAtEpochMillis: Long
): Boolean {
    val notificationAgeMillis = capturedAtEpochMillis - this.capturedAtEpochMillis
    return this.capturedAtEpochMillis > 0 &&
        notificationAgeMillis in 0..WECHAT_RECENT_NOTIFICATION_WINDOW_MILLIS
}
