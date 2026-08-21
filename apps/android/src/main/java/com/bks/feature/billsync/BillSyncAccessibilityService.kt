package com.bks.feature.billsync

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bks.data.local.BksDatabaseProvider
import com.bks.data.local.LocalLedgerRepository
import com.bks.data.local.LocalPreferencesRepository
import com.bks.feature.review.ReviewQueuePersistence
import com.ven.assists.AssistsCore
import com.ven.assists.service.AssistsService
import com.ven.assists.service.AssistsServiceListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BillSyncAccessibilityService : AssistsService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val database by lazy { BksDatabaseProvider.get(this) }
    private val preferencesRepository by lazy { LocalPreferencesRepository(database) }
    private val processor by lazy {
        BillSyncCaptureProcessor(
            pipeline = BillSyncPipeline(),
            reviewQueuePersistence = ReviewQueuePersistence(LocalLedgerRepository(database)),
            preferencesRepository = preferencesRepository
        )
    }
    private val recentCaptures = mutableMapOf<String, RecentCapture>()
    private var pendingCapture: Job? = null
    private val listener = object : AssistsServiceListener {
        override fun onAccessibilityEvent(event: AccessibilityEvent) {
            scheduleCapture(event)
        }

        override fun onUnbind() {
            BillSyncServiceHealth.markServiceConnected(this@BillSyncAccessibilityService, false)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AssistsService.listeners.remove(listener)
        AssistsService.listeners.add(listener)
        BillSyncServiceHealth.markServiceConnected(this, true)
    }

    private fun scheduleCapture(event: AccessibilityEvent) {
        if (!isAutomaticCaptureEvent(event.eventType)) return
        val eventPackage = event.packageName?.toString()?.takeIf(String::isNotBlank)
        val eventWindowId = event.windowId
        if (!shouldScheduleCapture(pendingCapture?.isActive == true)) return
        pendingCapture = serviceScope.launch {
            captureAfterSettle(eventPackage, eventWindowId)
        }
    }

    private suspend fun captureAfterSettle(triggerPackage: String?, triggerWindowId: Int) {
        delay(CAPTURE_SETTLE_MILLIS)
        val enabled = preferencesRepository.userPreferences.first().automaticBookkeepingEnabled
        if (!enabled) return

        val activeRoots = AssistsCore.getAccessibilityRootNodes(AssistsCore.NodeLookupScope.ActiveWindow)
        val activeRoot = activeRoots.firstOrNull()
        val allRoots = if (activeRoot == null) {
            AssistsCore.getAccessibilityRootNodes(AssistsCore.NodeLookupScope.AllWindows)
        } else {
            emptyList()
        }
        val root = activeRoot
            ?: allRoots.firstOrNull { it.windowId == triggerWindowId }
            ?: triggerPackage?.let { expected ->
                allRoots.firstOrNull { it.packageName?.toString() == expected }
            }
            ?: return
        val activePackage = root.packageName?.toString()?.takeIf(String::isNotBlank)
            ?: return
        if (!shouldCapturePackage(activePackage, packageName)) return

        val pageText = root.collectReadableText()
        if (pageText.isBlank()) return
        val now = System.currentTimeMillis()
        val fingerprint = pageText.hashCode()
        if (shouldDebounceCapture(recentCaptures[activePackage], fingerprint, now)) return
        val result = withContext(Dispatchers.IO) { processor.process(pageText) }
        if (result.recognized) recentCaptures[activePackage] = RecentCapture(fingerprint, now)
    }

    override fun onInterrupt() {
        super.onInterrupt()
    }

    override fun onDestroy() {
        AssistsService.listeners.remove(listener)
        pendingCapture?.cancel()
        serviceScope.cancel()
        BillSyncServiceHealth.markServiceConnected(this, false)
        super.onDestroy()
    }
}

internal data class RecentCapture(val fingerprint: Int, val capturedAtMillis: Long)

internal fun isAutomaticCaptureEvent(eventType: Int): Boolean = eventType in AUTOMATIC_CAPTURE_EVENT_TYPES

internal fun shouldCapturePackage(eventPackage: String, ownPackage: String): Boolean =
    eventPackage.isNotBlank() && eventPackage != ownPackage

internal fun shouldScheduleCapture(pendingIsActive: Boolean): Boolean = !pendingIsActive

internal fun shouldDebounceCapture(
    previous: RecentCapture?,
    fingerprint: Int,
    nowMillis: Long
): Boolean = previous?.fingerprint == fingerprint &&
    nowMillis - previous.capturedAtMillis < CAPTURE_DEBOUNCE_MILLIS

@Suppress("CyclomaticComplexMethod")
internal fun AccessibilityNodeInfo.collectReadableText(): String {
    val lines = linkedSetOf<String>()
    var visitedNodes = 0
    var collectedCharacters = 0

    fun add(value: CharSequence?) {
        val line = value?.toString()?.trim()?.takeIf(String::isNotBlank) ?: return
        if (line in lines) return
        val addedCharacters = line.length + if (lines.isEmpty()) 0 else 1
        if (collectedCharacters + addedCharacters > MAX_CAPTURE_CHARACTERS) return
        lines += line
        collectedCharacters += addedCharacters
    }

    val pendingNodes = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
    pendingNodes.add(this to 0)
    while (pendingNodes.isNotEmpty() && visitedNodes < MAX_CAPTURE_NODES) {
        val (node, depth) = pendingNodes.removeFirst()
        visitedNodes += 1
        if (node.isPassword || node.isEditable) continue
        if (node.isVisibleToUser) {
            add(node.text)
            add(node.contentDescription)
            add(node.hintText)
            add(node.paneTitle)
            add(node.tooltipText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(node.stateDescription)
        }
        if (depth == MAX_CAPTURE_DEPTH || collectedCharacters >= MAX_CAPTURE_CHARACTERS) continue
        for (index in 0 until node.childCount) {
            if (visitedNodes + pendingNodes.size >= MAX_CAPTURE_NODES) break
            node.getChild(index)?.let { pendingNodes.add(it to depth + 1) }
        }
    }

    return lines.joinToString("\n")
}

private val AUTOMATIC_CAPTURE_EVENT_TYPES = setOf(
    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
    AccessibilityEvent.TYPE_WINDOWS_CHANGED
)
internal const val CAPTURE_SETTLE_MILLIS = 500L
internal const val CAPTURE_DEBOUNCE_MILLIS = 30_000L
internal const val MAX_CAPTURE_NODES = 512
internal const val MAX_CAPTURE_DEPTH = 24
internal const val MAX_CAPTURE_CHARACTERS = 16 * 1024
