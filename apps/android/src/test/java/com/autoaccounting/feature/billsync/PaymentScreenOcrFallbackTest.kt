package com.autoaccounting.feature.billsync

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentScreenOcrFallbackTest {
    @Test
    fun ocrFallbackOnlyRunsForBlankWechatSurfacesOnAndroidElevenOrLater() {
        assertTrue(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "",
                sdkInt = Build.VERSION_CODES.R
            )
        )
        assertFalse(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "支付成功 ¥1.00",
                sdkInt = Build.VERSION_CODES.R
            )
        )
        assertFalse(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.Alipay.packageName,
                pageText = "",
                sdkInt = Build.VERSION_CODES.R
            )
        )
        assertFalse(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "",
                sdkInt = Build.VERSION_CODES.Q
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
    fun successfulOcrSurfaceIsProcessedOnceUntilAStableWechatPageResetsIt() {
        val guard = PaymentScreenOcrSessionGuard()

        assertTrue(guard.shouldAttempt())
        guard.markProcessed()
        assertFalse(guard.shouldAttempt())

        guard.reset()
        assertTrue(guard.shouldAttempt())
    }
}
