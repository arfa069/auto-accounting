package com.autoaccounting.feature.billsync

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlipayTransitCaptureDecisionTest {
    @Test
    fun completedMetroExitRequiresTheFullTransitSignature() {
        val screenshotText = """
            已出站
            车费将在稍后扣除
            乘车服务
            刷码乘车
            0.01元起购出行通勤卡
            用里程兑1元出行券
        """.trimIndent()

        assertTrue(isCompletedAlipayMetroExit(screenshotText))
        assertFalse(isCompletedAlipayMetroExit("已出站\n刷码乘车"))
        assertFalse(isCompletedAlipayMetroExit("车费将在稍后扣除\n刷码乘车"))
        assertFalse(isCompletedAlipayMetroExit("已进站\n车费将在稍后扣除\n刷码乘车"))
    }

    @Test
    fun transitOcrFallbackRequiresAnAlipayApplicationWindowWithVisibleTransitCue() {
        assertTrue(
            shouldAttemptAlipayTransitOcrFallback(
                packageName = BillSyncSource.Alipay.packageName,
                pageText = "已出站",
                sdkInt = Build.VERSION_CODES.R,
                isApplicationWindow = true
            )
        )
        assertFalse(
            shouldAttemptAlipayTransitOcrFallback(
                packageName = BillSyncSource.Alipay.packageName,
                pageText = "",
                sdkInt = Build.VERSION_CODES.R,
                isApplicationWindow = true
            )
        )
        assertFalse(
            shouldAttemptAlipayTransitOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "刷码乘车",
                sdkInt = Build.VERSION_CODES.R,
                isApplicationWindow = true
            )
        )
        assertFalse(
            shouldAttemptAlipayTransitOcrFallback(
                packageName = BillSyncSource.Alipay.packageName,
                pageText = "刷码乘车",
                sdkInt = Build.VERSION_CODES.Q,
                isApplicationWindow = true
            )
        )
        assertFalse(
            shouldAttemptAlipayTransitOcrFallback(
                packageName = BillSyncSource.Alipay.packageName,
                pageText = "刷码乘车",
                sdkInt = Build.VERSION_CODES.R,
                isApplicationWindow = false
            )
        )
    }
}
