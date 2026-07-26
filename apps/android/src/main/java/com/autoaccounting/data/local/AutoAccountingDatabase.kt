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
        LedgerBookEntity::class,
        PendingEntryEntity::class,
        LedgerEntryEntity::class,
        IgnoredEntryEntity::class,
        CategorizationRuleEntity::class,
        LocalSettingsEntity::class,
        AccountSyncStateEntity::class,
        AccountSyncMetadataEntity::class,
        AccountSyncOutboxEntity::class,
        AccountSyncConflictEntity::class
    ],
    version = AutoAccountingDatabase.SCHEMA_VERSION,
    exportSchema = true
)
@TypeConverters(LedgerTypeConverters::class)
abstract class AutoAccountingDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun fundingAccountDao(): FundingAccountDao
    abstract fun ledgerBookDao(): LedgerBookDao
    abstract fun pendingEntryDao(): PendingEntryDao
    abstract fun ledgerEntryDao(): LedgerEntryDao
    abstract fun ignoredEntryDao(): IgnoredEntryDao
    abstract fun categorizationRuleDao(): CategorizationRuleDao
    abstract fun localSettingsDao(): LocalSettingsDao
    abstract fun ledgerSyncDao(): LedgerSyncDao

    companion object {
        const val SCHEMA_VERSION = 8

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

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                DefaultCategorizationRules.insertMissing(db)
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE funding_accounts ADD COLUMN payment_source TEXT")
                db.execSQL("UPDATE funding_accounts SET payment_source = source")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ledger_entries_new` (
                        `id` TEXT NOT NULL,
                        `payment_source` TEXT,
                        `original_capture_source` TEXT,
                        `entry_origin` TEXT NOT NULL,
                        `origin_pending_entry_id` TEXT,
                        `flow_direction` TEXT NOT NULL,
                        `transaction_kind` TEXT NOT NULL,
                        `amount_minor` INTEGER NOT NULL,
                        `currency` TEXT NOT NULL,
                        `merchant_title` TEXT NOT NULL,
                        `transaction_time_epoch_millis` INTEGER NOT NULL,
                        `category_id` TEXT,
                        `funding_account_id` INTEGER,
                        `note` TEXT,
                        `evidence_summary` TEXT,
                        `parsed_fields_text` TEXT,
                        `confirmed_at_epoch_millis` INTEGER NOT NULL,
                        `updated_at_epoch_millis` INTEGER NOT NULL,
                        `deleted_at_epoch_millis` INTEGER,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`funding_account_id`) REFERENCES `funding_accounts`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO ledger_entries_new (
                        id, payment_source, original_capture_source, entry_origin,
                        origin_pending_entry_id, flow_direction, transaction_kind,
                        amount_minor, currency, merchant_title, transaction_time_epoch_millis,
                        category_id, funding_account_id, note, evidence_summary, parsed_fields_text,
                        confirmed_at_epoch_millis, updated_at_epoch_millis, deleted_at_epoch_millis
                    )
                    SELECT
                        id, source, source, 'LEGACY_CAPTURE', origin_pending_entry_id,
                        CASE transaction_kind
                            WHEN 'INCOME' THEN 'INFLOW'
                            WHEN 'REFUND' THEN 'INFLOW'
                            ELSE 'OUTFLOW'
                        END,
                        transaction_kind, amount_minor, currency, merchant_title,
                        transaction_time_epoch_millis, category_id, funding_account_id, note,
                        NULL, NULL, confirmed_at_epoch_millis, confirmed_at_epoch_millis, NULL
                    FROM ledger_entries
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE ledger_entries")
                db.execSQL("ALTER TABLE ledger_entries_new RENAME TO ledger_entries")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_payment_source ON ledger_entries(payment_source)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_original_capture_source ON ledger_entries(original_capture_source)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_entry_origin ON ledger_entries(entry_origin)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_flow_direction ON ledger_entries(flow_direction)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_transaction_kind ON ledger_entries(transaction_kind)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_transaction_time_epoch_millis ON ledger_entries(transaction_time_epoch_millis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_category_id ON ledger_entries(category_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_funding_account_id ON ledger_entries(funding_account_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_origin_pending_entry_id ON ledger_entries(origin_pending_entry_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_deleted_at_epoch_millis ON ledger_entries(deleted_at_epoch_millis)")
            }
        }

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ledger_books` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `created_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ledger_books_name` " +
                        "ON `ledger_books` (`name`)"
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO ledger_books (id, name, created_at_epoch_millis)
                    VALUES (?, ?, 0)
                    """.trimIndent(),
                    arrayOf(DEFAULT_LEDGER_BOOK_ID, DEFAULT_LEDGER_BOOK_NAME)
                )
                db.execSQL(
                    "ALTER TABLE local_settings ADD COLUMN active_ledger_id TEXT NOT NULL " +
                        "DEFAULT '$DEFAULT_LEDGER_BOOK_ID'"
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO local_settings (
                        id, ai_consent_granted, enhanced_context_granted,
                        continuous_bill_sync_completed, continuous_monitoring_enabled,
                        active_ledger_id
                    ) VALUES (?, 0, 0, 0, 0, ?)
                    """.trimIndent(),
                    arrayOf(LOCAL_SETTINGS_ID, DEFAULT_LEDGER_BOOK_ID)
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ledger_entries_new` (
                        `id` TEXT NOT NULL,
                        `ledger_book_id` TEXT NOT NULL DEFAULT '$DEFAULT_LEDGER_BOOK_ID',
                        `payment_source` TEXT,
                        `original_capture_source` TEXT,
                        `entry_origin` TEXT NOT NULL,
                        `origin_pending_entry_id` TEXT,
                        `flow_direction` TEXT NOT NULL,
                        `transaction_kind` TEXT NOT NULL,
                        `amount_minor` INTEGER NOT NULL,
                        `currency` TEXT NOT NULL,
                        `merchant_title` TEXT NOT NULL,
                        `transaction_time_epoch_millis` INTEGER NOT NULL,
                        `category_id` TEXT,
                        `funding_account_id` INTEGER,
                        `note` TEXT,
                        `evidence_summary` TEXT,
                        `parsed_fields_text` TEXT,
                        `confirmed_at_epoch_millis` INTEGER NOT NULL,
                        `updated_at_epoch_millis` INTEGER NOT NULL,
                        `deleted_at_epoch_millis` INTEGER,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`funding_account_id`) REFERENCES `funding_accounts`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`ledger_book_id`) REFERENCES `ledger_books`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO ledger_entries_new (
                        id, ledger_book_id, payment_source, original_capture_source, entry_origin,
                        origin_pending_entry_id, flow_direction, transaction_kind, amount_minor,
                        currency, merchant_title, transaction_time_epoch_millis, category_id,
                        funding_account_id, note, evidence_summary, parsed_fields_text,
                        confirmed_at_epoch_millis, updated_at_epoch_millis, deleted_at_epoch_millis
                    )
                    SELECT
                        id, '$DEFAULT_LEDGER_BOOK_ID', payment_source, original_capture_source,
                        entry_origin, origin_pending_entry_id, flow_direction, transaction_kind,
                        amount_minor, currency, merchant_title, transaction_time_epoch_millis,
                        category_id, funding_account_id, note, evidence_summary, parsed_fields_text,
                        confirmed_at_epoch_millis, updated_at_epoch_millis, deleted_at_epoch_millis
                    FROM ledger_entries
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE ledger_entries")
                db.execSQL("ALTER TABLE ledger_entries_new RENAME TO ledger_entries")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_ledger_book_id ON ledger_entries(ledger_book_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_payment_source ON ledger_entries(payment_source)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_original_capture_source ON ledger_entries(original_capture_source)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_entry_origin ON ledger_entries(entry_origin)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_flow_direction ON ledger_entries(flow_direction)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_transaction_kind ON ledger_entries(transaction_kind)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_transaction_time_epoch_millis ON ledger_entries(transaction_time_epoch_millis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_category_id ON ledger_entries(category_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_funding_account_id ON ledger_entries(funding_account_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_origin_pending_entry_id ON ledger_entries(origin_pending_entry_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_deleted_at_epoch_millis ON ledger_entries(deleted_at_epoch_millis)")
            }
        }

        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_ledger_entries_ledger_book_id")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_ledger_entries_book_deleted_transaction_time " +
                        "ON ledger_entries(" +
                        "ledger_book_id, deleted_at_epoch_millis, transaction_time_epoch_millis)"
                )
            }
        }

        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE funding_accounts ADD COLUMN sync_id TEXT")
                db.execSQL(
                    """
                    UPDATE funding_accounts
                    SET sync_id = lower(hex(randomblob(4))) || '-' ||
                        lower(hex(randomblob(2))) || '-' ||
                        lower(hex(randomblob(2))) || '-' ||
                        lower(hex(randomblob(2))) || '-' ||
                        lower(hex(randomblob(6)))
                    WHERE sync_id IS NULL
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_funding_accounts_sync_id " +
                        "ON funding_accounts(sync_id)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS account_sync_state (
                        id TEXT NOT NULL PRIMARY KEY,
                        profile_key TEXT,
                        enabled INTEGER NOT NULL,
                        cursor INTEGER NOT NULL,
                        last_success_at_millis INTEGER,
                        last_error TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS account_sync_metadata (
                        entity_type TEXT NOT NULL,
                        entity_id TEXT NOT NULL,
                        server_version INTEGER NOT NULL,
                        synced_payload TEXT,
                        deleted INTEGER NOT NULL,
                        blocked_by_conflict INTEGER NOT NULL,
                        PRIMARY KEY(entity_type, entity_id)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS account_sync_outbox (
                        mutation_id TEXT NOT NULL PRIMARY KEY,
                        entity_type TEXT NOT NULL,
                        entity_id TEXT NOT NULL,
                        base_version INTEGER NOT NULL,
                        deleted INTEGER NOT NULL,
                        payload TEXT,
                        created_at_millis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_account_sync_outbox_entity_type_entity_id " +
                        "ON account_sync_outbox(entity_type, entity_id)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS account_sync_conflicts (
                        conflict_id TEXT NOT NULL PRIMARY KEY,
                        entity_type TEXT NOT NULL,
                        entity_id TEXT NOT NULL,
                        canonical_version INTEGER NOT NULL,
                        canonical_deleted INTEGER NOT NULL,
                        canonical_payload TEXT,
                        candidate_deleted INTEGER NOT NULL,
                        candidate_payload TEXT,
                        created_at_millis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
