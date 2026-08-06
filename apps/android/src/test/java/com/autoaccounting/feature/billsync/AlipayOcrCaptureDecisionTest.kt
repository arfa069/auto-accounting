package com.autoaccounting.feature.billsync

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlipayOcrCaptureDecisionTest {
    @Test
    fun resultPageWithIncompleteAccessibilityFieldsTriggersOcrFallback() {
        assertTrue(
            shouldAttemptAlipayOcrFallback(
                request("支付成功\n收款方\n付款方式", isWindowStateChanged = false)
            )
        )
    }

    @Test
    fun blankResultPageRequiresRecentPaymentFlowButNotWindowTransition() {
        val pageText = ""
        assertTrue(
            shouldAttemptAlipayOcrFallback(
                request(pageText, hasRecentPaymentFlow = true)
            )
        )
        assertFalse(
            shouldAttemptAlipayOcrFallback(
                request(pageText)
            )
        )
    }

    @Test
    fun homeRecentMessageAndPaymentInitiationDoNotTriggerOcr() {
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
    fun recentPaymentFlowAllowsSuccessCueWithoutAccessibilityResultContext() {
        assertTrue(
            shouldAttemptAlipayOcrFallback(
                request("支付成功\n¥20.00", hasRecentPaymentFlow = true)
            )
        )
        assertFalse(
            shouldAttemptAlipayOcrFallback(
                request("支付成功\n¥20.00")
            )
        )
    }

    private fun request(
        pageText: String,
        isWindowStateChanged: Boolean = true,
        hasRecentPaymentFlow: Boolean = false
    ): AlipayOcrFallbackRequest = AlipayOcrFallbackRequest(
        packageName = BillSyncSource.Alipay.packageName,
        pageText = pageText,
        sdkInt = Build.VERSION_CODES.R,
        isApplicationWindow = true,
        isWindowStateChanged = isWindowStateChanged,
        hasRecentPaymentFlow = hasRecentPaymentFlow,
        accessibilityNeedsOcr = true
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
            fallbackTransactionTimeText = ALIPAY_OCR_FALLBACK_TRANSACTION_TIME
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
    fun ocrResultRejectsMissingMerchantFundingOrAmbiguousAmount() {
        val missingMerchant = "支付成功\n收款方\n金额\n¥20.00\n付款方式\n支付宝余额"
        val missingFunding = "支付成功\n收款方\n便利店\n金额\n¥20.00\n付款方式"
        val ambiguousAmount = "支付成功\n收款方\n便利店\n¥20.00\n¥5.00\n付款方式\n支付宝余额"

        assertEquals(
            AlipayOcrRejectionReason.MerchantMissing,
            decideAlipayOcrCapture(missingMerchant).rejectionReason
        )
        assertEquals(
            AlipayOcrRejectionReason.FundingAccountMissing,
            decideAlipayOcrCapture(missingFunding).rejectionReason
        )
        assertEquals(
            AlipayOcrRejectionReason.TransactionAmountMissingOrAmbiguous,
            decideAlipayOcrCapture(ambiguousAmount).rejectionReason
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
