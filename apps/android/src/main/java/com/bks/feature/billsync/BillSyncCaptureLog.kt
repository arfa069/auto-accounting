package com.bks.feature.billsync

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bks.BuildConfig

internal object BillSyncCaptureLog {
    fun event(event: AccessibilityEvent) = debug {
        "event type=${event.eventType} package=${event.packageName} window=${event.windowId}"
    }

    fun capture(packageName: String?, windowId: Int, enabled: Boolean) = debug {
        "capture triggerPackage=$packageName window=$windowId enabled=$enabled"
    }

    fun roots(
        allRoots: List<AccessibilityNodeInfo>,
        activeRoots: List<AccessibilityNodeInfo>,
        selectedRoot: AccessibilityNodeInfo?,
        triggerPackage: String?,
        triggerWindowId: Int
    ) = debug {
        "roots all=${allRoots.summary()} active=${activeRoots.summary()} " +
            "selected=${selectedRoot?.let { "${it.packageName}/${it.windowId}" }} " +
            "triggerPackage=$triggerPackage triggerWindow=$triggerWindowId"
    }

    fun ignored(reason: String, details: String? = null) = debug {
        "ignored reason=$reason${details?.let { " $it" }.orEmpty()}"
    }

    fun collected(packageName: String, windowId: Int, pageText: String) {
        debug {
            "collected package=$packageName window=$windowId lines=${pageText.lineSequence().count()} " +
                "chars=${pageText.length}"
        }
        pageText(pageText)
    }

    fun processed(recognized: Boolean, createdEntries: Int) = debug {
        "processed recognized=$recognized created=$createdEntries"
    }

    fun failed(packageName: String?, windowId: Int, error: Throwable) {
        if (BuildConfig.DEBUG) Log.e(TAG, "failed package=$packageName window=$windowId", error)
    }

    private fun debug(message: () -> String) {
        if (BuildConfig.DEBUG && Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message())
    }

    private fun pageText(pageText: String) {
        if (!BuildConfig.DEBUG || !Log.isLoggable(TAG, Log.DEBUG)) return
        val chunks = pageText.ifEmpty { "<blank>" }.chunked(CHUNK_SIZE)
        chunks.forEachIndexed { index, chunk ->
            Log.d(TAG, "page ${index + 1}/${chunks.size}:\n$chunk")
        }
    }

    private fun List<AccessibilityNodeInfo>.summary(): String =
        joinToString(prefix = "[", postfix = "]") { root ->
            "${root.packageName}/${root.windowId}/${root.className}"
        }

    private const val TAG = "BillSyncCapture"
    private const val CHUNK_SIZE = 3_000
}
