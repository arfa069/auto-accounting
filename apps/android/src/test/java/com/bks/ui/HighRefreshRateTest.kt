package com.bks.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HighRefreshRateTest {
    @Test
    fun selects120HzWhenItIsSupported() {
        assertEquals(
            120.00001f,
            requireNotNull(selectPreferredRefreshRate(listOf(60.000004f, 90f, 120.00001f))),
            0f
        )
    }

    @Test
    fun selects90HzWhen120HzIsUnavailable() {
        assertEquals(90f, requireNotNull(selectPreferredRefreshRate(listOf(60f, 90f))), 0f)
    }

    @Test
    fun selects60HzOn60HzOnlyDevices() {
        assertEquals(60f, requireNotNull(selectPreferredRefreshRate(listOf(60f))), 0f)
    }

    @Test
    fun ignoresInvalidRatesAndReturnsNullWhenNoneAreUsable() {
        assertNull(
            selectPreferredRefreshRate(
                listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)
            )
        )
    }

    @Test
    fun returnsNullForInvalidDesiredRate() {
        assertNull(selectPreferredRefreshRate(listOf(60f, 120f), Float.NaN))
        assertNull(selectPreferredRefreshRate(listOf(60f, 120f), 0f))
    }

    @Test
    fun returnsNullWhenAllSupportedRatesExceedTarget() {
        assertNull(selectPreferredRefreshRate(listOf(144f, 240f)))
    }

    @Test
    @Config(sdk = [34])
    fun missingCapabilityLeavesWindowPreferenceUntouched() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val originalPreference = activity.window.attributes.preferredRefreshRate

        activity.applyRefreshRatePreference(null)

        assertEquals(originalPreference, activity.window.attributes.preferredRefreshRate, 0f)
    }

    @Test
    @Config(sdk = [34])
    fun requestsResolvedRateFromWindowOnOlderAndroidVersions() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        activity.applyRefreshRatePreference(90f)

        assertEquals(
            90f,
            activity.window.attributes.preferredRefreshRate,
            0f
        )
    }

    @Test
    @Config(sdk = [35])
    fun requestsResolvedRateFromEntireContentTreeOnAndroid15() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val child = FrameLayout(activity).apply {
            addView(View(activity))
        }
        activity.setContentView(child)

        activity.applyRefreshRatePreference(90f)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content)
        assertEquals(90f, contentRoot.requestedFrameRate, 0f)
        assertEquals(90f, child.requestedFrameRate, 0f)
        assertEquals(90f, child.getChildAt(0).requestedFrameRate, 0f)
    }
}
