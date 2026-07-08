package com.autoaccounting.feature.billsync

import org.junit.Assert.assertEquals
import org.junit.Test

class BillPageParserTest {
    @Test
    fun parsesWechatBillPageText() {
        val entries = BillPageParser().parse(
            source = BillSyncSource.WeChat,
            pageText = """
                2026-07-08 12:20 午餐 支出 ¥35.90 微信零钱
                2026-07-08 21:10 退款到账 退款 ¥25.90 微信零钱
            """.trimIndent()
        )

        assertEquals(2, entries.size)
        assertEquals("午餐", entries[0].merchantTitle)
        assertEquals(3590L, entries[0].amountMinor)
        assertEquals("支出", entries[0].transactionKindLabel)
        assertEquals("微信零钱", entries[0].fundingAccountLabel)
        assertEquals("退款到账", entries[1].merchantTitle)
        assertEquals("退款", entries[1].transactionKindLabel)
    }

    @Test
    fun parsesAlipayBillPageText() {
        val entries = BillPageParser().parse(
            source = BillSyncSource.Alipay,
            pageText = "2026-07-08 08:10 地铁出行 支出 6.00元 支付宝余额"
        )

        assertEquals(1, entries.size)
        assertEquals("支付宝", entries[0].sourceLabel)
        assertEquals("地铁出行", entries[0].merchantTitle)
        assertEquals(600L, entries[0].amountMinor)
        assertEquals("支付宝余额", entries[0].fundingAccountLabel)
    }
}
