package com.bks.feature.diagnostics

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.awaitCancellation
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
        
        composeRule.waitUntil(timeoutMillis = 3_000L) {
            composeRule.onAllNodesWithText("测试商户秘密", substring = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }

        eventList.performScrollToIndex(EVENT_ITEM_INDEX)
        composeRule.onNodeWithText("敏感内容：••••••").assertIsDisplayed()
    }

    @Test
    fun queryAndFiltersSurviveRestoration() {
        val repository = FakeDiagnosticRepository(initialEnabled = true)
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            DiagnosticLogsScreen(
                isDebugBuild = true,
                onBack = {},
                repositoryOverride = repository,
                applySecureWindowFlag = false
            )
        }

        composeRule.onNodeWithText("筛选事件、原因、traceId / sessionId")
            .performScrollTo()
            .performTextInput("parsed")
        composeRule.onNodeWithText("Info").performScrollTo().performClick()
        composeRule.onNodeWithTag("diagnostic-event-list").performScrollToIndex(COMPONENT_FILTER_INDEX)
        composeRule.onNodeWithText("NotificationParser").performScrollTo().performClick()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("筛选事件、原因、traceId / sessionId")
            .performScrollTo()
            .assertTextContains("parsed")
        composeRule.onNodeWithText("Info").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("diagnostic-event-list").performScrollToIndex(COMPONENT_FILTER_INDEX)
        composeRule.onNodeWithText("NotificationParser").performScrollTo().assertIsSelected()
        composeRule.onNodeWithText("payment_notification_parsed", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun exportShowsProgressAndCanBeCancelled() {
        val repository = FakeDiagnosticRepository(initialEnabled = true).apply {
            blockExport = true
        }
        composeRule.setContent {
            DiagnosticLogsScreen(
                isDebugBuild = true,
                onBack = {},
                repositoryOverride = repository,
                applySecureWindowFlag = false
            )
        }

        startExport(repository)

        composeRule.onNodeWithTag("diagnostic-export-confirm").assertIsNotEnabled()
        composeRule.onNodeWithText("正在导出…").assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostic-export-cancel").performClick()
        composeRule.onNodeWithText("导出已取消").assertIsDisplayed()
    }

    @Test
    fun completedExportShowsFileNameInVisibleDialog() {
        val repository = FakeDiagnosticRepository(initialEnabled = true)
        composeRule.setContent {
            DiagnosticLogsScreen(
                isDebugBuild = true,
                onBack = {},
                repositoryOverride = repository,
                applySecureWindowFlag = false,
                exportWriterOverride = { "test-diagnostics.aadiag" }
            )
        }

        startExport(repository)

        composeRule.onNodeWithText("导出完成").assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostic-export-result-message")
            .assertIsDisplayed()
    }

    @Test
    fun failedExportKeepsResultVisibleAfterPassphraseDialogCloses() {
        val repository = FakeDiagnosticRepository(initialEnabled = true).apply {
            failExport = true
        }
        composeRule.setContent {
            DiagnosticLogsScreen(
                isDebugBuild = true,
                onBack = {},
                repositoryOverride = repository,
                applySecureWindowFlag = false
            )
        }

        startExport(repository)

        composeRule.onNodeWithText("导出失败").assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostic-export-result-message")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostic-export-passphrase").assertDoesNotExist()
    }

    private fun startExport(repository: FakeDiagnosticRepository) {
        val eventList = composeRule.onNodeWithTag("diagnostic-event-list")
        eventList.performScrollToIndex(ACTIONS_ITEM_INDEX)
        composeRule.onNodeWithText("加密导出").performClick()
        composeRule.onNodeWithTag("diagnostic-export-passphrase").performTextInput("12345678")
        composeRule.onNodeWithTag("diagnostic-export-confirmation").performTextInput("12345678")
        composeRule.onNodeWithTag("diagnostic-export-confirm").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { repository.exportCallCount == 1 }
    }

    private companion object {
        const val ACTIONS_ITEM_INDEX = 4
        const val COMPONENT_FILTER_INDEX = 7
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
    @Volatile
    var blockExport = false
    @Volatile
    var failExport = false
    @Volatile
    var exportCallCount = 0

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

    override suspend fun exportEncrypted(passphrase: CharArray): String {
        exportCallCount += 1
        if (blockExport) awaitCancellation()
        if (failExport) error("export failed")
        return "encrypted"
    }
}
