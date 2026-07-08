package com.autoaccounting.feature.settings

import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.feature.categorization.AiCategorizationSettings
import com.autoaccounting.feature.categorization.CategorizationRule
import com.autoaccounting.feature.ledger.LedgerFlowType
import com.autoaccounting.feature.ledger.LedgerUiEntry
import com.autoaccounting.feature.review.ReviewQueueConfirmedEntry
import com.autoaccounting.feature.review.ReviewQueueEntry
import com.autoaccounting.feature.review.ReviewQueueState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDataPortabilityTest {
    @Test
    fun csvExportUsesStableSpreadsheetSchema() {
        val csv = exportLedgerCsv(
            listOf(
                LedgerUiEntry(
                    id = "ledger-1",
                    title = "午餐",
                    amountMinor = 3590,
                    monthKey = "2026-07",
                    transactionTimeText = "2026-07-08 12:20",
                    category = "餐饮",
                    sourceLabel = "微信",
                    kindLabel = "支出",
                    flowType = LedgerFlowType.EXPENSE,
                    note = "客户会议"
                )
            )
        )

        assertEquals(
            "id,transaction_time,title,amount,flow_type,category,source,transaction_kind,note\n" +
                "ledger-1,2026-07-08 12:20,午餐,35.90,EXPENSE,餐饮,微信,支出,客户会议",
            csv
        )
    }

    @Test
    fun encryptedBackupRoundTripRestoresLocalSnapshotWithoutPlainTextLeak() {
        val snapshot = LocalDataSnapshot(
            reviewState = ReviewQueueState(
                pendingEntries = listOf(sampleEntry("pending-1")),
                confirmedEntries = listOf(
                    ReviewQueueConfirmedEntry.fromPending(sampleEntry("confirmed-1"))
                )
            ),
            categorizationRules = listOf(
                CategorizationRule(
                    id = "rule-1",
                    merchantContains = "午餐",
                    category = "餐饮",
                    updatedAtEpochMillis = 10
                )
            ),
            aiSettings = AiCategorizationSettings(
                aiConsentGranted = true,
                enhancedContextGranted = true
            )
        )

        val backup = exportEncryptedBackup(snapshot, passphrase = "Aa123456!")
        val restored = importEncryptedBackup(backup, passphrase = "Aa123456!")

        assertTrue(backup.startsWith("AUTO_ACCOUNTING_BACKUP_V1:"))
        assertFalse(backup.contains("午餐"))
        assertEquals(snapshot, restored)
    }

    @Test
    fun localDeletionRequiresReminderAndTypedPhrase() {
        val reminderOnly = reduceLocalDataDeletionState(
            LocalDataDeletionState(),
            LocalDataDeletionAction.SetBackupReminderAccepted(true)
        )
        val wrongPhrase = reduceLocalDataDeletionState(
            reminderOnly,
            LocalDataDeletionAction.UpdateConfirmationText("删除数据")
        )
        val exactPhrase = reduceLocalDataDeletionState(
            wrongPhrase,
            LocalDataDeletionAction.UpdateConfirmationText("删除本机数据")
        )

        assertFalse(wrongPhrase.canDelete)
        assertTrue(exactPhrase.canDelete)
    }

    private fun sampleEntry(id: String): ReviewQueueEntry = ReviewQueueEntry(
        id = id,
        title = "午餐",
        amountMinor = 3590,
        transactionTimeText = "2026-07-08 12:20",
        category = "餐饮",
        fundingAccountLabel = "微信零钱",
        sourceLabel = "微信",
        kindLabel = "支出",
        captureReasonLabel = "通知捕获",
        confidence = ConfidenceState.HIGH,
        capturedAtEpochMillis = 1_783_468_800_000L,
        captureTimeText = "2026-07-08 12:21",
        rawEvidenceText = "微信支付收款凭证 午餐 35.90",
        parsedFields = listOf("商户=午餐", "金额=35.90")
    )
}
