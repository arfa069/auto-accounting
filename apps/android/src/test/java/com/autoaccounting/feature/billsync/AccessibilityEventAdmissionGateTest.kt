package com.autoaccounting.feature.billsync

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityEventAdmissionGateTest {
    @Test
    fun onlyWindowAndContentEventsReachContinuousMonitoring() {
        assertTrue(isContinuousMonitoringEventRelevant(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertTrue(isContinuousMonitoringEventRelevant(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertTrue(isContinuousMonitoringEventRelevant(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
        assertFalse(isContinuousMonitoringEventRelevant(AccessibilityEvent.TYPE_VIEW_SCROLLED))
    }

    @Test
    fun duplicatePackageEventAndWindowAreCoalescedWithinTheShortWindow() {
        var now = 1_000L
        val gate = AccessibilityEventAdmissionGate(clock = { now })

        assertTrue(
            gate.shouldInspect(
                packageName = "com.tencent.mm",
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                windowId = 7
            )
        )
        now += 249L
        assertFalse(
            gate.shouldInspect(
                packageName = "com.tencent.mm",
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                windowId = 7
            )
        )
        now += 1L
        assertTrue(
            gate.shouldInspect(
                packageName = "com.tencent.mm",
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                windowId = 7
            )
        )
    }

    @Test
    fun differentEventsAndUndefinedWindowsAreNotSuppressed() {
        val gate = AccessibilityEventAdmissionGate(clock = { 1_000L })

        assertTrue(
            gate.shouldInspect(
                packageName = "com.tencent.mm",
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                windowId = 7
            )
        )
        assertTrue(
            gate.shouldInspect(
                packageName = "com.tencent.mm",
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                windowId = 7
            )
        )
        assertTrue(
            gate.shouldInspect(
                packageName = "com.tencent.mm",
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                windowId = UNDEFINED_ACCESSIBILITY_WINDOW_ID
            )
        )
    }
}
