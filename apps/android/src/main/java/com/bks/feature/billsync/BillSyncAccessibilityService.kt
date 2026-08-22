package com.bks.feature.billsync

import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bks.BuildConfig
import com.bks.data.local.BksDatabaseProvider
import com.bks.data.local.LocalLedgerRepository
import com.bks.data.local.LocalPreferencesRepository
import com.bks.feature.review.ReviewQueuePersistence
import com.ven.assists.AssistsCore
import com.ven.assists.service.AssistsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BillSyncAccessibilityService : AssistsService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val acceptedWindows = AcceptedWindowMemory()
    private var automaticBookkeepingEnabled = false
    private val database by lazy { BksDatabaseProvider.get(this) }
    private val preferencesRepository by lazy { LocalPreferencesRepository(database) }
    private val processor by lazy {
        BillSyncCaptureProcessor(
            pipeline = BillSyncPipeline(),
            reviewQueuePersistence = ReviewQueuePersistence(LocalLedgerRepository(database)),
            preferencesRepository = preferencesRepository
        )
    }

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            preferencesRepository.userPreferences.collect { preferences ->
                automaticBookkeepingEnabled = preferences.automaticBookkeepingEnabled
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        BillSyncServiceHealth.markServiceConnected(this, true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        super.onAccessibilityEvent(event)
        try {
            captureCurrentWindow(event)
        } catch (error: Throwable) {
            captureFailed(event.packageName?.toString(), event.windowId, error)
        }
    }

    private fun captureCurrentWindow(event: AccessibilityEvent) {
        captureEvent(event)
        val eventPackage = event.packageName?.toString()?.takeIf(String::isNotBlank)
        captureStarted(eventPackage, event.windowId, automaticBookkeepingEnabled)
        if (!automaticBookkeepingEnabled || eventPackage == packageName) return

        val allRoots = AssistsCore.getAccessibilityRootNodes(AssistsCore.NodeLookupScope.AllWindows)
        val eventRoot = selectEventRoot(allRoots, eventPackage, event.windowId)
        val activeRoots = if (eventRoot == null) {
            AssistsCore.getAccessibilityRootNodes(AssistsCore.NodeLookupScope.ActiveWindow)
        } else {
            emptyList()
        }
        val root = eventRoot ?: activeRoots.firstOrNull()
        captureRoots(allRoots, activeRoots, root, eventPackage, event.windowId)
        if (root == null) {
            captureIgnored("root_missing")
            return
        }
        val activePackage = root.packageName?.toString().orEmpty()
        if (activePackage == packageName) {
            captureIgnored("own_package", "package=$activePackage")
            return
        }

        val pageText = root.collectReadableText()
        captureCollected(activePackage, root.windowId, pageText)
        val recognized = processor.recognize(pageText)
        if (!recognized.recognized) {
            acceptedWindows.release(activePackage, root.windowId)
            captureProcessed(recognized = false, createdEntries = 0)
            return
        }
        if (!acceptedWindows.acceptIfNew(activePackage, root.windowId)) {
            captureIgnored(
                "already_captured_window",
                "package=$activePackage window=${root.windowId}"
            )
            return
        }
        serviceScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { processor.persist(recognized) }
                captureProcessed(recognized = true, createdEntries = result.createdEntries.size)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                acceptedWindows.release(activePackage, root.windowId)
                captureFailed(activePackage, root.windowId, error)
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        BillSyncServiceHealth.markServiceConnected(this, false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        BillSyncServiceHealth.markServiceConnected(this, false)
        super.onDestroy()
    }
}

internal fun selectEventRoot(
    roots: List<AccessibilityNodeInfo>,
    eventPackage: String?,
    eventWindowId: Int
): AccessibilityNodeInfo? = roots
    .firstOrNull { eventWindowId >= 0 && it.windowId == eventWindowId }
    ?: eventPackage?.let { expected ->
        roots.firstOrNull { it.packageName?.toString() == expected }
    }

internal class AcceptedWindowMemory {
    private val accepted = mutableSetOf<Pair<String, Int>>()

    fun acceptIfNew(packageName: String, windowId: Int): Boolean = accepted.add(packageName to windowId)

    fun release(packageName: String, windowId: Int) {
        accepted.remove(packageName to windowId)
    }
}

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

internal const val MAX_CAPTURE_NODES = 512
internal const val MAX_CAPTURE_DEPTH = 24
internal const val MAX_CAPTURE_CHARACTERS = 16 * 1024

private fun captureEvent(event: AccessibilityEvent) = captureDebug {
    "event type=${event.eventType} package=${event.packageName} window=${event.windowId}"
}

private fun captureStarted(packageName: String?, windowId: Int, enabled: Boolean) = captureDebug {
    "capture triggerPackage=$packageName window=$windowId enabled=$enabled"
}

private fun captureRoots(
    allRoots: List<AccessibilityNodeInfo>,
    activeRoots: List<AccessibilityNodeInfo>,
    selectedRoot: AccessibilityNodeInfo?,
    triggerPackage: String?,
    triggerWindowId: Int
) = captureDebug {
    "roots all=${allRoots.rootSummary()} active=${activeRoots.rootSummary()} " +
        "selected=${selectedRoot?.let { "${it.packageName}/${it.windowId}" }} " +
        "triggerPackage=$triggerPackage triggerWindow=$triggerWindowId"
}

private fun captureIgnored(reason: String, details: String? = null) = captureDebug {
    "ignored reason=$reason${details?.let { " $it" }.orEmpty()}"
}

private fun captureCollected(packageName: String, windowId: Int, pageText: String) {
    captureDebug {
        "collected package=$packageName window=$windowId lines=${pageText.lineSequence().count()} " +
            "chars=${pageText.length}"
    }
    captureDebugPageText(pageText)
}

private fun captureProcessed(recognized: Boolean, createdEntries: Int) = captureDebug {
    "processed recognized=$recognized created=$createdEntries"
}

private inline fun captureDebug(message: () -> String) {
    if (BuildConfig.DEBUG && Log.isLoggable(CAPTURE_DEBUG_TAG, Log.DEBUG)) {
        Log.d(CAPTURE_DEBUG_TAG, message())
    }
}

private fun captureDebugPageText(pageText: String) {
    if (!BuildConfig.DEBUG || !Log.isLoggable(CAPTURE_DEBUG_TAG, Log.DEBUG)) return
    val chunks = pageText.ifEmpty { "<blank>" }.chunked(CAPTURE_DEBUG_CHUNK_SIZE)
    chunks.forEachIndexed { index, chunk ->
        Log.d(CAPTURE_DEBUG_TAG, "page ${index + 1}/${chunks.size}:\n$chunk")
    }
}

private fun captureFailed(packageName: String?, windowId: Int, error: Throwable) {
    if (BuildConfig.DEBUG) {
        Log.e(CAPTURE_DEBUG_TAG, "failed package=$packageName window=$windowId", error)
    }
}

private fun List<AccessibilityNodeInfo>.rootSummary(): String =
    joinToString(prefix = "[", postfix = "]") { root ->
        "${root.packageName}/${root.windowId}/${root.className}"
    }

private const val CAPTURE_DEBUG_TAG = "BillSyncCapture"
private const val CAPTURE_DEBUG_CHUNK_SIZE = 3_000
