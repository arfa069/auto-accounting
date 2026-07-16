package com.autoaccounting.feature.settings

import com.autoaccounting.feature.ledger.LedgerFlowType
import com.autoaccounting.feature.ledger.LedgerUiEntry
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
                    title = "Lunch",
                    amountMinor = 3590,
                    monthKey = "2026-07",
                    transactionTimeText = "2026-07-08 12:20",
                    category = "Food",
                    sourceLabel = "WeChat",
                    kindLabel = "Expense",
                    flowType = LedgerFlowType.EXPENSE,
                    note = "Client meeting"
                )
            )
        )

        assertEquals(
            "id,transaction_time,title,amount,flow_type,category,source,transaction_kind,note\n" +
                "ledger-1,2026-07-08 12:20,Lunch,35.90,EXPENSE,Food,WeChat,Expense,Client meeting",
            csv
        )
    }

    @Test
    fun localDeletionRequiresReminderAndTypedPhrase() {
        val reminderOnly = reduceLocalDataDeletionState(
            LocalDataDeletionState(),
            LocalDataDeletionAction.SetBackupReminderAccepted(true)
        )
        val wrongPhrase = reduceLocalDataDeletionState(
            reminderOnly,
            LocalDataDeletionAction.UpdateConfirmationText("delete data")
        )
        val exactPhrase = reduceLocalDataDeletionState(
            wrongPhrase,
            LocalDataDeletionAction.UpdateConfirmationText(DELETE_LOCAL_DATA_PHRASE)
        )

        assertFalse(wrongPhrase.canDelete)
        assertTrue(exactPhrase.canDelete)
    }

    @Test
    fun csvFilenameIncludesSanitizedCurrentLedgerName() {
        assertEquals(
            "2026-07-16-14-30-家庭_日常-ledger.csv",
            ledgerCsvFilename(" 家庭/日常 ", "2026-07-16-14-30")
        )
    }
}
