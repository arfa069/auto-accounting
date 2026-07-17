package com.autoaccounting.feature.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentNotificationDiagnosticScopeTest {
    private val parser = PaymentNotificationParser()

    @Test
    fun ordinaryWechatChatIsClassifiedWithoutPaymentTextRetentionPermission() {
        val result = parser.parseDetailed(
            PaymentNotificationEvent(
                packageName = "com.tencent.mm",
                title = "张三",
                text = "晚上一起吃饭吗？",
                postedAtEpochMillis = 1L
            )
        )

        assertEquals(PaymentNotificationRejectionReason.NonPaymentNotification, result.rejectionReason)
        assertFalse(result.isPaymentRelated)
    }

    @Test
    fun paymentNotificationExtractsRequiredDiagnosticContext() {
        val result = parser.parseDetailed(
            PaymentNotificationEvent(
                packageName = "com.eg.android.AlipayGphone",
                title = "支付成功 商店A 12.34元",
                text = "付款方式：余额 账号：user@example.com 备注：午餐 订单号：ORDER-1 商户订单号：MERCHANT-2",
                postedAtEpochMillis = 1L
            )
        )

        assertTrue(result.isPaymentRelated)
        val parsed = requireNotNull(result.parsed)
        assertEquals(1234L, parsed.amountMinor)
        assertEquals("午餐", parsed.note)
        assertEquals("user@example.com", parsed.fundingAccountLabel)
        assertEquals("余额", parsed.paymentMethod)
        assertEquals("ORDER-1", parsed.orderNumber)
        assertEquals("MERCHANT-2", parsed.merchantOrderNumber)
    }

    @Test
    fun paymentOutcomeWithoutAmountIsSensitiveButHasStableRejectionReason() {
        val result = parser.parseDetailed(
            PaymentNotificationEvent(
                packageName = "com.tencent.mm",
                title = "微信支付",
                text = "支付成功，金额暂不可用",
                postedAtEpochMillis = 1L
            )
        )

        assertTrue(result.isPaymentRelated)
        assertEquals(PaymentNotificationRejectionReason.MissingAmount, result.rejectionReason)
    }
}
