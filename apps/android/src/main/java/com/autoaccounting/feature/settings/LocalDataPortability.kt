package com.autoaccounting.feature.settings

import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.feature.categorization.AiCategorizationSettings
import com.autoaccounting.feature.categorization.CategorizationRule
import com.autoaccounting.feature.ledger.LedgerUiEntry
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import com.autoaccounting.feature.review.ReviewQueueConfirmedEntry
import com.autoaccounting.feature.review.ReviewQueueEntry
import com.autoaccounting.feature.review.ReviewQueueIgnoredEntry
import com.autoaccounting.feature.review.ReviewQueueState
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class LocalDataSnapshot(
    val reviewState: ReviewQueueState = ReviewQueueState(),
    val categorizationRules: List<CategorizationRule> = emptyList(),
    val aiSettings: AiCategorizationSettings = AiCategorizationSettings(),
    val continuousMonitoringState: ContinuousMonitoringState = ContinuousMonitoringState()
)

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

fun exportEncryptedBackup(
    snapshot: LocalDataSnapshot,
    passphrase: String
): String {
    val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
    val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, keyFromPassphrase(passphrase), GCMParameterSpec(GCM_TAG_BITS, iv))
    val encrypted = cipher.doFinal(snapshot.serialize().toByteArray(StandardCharsets.UTF_8))
    return "$BACKUP_PREFIX${b64(iv + encrypted)}"
}

fun importEncryptedBackup(
    backupText: String,
    passphrase: String
): LocalDataSnapshot {
    require(backupText.startsWith(BACKUP_PREFIX)) { "Unsupported backup format" }
    val bytes = Base64.getDecoder().decode(backupText.removePrefix(BACKUP_PREFIX))
    require(bytes.size > GCM_IV_BYTES) { "Invalid backup payload" }
    val iv = bytes.copyOfRange(0, GCM_IV_BYTES)
    val encrypted = bytes.copyOfRange(GCM_IV_BYTES, bytes.size)
    val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, keyFromPassphrase(passphrase), GCMParameterSpec(GCM_TAG_BITS, iv))
    val plainText = String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    return parseSnapshot(plainText)
}

private fun LocalDataSnapshot.serialize(): String {
    val lines = mutableListOf<String>()
    lines += fields(
        "M",
        reviewState.nowEpochMillis.toString(),
        reviewState.todayStartEpochMillis.toString()
    )
    lines += fields(
        "S",
        aiSettings.aiConsentGranted.toString(),
        aiSettings.enhancedContextGranted.toString()
    )
    lines += fields(
        "G",
        continuousMonitoringState.billSyncCompleted.toString(),
        continuousMonitoringState.enabled.toString()
    )
    reviewState.pendingEntries.forEach { entry ->
        lines += fields("P", *entryFields(entry))
    }
    reviewState.confirmedEntries.forEach { confirmed ->
        lines += fields("C", confirmed.id, confirmed.originPendingId, *entryFields(confirmed.entry))
    }
    reviewState.ignoredEntries.forEach { ignored ->
        lines += fields(
            "I",
            ignored.id,
            ignored.originalPendingId,
            ignored.ignoredAtEpochMillis.toString(),
            ignored.expiresAtEpochMillis.toString(),
            *entryFields(ignored.entry)
        )
    }
    categorizationRules.forEach { rule ->
        lines += fields(
            "R",
            rule.id,
            rule.merchantContains,
            rule.titleContains,
            rule.sourceLabel,
            rule.transactionKind,
            rule.category,
            rule.priority.toString(),
            rule.enabled.toString(),
            rule.updatedAtEpochMillis.toString()
        )
    }
    return lines.joinToString("\n")
}

private fun parseSnapshot(text: String): LocalDataSnapshot {
    var now = 0L
    var todayStart = 0L
    var aiSettings = AiCategorizationSettings()
    var continuousMonitoringState = ContinuousMonitoringState()
    val pendingEntries = mutableListOf<ReviewQueueEntry>()
    val confirmedEntries = mutableListOf<ReviewQueueConfirmedEntry>()
    val ignoredEntries = mutableListOf<ReviewQueueIgnoredEntry>()
    val rules = mutableListOf<CategorizationRule>()

    text.lineSequence()
        .filter { it.isNotBlank() }
        .forEach { line ->
            val values = line.split("|").map { unfield(it) }
            when (values.first()) {
                "M" -> {
                    now = values[1].toLong()
                    todayStart = values[2].toLong()
                }
                "S" -> aiSettings = AiCategorizationSettings(
                    aiConsentGranted = values[1].toBoolean(),
                    enhancedContextGranted = values[2].toBoolean()
                )
                "G" -> continuousMonitoringState = ContinuousMonitoringState(
                    billSyncCompleted = values[1].toBoolean(),
                    enabled = values[2].toBoolean()
                )
                "P" -> pendingEntries += parseEntry(values.drop(1))
                "C" -> confirmedEntries += ReviewQueueConfirmedEntry(
                    id = values[1],
                    originPendingId = values[2],
                    entry = parseEntry(values.drop(3))
                )
                "I" -> ignoredEntries += ReviewQueueIgnoredEntry(
                    id = values[1],
                    originalPendingId = values[2],
                    ignoredAtEpochMillis = values[3].toLong(),
                    expiresAtEpochMillis = values[4].toLong(),
                    entry = parseEntry(values.drop(5))
                )
                "R" -> rules += CategorizationRule(
                    id = values[1],
                    merchantContains = values[2],
                    titleContains = values[3],
                    sourceLabel = values[4],
                    transactionKind = values[5],
                    category = values[6],
                    priority = values[7].toInt(),
                    enabled = values[8].toBoolean(),
                    updatedAtEpochMillis = values[9].toLong()
                )
            }
        }

    return LocalDataSnapshot(
        reviewState = ReviewQueueState(
            pendingEntries = pendingEntries,
            confirmedEntries = confirmedEntries,
            ignoredEntries = ignoredEntries,
            nowEpochMillis = now,
            todayStartEpochMillis = todayStart
        ),
        categorizationRules = rules,
        aiSettings = aiSettings,
        continuousMonitoringState = continuousMonitoringState
    )
}

private fun entryFields(entry: ReviewQueueEntry): Array<String> = arrayOf(
    entry.id,
    entry.title,
    entry.amountMinor.toString(),
    entry.transactionTimeText,
    entry.category,
    entry.fundingAccountLabel,
    entry.sourceLabel,
    entry.kindLabel,
    entry.captureReasonLabel,
    entry.confidence.name,
    entry.capturedAtEpochMillis.toString(),
    entry.captureTimeText,
    entry.note.orEmpty(),
    entry.rawEvidenceText,
    entry.parsedFields.joinToString(LIST_SEPARATOR)
)

private fun parseEntry(values: List<String>): ReviewQueueEntry = ReviewQueueEntry(
    id = values[0],
    title = values[1],
    amountMinor = values[2].toLong(),
    transactionTimeText = values[3],
    category = values[4],
    fundingAccountLabel = values[5],
    sourceLabel = values[6],
    kindLabel = values[7],
    captureReasonLabel = values[8],
    confidence = ConfidenceState.valueOf(values[9]),
    capturedAtEpochMillis = values[10].toLong(),
    captureTimeText = values[11],
    note = values[12].ifBlank { null },
    rawEvidenceText = values[13],
    parsedFields = values[14].split(LIST_SEPARATOR).filter { it.isNotBlank() }
)

private fun fields(vararg values: String): String = values.joinToString("|") { field(it) }

private fun field(value: String): String =
    b64(value.toByteArray(StandardCharsets.UTF_8))

private fun unfield(value: String): String =
    String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)

private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

private fun keyFromPassphrase(passphrase: String): SecretKeySpec {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(passphrase.toByteArray(StandardCharsets.UTF_8))
    return SecretKeySpec(digest, "AES")
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
private const val BACKUP_PREFIX = "AUTO_ACCOUNTING_BACKUP_V1:"
private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BITS = 128
private const val LIST_SEPARATOR = "\u001F"
private val secureRandom = SecureRandom()
