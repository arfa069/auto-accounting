package com.autoaccounting.feature.settings

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
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
    fun exportCopyDistinguishesCurrentLedgerCsvFromFullBackup() {
        composeRule.setContent {
            DataAndBackupScreen(
                ledgerEntries = emptyList(),
                currentLedgerName = "家庭账本",
                onExportEncryptedBackup = { "backup" },
                onValidateEncryptedBackup = { _, _ -> },
                onImportEncryptedBackup = { _, _ -> },
                onDeleteLocalData = {},
                onBack = {}
            )
        }

        composeRule.onNodeWithText("CSV 仅导出当前账本「家庭账本」", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("加密备份包含全部账本", substring = true)
            .assertIsDisplayed()
        composeRule.onAllNodesWithTag("backup-password-dialog-input").assertCountEquals(0)
        composeRule.onNodeWithText("导出加密备份").assertIsDisplayed()
        composeRule.onNodeWithText("导入加密备份").assertIsDisplayed()
    }

    @Test
    fun exportPromptsForPasswordAndRequiresMoreThanEightCharacters() {
        var exportedPassphrase: String? = null
        composeRule.setContent {
            DataAndBackupScreen(
                ledgerEntries = emptyList(),
                onExportEncryptedBackup = { passphrase ->
                    exportedPassphrase = passphrase
                    "backup"
                },
                onValidateEncryptedBackup = { _, _ -> },
                onImportEncryptedBackup = { _, _ -> },
                onDeleteLocalData = {},
                onBack = {}
            )
        }

        composeRule.onNodeWithText("导出加密备份").performClick()
        composeRule.onNodeWithTag("backup-password-dialog-input").performTextInput("12345678")
        composeRule.onNodeWithText("确认导出").assertIsNotEnabled()
        composeRule.onNodeWithTag("backup-password-dialog-input").performTextInput("9")
        composeRule.onNodeWithText("确认导出").assertIsEnabled().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { exportedPassphrase != null }
        assertEquals("123456789", exportedPassphrase)
    }

    @Test
    fun encryptedBackupPromptsForPasswordAndRequiresConfirmationBeforeRestore() {
        val encryptedBackup = "AUTO_ACCOUNTING_BACKUP_V4:backup"
        val validatedPassphrases = mutableListOf<String>()
        var validatedBackup: String? = null
        var importedPassphrase: String? = null
        setContentWithBackupFile(encryptedBackup) {
            DataAndBackupScreen(
                ledgerEntries = emptyList(),
                onExportEncryptedBackup = { "backup" },
                onValidateEncryptedBackup = { backup, passphrase ->
                    validatedBackup = backup
                    validatedPassphrases += passphrase
                    if (passphrase != "correct-password") error("invalid")
                },
                onImportEncryptedBackup = { _, passphrase -> importedPassphrase = passphrase },
                onDeleteLocalData = {},
                onBack = {}
            )
        }

        composeRule.onNodeWithText("导入加密备份").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("backup-password-dialog-input")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("backup-password-dialog-input").performTextInput("wrong-password")
        composeRule.onNodeWithText("确认").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("密码错误，或备份文件已损坏，请重试")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("backup-password-dialog-input").performTextClearance()
        composeRule.onNodeWithTag("backup-password-dialog-input").performTextInput("correct-password")
        composeRule.onNodeWithText("确认").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("确认替换并恢复")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        assertEquals(encryptedBackup, validatedBackup)
        assertEquals(listOf("wrong-password", "correct-password"), validatedPassphrases)
        assertEquals(null, importedPassphrase)
        composeRule.onNodeWithText("将替换本机现有数据", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("确认替换并恢复").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { importedPassphrase != null }
        assertEquals("correct-password", importedPassphrase)
    }

    @Test
    fun unencryptedFileDoesNotPromptForPassword() {
        val snackbarHostState = SnackbarHostState()
        var validated = false
        setContentWithBackupFile("plain backup") {
            DataAndBackupScreen(
                ledgerEntries = emptyList(),
                onExportEncryptedBackup = { "backup" },
                onValidateEncryptedBackup = { _, _ -> validated = true },
                onImportEncryptedBackup = { _, _ -> },
                onDeleteLocalData = {},
                onBack = {},
                snackbarHostState = snackbarHostState
            )
        }

        composeRule.onNodeWithText("导入加密备份").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            snackbarHostState.currentSnackbarData != null
        }

        assertFalse(validated)
        assertEquals(
            "所选文件不是受支持的加密备份",
            snackbarHostState.currentSnackbarData?.visuals?.message
        )
        composeRule.onAllNodesWithTag("backup-password-dialog-input").assertCountEquals(0)
        composeRule.onAllNodesWithText("确认替换并恢复").assertCountEquals(0)
    }

    @Test
    fun cancellingValidatedRestoreKeepsImportCallbackUntouched() {
        var imported = false
        setContentWithBackupFile("AUTO_ACCOUNTING_BACKUP_V4:backup") {
            DataAndBackupScreen(
                ledgerEntries = emptyList(),
                onExportEncryptedBackup = { "backup" },
                onValidateEncryptedBackup = { _, _ -> },
                onImportEncryptedBackup = { _, _ -> imported = true },
                onDeleteLocalData = {},
                onBack = {}
            )
        }

        composeRule.onNodeWithText("导入加密备份").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("backup-password-dialog-input")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("backup-password-dialog-input").performTextInput("secret")
        composeRule.onNodeWithText("确认").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("确认替换并恢复")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("取消").performClick()

        assertFalse(imported)
        composeRule.onAllNodesWithText("确认替换并恢复").assertCountEquals(0)
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
