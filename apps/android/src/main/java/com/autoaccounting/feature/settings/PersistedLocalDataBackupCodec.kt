package com.autoaccounting.feature.settings

import com.autoaccounting.data.local.CategoryEntity
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.FundingAccountSourceScope
import com.autoaccounting.data.local.LedgerBookEntity
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.TransactionKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal fun PersistedLocalDataSnapshot.toBytes(): ByteArray {
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use { data ->
        data.writeInt(BACKUP_MAGIC)
        data.writeInt(BACKUP_VERSION_V5)
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
        settings?.let { data.writeSettingsV5(it) }
    }
    return output.toByteArray()
}

@Suppress("CyclomaticComplexMethod")
internal fun snapshotFromBytes(bytes: ByteArray): PersistedLocalDataSnapshot =
    DataInputStream(ByteArrayInputStream(bytes)).use { data ->
        require(data.readInt() == BACKUP_MAGIC) { "Invalid backup payload" }
        val version = data.readInt()
        require(
            version == BACKUP_VERSION_V2 ||
                version == BACKUP_VERSION_V3 ||
                version == BACKUP_VERSION_V4 ||
                version == BACKUP_VERSION_V5
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
        val ledgerBooks = if (version == BACKUP_VERSION_V4 || version == BACKUP_VERSION_V5) {
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
            version == BACKUP_VERSION_V5 -> data.readSettingsV5()
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

internal const val BACKUP_MAGIC = 0x41414343
internal const val BACKUP_VERSION_V2 = 2
internal const val BACKUP_VERSION_V3 = 3
internal const val BACKUP_VERSION_V4 = 4
internal const val BACKUP_VERSION_V5 = 5
internal const val MAX_BACKUP_RECORDS = 1_000_000
internal const val MAX_BACKUP_STRING_BYTES = 16 * 1024 * 1024
