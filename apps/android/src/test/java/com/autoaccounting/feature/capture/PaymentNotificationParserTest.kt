package com.autoaccounting.feature.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentNotificationParserTest {
    @Test
    fun parsesWechatPaymentNotification() {
        val parsed = PaymentNotificationParser().parse(
            PaymentNotificationEvent(
                packageName = "com.tencent.mm",
                title = "微信支付",
                text = "付款成功 商户：午餐 金额：¥35.90",
                postedAtEpochMillis = NOW
            )
        )

        assertEquals("微信", parsed?.sourceLabel)
        assertEquals("午餐", parsed?.merchantTitle)
        assertEquals(3590L, parsed?.amountMinor)
        assertEquals("支出", parsed?.transactionKindLabel)
        assertEquals("微信零钱", parsed?.fundingAccountLabel)
    }

    @Test
    fun parsesAlipayPaymentNotification() {
        val parsed = PaymentNotificationParser().parse(
            PaymentNotificationEvent(
                packageName = "com.eg.android.AlipayGphone",
                title = "支付宝",
                text = "支付成功 地铁出行 6.00元",
                postedAtEpochMillis = NOW
            )
        )

        assertEquals("支付宝", parsed?.sourceLabel)
        assertEquals("地铁出行", parsed?.merchantTitle)
        assertEquals(600L, parsed?.amountMinor)
        assertEquals("支出", parsed?.transactionKindLabel)
        assertEquals("支付宝余额", parsed?.fundingAccountLabel)
    }

    @Test
    fun ignoresUnrelatedNotificationsAndUnsupportedSources() {
        val parser = PaymentNotificationParser()

        assertNull(
            parser.parse(
                PaymentNotificationEvent(
                    packageName = "com.tencent.mm",
                    title = "微信消息",
                    text = "今晚吃什么？",
                    postedAtEpochMillis = NOW
                )
            )
        )
        assertNull(
            parser.parse(
                PaymentNotificationEvent(
                    packageName = "com.example.mail",
                    title = "付款成功",
                    text = "金额：¥35.90",
                    postedAtEpochMillis = NOW
                )
            )
        )
    }

    // ---- P2P: Red Packet (received) ----

    @Test
    fun parsesWechatReceivedRedPacket() {
        val parsed = PaymentNotificationParser().parse(
            PaymentNotificationEvent(
                packageName = "com.tencent.mm",
                title = "微信红包",
                text = "收到张三的红包 ¥8.88",
                postedAtEpochMillis = NOW
            )
        )

        assertNotNull(parsed)
        assertEquals("微信", parsed!!.sourceLabel)
        assertEquals("张三", parsed.merchantTitle)
        assertEquals(888L, parsed.amountMinor)
        assertEquals("收入", parsed.transactionKindLabel)
    }

    // ---- P2P: Red Packet (sent) ----

    @Test
    fun parsesWechatSentRedPacket() {
        val parsed = PaymentNotificationParser().parse(
            PaymentNotificationEvent(
                packageName = "com.tencent.mm",
                title = "微信红包",
                text = "发出红包 ¥66.00",
                postedAtEpochMillis = NOW
            )
        )

        assertNotNull(parsed)
        assertEquals("微信", parsed!!.sourceLabel)
        assertEquals("红包", parsed.merchantTitle)
        assertEquals(6600L, parsed.amountMinor)
        assertEquals("支出", parsed.transactionKindLabel)
    }

    // ---- P2P: Transfer (received) ----

    @Test
    fun parsesWechatReceivedTransfer() {
        val parsed = PaymentNotificationParser().parse(
            PaymentNotificationEvent(
                packageName = "com.tencent.mm",
                title = "微信转账",
                text = "收到李四的转账 ¥200.00",
                postedAtEpochMillis = NOW
            )
        )

        assertNotNull(parsed)
        assertEquals("微信", parsed!!.sourceLabel)
        assertEquals("李四", parsed.merchantTitle)
        assertEquals(20000L, parsed.amountMinor)
        assertEquals("收入", parsed.transactionKindLabel)
    }

    @Test
    fun parsesAlipayReceivedTransfer() {
        val parsed = PaymentNotificationParser().parse(
            PaymentNotificationEvent(
                packageName = "com.eg.android.AlipayGphone",
                title = "支付宝",
                text = "王五向你转账 ¥50.00",
                postedAtEpochMillis = NOW
            )
        )

        assertNotNull(parsed)
        assertEquals("支付宝", parsed!!.sourceLabel)
        assertEquals("王五", parsed.merchantTitle)
        assertEquals(5000L, parsed.amountMinor)
        assertEquals("收入", parsed.transactionKindLabel)
    }

    // ---- P2P: Transfer (sent) ----

    @Test
    fun parsesWechatSentTransfer() {
        val parsed = PaymentNotificationParser().parse(
            PaymentNotificationEvent(
                packageName = "com.tencent.mm",
                title = "微信转账",
                text = "转账给赵六 ¥100.00",
                postedAtEpochMillis = NOW
            )
        )

        assertNotNull(parsed)
        assertEquals("微信", parsed!!.sourceLabel)
        assertEquals("赵六", parsed.merchantTitle)
        assertEquals(10000L, parsed.amountMinor)
        assertEquals("支出", parsed.transactionKindLabel)
    }

    @Test
    fun parsesAlipaySentTransfer() {
        val parsed = PaymentNotificationParser().parse(
            PaymentNotificationEvent(
                packageName = "com.eg.android.AlipayGphone",
                title = "支付宝",
                text = "向孙七转账 ¥30.00",
                postedAtEpochMillis = NOW
            )
        )

        assertNotNull(parsed)
        assertEquals("支付宝", parsed!!.sourceLabel)
        assertEquals("孙七", parsed.merchantTitle)
        assertEquals(3000L, parsed.amountMinor)
        assertEquals("支出", parsed.transactionKindLabel)
    }

    @Test
    fun parsesAlipayExpenseNotificationWithTransactionSummary() {
        val parsed = PaymentNotificationParser().parse(
            PaymentNotificationEvent(
                packageName = "com.eg.android.AlipayGphone",
                title = "支付宝",
                text = "支出 交易 ¥0.01 余额 5.00",
                postedAtEpochMillis = NOW
            )
        )

        assertNotNull(parsed)
        assertEquals("支付宝", parsed!!.sourceLabel)
        assertEquals(FALLBACK_COUNTERPARTY, parsed.merchantTitle)
        assertEquals(1L, parsed.amountMinor)
        assertEquals("支出", parsed.transactionKindLabel)
    }

    @Test
    fun ignoresAmbiguousAmountsWithoutCurrencyMarker() {
        val parsed = PaymentNotificationParser().parse(
            PaymentNotificationEvent(
                packageName = "com.eg.android.AlipayGphone",
                title = "支付宝",
                text = "支出 交易 0.01 余额 5.00",
                postedAtEpochMillis = NOW
            )
        )

        assertNull(parsed)
    }

    // ---- P2P: Fallback ----

    @Test
    fun fallsBackToUnknownSourceWhenNoCounterpartyFound() {
        val parsed = PaymentNotificationParser().parse(
            PaymentNotificationEvent(
                packageName = "com.tencent.mm",
                title = "微信支付",
                text = "收到一笔转账 ¥15.00",
                postedAtEpochMillis = NOW
            )
        )

        assertNotNull(parsed)
        assertEquals(FALLBACK_COUNTERPARTY, parsed!!.merchantTitle)
        assertEquals(1500L, parsed.amountMinor)
        assertEquals("收入", parsed.transactionKindLabel)
    }

    private companion object {
        const val NOW = 1_783_468_800_000L
    }
}
