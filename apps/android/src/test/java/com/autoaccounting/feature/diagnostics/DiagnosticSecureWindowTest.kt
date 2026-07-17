package com.autoaccounting.feature.diagnostics

import android.view.WindowManager
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticSecureWindowTest {
    @Test
    fun sensitiveWindowFlagCanBeAppliedAndCleared() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        setDiagnosticSecureFlag(activity, true)
        assertTrue(
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        )

        setDiagnosticSecureFlag(activity, false)
        assertEquals(
            0,
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE
        )
    }
}
