package com.autoaccounting.feature.billsync

import android.os.Build
import com.autoaccounting.feature.monitoring.isContinuousMonitoringPackageAllowed

internal class AccessibilityCaptureRouter(
    private val onManualWechatOcr: (String) -> Unit,
    private val onAutomaticWechatOcr: (String) -> Unit
) {
    fun captureRoute(
        packageName: String,
        continuousMonitoringEnabled: Boolean
    ): AccessibilityCaptureRoute = resolveAccessibilityCaptureRoute(
        manualBillSyncAcceptsPackage = BillSyncSessions.controller.acceptsPackage(packageName),
        continuousMonitoringEnabled = continuousMonitoringEnabled,
        continuousMonitoringPackageAllowed = isContinuousMonitoringPackageAllowed(packageName)
    )

    fun handleWechatCaptureRoute(
        packageName: String,
        pageText: String,
        manualBillSyncAcceptsPackage: Boolean,
        shouldConsiderContinuousMonitoring: Boolean,
        windowEvidence: WechatWindowEvidence?
    ): Boolean {
        val isManualWechatPackage = manualBillSyncAcceptsPackage &&
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
        val shouldEvaluateAutomaticOcr = shouldConsiderContinuousMonitoring &&
            windowEvidence != null &&
            isWechatOcrFallbackCandidate(
                packageName = packageName,
                pageText = pageText,
                sdkInt = Build.VERSION.SDK_INT,
                windowEvidence = windowEvidence
            )
        if (!shouldEvaluateAutomaticOcr) return false
        onAutomaticWechatOcr(packageName)
        return true
    }
}
