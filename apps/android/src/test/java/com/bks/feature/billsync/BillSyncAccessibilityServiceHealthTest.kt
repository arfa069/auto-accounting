package com.bks.feature.billsync

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.ven.assists.service.AssistsService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BillSyncAccessibilityServiceHealthTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun serviceConnectionHealthTracksLifecycle() {
        BillSyncServiceHealth.markServiceConnected(context, false)
        val controller = Robolectric.buildService(BillSyncAccessibilityService::class.java)
            .create()
        val service = controller.get()

        BillSyncAccessibilityService::class.java
            .getDeclaredMethod("onServiceConnected")
            .apply { isAccessible = true }
            .invoke(service)
        service.onInterrupt()

        assertTrue(BillSyncServiceHealth.isServiceConnected(context))
        assertTrue(AssistsService.listeners.any { it === serviceListener(service) })

        controller.destroy()
        assertFalse(BillSyncServiceHealth.isServiceConnected(context))
        assertFalse(AssistsService.listeners.any { it === serviceListener(service) })
    }

    @Test
    fun capturePolicyFiltersEventsAndDebouncesSameSnapshot() {
        assertTrue(isAutomaticCaptureEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertTrue(isAutomaticCaptureEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertTrue(isAutomaticCaptureEvent(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
        assertFalse(isAutomaticCaptureEvent(AccessibilityEvent.TYPE_VIEW_CLICKED))
        assertFalse(shouldCapturePackage("com.bks", "com.bks"))
        assertFalse(shouldCapturePackage("", "com.bks"))
        assertTrue(shouldCapturePackage("com.example.pay", "com.bks"))

        val previous = RecentCapture(fingerprint = 42, capturedAtMillis = 1_000)
        assertTrue(shouldDebounceCapture(previous, fingerprint = 42, nowMillis = 1_500))
        assertFalse(
            shouldDebounceCapture(
                previous,
                fingerprint = 42,
                nowMillis = 1_000 + CAPTURE_DEBOUNCE_MILLIS
            )
        )
        assertFalse(shouldDebounceCapture(previous, fingerprint = 7, nowMillis = 1_500))
        assertEquals(500L, CAPTURE_SETTLE_MILLIS)
    }

    @Test
    fun readableTextSkipsSensitiveNodesAndHonorsTraversalLimits() {
        val root = node("root")
        shadowOf(root).addChild(node("safe"))
        shadowOf(root).addChild(node("password").apply { isPassword = true })
        shadowOf(root).addChild(node("editable").apply { isEditable = true })
        shadowOf(root).addChild(node("hidden").apply { isVisibleToUser = false })

        val text = root.collectReadableText()

        assertEquals("root\nsafe", text)

        val wideRoot = node("root")
        repeat(MAX_CAPTURE_NODES) { shadowOf(wideRoot).addChild(node("child-$it")) }
        assertEquals(MAX_CAPTURE_NODES, wideRoot.collectReadableText().lineSequence().count())

        val deepRoot = node("depth-0")
        var parent = deepRoot
        repeat(MAX_CAPTURE_DEPTH + 2) { index ->
            node("depth-${index + 1}").also {
                shadowOf(parent).addChild(it)
                parent = it
            }
        }
        val deepText = deepRoot.collectReadableText()
        assertTrue(deepText.contains("depth-$MAX_CAPTURE_DEPTH"))
        assertFalse(deepText.contains("depth-${MAX_CAPTURE_DEPTH + 1}"))

        val textRoot = node()
        repeat(40) { shadowOf(textRoot).addChild(node("$it-" + "x".repeat(1_000))) }
        assertTrue(textRoot.collectReadableText().length <= MAX_CAPTURE_CHARACTERS)
    }

    @Suppress("DEPRECATION")
    private fun node(text: String = ""): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        isVisibleToUser = true
        this.text = text
    }

    private fun serviceListener(service: BillSyncAccessibilityService): Any = requireNotNull(
        BillSyncAccessibilityService::class.java.getDeclaredField("listener")
            .apply { isAccessible = true }
            .get(service)
    )
}
