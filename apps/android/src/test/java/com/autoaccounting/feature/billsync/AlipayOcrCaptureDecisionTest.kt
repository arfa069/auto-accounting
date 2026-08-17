package com.autoaccounting.feature.billsync

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.autoaccounting.feature.monitoring.hasAlipayPaymentResultPageSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlipayOcrCaptureDecisionTest {
    @Test
    fun resultPageWithIncompleteAccessibilityFieldsTriggersOcrFallback() {
        assertTrue(
            shouldAttemptAlipayOcrFallback(
                request("支付成功\n收款方\n付款方式")
            )
        )
    }

    @Test
    fun completeAccessibilityFieldsStillTriggerEvidenceFusionOcr() {
        assertTrue(
            shouldAttemptAlipayOcrFallback(
                request("支付成功\n中国电信\n¥7.98\n交易方式\n花呗\n回首页")
            )
        )
    }

    @Test
    fun blankResultPageAllowsWindowTransitionOrRecentPaymentFlow() {
        val pageText = ""
        assertTrue(
            shouldAttemptAlipayOcrFallback(
                request(pageText, hasRecentPaymentFlow = true)
            )
        )
        assertTrue(
            shouldAttemptAlipayOcrFallback(
                request(pageText, eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            )
        )
        assertTrue(
            shouldAttemptAlipayOcrFallback(
                request(
                    pageText,
                    eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                    windowId = -1
                )
            )
        )
        assertFalse(shouldAttemptAlipayOcrFallback(request(pageText)))
    }

    @Test
    fun homeAndPaymentInitiationNeverTriggerResultOcr() {
        val homeText = "支付宝 首页\n最近消息\n便利店 付款成功 ¥20.00"
        assertFalse(
            shouldAttemptAlipayOcrFallback(
                request(homeText)
            )
        )
        assertFalse(
            shouldAttemptAlipayOcrFallback(
                request(homeText, hasRecentPaymentFlow = true)
            )
        )
        assertFalse(
            shouldAttemptAlipayOcrFallback(
                request("收银台\n立即付款\n¥20.00", hasRecentPaymentFlow = true)
            )
        )
    }

    @Test
    fun successCueAllowsProbeWithoutAccessibilityResultContext() {
        assertTrue(
            shouldAttemptAlipayOcrFallback(
                request("支付成功\n¥20.00", hasRecentPaymentFlow = true)
            )
        )
        assertTrue(
            shouldAttemptAlipayOcrFallback(
                request("支付成功\n¥20.00")
            )
        )
    }

    @Test
    fun notificationTriggerAllowsProbeButDoesNotBypassFinalValidation() {
        assertTrue(
            shouldAttemptAlipayOcrFallback(
                request("", hasNotificationTrigger = true)
            )
        )
        assertEquals(
            AlipayOcrRejectionReason.BlankText,
            decideAlipayOcrCapture("").rejectionReason
        )
    }

    private fun request(
        pageText: String,
        hasRecentPaymentFlow: Boolean = false,
        eventType: Int = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        hasNotificationTrigger: Boolean = false,
        windowId: Int = 1
    ): AlipayOcrFallbackRequest = AlipayOcrFallbackRequest(
        packageName = BillSyncSource.Alipay.packageName,
        pageText = pageText,
        sdkInt = Build.VERSION_CODES.R,
        isApplicationWindow = true,
        hasRecentPaymentFlow = hasRecentPaymentFlow,
        eventType = eventType,
        windowId = windowId,
        hasNotificationTrigger = hasNotificationTrigger
    )

    @Test
    fun ocrResultRequiresMerchantFundingMethodAndOneAmount() {
        val pageText = """
            支付成功
            收款方
            便利店
            金额
            ¥20.00
            交易方式
            支付宝余额
        """.trimIndent()

        val decision = decideAlipayOcrCapture(pageText)

        assertTrue(decision.shouldCapture)
        assertEquals(null, decision.rejectionReason)
        val entry = BillPageParser().parse(
            source = BillSyncSource.Alipay,
            pageText = pageText,
            fallbackTransactionTimeText = "1970-01-01 00:00"
        ).single()
        assertEquals("便利店", entry.merchantTitle)
        assertEquals("支付宝余额", entry.fundingAccountLabel)
        assertEquals(2_000L, entry.amountMinor)
        assertFalse(entry.merchantTitleFromFallback)
        assertFalse(entry.fundingAccountFromFallback)
    }

    @Test
    fun ocrAcceptsAlipayCompletedPaymentWording() {
        val decision = decideAlipayOcrCapture(
            """
                完成支付
                收款方
                便利店
                金额
                ¥20.00
                付款方式
                支付宝余额
            """.trimIndent()
        )

        assertTrue(decision.shouldCapture)
        assertEquals(null, decision.rejectionReason)
    }

    @Test
    fun ocrResultAllowsPartialFieldsButRejectsAmbiguousAmount() {
        val missingMerchant = "支付成功\n收款方\n金额\n¥20.00\n付款方式\n支付宝余额"
        val missingFunding = "支付成功\n收款方\n便利店\n金额\n¥20.00\n付款方式"
        val ambiguousAmount = "支付成功\n收款方\n便利店\n¥20.00\n¥5.00\n付款方式\n支付宝余额"

        assertTrue(decideAlipayOcrCapture(missingMerchant).shouldCapture)
        assertTrue(decideAlipayOcrCapture(missingFunding).shouldCapture)
        assertEquals(
            AlipayOcrRejectionReason.TransactionAmountMissingOrAmbiguous,
            decideAlipayOcrCapture(ambiguousAmount).rejectionReason
        )
    }

    @Test
    fun resultContextAndAmountDoNotReplaceCompletionEvidence() {
        assertEquals(
            AlipayOcrRejectionReason.PaymentCompletionMissing,
            decideAlipayOcrCapture("支付信息\n收款方：便利店\n金额 ¥20.00").rejectionReason
        )
    }

    @Test
    fun recentPaymentContextCanReplaceMissingResultContextLabel() {
        val pageText = "支付成功\n便利店\n¥20.00"

        assertEquals(
            AlipayOcrRejectionReason.PaymentResultContextMissing,
            decideAlipayOcrCapture(pageText).rejectionReason
        )
        assertTrue(
            decideAlipayOcrCapture(
                pageText = pageText,
                allowRecentPaymentContext = true
            ).shouldCapture
        )
        assertTrue(
            hasAlipayPaymentResultPageSignature(
                pageText.withTrustedAlipayPaymentContext(allowRecentPaymentContext = true)
            )
        )
    }

    @Test
    fun recentPaymentContextDoesNotAllowAlipayHomeMessageSurface() {
        val pageText = "支付宝首页\n最近消息\n便利店 付款成功 ¥20.00"

        assertEquals(
            AlipayOcrRejectionReason.PaymentResultContextMissing,
            decideAlipayOcrCapture(
                pageText = pageText,
                allowRecentPaymentContext = true
            ).rejectionReason
        )
    }
}
