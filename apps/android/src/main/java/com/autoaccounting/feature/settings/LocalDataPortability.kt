package com.autoaccounting.feature.settings

import com.autoaccounting.feature.ledger.LedgerUiEntry

data class LocalDataDeletionState(
    val backupReminderAccepted: Boolean = false,
    val confirmationText: String = ""
) {
    val canDelete: Boolean
        get() = backupReminderAccepted && confirmationText == DELETE_LOCAL_DATA_PHRASE
}

sealed interface LocalDataDeletionAction {
    data class SetBackupReminderAccepted(val accepted: Boolean) : LocalDataDeletionAction
    data class UpdateConfirmationText(val text: String) : LocalDataDeletionAction
}

fun reduceLocalDataDeletionState(
    state: LocalDataDeletionState,
    action: LocalDataDeletionAction
): LocalDataDeletionState = when (action) {
    is LocalDataDeletionAction.SetBackupReminderAccepted -> state.copy(
        backupReminderAccepted = action.accepted
    )
    is LocalDataDeletionAction.UpdateConfirmationText -> state.copy(
        confirmationText = action.text
    )
}

fun exportLedgerCsv(entries: List<LedgerUiEntry>): String {
    val header = listOf(
        "id",
        "transaction_time",
        "title",
        "amount",
        "flow_type",
        "category",
        "source",
        "transaction_kind",
        "note"
    ).joinToString(",")
    val rows = entries.map { entry ->
        listOf(
            entry.id,
            entry.transactionTimeText,
            entry.title,
            minorToText(entry.amountMinor),
            entry.flowType.name,
            entry.category,
            entry.sourceLabel,
            entry.kindLabel,
            entry.note.orEmpty()
        ).joinToString(",") { it.csvCell() }
    }
    return (listOf(header) + rows).joinToString("\n")
}

internal fun ledgerCsvFilename(ledgerName: String, timestamp: String): String {
    val safeLedgerName = ledgerName
        .trim()
        .replace(Regex("""[\\/:*?"<>|\r\n]+"""), "_")
        .ifBlank { "默认账本" }
    return "$timestamp-$safeLedgerName-ledger.csv"
}

private fun String.csvCell(): String {
    val needsQuoting = contains(",") || contains("\"") || contains("\n")
    val escaped = replace("\"", "\"\"")
    return if (needsQuoting) "\"$escaped\"" else escaped
}

private fun minorToText(amountMinor: Long): String {
    val yuan = amountMinor / 100
    val cents = kotlin.math.abs(amountMinor % 100)
    return "$yuan.${cents.toString().padStart(2, '0')}"
}

const val DELETE_LOCAL_DATA_PHRASE = "删除本机数据"
