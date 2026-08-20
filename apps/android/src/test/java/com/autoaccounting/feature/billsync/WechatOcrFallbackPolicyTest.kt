package com.autoaccounting.feature.billsync

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatOcrFallbackPolicyTest {
    @Test
    fun manualFallbackAcceptsUnreadableWechatApplicationWindow() {
        val evidence = WechatWindowEvidence(
            activityClassName = "com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoUI",
            isApplicationWindow = true
        )

        assertTrue(
            shouldAttemptManualWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "账单服务",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = evidence
            )
        )
        assertTrue(
            shouldAttemptManualWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "返回",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = evidence
            )
        )
        assertFalse(
            shouldAttemptManualWechatOcrFallback(
                packageName = BillSyncSource.Alipay.packageName,
                pageText = "账单服务",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = evidence
            )
        )
    }

    @Test
    fun manualFallbackRequiresAndroidElevenAndApplicationWindow() {
        val evidence = WechatWindowEvidence(
            activityClassName = "com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoUI",
            isApplicationWindow = true
        )

        assertFalse(
            shouldAttemptManualWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "账单服务",
                sdkInt = Build.VERSION_CODES.Q,
                windowEvidence = evidence
            )
        )
        assertFalse(
            shouldAttemptManualWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "账单服务",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = evidence.copy(isApplicationWindow = false)
            )
        )
    }

    @Test
    fun manualOcrRequiresInteractiveUnlockedScreen() {
        assertTrue(isScreenReadyForWechatOcr(screenInteractive = true, keyguardLocked = false))
        assertFalse(isScreenReadyForWechatOcr(screenInteractive = false, keyguardLocked = false))
        assertFalse(isScreenReadyForWechatOcr(screenInteractive = true, keyguardLocked = true))
    }
}
