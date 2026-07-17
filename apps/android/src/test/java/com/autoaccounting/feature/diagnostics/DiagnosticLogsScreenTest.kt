package com.autoaccounting.feature.diagnostics

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticLogsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sensitivePayloadIsMaskedUntilConfirmedAndCanBeMaskedAgain() {
        val repository = FakeDiagnosticRepository(initialEnabled = true)
        composeRule.setContent {
            DiagnosticLogsScreen(
                isDebugBuild = true,
                onBack = {},
                repositoryOverride = repository,
                applySecureWindowFlag = false
            )
        }

        val eventList = composeRule.onNodeWithTag("diagnostic-event-list")
        eventList.performScrollToIndex(EVENT_ITEM_INDEX)
        composeRule.onNodeWithText("payment_notification_parsed", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("敏感内容：••••••").assertIsDisplayed()
        composeRule.onNodeWithText("测试商户秘密").assertDoesNotExist()

        eventList.performScrollToIndex(ACTIONS_ITEM_INDEX)
        composeRule.onNodeWithText("显示敏感内容").performClick()
        composeRule.onNodeWithText("显示").performClick()

        eventList.performScrollToIndex(EVENT_ITEM_INDEX)
        composeRule.onNodeWithText("Merchant: 测试商户秘密").assertIsDisplayed()
        eventList.performScrollToIndex(ACTIONS_ITEM_INDEX)
        composeRule.onNodeWithText("遮罩内容").performClick()
        eventList.performScrollToIndex(EVENT_ITEM_INDEX)
        composeRule.onNodeWithText("测试商户秘密", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("敏感内容：••••••").assertIsDisplayed()
    }

    @Test
    fun releaseEnableRequiresExplicitConfirmation() {
        val repository = FakeDiagnosticRepository(initialEnabled = false)
        composeRule.setContent {
            DiagnosticLogsScreen(
                isDebugBuild = false,
                onBack = {},
                repositoryOverride = repository,
                applySecureWindowFlag = false
            )
        }

        composeRule.onNodeWithTag("diagnostic-enabled-switch").performClick()
        composeRule.onNodeWithText("开启敏感诊断日志？").assertIsDisplayed()
        assertEquals(false, repository.enabled.value)

        composeRule.onNodeWithText("理解并开启").performClick()
        assertEquals(true, repository.enabled.value)
        assertTrue(repository.lastUserConfirmed)
    }

    @Test
    fun backgroundingRemasksSensitivePayload() {
        val repository = FakeDiagnosticRepository(initialEnabled = true)
        composeRule.setContent {
            DiagnosticLogsScreen(
                isDebugBuild = true,
                onBack = {},
                repositoryOverride = repository,
                applySecureWindowFlag = false
            )
        }
        val eventList = composeRule.onNodeWithTag("diagnostic-event-list")
        eventList.performScrollToIndex(ACTIONS_ITEM_INDEX)
        composeRule.onNodeWithText("显示敏感内容").performClick()
        composeRule.onNodeWithText("显示").performClick()
        eventList.performScrollToIndex(EVENT_ITEM_INDEX)
        composeRule.onNodeWithText("Merchant: 测试商户秘密").assertIsDisplayed()

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()

        eventList.performScrollToIndex(EVENT_ITEM_INDEX)
        composeRule.onNodeWithText("测试商户秘密", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("敏感内容：••••••").assertIsDisplayed()
    }

    private companion object {
        const val ACTIONS_ITEM_INDEX = 4
        const val EVENT_ITEM_INDEX = 8
    }
}

private class FakeDiagnosticRepository(initialEnabled: Boolean) : DiagnosticLogRepository {
    private val mutableEnabled = MutableStateFlow(initialEnabled)
    private val mutableEvents = MutableStateFlow(
        listOf(
            DiagnosticEvent(
                metadata = DiagnosticEventMetadata(
                    timestampEpochMillis = 1L,
                    level = DiagnosticLevel.Info,
                    component = DiagnosticComponent.NotificationParser,
                    event = "payment_notification_parsed",
                    traceId = "trace-ui",
                    source = DiagnosticSource.Alipay,
                    outcome = "success",
                    reason = "parsed"
                ),
                sensitivePayload = DiagnosticSensitivePayload(
                    mapOf(DiagnosticSensitiveField.Merchant to "测试商户秘密")
                )
            )
        )
    )
    private val mutableStats = MutableStateFlow(DiagnosticLogStats(eventCount = 1))
    var lastUserConfirmed = false

    override val enabled: StateFlow<Boolean> = mutableEnabled
    override val events: StateFlow<List<DiagnosticEvent>> = mutableEvents
    override val stats: StateFlow<DiagnosticLogStats> = mutableStats

    override fun setEnabled(enabled: Boolean, userConfirmed: Boolean): Boolean {
        lastUserConfirmed = userConfirmed
        mutableEnabled.value = enabled
        return true
    }

    override fun record(event: DiagnosticEvent) = Unit
    override suspend fun refresh(limit: Int) = Unit
    override suspend fun clear(keepEnabledPreference: Boolean) {
        mutableEvents.value = emptyList()
        mutableStats.value = DiagnosticLogStats()
    }

    override suspend fun exportEncrypted(passphrase: CharArray): String = "encrypted"
}
