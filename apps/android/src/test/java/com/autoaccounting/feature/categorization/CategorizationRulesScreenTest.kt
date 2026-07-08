package com.autoaccounting.feature.categorization

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.autoaccounting.feature.account.AccountDeletionUiState
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import com.autoaccounting.feature.settings.LocalDataSnapshot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CategorizationRulesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun userCanCreateAndEditRule() {
        composeRule.setContent {
            CategorizationRulesScreen()
        }

        composeRule.onNodeWithText("分类规则").assertIsDisplayed()
        composeRule.onNodeWithText("新建规则").performClick()
        composeRule.onNodeWithText("商户包含").performTextInput("星巴克")
        composeRule.onNodeWithText("标题关键词").performTextInput("拿铁")
        composeRule.onNodeWithText("来源").performTextInput("微信")
        composeRule.onNodeWithText("交易类型").performTextInput("支出")
        composeRule.onNodeWithText("分类").performTextInput("餐饮")
        composeRule.onNodeWithText("保存规则").performClick()

        composeRule.onNodeWithText("星巴克").assertIsDisplayed()
        composeRule.onNodeWithText("餐饮").assertIsDisplayed()

        composeRule.onNodeWithText("编辑").performClick()
        composeRule.onNodeWithText("分类").performTextClearance()
        composeRule.onNodeWithText("分类").performTextInput("工作餐")
        composeRule.onNodeWithText("保存规则").performClick()

        composeRule.onNodeWithText("工作餐").assertIsDisplayed()
    }

    @Test
    fun profileCanShowNotificationPermissionItem() {
        composeRule.setContent {
            CategorizationRulesScreen(showPermissionCenter = true)
        }

        composeRule.onNodeWithText("权限中心").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("通知监听").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("用于识别微信、支付宝的收付款通知，生成待确认账目。")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun profileCanToggleCloudAiConsent() {
        var settings = AiCategorizationSettings()
        composeRule.setContent {
            CategorizationRulesScreen(
                showPermissionCenter = true,
                aiSettings = settings,
                onAiSettingsChange = { settings = it }
            )
        }

        composeRule.onNodeWithText("云端 AI 分类").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("开启云端 AI").performScrollTo().performClick()
        assertTrue(settings.aiConsentGranted)

        composeRule.onNodeWithText("提供更多上下文").performScrollTo().performClick()
        assertTrue(settings.enhancedContextGranted)
    }

    @Test
    fun profileCanExportBackupAndConfirmLocalDataDeletion() {
        var deleted = false
        var restored: LocalDataSnapshot? = null
        composeRule.setContent {
            CategorizationRulesScreen(
                showPermissionCenter = true,
                onRestoreLocalData = { restored = it },
                onDeleteLocalData = { deleted = true }
            )
        }

        composeRule.onNodeWithText("备份和导出").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("导出 CSV").performScrollTo().performClick()
        composeRule.onNodeWithText("CSV 已生成").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("导出加密备份").performScrollTo().performClick()
        composeRule.onNodeWithText("加密备份已生成").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("导入加密备份").performScrollTo().performClick()
        assertTrue(restored != null)

        composeRule.onNodeWithText("删除本机数据").performScrollTo().performClick()
        composeRule.onNodeWithText("确认删除前请先导出加密备份。").assertIsDisplayed()
        composeRule.onNodeWithText("我已了解并完成需要的备份").performClick()
        composeRule.onNodeWithText("输入 删除本机数据").performTextInput("删除本机数据")
        composeRule.onNodeWithText("确认删除").performClick()

        assertTrue(deleted)
    }

    @Test
    fun profileCanRequestAndCancelAccountDeletion() {
        var deletionState = AccountDeletionUiState()
        composeRule.setContent {
            CategorizationRulesScreen(
                showPermissionCenter = true,
                accountSession = AccountSession.SignedIn("13800138000", "token-1"),
                accountDeletionState = deletionState,
                onAccountDeletionStateChange = { deletionState = it }
            )
        }

        composeRule.onNodeWithText("账号注销").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("申请注销账号").performScrollTo().performClick()

        assertTrue(deletionState.isPending)
        composeRule.onNodeWithText("注销冷静期中").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("云端 AI 和设备配置写入已暂停").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("取消注销").performScrollTo().performClick()

        assertTrue(deletionState.cloudWritesAllowed)
    }

    @Test
    fun complianceMaterialsAreReachableAfterLoginFromProfile() {
        composeRule.setContent {
            CategorizationRulesScreen(showPermissionCenter = true)
        }

        composeRule.onNodeWithText("关于与合规").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("隐私与合规材料").performScrollTo().performClick()

        composeRule.onNodeWithText("隐私政策").assertIsDisplayed()
        composeRule.onNodeWithText("第三方服务清单").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun advancedMonitoringCanBeEnabledAndDisabledFromProfileAfterBillSync() {
        var monitoringState = ContinuousMonitoringState(billSyncCompleted = true)
        composeRule.setContent {
            CategorizationRulesScreen(
                showPermissionCenter = true,
                continuousMonitoringState = monitoringState,
                onContinuousMonitoringStateChange = { monitoringState = it }
            )
        }

        composeRule.onNodeWithText("高级监控").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("开启连续监控").performScrollTo().performClick()
        assertTrue(monitoringState.enabled)

        composeRule.onNodeWithText("关闭连续监控").performScrollTo().performClick()
        assertTrue(!monitoringState.enabled)
    }

    @Test
    fun internalBetaReadinessIsReachableFromProfile() {
        composeRule.setContent {
            CategorizationRulesScreen(showPermissionCenter = true)
        }

        composeRule.onNodeWithText("内测准备").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("查看内测检查").performScrollTo().performClick()

        composeRule.onNodeWithText("内测准备检查").assertIsDisplayed()
        composeRule.onNodeWithText("Android 10 baseline").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("无密钥入库").performScrollTo().assertIsDisplayed()
    }
}
