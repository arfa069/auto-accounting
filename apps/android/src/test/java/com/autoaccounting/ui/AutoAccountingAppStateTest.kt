package com.autoaccounting.ui

import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.autoaccounting.AppTab
import com.autoaccounting.feature.profile.ProfileDestination
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoAccountingAppStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun navigationStateSurvivesRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        val state = AtomicReference<AutoAccountingAppState>()
        restorationTester.setContent {
            state.set(rememberAutoAccountingAppState())
        }

        composeRule.runOnIdle {
            state.get().selectedTab.value = AppTab.Reports
            state.get().manualEntryOpen.value = true
            state.get().profileDestination.value = ProfileDestination.AccountManagement
        }

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle {
            assertEquals(AppTab.Reports, state.get().selectedTab.value)
            assertTrue(state.get().manualEntryOpen.value)
            assertEquals(ProfileDestination.AccountManagement, state.get().profileDestination.value)
        }
    }
}
