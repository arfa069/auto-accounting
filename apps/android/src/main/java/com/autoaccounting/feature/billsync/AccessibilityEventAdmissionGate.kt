package com.autoaccounting.feature.billsync

import android.view.accessibility.AccessibilityEvent

internal const val UNDEFINED_ACCESSIBILITY_WINDOW_ID = -1

internal fun isContinuousMonitoringEventRelevant(eventType: Int): Boolean =
    eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
        eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED

internal enum class AccessibilityCaptureRoute {
    ManualBillSync,
    ContinuousMonitoring,
    Reject
}

internal fun resolveAccessibilityCaptureRoute(
    manualBillSyncAcceptsPackage: Boolean,
    continuousMonitoringEnabled: Boolean,
    continuousMonitoringPackageAllowed: Boolean
): AccessibilityCaptureRoute = when {
    manualBillSyncAcceptsPackage -> AccessibilityCaptureRoute.ManualBillSync
    continuousMonitoringEnabled && continuousMonitoringPackageAllowed ->
        AccessibilityCaptureRoute.ContinuousMonitoring
    else -> AccessibilityCaptureRoute.Reject
}

internal class AccessibilityEventAdmissionGate(
    private val duplicateWindowMillis: Long = DUPLICATE_WINDOW_MILLIS,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
    private var previousPackageName: String? = null
    private var previousEventType: Int? = null
    private var previousWindowId: Int? = null
    private var previousEventAtElapsedMillis = Long.MIN_VALUE

    @Synchronized
    fun shouldInspect(
        packageName: String,
        eventType: Int,
        windowId: Int
    ): Boolean {
        if (windowId == UNDEFINED_ACCESSIBILITY_WINDOW_ID) {
            return true
        }
        val now = clock()
        val isDuplicate = packageName == previousPackageName &&
            eventType == previousEventType &&
            windowId == previousWindowId &&
            now - previousEventAtElapsedMillis in 0 until duplicateWindowMillis
        if (isDuplicate) {
            return false
        }
        previousPackageName = packageName
        previousEventType = eventType
        previousWindowId = windowId
        previousEventAtElapsedMillis = now
        return true
    }

    private companion object {
        const val DUPLICATE_WINDOW_MILLIS = 250L
    }
}
