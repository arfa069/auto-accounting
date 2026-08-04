package com.autoaccounting.feature.billsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentScreenOcrSessionGuardTest {
    @Test
    fun successfulOcrSurfaceIsProcessedOnceUntilTransactionFingerprintChangesOrResets() {
        val guard = PaymentScreenOcrSessionGuard()
        val firstTransaction = requireNotNull(
            wechatOcrPaymentFingerprint("支付成功\n中国电信\n¥6.99\n返回商家")
        )
        val nextTransaction = requireNotNull(
            wechatOcrPaymentFingerprint("支付成功\n便利店\n¥12.00\n返回商家")
        )

        assertTrue(guard.shouldProcess(firstTransaction))
        guard.markProcessed(firstTransaction)
        assertFalse(guard.shouldProcess(firstTransaction))
        assertTrue(guard.shouldProcess(nextTransaction))

        guard.resetCurrentFingerprint()
        assertTrue(guard.shouldProcess(firstTransaction))
    }

    @Test
    fun sentRedPacketSessionGuardBoundsRememberedFingerprints() {
        val guard = PaymentScreenOcrSessionGuard()
        val fingerprints = (1..65).map { amount ->
            requireNotNull(
                wechatOcrPaymentFingerprint(
                    "Arfa的红包\n红包金额$amount.00元，等待对方领取\n" +
                        "未领取的红包，将于24小时后发起退款"
                )
            )
        }

        fingerprints.forEach(guard::markProcessed)
        guard.resetCurrentFingerprint()

        assertTrue(guard.shouldProcess(fingerprints.first()))
        assertFalse(guard.shouldProcess(fingerprints.last()))
    }

    @Test
    fun wechatOcrFingerprintIgnoresStatusBarChangesButTracksTransactionFields() {
        val first = wechatOcrPaymentFingerprint(
            "21:12\n0.99 KB/s\n支付成功\n中国电信\n¥6.99\n返回商家"
        )
        val sameTransaction = wechatOcrPaymentFingerprint(
            "21:14\n1.20 KB/s\n支付成功\n中国电信\n¥6.99\n返回商家"
        )
        val differentTransaction = wechatOcrPaymentFingerprint(
            "21:15\n支付成功\n便利店\n¥12.00\n返回商家"
        )

        assertEquals(first, sameTransaction)
        assertTrue(first != differentTransaction)
    }
}
