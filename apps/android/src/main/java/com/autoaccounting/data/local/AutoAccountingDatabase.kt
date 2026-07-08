package com.autoaccounting.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        const val SCHEMA_VERSION = 2

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ignored_entries ADD COLUMN capture_reason TEXT NOT NULL DEFAULT 'NOTIFICATION'"
                )
                db.execSQL(
                    "ALTER TABLE ignored_entries ADD COLUMN confidence TEXT NOT NULL DEFAULT 'NEEDS_REVIEW'"
                )
                db.execSQL(
                    "ALTER TABLE ignored_entries ADD COLUMN captured_at_epoch_millis INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("ALTER TABLE pending_entries ADD COLUMN funding_account_label TEXT")
                db.execSQL("ALTER TABLE pending_entries ADD COLUMN parsed_fields_text TEXT")
                db.execSQL("ALTER TABLE ignored_entries ADD COLUMN funding_account_label TEXT")
                db.execSQL("ALTER TABLE ignored_entries ADD COLUMN note TEXT")
                db.execSQL("ALTER TABLE ignored_entries ADD COLUMN evidence_summary TEXT")
                db.execSQL("ALTER TABLE ignored_entries ADD COLUMN parsed_fields_text TEXT")
            }
        }
    }
}
