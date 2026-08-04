package com.autoaccounting.feature.billsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentScreenOcrRecognitionTest {
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
    fun visualRowOrderingReconstructsWechatTwoColumnHistoryDetail() {
        val normalizedText = normalizePaymentScreenOcrText(
            lines = listOf(
                OcrLineObservation("账单服务", 40, left = 90, top = 40, bottom = 80),
                OcrLineObservation("-10.40", 100, left = 390, top = 120, bottom = 220),
                OcrLineObservation("当前状态", 36, left = 90, top = 320, bottom = 356),
                OcrLineObservation("支付时间", 36, left = 90, top = 390, bottom = 426),
                OcrLineObservation("商品", 36, left = 90, top = 460, bottom = 496),
                OcrLineObservation("支付方式", 36, left = 90, top = 530, bottom = 566),
                OcrLineObservation("支付成功", 38, left = 320, top = 318, bottom = 356),
                OcrLineObservation(
                    "2026年07月12日 09:16:07",
                    36,
                    left = 320,
                    top = 391,
                    bottom = 427
                ),
                OcrLineObservation(
                    "KFC_PREWX10012651367114169061602",
                    36,
                    left = 320,
                    top = 459,
                    bottom = 495
                ),
                OcrLineObservation("零钱", 36, left = 320, top = 531, bottom = 567)
            ),
            imageHeight = 2_400
        )

        assertEquals(
            listOf(
                "账单服务",
                "¥10.40",
                "当前状态",
                "支付成功",
                "支付时间",
                "2026年07月12日 09:16:07",
                "商品",
                "KFC_PREWX10012651367114169061602",
                "支付方式",
                "零钱"
            ),
            normalizedText.lines()
        )
        assertTrue(hasCurrentStatusPaymentSuccessPair(normalizedText))
        assertTrue(prepareManualWechatOcrResultText(normalizedText) != null)
    }
}
