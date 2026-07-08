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
        IgnoredEntryEntity::class,
        CategorizationRuleEntity::class,
        LocalSettingsEntity::class
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
    abstract fun categorizationRuleDao(): CategorizationRuleDao
    abstract fun localSettingsDao(): LocalSettingsDao

    companion object {
        const val SCHEMA_VERSION = 3

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

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_entries ADD COLUMN suggested_category_label TEXT")
                db.execSQL("ALTER TABLE ignored_entries ADD COLUMN suggested_category_label TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `categorization_rules` (
                        `id` TEXT NOT NULL,
                        `merchant_contains` TEXT NOT NULL,
                        `title_contains` TEXT NOT NULL,
                        `source_label` TEXT NOT NULL,
                        `transaction_kind` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `priority` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `updated_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_settings` (
                        `id` TEXT NOT NULL,
                        `ai_consent_granted` INTEGER NOT NULL,
                        `enhanced_context_granted` INTEGER NOT NULL,
                        `continuous_bill_sync_completed` INTEGER NOT NULL,
                        `continuous_monitoring_enabled` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
