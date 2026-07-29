package com.autoaccounting.feature.billsync

import android.os.Build

internal fun isCompletedAlipayMetroExit(pageText: String): Boolean {
    val compactText = pageText.filterNot(Char::isWhitespace)
    return compactText.contains("已出站") &&
        compactText.contains("车费将在稍后扣除") &&
        ALIPAY_TRANSIT_COMPLETION_CUES.any(compactText::contains)
}

internal fun shouldAttemptAlipayTransitOcrFallback(
    packageName: String,
    pageText: String,
    sdkInt: Int,
    isApplicationWindow: Boolean
): Boolean =
    packageName == BillSyncSource.Alipay.packageName &&
        sdkInt >= Build.VERSION_CODES.R &&
        isApplicationWindow &&
        ALIPAY_TRANSIT_OCR_TRIGGER_CUES.any(pageText::contains)

private val ALIPAY_TRANSIT_COMPLETION_CUES = listOf(
    "刷码乘车",
    "乘车服务"
)

private val ALIPAY_TRANSIT_OCR_TRIGGER_CUES = listOf(
    "已出站",
    "刷码乘车",
    "乘车服务",
    "乘车码"
)
