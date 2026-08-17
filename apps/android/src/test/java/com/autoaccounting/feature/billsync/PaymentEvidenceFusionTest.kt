package com.autoaccounting.feature.billsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentEvidenceFusionTest {
    @Test
    fun fusesMerchantAfterAmountWithoutNotificationOrTextOrderAssumption() {
        val accessibility = PaymentTextEvidence(
            text = """
                支付成功 ¥7.98
                支付成功
                7.98
                中国电信
                ¥7.98
                交易方式
                花呗
                完成
                回首页
            """.trimIndent(),
            observations = listOf(
                observation("支付成功", 180, 220, 48),
                observation("7.98", 320, 440, 120),
                observation("中国电信", 480, 530, 50),
                observation("交易方式", 650, 690, 40),
                observation("花呗", 720, 765, 45),
                observation("完成", 900, 950, 50),
                observation("回首页", 1_000, 1_050, 50)
            ),
            imageHeight = 2_400
        )
        val ocr = PaymentTextEvidence(
            text = "支付成功\n回首页\n¥7.98\n中国电倍\n798\n交易方式\n花呗",
            observations = listOf(
                observation("支付成功", 180, 220, 40),
                observation("回首页", 180, 220, 40),
                observation("¥7.98", 320, 450, 130),
                observation("中国电倍", 490, 545, 55),
                observation("798", 610, 645, 35),
                observation("交易方式", 700, 740, 40),
                observation("花呗", 760, 805, 45)
            ),
            imageHeight = 2_400
        )

        val fusedText = fusePaymentEvidenceText(BillSyncSource.Alipay, accessibility, ocr)
        val entry = BillPageParser().parse(
            source = BillSyncSource.Alipay,
            pageText = fusedText,
            fallbackTransactionTimeText = "2026-08-17 00:44"
        ).single()

        assertEquals("中国电信", entry.merchantTitle)
        assertEquals(798L, entry.amountMinor)
        assertEquals("支出", entry.transactionKindLabel)
        assertEquals("花呗", entry.fundingAccountLabel)
        assertEquals("2026-08-17 00:44", entry.transactionTimeText)
        assertFalse(entry.merchantTitleFromFallback)
        assertFalse(entry.fundingAccountFromFallback)
    }

    @Test
    fun sameFusionRuleWorksForWechatMerchantBelowAmount() {
        val evidence = PaymentTextEvidence(
            text = "支付成功\n¥6.99\n中国电信\n返回商家",
            observations = listOf(
                observation("支付成功", 100, 145, 45),
                observation("¥6.99", 220, 340, 120),
                observation("中国电信", 390, 445, 55),
                observation("返回商家", 700, 750, 50)
            ),
            imageHeight = 2_400
        )

        val entry = BillPageParser().parse(
            source = BillSyncSource.WeChat,
            pageText = fusePaymentEvidenceText(BillSyncSource.WeChat, evidence, null),
            fallbackTransactionTimeText = "2026-08-17 00:44"
        ).single()

        assertEquals("中国电信", entry.merchantTitle)
        assertEquals(699L, entry.amountMinor)
    }

    @Test
    fun notificationIsFallbackAndConflictingAmountsAreNotGuessed() {
        val accessibility = PaymentTextEvidence(
            text = "支付成功\n收款方：页面商户\n¥7.98\n交易方式：花呗"
        )
        val ocr = PaymentTextEvidence(text = "支付信息\n¥8.98")
        val notification = PaymentTextEvidence(
            text = "收款方：通知商户\n金额 ¥7.98\n交易方式：余额\n交易时间 2026-08-17 00:43"
        )

        val fused = fusePaymentEvidenceText(
            BillSyncSource.Alipay,
            accessibility,
            ocr,
            notification
        )

        assertFalse(fused.startsWith("金额 "))
        assertTrue(fused.contains("商户：页面商户"))
        assertTrue(fused.contains("交易方式：花呗"))
        assertTrue(fused.contains("交易时间 2026-08-17 00:43"))
    }

    private fun observation(text: String, top: Int, bottom: Int, height: Int) =
        PaymentTextObservation(text = text, height = height, left = 100, top = top, bottom = bottom)
}
