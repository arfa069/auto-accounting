package com.autoaccounting.feature.billsync

import android.os.Build
import com.autoaccounting.feature.monitoring.hasAlipayPaymentResultPageSignature

internal data class AlipayOcrCaptureDecision(
    val shouldCapture: Boolean,
    val rejectionReason: AlipayOcrRejectionReason? = null
)

internal enum class AlipayOcrRejectionReason {
    BlankText,
    PaymentInitiation,
    PaymentFailedOrPending,
    PaymentResultContextMissing,
    TransactionAmountMissingOrAmbiguous,
    MerchantMissing,
    FundingAccountMissing
}

internal data class AlipayOcrFallbackRequest(
    val packageName: String,
    val pageText: String,
    val sdkInt: Int,
    val isApplicationWindow: Boolean,
    val isWindowStateChanged: Boolean,
    val hasRecentPaymentFlow: Boolean,
    val accessibilityNeedsOcr: Boolean
)

internal fun shouldAttemptAlipayOcrFallback(request: AlipayOcrFallbackRequest): Boolean {
    val compactText = request.pageText.filterNot(Char::isWhitespace)
    return when {
        request.packageName != BillSyncSource.Alipay.packageName -> false
        request.sdkInt < Build.VERSION_CODES.R -> false
        !request.isApplicationWindow -> false
        !request.accessibilityNeedsOcr -> false
        ALIPAY_NON_RESULT_SURFACE_KEYWORDS.any(compactText::contains) -> false
        compactText.isBlank() -> request.hasRecentPaymentFlow
        ALIPAY_OCR_PAYMENT_INITIATION_KEYWORDS.any(compactText::contains) -> false
        ALIPAY_OCR_PAYMENT_FAILURE_KEYWORDS.any(compactText::contains) -> false
        else -> {
            val hasCompletionCue = ALIPAY_OCR_PAYMENT_COMPLETION_KEYWORDS.any(
                compactText::contains
            )
            val hasResultContext = hasAlipayPaymentResultPageSignature(request.pageText)
            hasCompletionCue && (hasResultContext || request.hasRecentPaymentFlow)
        }
    }
}

internal fun decideAlipayOcrCapture(
    pageText: String,
    allowRecentPaymentContext: Boolean = false
): AlipayOcrCaptureDecision {
    val compactText = pageText.filterNot(Char::isWhitespace)
    val rejectionReason = when {
        compactText.isBlank() -> AlipayOcrRejectionReason.BlankText
        ALIPAY_OCR_PAYMENT_INITIATION_KEYWORDS.any(compactText::contains) ->
            AlipayOcrRejectionReason.PaymentInitiation
        ALIPAY_OCR_PAYMENT_FAILURE_KEYWORDS.any(compactText::contains) ->
            AlipayOcrRejectionReason.PaymentFailedOrPending
        ALIPAY_NON_RESULT_SURFACE_KEYWORDS.any(compactText::contains) ->
            AlipayOcrRejectionReason.PaymentResultContextMissing
        !hasAlipayPaymentResultPageSignature(pageText) && !allowRecentPaymentContext ->
            AlipayOcrRejectionReason.PaymentResultContextMissing
        !hasUnambiguousTransactionAmount(pageText) ->
            AlipayOcrRejectionReason.TransactionAmountMissingOrAmbiguous
        else -> {
            val parserPageText = pageText.withTrustedAlipayPaymentContext(allowRecentPaymentContext)
            val parsedEntry = BillPageParser().parse(
                source = BillSyncSource.Alipay,
                pageText = parserPageText,
                fallbackTransactionTimeText = ALIPAY_OCR_FALLBACK_TRANSACTION_TIME
            ).singleOrNull()
            when {
                parsedEntry == null || parsedEntry.merchantTitleFromFallback ->
                    AlipayOcrRejectionReason.MerchantMissing
                parsedEntry.fundingAccountFromFallback && !allowRecentPaymentContext ->
                    AlipayOcrRejectionReason.FundingAccountMissing
                else -> null
            }
        }
    }
    return AlipayOcrCaptureDecision(
        shouldCapture = rejectionReason == null,
        rejectionReason = rejectionReason
    )
}

internal fun isAlipayPaymentInitiationPage(pageText: String): Boolean {
    val compactText = pageText.filterNot(Char::isWhitespace)
    return ALIPAY_OCR_PAYMENT_INITIATION_KEYWORDS.any(compactText::contains)
}

internal const val ALIPAY_OCR_FALLBACK_TRANSACTION_TIME = "1970-01-01 00:00"

private fun String.withTrustedAlipayPaymentContext(allowRecentPaymentContext: Boolean): String =
    if (
        allowRecentPaymentContext &&
        !hasAlipayPaymentResultPageSignature(this)
    ) {
        "$this\n支付信息"
    } else {
        this
    }

private val ALIPAY_OCR_PAYMENT_COMPLETION_KEYWORDS = listOf(
    "支付成功",
    "完成支付",
    "支付完成",
    "付款成功",
    "交易成功",
    "已支付",
    "已付款",
    "payment successful",
    "payment complete"
)

private val ALIPAY_OCR_PAYMENT_INITIATION_KEYWORDS = listOf(
    "收银台",
    "立即付款",
    "确认付款",
    "确认支付",
    "支付密码",
    "输入密码",
    "添加转账说明",
    "pay now",
    "confirm payment",
    "cashier"
)

private val ALIPAY_OCR_PAYMENT_FAILURE_KEYWORDS = listOf(
    "支付失败",
    "付款失败",
    "交易关闭",
    "支付处理中",
    "处理中",
    "待支付",
    "已取消",
    "交易取消"
)

private val ALIPAY_NON_RESULT_SURFACE_KEYWORDS = listOf(
    "支付宝首页",
    "最近消息",
    "消息盒子"
)
