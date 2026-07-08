package com.autoaccounting.feature.billsync

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class BillSyncAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
