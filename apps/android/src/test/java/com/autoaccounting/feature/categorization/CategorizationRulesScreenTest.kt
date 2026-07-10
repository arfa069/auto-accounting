package com.autoaccounting.feature.categorization

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.autoaccounting.feature.account.AccountDeletionUiState
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.monitoring.ContinuousMonitoringPermissionHealth
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
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
    fun userCanDeleteRule() {
        var updatedRules: List<CategorizationRule>? = null
        composeRule.setContent {
            CategorizationRulesScreen(
                rules = listOf(
                    CategorizationRule(
                        id = "rule-delete",
                        merchantContains = "coffee",
                        category = "food"
                    )
                ),
                onRulesChange = { updatedRules = it }
            )
        }

        composeRule.onNodeWithTag("delete-rule-rule-delete").performClick()

        assertTrue(updatedRules?.isEmpty() == true)
    }

    @Test
    fun profileCanShowNotificationPermissionItem() {
        var settingsOpened = false
        composeRule.setContent {
            CategorizationRulesScreen(
                showPermissionCenter = true,
                notificationListenerAccessGranted = true,
                onOpenNotificationListenerSettings = { settingsOpened = true }
            )
        }

        composeRule.onNodeWithText("权限中心").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("通知监听").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("用于识别微信、支付宝的收付款通知，生成待确认账目。")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("当前状态：已授权").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("notification-listener-settings").performScrollTo().performClick()
        assertTrue(settingsOpened)
    }

    @Test
    fun profileCanRequestBookkeepingResultNotifications() {
        var permissionRequested = false
        composeRule.setContent {
            CategorizationRulesScreen(
                showPermissionCenter = true,
                resultNotificationPermissionGranted = false,
                onRequestResultNotificationPermission = { permissionRequested = true }
            )
        }

        composeRule.onNodeWithText("记账结果通知").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("result-notification-permission")
            .performScrollTo()
            .performClick()

        assertTrue(permissionRequested)
    }

    @Test
    fun profileShowsBillSyncAccessibilityStateAndSettingsLink() {
        var settingsOpened = false
        composeRule.setContent {
            CategorizationRulesScreen(
                showPermissionCenter = true,
                billSyncAccessibilityAccessGranted = true,
                onOpenBillSyncAccessibilitySettings = { settingsOpened = true }
            )
        }

        composeRule.onNodeWithText("账单同步与监控权限").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("bill-sync-accessibility-settings")
            .performScrollTo()
            .performClick()

        assertTrue(settingsOpened)
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
        var importedBackup: String? = null

        val context = RuntimeEnvironment.getApplication()
        val uri = android.net.Uri.parse("content://test/backup.bak")
        val backupContent = "backup-1"
        shadowOf(context.contentResolver).registerInputStream(
            uri,
            ByteArrayInputStream(backupContent.toByteArray(Charsets.UTF_8))
        )

        val testRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?
            ) {
                dispatchResult(requestCode, uri as O)
            }
        }
        val registryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = testRegistry
        }

        composeRule.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides registryOwner
            ) {
                CategorizationRulesScreen(
                    showPermissionCenter = true,
                    onExportEncryptedBackup = { "backup-1" },
                    onImportEncryptedBackup = { backup, _ -> importedBackup = backup },
                    onDeleteLocalData = { deleted = true }
                )
            }
        }

        composeRule.onNodeWithText("备份和导出").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("backup-passphrase")
            .performScrollTo()
            .performTextInput("test-passphrase")
        composeRule.onNodeWithText("导出 CSV").performScrollTo().performClick()
        composeRule.onNodeWithText("CSV 已生成").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("导出加密备份到文件").performScrollTo().performClick()
        // Wait for coroutine to finish
        composeRule.waitForIdle()

        composeRule.onNodeWithText("从文件导入备份").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(importedBackup == "backup-1")

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
    fun automaticCaptureCanBeEnabledAndDisabledWithoutManualBillSync() {
        var monitoringState = ContinuousMonitoringState()
        composeRule.setContent {
            CategorizationRulesScreen(
                showPermissionCenter = true,
                notificationListenerAccessGranted = true,
                billSyncAccessibilityAccessGranted = true,
                continuousMonitoringState = monitoringState,
                continuousMonitoringPermissionHealth = healthyPermissions,
                onContinuousMonitoringStateChange = { monitoringState = it }
            )
        }

        composeRule.onNodeWithText("自动记账").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("开启自动记账").performScrollTo().performClick()
        assertTrue(monitoringState.enabled)

        composeRule.onNodeWithText("关闭自动记账").performScrollTo().performClick()
        assertTrue(!monitoringState.enabled)
    }

    @Test
    fun automaticCaptureRequiresAccessibilityAndShowsRomGuidance() {
        var accessibilitySettingsOpened = false
        var monitoringState = ContinuousMonitoringState()
        composeRule.setContent {
            CategorizationRulesScreen(
                showPermissionCenter = true,
                continuousMonitoringState = monitoringState,
                continuousMonitoringPermissionHealth = ContinuousMonitoringPermissionHealth(
                    billSyncAccessibilityGranted = false
                ),
                onContinuousMonitoringStateChange = { monitoringState = it },
                onOpenBillSyncAccessibilitySettings = { accessibilitySettingsOpened = true }
            )
        }

        composeRule.onNodeWithText("自动记账").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("只处理支付结果和支付记录，不处理聊天、普通消息、付款发起或转账发送。")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("后台保活和自启动受手机系统限制，本应用只提示你检查，不保证一定可靠。")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("当前状态：需要开启自动记账无障碍权限")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("开启自动记账").performScrollTo().assertIsNotEnabled()

        composeRule.onNodeWithTag("continuous-monitoring-accessibility-settings")
            .performScrollTo()
            .performClick()

        assertTrue(accessibilitySettingsOpened)
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

    private companion object {
        val healthyPermissions = ContinuousMonitoringPermissionHealth(
            billSyncAccessibilityGranted = true
        )
    }
}
