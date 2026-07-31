package com.autoaccounting.feature.billsync

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import com.autoaccounting.feature.monitoring.ContinuousMonitoringPermissionHealth
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState

internal interface AccessibilityCaptureHost {
    val currentRoot: AccessibilityNodeInfo?
    val monitoringState: ContinuousMonitoringState

    fun currentPermissionHealth(): ContinuousMonitoringPermissionHealth

    fun isScreenReady(): Boolean

    fun isApplicationWindow(windowId: Int): Boolean

    fun currentWechatWindowEvidence(
        windowId: Int,
        windowIdentity: WechatWindowIdentity?
    ): WechatWindowEvidence

    suspend fun captureScreenBitmap(windowId: Int): Bitmap?

    suspend fun recognizeScreen(bitmap: Bitmap): String
}
