package com.autoaccounting.feature.billsync

import android.os.Build

internal enum class AccessibilityCaptureRoute {
    ManualBillSync,
    Reject
}

internal class AccessibilityCaptureRouter(
    private val onManualWechatOcr: (String) -> Unit
) {
    fun captureRoute(
        packageName: String
    ): AccessibilityCaptureRoute = if (BillSyncSessions.controller.acceptsPackage(packageName)) {
        AccessibilityCaptureRoute.ManualBillSync
    } else {
        AccessibilityCaptureRoute.Reject
    }

    fun handleWechatCaptureRoute(
        packageName: String,
        pageText: String,
        windowEvidence: WechatWindowEvidence?
    ): Boolean {
        val isManualWechatPackage = BillSyncSessions.controller.acceptsPackage(packageName) &&
            packageName == BillSyncSource.WeChat.packageName
        val isManualWechatOcrSession = isManualWechatPackage &&
            BillSyncSessions.controller.acceptsManualOcr(packageName)
        val shouldEvaluateManualOcr = isManualWechatOcrSession &&
            windowEvidence != null &&
            shouldAttemptManualWechatOcrFallback(
                packageName = packageName,
                pageText = pageText,
                sdkInt = Build.VERSION.SDK_INT,
                windowEvidence = windowEvidence
            )
        if (shouldEvaluateManualOcr) {
            onManualWechatOcr(packageName)
            return true
        }
        if (isManualWechatPackage) return true
        return false
    }
}
