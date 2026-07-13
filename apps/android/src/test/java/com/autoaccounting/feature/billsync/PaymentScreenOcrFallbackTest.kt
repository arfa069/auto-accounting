package com.autoaccounting.feature.billsync

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentScreenOcrFallbackTest {
    @Test
    fun ocrFallbackRequiresUnreadableWechatApplicationWindowOnAndroidElevenOrLater() {
        val applicationWindow = WechatWindowEvidence(
            activityClassName = WECHAT_MERCHANT_PAYMENT_ACTIVITY_CLASS,
            isApplicationWindow = true
        )
        assertTrue(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = applicationWindow
            )
        )
        assertFalse(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "支付成功 ¥1.00",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = applicationWindow
            )
        )
        assertFalse(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.Alipay.packageName,
                pageText = "",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = applicationWindow
            )
        )
        assertFalse(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "",
                sdkInt = Build.VERSION_CODES.Q,
                windowEvidence = applicationWindow
            )
        )
        assertFalse(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = applicationWindow.copy(isApplicationWindow = false)
            )
        )
    }

    @Test
    fun ocrFallbackDoesNotCaptureWhileScreenIsOffOrLocked() {
        assertTrue(
            isScreenReadyForWechatOcr(
                screenInteractive = true,
                keyguardLocked = false
            )
        )
        assertFalse(
            isScreenReadyForWechatOcr(
                screenInteractive = false,
                keyguardLocked = false
            )
        )
        assertFalse(
            isScreenReadyForWechatOcr(
                screenInteractive = true,
                keyguardLocked = true
            )
        )
    }

    @Test
    fun ocrFallbackAcceptsOnlyBlankOrGenericWechatNodeText() {
        val applicationWindow = WechatWindowEvidence(
            activityClassName = WECHAT_MERCHANT_PAYMENT_ACTIVITY_CLASS,
            isApplicationWindow = true
        )
        assertTrue(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "返回",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = applicationWindow
            )
        )
        assertTrue(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "返回",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = WechatWindowEvidence(
                    activityClassName = WECHAT_TRANSFER_RESULT_ACTIVITY_CLASS,
                    isApplicationWindow = true
                )
            )
        )
        assertFalse(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "支付成功\n中国电信\n¥6.99\n返回商家",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = applicationWindow
            )
        )
        assertFalse(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "聊天\n测试消息\n发送消息\n返回",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = applicationWindow
            )
        )
        assertFalse(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "返回",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = applicationWindow.copy(
                    activityClassName = "com.tencent.mm.plugin.webview.ui.tools.WebViewUI"
                )
            )
        )
        assertFalse(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "返回",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = applicationWindow.copy(isApplicationWindow = false)
            )
        )
        assertTrue(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "返回",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = applicationWindow.copy(isApplicationWindow = false),
                hasRecentPaymentNotification = true
            )
        )
    }

    @Test
    fun prominentAmountSelectionIgnoresSmallStatusAndPromotionText() {
        val selected = selectProminentPaymentAmountLine(
            lines = listOf(
                OcrLineObservation("3.59 KB/s", 22),
                OcrLineObservation("测试商户", 46),
                OcrLineObservation("12.34", 118),
                OcrLineObservation("2元 免费领", 34)
            ),
            imageHeight = 2_400
        )

        assertEquals(2, selected)
    }

    @Test
    fun amountSelectionRejectsSmallOrEquallyProminentNumericLines() {
        assertEquals(
            null,
            selectProminentPaymentAmountLine(
                lines = listOf(OcrLineObservation("12.34", 20)),
                imageHeight = 2_400
            )
        )
        assertEquals(
            null,
            selectProminentPaymentAmountLine(
                lines = listOf(
                    OcrLineObservation("12.34", 80),
                    OcrLineObservation("56.78", 70)
                ),
                imageHeight = 2_400
            )
        )
    }

    @Test
    fun amountNormalizationRepairsLetterOAndDecimalSpacing() {
        assertEquals("¥0.05", normalizeOcrAmountLine("￥ O . O5"))
        assertEquals("¥0.05", normalizeOcrAmountLine("未知符号 O . O5"))
        assertEquals("¥20", normalizeOcrAmountLine("2O"))
        assertEquals(null, normalizeOcrAmountLine("3.58 KB/s"))
        assertEquals(null, normalizeOcrAmountLine("100%"))
    }

    @Test
    fun wechatMerchantOcrUsesTrustedWindowOrRequiresRecentNotification() {
        val pageText = "支付成功\n中国电信\n¥6.99\n返回商家"

        val trustedWindow = decideWechatOcrCapture(
            pageText = pageText,
            windowEvidence = WechatWindowEvidence(
                activityClassName = WECHAT_MERCHANT_PAYMENT_ACTIVITY_CLASS,
                isApplicationWindow = true
            )
        )
        val untrustedWindow = decideWechatOcrCapture(
            pageText = pageText,
            windowEvidence = WechatWindowEvidence(
                activityClassName = "com.tencent.mm.ui.LauncherUI",
                isApplicationWindow = true
            )
        )
        val nonApplicationWindow = decideWechatOcrCapture(
            pageText = pageText,
            windowEvidence = WechatWindowEvidence(
                activityClassName = WECHAT_MERCHANT_PAYMENT_ACTIVITY_CLASS,
                isApplicationWindow = false
            )
        )
        val incompleteSignature = decideWechatOcrCapture(
            pageText = "支付成功\n中国电信\n¥6.99",
            windowEvidence = WechatWindowEvidence(
                activityClassName = WECHAT_MERCHANT_PAYMENT_ACTIVITY_CLASS,
                isApplicationWindow = true
            )
        )
        val ambiguousCompletion = decideWechatOcrCapture(
            pageText = "付款成功\n中国电信\n¥6.99",
            windowEvidence = WechatWindowEvidence(
                activityClassName = WECHAT_MERCHANT_PAYMENT_ACTIVITY_CLASS,
                isApplicationWindow = true
            )
        )
        val completedTransfer = decideWechatOcrCapture(
            pageText = "转账成功\n测试对象\n¥0.01",
            windowEvidence = WechatWindowEvidence(
                activityClassName = WECHAT_TRANSFER_RESULT_ACTIVITY_CLASS,
                isApplicationWindow = true
            )
        )

        assertTrue(trustedWindow.shouldCapture)
        assertEquals(AutomaticCaptureVerification.Standard, trustedWindow.verification)
        assertTrue(untrustedWindow.shouldCapture)
        assertEquals(
            AutomaticCaptureVerification.RequireRecentNotification,
            untrustedWindow.verification
        )
        assertEquals(
            AutomaticCaptureVerification.RequireRecentNotification,
            nonApplicationWindow.verification
        )
        assertFalse(incompleteSignature.shouldCapture)
        assertFalse(ambiguousCompletion.shouldCapture)
        assertTrue(completedTransfer.shouldCapture)
        assertEquals(AutomaticCaptureVerification.Standard, completedTransfer.verification)
        assertTrue(
            wechatOcrPaymentFingerprint("转账成功\n测试对象\n¥0.01") != null
        )
    }

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

        guard.reset()
        assertTrue(guard.shouldProcess(firstTransaction))
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
