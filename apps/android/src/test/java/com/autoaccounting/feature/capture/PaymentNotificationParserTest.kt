package com.autoaccounting.feature.capture

import org.junit.Assert.assertEquals
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

    private companion object {
        const val NOW = 1_783_468_800_000L
    }
}
