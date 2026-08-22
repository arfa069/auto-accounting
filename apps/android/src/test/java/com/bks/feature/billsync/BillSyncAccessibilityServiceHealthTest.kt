package com.bks.feature.billsync

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
        assertEquals(
            BillSyncAccessibilityService::class.java,
            BillSyncAccessibilityService::class.java
                .getMethod("onAccessibilityEvent", AccessibilityEvent::class.java)
                .declaringClass
        )

        service.onUnbind(Intent())
        assertFalse(BillSyncServiceHealth.isServiceConnected(context))

        controller.destroy()
        assertFalse(BillSyncServiceHealth.isServiceConnected(context))
    }

    @Test
    fun eventRootSelectionDoesNotFallBackToTheUnrelatedActiveWindow() {
        val unrelatedActiveRoot = node(packageName = "com.miui.home")
        val paymentRoot = node(packageName = "com.example.pay")

        val selected = selectEventRoot(
            roots = listOf(unrelatedActiveRoot, paymentRoot),
            eventPackage = "com.example.pay",
            eventWindowId = -1
        )

        assertSame(paymentRoot, selected)
    }

    @Test
    fun acceptedWindowIsReleasedWhenThePageIsNoLongerRecognized() {
        val memory = AcceptedWindowMemory()

        assertTrue(memory.acceptIfNew("com.example.pay", windowId = 7))
        assertFalse(memory.acceptIfNew("com.example.pay", windowId = 7))

        memory.release("com.example.pay", windowId = 7)

        assertTrue(memory.acceptIfNew("com.example.pay", windowId = 7))
    }

    @Test
    fun readableTextSkipsSensitiveNodesAndHonorsTraversalLimits() {
        val root = node("root")
        shadowOf(root).addChild(node("safe"))
        shadowOf(root).addChild(node("password").apply { isPassword = true })
        shadowOf(root).addChild(node("editable").apply { isEditable = true })
        val hiddenContainer = node("hidden").apply { isVisibleToUser = false }
        shadowOf(hiddenContainer).addChild(node("visible descendant"))
        shadowOf(root).addChild(hiddenContainer)
        shadowOf(root).addChild(node().apply { stateDescription = "semantic state" })

        val text = root.collectReadableText()

        assertEquals("root\nsafe\nsemantic state\nvisible descendant", text)

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

        val breadthFirstRoot = node()
        val denseFirstBranch = node()
        repeat(MAX_CAPTURE_NODES) { shadowOf(denseFirstBranch).addChild(node("decoy-$it")) }
        shadowOf(breadthFirstRoot).addChild(denseFirstBranch)
        shadowOf(breadthFirstRoot).addChild(node().apply { contentDescription = "支付成功￥4.99" })
        assertTrue(breadthFirstRoot.collectReadableText().contains("支付成功￥4.99"))
    }

    @Suppress("DEPRECATION")
    private fun node(
        text: String = "",
        packageName: String? = null
    ): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        isVisibleToUser = true
        this.text = text
        this.packageName = packageName
    }
}
