package com.autoaccounting.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        CategoryEntity::class,
        FundingAccountEntity::class,
        PendingEntryEntity::class,
        LedgerEntryEntity::class,
        IgnoredEntryEntity::class
    ],
    version = AutoAccountingDatabase.SCHEMA_VERSION,
    exportSchema = true
)
@TypeConverters(LedgerTypeConverters::class)
abstract class AutoAccountingDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun fundingAccountDao(): FundingAccountDao
    abstract fun pendingEntryDao(): PendingEntryDao
    abstract fun ledgerEntryDao(): LedgerEntryDao
    abstract fun ignoredEntryDao(): IgnoredEntryDao

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
