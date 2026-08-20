package com.bks.feature.billsync

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo

internal interface AccessibilityCaptureHost {
    val currentRoot: AccessibilityNodeInfo?

    fun isScreenReady(): Boolean

    fun currentWechatWindowEvidence(
        windowId: Int,
        windowIdentity: WechatWindowIdentity?
    ): WechatWindowEvidence

    suspend fun captureScreenBitmap(windowId: Int, traceId: String? = null): Bitmap?

    suspend fun recognizeScreen(bitmap: Bitmap): String
}
