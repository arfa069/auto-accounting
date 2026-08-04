package com.autoaccounting.feature.billsync

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatOcrFallbackPolicyTest {
    @Test
    fun manualOcrFallbackAcceptsUnreadableWechatApplicationWindowsRegardlessOfActivity() {
        val historyDetailWindow = WechatWindowEvidence(
            activityClassName = "com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoUI",
            isApplicationWindow = true
        )

        assertTrue(
            shouldAttemptManualWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "账单服务",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = historyDetailWindow
            )
        )
        assertFalse(
            shouldAttemptManualWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "交易成功 ¥10.40",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = historyDetailWindow
            )
        )
        assertTrue(
            shouldAttemptManualWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = historyDetailWindow.copy(
                    activityClassName = "com.tencent.mm.ui.LauncherUI"
                )
            )
        )
        assertFalse(
            shouldAttemptManualWechatOcrFallback(
                packageName = BillSyncSource.Alipay.packageName,
                pageText = "",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = historyDetailWindow
            )
        )
        assertFalse(
            shouldAttemptManualWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "",
                sdkInt = Build.VERSION_CODES.Q,
                windowEvidence = historyDetailWindow
            )
        )
        assertFalse(
            shouldAttemptManualWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = historyDetailWindow.copy(isApplicationWindow = false)
            )
        )
    }

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
}
