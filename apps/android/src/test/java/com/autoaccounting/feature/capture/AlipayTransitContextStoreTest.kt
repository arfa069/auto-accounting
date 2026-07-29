package com.autoaccounting.feature.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlipayTransitContextStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferencesName = "alipay-transit-test-${System.nanoTime()}"
    private lateinit var store: SharedPreferencesAlipayTransitContextStore

    @Before
    fun setUp() {
        store = SharedPreferencesAlipayTransitContextStore(context, preferencesName)
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun contextPersistsAcrossStoreInstancesAndIsConsumedOnlyOnce() {
        store.record(NOW)
        val reopened = SharedPreferencesAlipayTransitContextStore(context, preferencesName)

        assertTrue(reopened.consumeForNotification(NOW + WINDOW_MILLIS - 1))
        assertFalse(reopened.consumeForNotification(NOW + WINDOW_MILLIS - 1))
    }

    @Test
    fun exactFiveMinuteBoundaryIsExcluded() {
        store.record(NOW)

        assertFalse(store.consumeForNotification(NOW + WINDOW_MILLIS))
    }

    @Test
    fun repeatedPageCaptureDoesNotExtendTheOriginalWindow() {
        store.record(NOW)
        store.record(NOW + WINDOW_MILLIS - 1)

        assertFalse(store.consumeForNotification(NOW + WINDOW_MILLIS))
    }

    @Test
    fun repeatedPageCaptureCannotReactivateConsumedContext() {
        store.record(NOW)
        assertTrue(store.consumeForNotification(NOW + 1))

        store.record(NOW + 2)

        assertFalse(store.consumeForNotification(NOW + 3))
    }

    private companion object {
        const val NOW = 1_783_468_800_000L
        const val WINDOW_MILLIS = 5 * 60_000L
    }
}
