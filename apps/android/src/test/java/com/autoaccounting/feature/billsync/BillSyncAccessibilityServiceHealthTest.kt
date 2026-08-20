package com.autoaccounting.feature.billsync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
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

        controller.destroy()
        assertFalse(BillSyncServiceHealth.isServiceConnected(context))
    }
}
