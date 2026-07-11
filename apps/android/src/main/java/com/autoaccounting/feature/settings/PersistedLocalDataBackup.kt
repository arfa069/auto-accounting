package com.autoaccounting.feature.settings

import androidx.room.withTransaction
import com.autoaccounting.data.local.AutoAccountingDatabase
import com.autoaccounting.data.local.CaptureReason
import com.autoaccounting.data.local.CategorizationRuleEntity
import com.autoaccounting.data.local.CategoryEntity
import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.data.local.EntryOrigin
import com.autoaccounting.data.local.FlowDirection
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.FundingAccountSourceScope
import com.autoaccounting.data.local.IgnoreReason
import com.autoaccounting.data.local.IgnoredEntryEntity
import com.autoaccounting.data.local.LedgerEntryEntity
import com.autoaccounting.data.local.LocalSettingsEntity
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.PendingEntryEntity
import com.autoaccounting.data.local.TransactionKind
import com.autoaccounting.data.local.defaultFlowDirection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class PersistedLocalDataSnapshot(
    val categories: List<CategoryEntity>,
    val fundingAccounts: List<FundingAccountEntity>,
    val pendingEntries: List<PendingEntryEntity>,
    val ledgerEntries: List<LedgerEntryEntity>,
    val ignoredEntries: List<IgnoredEntryEntity>,
    val categorizationRules: List<CategorizationRuleEntity>,
    val settings: LocalSettingsEntity?
)

class LocalDataBackupRepository(
    private val database: AutoAccountingDatabase
) {
    suspend fun exportEncryptedBackup(passphrase: String): String {
        val snapshot = database.withTransaction {
            PersistedLocalDataSnapshot(
                categories = database.categoryDao().getAllCategories(),
                fundingAccounts = database.fundingAccountDao().getAllFundingAccounts(),
                pendingEntries = database.pendingEntryDao().listPendingEntries(),
                ledgerEntries = database.ledgerEntryDao().listAllLedgerEntries(),
                ignoredEntries = database.ignoredEntryDao().listAll(),
                categorizationRules = database.categorizationRuleDao().listRules(),
                settings = database.localSettingsDao().getById()
            )
        }
        return encryptPersistedLocalData(snapshot, passphrase)
    }

    suspend fun importEncryptedBackup(
        backupText: String,
        passphrase: String
    ) {
        val snapshot = decryptPersistedLocalData(backupText, passphrase).validated()
        database.withTransaction {
            database.ledgerEntryDao().deleteAll()
            database.pendingEntryDao().deleteAll()
            database.ignoredEntryDao().deleteAll()
            database.fundingAccountDao().deleteAll()
            database.categoryDao().deleteAll()
            database.categorizationRuleDao().deleteAll()
            database.localSettingsDao().deleteAll()

            database.categoryDao().upsertAll(snapshot.categories)
            database.fundingAccountDao().upsertAll(snapshot.fundingAccounts)
            database.pendingEntryDao().upsertAll(snapshot.pendingEntries)
            database.ledgerEntryDao().upsertAll(snapshot.ledgerEntries)
            database.ignoredEntryDao().upsertAll(snapshot.ignoredEntries)
            database.categorizationRuleDao().upsertAll(snapshot.categorizationRules)
            snapshot.settings?.let { database.localSettingsDao().upsert(it) }
        }
    }
}

private fun PersistedLocalDataSnapshot.validated(): PersistedLocalDataSnapshot = apply {
    require(categories.all { it.id.isNotBlank() && it.name.isNotBlank() }) {
        "Backup contains an invalid category"
    }
    require(fundingAccounts.all { it.id >= 0 && it.label.isNotBlank() }) {
        "Backup contains an invalid funding account"
    }
    require(pendingEntries.all { entry ->
        entry.id.isNotBlank() &&
            entry.amountMinor > 0 &&
            entry.currency == SUPPORTED_BACKUP_CURRENCY &&
            entry.transactionTimeEpochMillis >= 0 &&
            entry.capturedAtEpochMillis >= 0
    }) { "Backup contains an invalid pending entry" }
    require(ledgerEntries.all { entry ->
        entry.id.isNotBlank() &&
            entry.amountMinor > 0 &&
            entry.currency == SUPPORTED_BACKUP_CURRENCY &&
            entry.transactionTimeEpochMillis >= 0 &&
            entry.confirmedAtEpochMillis >= 0 &&
            entry.updatedAtEpochMillis >= entry.confirmedAtEpochMillis &&
            (entry.deletedAtEpochMillis == null || entry.deletedAtEpochMillis >= entry.confirmedAtEpochMillis)
    }) { "Backup contains an invalid ledger entry" }
    require(ignoredEntries.all { it.id.isNotBlank() && it.ignoredAtEpochMillis >= 0 }) {
        "Backup contains an invalid ignored entry"
    }
}

internal fun encryptPersistedLocalData(
    snapshot: PersistedLocalDataSnapshot,
    passphrase: String
): String {
    require(passphrase.isNotBlank()) { "Backup passphrase is required" }
    val plainText = snapshot.toBytes()
    val salt = ByteArray(KDF_SALT_BYTES).also { secureRandom.nextBytes(it) }
    val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
    val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
    cipher.init(
        Cipher.ENCRYPT_MODE,
        keyFromPassphrase(passphrase, salt),
        GCMParameterSpec(GCM_TAG_BITS, iv)
    )
    val encrypted = cipher.doFinal(plainText)
    return BACKUP_PREFIX_V3 + Base64.getEncoder().encodeToString(salt + iv + encrypted)
}

internal fun decryptPersistedLocalData(
    backupText: String,
    passphrase: String
): PersistedLocalDataSnapshot {
    require(passphrase.isNotBlank()) { "Backup passphrase is required" }
    val prefix = when {
        backupText.startsWith(BACKUP_PREFIX_V3) -> BACKUP_PREFIX_V3
        backupText.startsWith(BACKUP_PREFIX_V2) -> BACKUP_PREFIX_V2
        else -> error("Unsupported backup format")
    }
    val bytes = Base64.getDecoder().decode(backupText.removePrefix(prefix))
    require(bytes.size > KDF_SALT_BYTES + GCM_IV_BYTES) { "Invalid backup payload" }
    val salt = bytes.copyOfRange(0, KDF_SALT_BYTES)
    val iv = bytes.copyOfRange(KDF_SALT_BYTES, KDF_SALT_BYTES + GCM_IV_BYTES)
    val encrypted = bytes.copyOfRange(KDF_SALT_BYTES + GCM_IV_BYTES, bytes.size)
    val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
    cipher.init(
        Cipher.DECRYPT_MODE,
        keyFromPassphrase(passphrase, salt),
        GCMParameterSpec(GCM_TAG_BITS, iv)
    )
    return snapshotFromBytes(cipher.doFinal(encrypted))
}

private fun PersistedLocalDataSnapshot.toBytes(): ByteArray {
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use { data ->
        data.writeInt(BACKUP_MAGIC)
        data.writeInt(BACKUP_VERSION_V3)
        data.writeList(categories) { category ->
            writeString(category.id)
            writeString(category.name)
            writeNullableString(category.kind?.name)
            writeInt(category.sortOrder)
            writeBoolean(category.isSystem)
            writeLong(category.createdAtEpochMillis)
        }
        data.writeList(fundingAccounts) { account ->
            writeLong(account.id)
            writeString(account.sourceScope.name)
            writeNullableString(account.paymentSource?.name)
            writeString(account.label)
            writeLong(account.createdAtEpochMillis)
        }
        data.writeList(pendingEntries, DataOutputStream::writePendingEntry)
        data.writeList(ledgerEntries, DataOutputStream::writeLedgerEntry)
        data.writeList(ignoredEntries, DataOutputStream::writeIgnoredEntry)
        data.writeList(categorizationRules, DataOutputStream::writeCategorizationRule)
        data.writeBoolean(settings != null)
        settings?.let { data.writeSettings(it) }
    }
    return output.toByteArray()
}

private fun snapshotFromBytes(bytes: ByteArray): PersistedLocalDataSnapshot =
    DataInputStream(ByteArrayInputStream(bytes)).use { data ->
        require(data.readInt() == BACKUP_MAGIC) { "Invalid backup payload" }
        val version = data.readInt()
        require(version == BACKUP_VERSION_V2 || version == BACKUP_VERSION_V3) {
            "Unsupported backup version"
        }
        val snapshot = PersistedLocalDataSnapshot(
            categories = data.readList {
                CategoryEntity(
                    id = readString(),
                    name = readString(),
                    kind = readNullableString()?.let(TransactionKind::valueOf),
                    sortOrder = readInt(),
                    isSystem = readBoolean(),
                    createdAtEpochMillis = readLong()
                )
            },
            fundingAccounts = data.readList {
                if (version == BACKUP_VERSION_V2) {
                    val id = readLong()
                    val source = PaymentSource.valueOf(readString())
                    FundingAccountEntity(
                        id = id,
                        sourceScope = source.toScope(),
                        paymentSource = source,
                        label = readString(),
                        createdAtEpochMillis = readLong()
                    )
                } else {
                    FundingAccountEntity(
                        id = readLong(),
                        sourceScope = FundingAccountSourceScope.valueOf(readString()),
                        paymentSource = readNullableString()?.let(PaymentSource::valueOf),
                        label = readString(),
                        createdAtEpochMillis = readLong()
                    )
                }
            },
            pendingEntries = data.readList(DataInputStream::readPendingEntry),
            ledgerEntries = if (version == BACKUP_VERSION_V2) {
                data.readList(DataInputStream::readLedgerEntryV2)
            } else {
                data.readList(DataInputStream::readLedgerEntryV3)
            },
            ignoredEntries = data.readList(DataInputStream::readIgnoredEntry),
            categorizationRules = data.readList(DataInputStream::readCategorizationRule),
            settings = if (data.readBoolean()) data.readSettings() else null
        )
        require(data.available() == 0) { "Invalid backup payload" }
        snapshot
    }

private fun DataOutputStream.writePendingEntry(entry: PendingEntryEntity) {
    writeString(entry.id)
    writeString(entry.source.name)
    writeString(entry.captureReason.name)
    writeString(entry.confidence.name)
    writeString(entry.transactionKind.name)
    writeLong(entry.amountMinor)
    writeString(entry.currency)
    writeString(entry.merchantTitle)
    writeLong(entry.transactionTimeEpochMillis)
    writeLong(entry.capturedAtEpochMillis)
    writeNullableString(entry.suggestedCategoryId)
    writeNullableLong(entry.fundingAccountId)
    writeNullableString(entry.fundingAccountLabel)
    writeNullableString(entry.note)
    writeNullableString(entry.evidenceSummary)
    writeNullableString(entry.parsedFieldsText)
    writeNullableString(entry.suggestedCategoryLabel)
}

private fun DataInputStream.readPendingEntry(): PendingEntryEntity = PendingEntryEntity(
    id = readString(),
    source = PaymentSource.valueOf(readString()),
    captureReason = CaptureReason.valueOf(readString()),
    confidence = ConfidenceState.valueOf(readString()),
    transactionKind = TransactionKind.valueOf(readString()),
    amountMinor = readLong(),
    currency = readString(),
    merchantTitle = readString(),
    transactionTimeEpochMillis = readLong(),
    capturedAtEpochMillis = readLong(),
    suggestedCategoryId = readNullableString(),
    fundingAccountId = readNullableLong(),
    fundingAccountLabel = readNullableString(),
    note = readNullableString(),
    evidenceSummary = readNullableString(),
    parsedFieldsText = readNullableString(),
    suggestedCategoryLabel = readNullableString()
)

private fun DataOutputStream.writeLedgerEntry(entry: LedgerEntryEntity) {
    writeString(entry.id)
    writeNullableString(entry.paymentSource?.name)
    writeNullableString(entry.originalCaptureSource?.name)
    writeString(entry.entryOrigin.name)
    writeNullableString(entry.originPendingEntryId)
    writeString(entry.flowDirection.name)
    writeString(entry.transactionKind.name)
    writeLong(entry.amountMinor)
    writeString(entry.currency)
    writeString(entry.merchantTitle)
    writeLong(entry.transactionTimeEpochMillis)
    writeNullableString(entry.categoryId)
    writeNullableLong(entry.fundingAccountId)
    writeNullableString(entry.note)
    writeNullableString(entry.evidenceSummary)
    writeNullableString(entry.parsedFieldsText)
    writeLong(entry.confirmedAtEpochMillis)
    writeLong(entry.updatedAtEpochMillis)
    writeNullableLong(entry.deletedAtEpochMillis)
}

private fun DataInputStream.readLedgerEntryV3(): LedgerEntryEntity = LedgerEntryEntity(
    id = readString(),
    paymentSource = readNullableString()?.let(PaymentSource::valueOf),
    originalCaptureSource = readNullableString()?.let(PaymentSource::valueOf),
    entryOrigin = EntryOrigin.valueOf(readString()),
    originPendingEntryId = readNullableString(),
    flowDirection = FlowDirection.valueOf(readString()),
    transactionKind = TransactionKind.valueOf(readString()),
    amountMinor = readLong(),
    currency = readString(),
    merchantTitle = readString(),
    transactionTimeEpochMillis = readLong(),
    categoryId = readNullableString(),
    fundingAccountId = readNullableLong(),
    note = readNullableString(),
    evidenceSummary = readNullableString(),
    parsedFieldsText = readNullableString(),
    confirmedAtEpochMillis = readLong(),
    updatedAtEpochMillis = readLong(),
    deletedAtEpochMillis = readNullableLong()
)

private fun DataInputStream.readLedgerEntryV2(): LedgerEntryEntity {
    val id = readString()
    val source = PaymentSource.valueOf(readString())
    val originPendingEntryId = readNullableString()
    val transactionKind = TransactionKind.valueOf(readString())
    val amountMinor = readLong()
    val currency = readString()
    val merchantTitle = readString()
    val transactionTimeEpochMillis = readLong()
    val categoryId = readNullableString()
    val fundingAccountId = readNullableLong()
    val note = readNullableString()
    val confirmedAtEpochMillis = readLong()
    return LedgerEntryEntity(
        id = id,
        paymentSource = source,
        originalCaptureSource = source,
        entryOrigin = EntryOrigin.LEGACY_CAPTURE,
        originPendingEntryId = originPendingEntryId,
        flowDirection = transactionKind.defaultFlowDirection(),
        transactionKind = transactionKind,
        amountMinor = amountMinor,
        currency = currency,
        merchantTitle = merchantTitle,
        transactionTimeEpochMillis = transactionTimeEpochMillis,
        categoryId = categoryId,
        fundingAccountId = fundingAccountId,
        note = note,
        evidenceSummary = null,
        parsedFieldsText = null,
        confirmedAtEpochMillis = confirmedAtEpochMillis,
        updatedAtEpochMillis = confirmedAtEpochMillis,
        deletedAtEpochMillis = null
    )
}

private fun DataOutputStream.writeIgnoredEntry(entry: IgnoredEntryEntity) {
    writeString(entry.id)
    writeString(entry.originalPendingEntryId)
    writeString(entry.source.name)
    writeString(entry.captureReason.name)
    writeString(entry.confidence.name)
    writeString(entry.transactionKind.name)
    writeLong(entry.amountMinor)
    writeString(entry.currency)
    writeString(entry.merchantTitle)
    writeLong(entry.transactionTimeEpochMillis)
    writeLong(entry.capturedAtEpochMillis)
    writeNullableString(entry.suggestedCategoryId)
    writeNullableLong(entry.fundingAccountId)
    writeNullableString(entry.fundingAccountLabel)
    writeNullableString(entry.note)
    writeNullableString(entry.evidenceSummary)
    writeNullableString(entry.parsedFieldsText)
    writeLong(entry.ignoredAtEpochMillis)
    writeLong(entry.expiresAtEpochMillis)
    writeString(entry.reason.name)
    writeNullableString(entry.suggestedCategoryLabel)
}

private fun DataInputStream.readIgnoredEntry(): IgnoredEntryEntity = IgnoredEntryEntity(
    id = readString(),
    originalPendingEntryId = readString(),
    source = PaymentSource.valueOf(readString()),
    captureReason = CaptureReason.valueOf(readString()),
    confidence = ConfidenceState.valueOf(readString()),
    transactionKind = TransactionKind.valueOf(readString()),
    amountMinor = readLong(),
    currency = readString(),
    merchantTitle = readString(),
    transactionTimeEpochMillis = readLong(),
    capturedAtEpochMillis = readLong(),
    suggestedCategoryId = readNullableString(),
    fundingAccountId = readNullableLong(),
    fundingAccountLabel = readNullableString(),
    note = readNullableString(),
    evidenceSummary = readNullableString(),
    parsedFieldsText = readNullableString(),
    ignoredAtEpochMillis = readLong(),
    expiresAtEpochMillis = readLong(),
    reason = IgnoreReason.valueOf(readString()),
    suggestedCategoryLabel = readNullableString()
)

private fun DataOutputStream.writeCategorizationRule(rule: CategorizationRuleEntity) {
    writeString(rule.id)
    writeString(rule.merchantContains)
    writeString(rule.titleContains)
    writeString(rule.sourceLabel)
    writeString(rule.transactionKind)
    writeString(rule.category)
    writeInt(rule.priority)
    writeBoolean(rule.enabled)
    writeLong(rule.updatedAtEpochMillis)
}

private fun DataInputStream.readCategorizationRule(): CategorizationRuleEntity =
    CategorizationRuleEntity(
        id = readString(),
        merchantContains = readString(),
        titleContains = readString(),
        sourceLabel = readString(),
        transactionKind = readString(),
        category = readString(),
        priority = readInt(),
        enabled = readBoolean(),
        updatedAtEpochMillis = readLong()
    )

private fun DataOutputStream.writeSettings(settings: LocalSettingsEntity) {
    writeString(settings.id)
    writeBoolean(settings.aiConsentGranted)
    writeBoolean(settings.enhancedContextGranted)
    writeBoolean(settings.continuousBillSyncCompleted)
    writeBoolean(settings.continuousMonitoringEnabled)
}

private fun DataInputStream.readSettings(): LocalSettingsEntity = LocalSettingsEntity(
    id = readString(),
    aiConsentGranted = readBoolean(),
    enhancedContextGranted = readBoolean(),
    continuousBillSyncCompleted = readBoolean(),
    continuousMonitoringEnabled = readBoolean()
)

private inline fun <T> DataOutputStream.writeList(
    values: List<T>,
    writeValue: DataOutputStream.(T) -> Unit
) {
    writeInt(values.size)
    values.forEach { writeValue(it) }
}

private inline fun <T> DataInputStream.readList(
    readValue: DataInputStream.() -> T
): List<T> {
    val size = readInt()
    require(size in 0..MAX_BACKUP_RECORDS) { "Invalid backup payload" }
    return List(size) { readValue() }
}

private fun DataOutputStream.writeString(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readString(): String {
    val size = readInt()
    require(size in 0..MAX_BACKUP_STRING_BYTES) { "Invalid backup payload" }
    val bytes = ByteArray(size)
    readFully(bytes)
    return String(bytes, StandardCharsets.UTF_8)
}

private fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    value?.let(::writeString)
}

private fun DataInputStream.readNullableString(): String? =
    if (readBoolean()) readString() else null

private fun DataOutputStream.writeNullableLong(value: Long?) {
    writeBoolean(value != null)
    value?.let(::writeLong)
}

private fun DataInputStream.readNullableLong(): Long? =
    if (readBoolean()) readLong() else null

private fun keyFromPassphrase(
    passphrase: String,
    salt: ByteArray
): SecretKeySpec {
    val spec = PBEKeySpec(
        passphrase.toCharArray(),
        salt,
        KDF_ITERATIONS,
        AES_KEY_BITS
    )
    return try {
        val key = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            .generateSecret(spec)
            .encoded
        SecretKeySpec(key, "AES")
    } finally {
        spec.clearPassword()
    }
}

private fun PaymentSource.toScope(): FundingAccountSourceScope = when (this) {
    PaymentSource.WECHAT -> FundingAccountSourceScope.WECHAT
    PaymentSource.ALIPAY -> FundingAccountSourceScope.ALIPAY
}

private const val BACKUP_PREFIX_V2 = "AUTO_ACCOUNTING_BACKUP_V2:"
private const val BACKUP_PREFIX_V3 = "AUTO_ACCOUNTING_BACKUP_V3:"
private const val BACKUP_MAGIC = 0x41414343
private const val BACKUP_VERSION_V2 = 2
private const val BACKUP_VERSION_V3 = 3
private const val SUPPORTED_BACKUP_CURRENCY = "CNY"
private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
private const val KDF_SALT_BYTES = 16
private const val KDF_ITERATIONS = 120_000
private const val AES_KEY_BITS = 256
private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BITS = 128
private const val MAX_BACKUP_RECORDS = 1_000_000
private const val MAX_BACKUP_STRING_BYTES = 16 * 1024 * 1024
private val secureRandom = SecureRandom()
