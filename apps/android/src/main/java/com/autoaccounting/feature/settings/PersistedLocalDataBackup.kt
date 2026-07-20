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
import com.autoaccounting.data.local.LedgerBookEntity
import com.autoaccounting.data.local.LedgerEntryEntity
import com.autoaccounting.data.local.LocalSettingsEntity
import com.autoaccounting.data.local.LOCAL_SETTINGS_ID
import com.autoaccounting.data.local.DEFAULT_LEDGER_BOOK_ID
import com.autoaccounting.data.local.DEFAULT_LEDGER_BOOK_NAME
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.PendingEntryEntity
import com.autoaccounting.data.local.TransactionKind
import com.autoaccounting.data.local.defaultFlowDirection
import com.autoaccounting.data.crypto.PassphraseAesGcm
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PersistedLocalDataSnapshot(
    val categories: List<CategoryEntity>,
    val fundingAccounts: List<FundingAccountEntity>,
    val pendingEntries: List<PendingEntryEntity>,
    val ledgerEntries: List<LedgerEntryEntity>,
    val ignoredEntries: List<IgnoredEntryEntity>,
    val categorizationRules: List<CategorizationRuleEntity>,
    val settings: LocalSettingsEntity?,
    val ledgerBooks: List<LedgerBookEntity> = listOf(defaultBackupLedgerBook())
)

class LocalDataBackupRepository(
    private val database: AutoAccountingDatabase
) {
    suspend fun exportEncryptedBackup(passphrase: String): String = withContext(Dispatchers.Default) {
        val snapshot = database.withTransaction {
            val ledgerBooks = database.ledgerBookDao().getAll()
                .ifEmpty { listOf(defaultBackupLedgerBook()) }
            PersistedLocalDataSnapshot(
                categories = database.categoryDao().getAllCategories(),
                fundingAccounts = database.fundingAccountDao().getAllFundingAccounts(),
                pendingEntries = database.pendingEntryDao().listPendingEntries(),
                ledgerEntries = database.ledgerEntryDao().listAllLedgerEntries(),
                ignoredEntries = database.ignoredEntryDao().listAll(),
                categorizationRules = database.categorizationRuleDao().listRules(),
                settings = database.localSettingsDao().getById()
                    ?: defaultBackupSettings(ledgerBooks.first().id),
                ledgerBooks = ledgerBooks
            )
        }
        encryptPersistedLocalData(snapshot, passphrase)
    }

    suspend fun importEncryptedBackup(
        backupText: String,
        passphrase: String
    ) {
        val snapshot = decodeAndValidate(backupText, passphrase)
        withContext(Dispatchers.IO) {
            database.withTransaction {
                database.ledgerEntryDao().deleteAll()
                database.pendingEntryDao().deleteAll()
                database.ignoredEntryDao().deleteAll()
                database.fundingAccountDao().deleteAll()
                database.categoryDao().deleteAll()
                database.ledgerBookDao().deleteAll()
                database.categorizationRuleDao().deleteAll()
                database.localSettingsDao().deleteAll()

                database.categoryDao().upsertAll(snapshot.categories)
                database.fundingAccountDao().upsertAll(snapshot.fundingAccounts)
                database.ledgerBookDao().insertAll(snapshot.ledgerBooks)
                database.pendingEntryDao().upsertAll(snapshot.pendingEntries)
                database.ledgerEntryDao().upsertAll(snapshot.ledgerEntries)
                database.ignoredEntryDao().upsertAll(snapshot.ignoredEntries)
                database.categorizationRuleDao().upsertAll(snapshot.categorizationRules)
                database.localSettingsDao().upsert(requireNotNull(snapshot.settings))
            }
        }
    }

    suspend fun validateEncryptedBackup(
        backupText: String,
        passphrase: String
    ) {
        decodeAndValidate(backupText, passphrase)
    }

    private suspend fun decodeAndValidate(
        backupText: String,
        passphrase: String
    ): PersistedLocalDataSnapshot = withContext(Dispatchers.Default) {
        decryptPersistedLocalData(backupText, passphrase).validated()
    }
}

private fun PersistedLocalDataSnapshot.validated(): PersistedLocalDataSnapshot = apply {
    require(categories.map { it.id }.allDistinct()) { "Backup contains duplicate categories" }
    require(categories.map { it.name }.allDistinct()) {
        "Backup contains duplicate category names"
    }
    require(categories.all { it.id.isNotBlank() && it.name.isNotBlank() }) {
        "Backup contains an invalid category"
    }
    require(fundingAccounts.map { it.id }.allDistinct()) { "Backup contains duplicate funding accounts" }
    require(fundingAccounts.all { it.id >= 0 && it.label.isNotBlank() }) {
        "Backup contains an invalid funding account"
    }
    require(
        fundingAccounts
            .map { it.sourceScope to it.label }
            .allDistinct()
    ) { "Backup contains duplicate funding account names for a payment source" }
    require(ledgerBooks.isNotEmpty()) { "Backup must contain at least one ledger book" }
    require(ledgerBooks.map { it.id }.allDistinct()) {
        "Backup contains duplicate ledger books"
    }
    require(ledgerBooks.map { it.name }.allDistinct()) {
        "Backup contains duplicate ledger book names"
    }
    require(ledgerBooks.all {
        it.id.isNotBlank() &&
            it.name.isNotBlank() &&
            it.name == it.name.trim() &&
            it.createdAtEpochMillis >= 0
    }) { "Backup contains an invalid ledger book" }
    val categoryIds = categories.mapTo(mutableSetOf()) { it.id }
    val fundingAccountIds = fundingAccounts.mapTo(mutableSetOf()) { it.id }
    val ledgerBookIds = ledgerBooks.mapTo(mutableSetOf()) { it.id }
    fun referencesExist(categoryId: String?, fundingAccountId: Long?): Boolean =
        (categoryId == null || categoryId in categoryIds) &&
            (fundingAccountId == null || fundingAccountId in fundingAccountIds)
    require(pendingEntries.map { it.id }.allDistinct()) { "Backup contains duplicate pending entries" }
    require(pendingEntries.all { entry ->
        entry.id.isNotBlank() &&
            entry.amountMinor > 0 &&
            entry.currency == SUPPORTED_BACKUP_CURRENCY &&
            entry.transactionTimeEpochMillis >= 0 &&
            entry.capturedAtEpochMillis >= 0 &&
            referencesExist(entry.suggestedCategoryId, entry.fundingAccountId)
    }) { "Backup contains an invalid pending entry" }
    require(ledgerEntries.map { it.id }.allDistinct()) { "Backup contains duplicate ledger entries" }
    require(ledgerEntries.all { entry ->
        entry.id.isNotBlank() &&
            entry.amountMinor > 0 &&
            entry.currency == SUPPORTED_BACKUP_CURRENCY &&
            entry.transactionTimeEpochMillis >= 0 &&
            entry.confirmedAtEpochMillis >= 0 &&
            entry.updatedAtEpochMillis >= entry.confirmedAtEpochMillis &&
            (entry.deletedAtEpochMillis == null || entry.deletedAtEpochMillis >= entry.confirmedAtEpochMillis) &&
            entry.ledgerBookId in ledgerBookIds &&
            referencesExist(entry.categoryId, entry.fundingAccountId)
    }) { "Backup contains an invalid ledger entry" }
    require(ignoredEntries.map { it.id }.allDistinct()) { "Backup contains duplicate ignored entries" }
    require(ignoredEntries.all {
        it.id.isNotBlank() &&
            it.ignoredAtEpochMillis >= 0 &&
            referencesExist(it.suggestedCategoryId, it.fundingAccountId)
    }) {
        "Backup contains an invalid ignored entry"
    }
    require(categorizationRules.map { it.id }.allDistinct()) {
        "Backup contains duplicate categorization rules"
    }
    require(categorizationRules.all {
        it.id.isNotBlank() && it.category.isNotBlank() && it.updatedAtEpochMillis >= 0
    }) { "Backup contains an invalid categorization rule" }
    require(
        settings != null &&
            settings.id == LOCAL_SETTINGS_ID &&
            settings.activeLedgerId in ledgerBookIds &&
            (settings.aiConsentGranted || !settings.enhancedContextGranted)
    ) { "Backup contains invalid local settings" }
}

private fun <T> List<T>.allDistinct(): Boolean = size == toSet().size

internal fun encryptPersistedLocalData(
    snapshot: PersistedLocalDataSnapshot,
    passphrase: String
): String {
    require(isValidNewBackupPassphrase(passphrase)) {
        "Backup passphrase must be longer than 8 characters"
    }
    val plainText = snapshot.toBytes()
    val passphraseChars = passphrase.toCharArray()
    return try {
        BACKUP_PREFIX_V4 + Base64.getEncoder().encodeToString(
            PassphraseAesGcm.encrypt(plainText, passphraseChars)
        )
    } finally {
        passphraseChars.fill('\u0000')
    }
}

internal fun decryptPersistedLocalData(
    backupText: String,
    passphrase: String
): PersistedLocalDataSnapshot {
    require(passphrase.isNotBlank()) { "Backup passphrase is required" }
    val prefix = when {
        backupText.startsWith(BACKUP_PREFIX_V4) -> BACKUP_PREFIX_V4
        backupText.startsWith(BACKUP_PREFIX_V3) -> BACKUP_PREFIX_V3
        backupText.startsWith(BACKUP_PREFIX_V2) -> BACKUP_PREFIX_V2
        else -> error("Unsupported backup format")
    }
    val bytes = Base64.getDecoder().decode(backupText.removePrefix(prefix))
    val passphraseChars = passphrase.toCharArray()
    return try {
        snapshotFromBytes(PassphraseAesGcm.decrypt(bytes, passphraseChars))
    } finally {
        passphraseChars.fill('\u0000')
    }
}

internal fun isEncryptedLocalDataBackup(backupText: String): Boolean =
    backupText.startsWith(BACKUP_PREFIX_V2) ||
        backupText.startsWith(BACKUP_PREFIX_V3) ||
        backupText.startsWith(BACKUP_PREFIX_V4)

internal const val MIN_BACKUP_PASSPHRASE_LENGTH = 9

internal fun isValidNewBackupPassphrase(passphrase: String): Boolean =
    passphrase.isNotBlank() && passphrase.length >= MIN_BACKUP_PASSPHRASE_LENGTH

private fun PersistedLocalDataSnapshot.toBytes(): ByteArray {
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use { data ->
        data.writeInt(BACKUP_MAGIC)
        data.writeInt(BACKUP_VERSION_V4)
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
        data.writeList(ledgerBooks) { ledgerBook ->
            writeString(ledgerBook.id)
            writeString(ledgerBook.name)
            writeLong(ledgerBook.createdAtEpochMillis)
        }
        data.writeList(pendingEntries, DataOutputStream::writePendingEntry)
        data.writeList(ledgerEntries, DataOutputStream::writeLedgerEntryV4)
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
        require(
            version == BACKUP_VERSION_V2 ||
                version == BACKUP_VERSION_V3 ||
                version == BACKUP_VERSION_V4
        ) {
            "Unsupported backup version"
        }
        val categories = data.readList {
            CategoryEntity(
                id = readString(),
                name = readString(),
                kind = readNullableString()?.let(TransactionKind::valueOf),
                sortOrder = readInt(),
                isSystem = readBoolean(),
                createdAtEpochMillis = readLong()
            )
        }
        val fundingAccounts = data.readList {
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
        }
        val ledgerBooks = if (version == BACKUP_VERSION_V4) {
            data.readList {
                LedgerBookEntity(
                    id = readString(),
                    name = readString(),
                    createdAtEpochMillis = readLong()
                )
            }
        } else {
            listOf(defaultBackupLedgerBook())
        }
        val pendingEntries = data.readList(DataInputStream::readPendingEntry)
        val ledgerEntries = when (version) {
            BACKUP_VERSION_V2 -> data.readList(DataInputStream::readLedgerEntryV2)
            BACKUP_VERSION_V3 -> data.readList(DataInputStream::readLedgerEntryV3)
            else -> data.readList(DataInputStream::readLedgerEntryV4)
        }
        val ignoredEntries = data.readList(DataInputStream::readIgnoredEntry)
        val categorizationRules = data.readList(DataInputStream::readCategorizationRule)
        val settingsPresent = data.readBoolean()
        val settings = when {
            !settingsPresent -> defaultBackupSettings(ledgerBooks.first().id)
            version == BACKUP_VERSION_V4 -> data.readSettingsV4()
            else -> data.readSettingsV3()
        }
        val snapshot = PersistedLocalDataSnapshot(
            categories = categories,
            fundingAccounts = fundingAccounts,
            pendingEntries = pendingEntries,
            ledgerEntries = ledgerEntries,
            ignoredEntries = ignoredEntries,
            categorizationRules = categorizationRules,
            settings = settings,
            ledgerBooks = ledgerBooks
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

private fun DataOutputStream.writeLedgerEntryV4(entry: LedgerEntryEntity) {
    writeString(entry.id)
    writeString(entry.ledgerBookId)
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

private fun DataInputStream.readLedgerEntryV4(): LedgerEntryEntity = LedgerEntryEntity(
    id = readString(),
    ledgerBookId = readString(),
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
    writeString(settings.activeLedgerId)
}

private fun DataInputStream.readSettingsV4(): LocalSettingsEntity = LocalSettingsEntity(
    id = readString(),
    aiConsentGranted = readBoolean(),
    enhancedContextGranted = readBoolean(),
    continuousBillSyncCompleted = readBoolean(),
    continuousMonitoringEnabled = readBoolean(),
    activeLedgerId = readString()
)

private fun DataInputStream.readSettingsV3(): LocalSettingsEntity = LocalSettingsEntity(
    id = readString(),
    aiConsentGranted = readBoolean(),
    enhancedContextGranted = readBoolean(),
    continuousBillSyncCompleted = readBoolean(),
    continuousMonitoringEnabled = readBoolean(),
    activeLedgerId = DEFAULT_LEDGER_BOOK_ID
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

private fun PaymentSource.toScope(): FundingAccountSourceScope = when (this) {
    PaymentSource.WECHAT -> FundingAccountSourceScope.WECHAT
    PaymentSource.ALIPAY -> FundingAccountSourceScope.ALIPAY
}

private fun defaultBackupLedgerBook(): LedgerBookEntity = LedgerBookEntity(
    id = DEFAULT_LEDGER_BOOK_ID,
    name = DEFAULT_LEDGER_BOOK_NAME,
    createdAtEpochMillis = 0
)

private fun defaultBackupSettings(activeLedgerId: String): LocalSettingsEntity =
    LocalSettingsEntity(
        aiConsentGranted = false,
        enhancedContextGranted = false,
        continuousBillSyncCompleted = false,
        continuousMonitoringEnabled = false,
        activeLedgerId = activeLedgerId
    )

private const val BACKUP_PREFIX_V2 = "AUTO_ACCOUNTING_BACKUP_V2:"
private const val BACKUP_PREFIX_V3 = "AUTO_ACCOUNTING_BACKUP_V3:"
private const val BACKUP_PREFIX_V4 = "AUTO_ACCOUNTING_BACKUP_V4:"
private const val BACKUP_MAGIC = 0x41414343
private const val BACKUP_VERSION_V2 = 2
private const val BACKUP_VERSION_V3 = 3
private const val BACKUP_VERSION_V4 = 4
private const val SUPPORTED_BACKUP_CURRENCY = "CNY"
private const val MAX_BACKUP_RECORDS = 1_000_000
private const val MAX_BACKUP_STRING_BYTES = 16 * 1024 * 1024
