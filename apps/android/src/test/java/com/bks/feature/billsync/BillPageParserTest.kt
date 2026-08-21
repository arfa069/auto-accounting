package com.bks.feature.billsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillPageParserTest {
    private val parser = BillPageParser()

    @Test
    fun recognizesGenericOutflowAndExtractsMerchant() {
        val entry = parse("支付成功\n¥35.90\n商户：社区便利店\n交易时间 2026-08-21 09:12").single()

        assertEquals("社区便利店", entry.merchantTitle)
        assertEquals(3_590L, entry.amountMinor)
        assertEquals("支出", entry.transactionKindLabel)
        assertEquals("2026-08-21 09:12", entry.transactionTimeText)
    }

    @Test
    fun recognizesGenericInflowAndFallsBackMerchant() {
        val entry = parse("收款成功\n12.00元\n交易单号 20260821001").single()

        assertEquals(FALLBACK_MERCHANT_TITLE, entry.merchantTitle)
        assertEquals("收入", entry.transactionKindLabel)
        assertTrue(entry.merchantTitleFromFallback)
    }

    @Test
    fun repeatedSameAmountIsNotAmbiguous() {
        assertEquals(
            1,
            parse(
                "支付成功￥4.99\n支付成功\n4.99\n中国电信\n￥4.99\n交易方式\n花呗\n完成"
            ).size
        )
    }

    @Test
    fun usesFirstAmountWhenPageShowsDifferentAmounts() {
        assertEquals(880L, parse("支付成功\n¥8.80\n¥9.90\n订单 1").single().amountMinor)
    }

    @Test
    fun rejectsMissingOrAmbiguousRequiredEvidence() {
        listOf(
            "支付成功\n订单 1",
            "交易成功\n¥8.80\n订单 1",
            "支付成功\n¥8.80",
            "支付成功\n收款成功\n¥8.80\n订单 1"
        ).forEach { assertTrue(parse(it).isEmpty()) }
    }

    @Test
    fun rejectsInitiationPendingFailureAndCancellation() {
        listOf("确认付款", "待支付", "处理中", "支付失败", "已取消").forEach { status ->
            assertTrue(parse("$status\n支付成功\n¥8.80\n订单 1").isEmpty())
        }
    }

    private fun parse(text: String): List<ParsedBillEntry> =
        parser.parse(text, fallbackTransactionTimeText = "2026-08-21 10:00")
}
