package com.autoaccounting.feature.settings

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataAndBackupScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun validBackupRequiresConfirmationBeforeRestore() {
        var validated = false
        var imported = false
        setContentWithBackupFile("backup") {
            DataAndBackupScreen(
                ledgerEntries = emptyList(),
                onExportEncryptedBackup = { "backup" },
                onValidateEncryptedBackup = { backup, passphrase ->
                    assertEquals("backup", backup)
                    assertEquals("secret", passphrase)
                    validated = true
                },
                onImportEncryptedBackup = { _, _ -> imported = true },
                onDeleteLocalData = {},
                onBack = {}
            )
        }

        composeRule.onNodeWithTag("backup-passphrase").performTextInput("secret")
        composeRule.onNodeWithText("从文件导入备份").performClick()
        composeRule.waitForIdle()

        assertTrue(validated)
        assertFalse(imported)
        composeRule.onNodeWithText("将替换本机现有数据", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("确认替换并恢复").performClick()
        composeRule.waitForIdle()
        assertTrue(imported)
    }

    @Test
    fun failedValidationDoesNotOfferRestore() {
        var imported = false
        setContentWithBackupFile("broken") {
            DataAndBackupScreen(
                ledgerEntries = emptyList(),
                onExportEncryptedBackup = { "backup" },
                onValidateEncryptedBackup = { _, _ -> error("invalid") },
                onImportEncryptedBackup = { _, _ -> imported = true },
                onDeleteLocalData = {},
                onBack = {}
            )
        }

        composeRule.onNodeWithTag("backup-passphrase").performTextInput("secret")
        composeRule.onNodeWithText("从文件导入备份").performClick()
        composeRule.waitForIdle()

        assertFalse(imported)
        composeRule.onNodeWithText("确认替换并恢复").assertDoesNotExist()
    }

    @Test
    fun cancellingValidatedRestoreKeepsImportCallbackUntouched() {
        var imported = false
        setContentWithBackupFile("backup") {
            DataAndBackupScreen(
                ledgerEntries = emptyList(),
                onExportEncryptedBackup = { "backup" },
                onValidateEncryptedBackup = { _, _ -> },
                onImportEncryptedBackup = { _, _ -> imported = true },
                onDeleteLocalData = {},
                onBack = {}
            )
        }

        composeRule.onNodeWithTag("backup-passphrase").performTextInput("secret")
        composeRule.onNodeWithText("从文件导入备份").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("取消").performClick()

        assertFalse(imported)
        composeRule.onNodeWithText("确认替换并恢复").assertDoesNotExist()
    }

    @Test
    fun deleteRemainsInDangerZoneAndRequiresBothConfirmations() {
        var deleted = false
        composeRule.setContent {
            DataAndBackupScreen(
                ledgerEntries = emptyList(),
                onExportEncryptedBackup = { "backup" },
                onValidateEncryptedBackup = { _, _ -> },
                onImportEncryptedBackup = { _, _ -> },
                onDeleteLocalData = { deleted = true },
                onBack = {}
            )
        }

        composeRule.onNodeWithText("危险区").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("删除本机数据").performScrollTo().performClick()
        composeRule.onNodeWithText("确认删除").assertIsNotEnabled()
        composeRule.onNodeWithText("我已了解并完成需要的备份").performClick()
        composeRule.onNodeWithText("输入 删除本机数据").performTextInput("删除本机数据")
        composeRule.onNodeWithText("确认删除").performClick()

        assertTrue(deleted)
    }

    private fun setContentWithBackupFile(content: String, screen: @Composable () -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://test/backup.bak")
        shadowOf(context.contentResolver).registerInputStream(
            uri,
            ByteArrayInputStream(content.toByteArray())
        )
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?
            ) {
                @Suppress("UNCHECKED_CAST")
                dispatchResult(requestCode, uri as O)
            }
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                screen()
            }
        }
    }
}
