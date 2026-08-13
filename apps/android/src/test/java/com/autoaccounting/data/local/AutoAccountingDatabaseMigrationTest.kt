package com.autoaccounting.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.autoaccounting.feature.ledger.categoryExpenseTotals
import com.autoaccounting.feature.ledger.monthlySummary
import com.autoaccounting.feature.ledger.toLedgerUiEntry
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoAccountingDatabaseMigrationTest {
    @Test
    fun schemaVersionIsCurrent() {
        assertEquals(9, AutoAccountingDatabase.SCHEMA_VERSION)
    }

    @Test
    fun migrationFromFiveToCurrentAssignsEntriesAndAddsScopedIndex() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "migration-5-6-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val versionFive = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        createVersionFiveSchema(versionFive)
        versionFive.execSQL(
            """
            INSERT INTO local_settings (
                id, ai_consent_granted, enhanced_context_granted,
                continuous_bill_sync_completed, continuous_monitoring_enabled
            ) VALUES ('local', 1, 0, 1, 0)
            """.trimIndent()
        )
        insertVersionFiveLedger(
            database = versionFive,
            id = "v5-active",
            deletedAtEpochMillis = null
        )
        insertVersionFiveLedger(
            database = versionFive,
            id = "v5-deleted",
            deletedAtEpochMillis = NOW - 1_000
        )
        versionFive.execSQL("PRAGMA user_version = 5")
        versionFive.close()

        val migrated = Room.databaseBuilder(
            context,
            AutoAccountingDatabase::class.java,
            databaseName
        )
            .addMigrations(AutoAccountingDatabase.MIGRATION_5_6)
            .addMigrations(AutoAccountingDatabase.MIGRATION_6_7)
            .addMigrations(AutoAccountingDatabase.MIGRATION_7_8)
            .addMigrations(AutoAccountingDatabase.MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()

        val ledgerBooks = runBlocking {
            migrated.ledgerBookDao().getAll()
        }
        val entries = runBlocking {
            migrated.ledgerEntryDao().listAllLedgerEntries()
        }
        val settings = runBlocking {
            migrated.localSettingsDao().getById()
        }
        val writableDatabase = migrated.openHelper.writableDatabase
        val ledgerEntryIndexes = mutableSetOf<String>()
        val activeLedgerQueryPlan = mutableListOf<String>()
        writableDatabase.query("PRAGMA index_list(`ledger_entries`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                ledgerEntryIndexes += cursor.getString(nameColumn)
            }
        }
        writableDatabase.query(
            """
            EXPLAIN QUERY PLAN
            SELECT * FROM ledger_entries
            WHERE ledger_book_id = '$DEFAULT_LEDGER_BOOK_ID'
                AND deleted_at_epoch_millis IS NULL
            ORDER BY transaction_time_epoch_millis DESC
            """.trimIndent()
        ).use { cursor ->
            val detailColumn = cursor.getColumnIndexOrThrow("detail")
            while (cursor.moveToNext()) {
                activeLedgerQueryPlan += cursor.getString(detailColumn)
            }
        }
        var hasRestrictLedgerBookForeignKey = false
        writableDatabase.query("PRAGMA foreign_key_list(`ledger_entries`)").use { cursor ->
            val tableColumn = cursor.getColumnIndexOrThrow("table")
            val fromColumn = cursor.getColumnIndexOrThrow("from")
            val onDeleteColumn = cursor.getColumnIndexOrThrow("on_delete")
            while (cursor.moveToNext()) {
                if (
                    cursor.getString(tableColumn) == "ledger_books" &&
                    cursor.getString(fromColumn) == "ledger_book_id" &&
                    cursor.getString(onDeleteColumn) == "RESTRICT"
                ) {
                    hasRestrictLedgerBookForeignKey = true
                }
            }
        }
        var hasUniqueLedgerBookNameIndex = false
        writableDatabase.query("PRAGMA index_list(`ledger_books`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            val uniqueColumn = cursor.getColumnIndexOrThrow("unique")
            while (cursor.moveToNext()) {
                if (
                    cursor.getString(nameColumn) == "index_ledger_books_name" &&
                    cursor.getInt(uniqueColumn) == 1
                ) {
                    hasUniqueLedgerBookNameIndex = true
                }
            }
        }
        val deleteFailure = runCatching {
            runBlocking {
                migrated.ledgerBookDao().deleteById(DEFAULT_LEDGER_BOOK_ID)
            }
        }.exceptionOrNull()

        assertEquals(
            listOf(
                LedgerBookEntity(
                    id = DEFAULT_LEDGER_BOOK_ID,
                    name = DEFAULT_LEDGER_BOOK_NAME,
                    createdAtEpochMillis = 0
                )
            ),
            ledgerBooks
        )
        assertEquals(
            setOf("v5-active", "v5-deleted"),
            entries.map { it.id }.toSet()
        )
        assertTrue(entries.all { it.ledgerBookId == DEFAULT_LEDGER_BOOK_ID })
        val activeEntry = entries.single { it.id == "v5-active" }
        assertNull(activeEntry.deletedAtEpochMillis)
        assertEquals(PaymentSource.WECHAT, activeEntry.paymentSource)
        assertEquals(PaymentSource.WECHAT, activeEntry.originalCaptureSource)
        assertEquals(EntryOrigin.NOTIFICATION, activeEntry.entryOrigin)
        assertEquals("pending-v5-active", activeEntry.originPendingEntryId)
        assertEquals("微信支付收款凭证", activeEntry.evidenceSummary)
        assertEquals("金额=15.90", activeEntry.parsedFieldsText)
        assertEquals(
            NOW - 1_000,
            entries.single { it.id == "v5-deleted" }.deletedAtEpochMillis
        )
        assertEquals(DEFAULT_LEDGER_BOOK_ID, settings?.activeLedgerId)
        assertEquals(true, settings?.aiConsentGranted)
        assertEquals(true, settings?.continuousBillSyncCompleted)
        assertTrue("index_ledger_entries_book_deleted_transaction_time" in ledgerEntryIndexes)
        assertTrue("index_ledger_entries_ledger_book_id" !in ledgerEntryIndexes)
        assertTrue(
            activeLedgerQueryPlan.any {
                it.contains("index_ledger_entries_book_deleted_transaction_time")
            }
        )
        assertTrue(hasRestrictLedgerBookForeignKey)
        assertTrue(hasUniqueLedgerBookNameIndex)
        assertTrue(deleteFailure != null)

        migrated.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationFromOneToTwoPreservesIgnoredAndPendingReviewMetadata() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "migration-1-2-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val legacyDatabase = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        legacyDatabase.execSQL(LEGACY_CREATE_CATEGORIES)
        legacyDatabase.execSQL(LEGACY_CREATE_FUNDING_ACCOUNTS)
        legacyDatabase.execSQL(LEGACY_CREATE_PENDING_ENTRIES)
        legacyDatabase.execSQL(LEGACY_CREATE_LEDGER_ENTRIES)
        legacyDatabase.execSQL(LEGACY_CREATE_IGNORED_ENTRIES)
        LEGACY_INDEXES.forEach(legacyDatabase::execSQL)
        legacyDatabase.execSQL(
            """
            INSERT INTO ignored_entries (
                id, original_pending_entry_id, source, transaction_kind, amount_minor, currency,
                merchant_title, transaction_time_epoch_millis, suggested_category_id,
                funding_account_id, ignored_at_epoch_millis, expires_at_epoch_millis, reason
            ) VALUES (
                'ignored-legacy', 'pending-legacy', 'WECHAT', 'EXPENSE', 1590, 'CNY',
                '便利店', ${NOW - 60_000}, NULL, NULL, $NOW,
                ${NOW + LocalLedgerRepository.IGNORED_RETENTION_MILLIS}, 'USER_IGNORED'
            )
            """.trimIndent()
        )
        legacyDatabase.execSQL(
            """
            INSERT INTO pending_entries (
                id, source, capture_reason, confidence, transaction_kind, amount_minor,
                currency, merchant_title, transaction_time_epoch_millis, captured_at_epoch_millis,
                suggested_category_id, funding_account_id, note, evidence_summary
            ) VALUES (
                'pending-legacy', 'ALIPAY', 'BILL_SYNC', 'HIGH', 'EXPENSE', 3590,
                'CNY', '午餐', ${NOW - 120_000}, $NOW, NULL, NULL, NULL, '支付宝账单 午餐 35.90'
            )
            """.trimIndent()
        )
        legacyDatabase.execSQL(
            """
            INSERT INTO funding_accounts (id, source, label, created_at_epoch_millis)
            VALUES (7, 'ALIPAY', '余额', $NOW)
            """.trimIndent()
        )
        legacyDatabase.execSQL(
            """
            INSERT INTO ledger_entries (
                id, source, origin_pending_entry_id, transaction_kind, amount_minor,
                currency, merchant_title, transaction_time_epoch_millis, category_id,
                funding_account_id, note, confirmed_at_epoch_millis
            ) VALUES (
                'ledger-legacy', 'ALIPAY', 'pending-confirmed', 'TRANSFER', 20000,
                'CNY', '账户间转账', ${NOW - 30_000}, NULL, 7, '旧账目', $NOW
            )
            """.trimIndent()
        )
        legacyDatabase.execSQL("PRAGMA user_version = 1")
        legacyDatabase.close()

        val migratedDatabase = Room.databaseBuilder(
            context,
            AutoAccountingDatabase::class.java,
            databaseName
        )
            .addMigrations(AutoAccountingDatabase.MIGRATION_1_2)
            .addMigrations(AutoAccountingDatabase.MIGRATION_2_3)
            .addMigrations(AutoAccountingDatabase.MIGRATION_3_4)
            .addMigrations(AutoAccountingDatabase.MIGRATION_4_5)
            .addMigrations(AutoAccountingDatabase.MIGRATION_5_6)
            .addMigrations(AutoAccountingDatabase.MIGRATION_6_7)
            .addMigrations(AutoAccountingDatabase.MIGRATION_7_8)
            .addMigrations(AutoAccountingDatabase.MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()

        val ignored = runBlocking {
            migratedDatabase.ignoredEntryDao().listRecoverable(NOW).single()
        }
        val pending = runBlocking {
            migratedDatabase.pendingEntryDao().getById("pending-legacy")
        }
        val ledger = runBlocking {
            migratedDatabase.ledgerEntryDao().getById("ledger-legacy")
        }
        val fundingAccount = runBlocking {
            migratedDatabase.fundingAccountDao().getAllFundingAccounts().single()
        }

        assertEquals(CaptureReason.NOTIFICATION, ignored.captureReason)
        assertEquals(ConfidenceState.NEEDS_REVIEW, ignored.confidence)
        assertEquals(0, ignored.capturedAtEpochMillis)
        assertNull(ignored.fundingAccountLabel)
        assertNull(ignored.parsedFieldsText)
        assertNull(ignored.suggestedCategoryLabel)
        assertEquals("支付宝账单 午餐 35.90", pending?.evidenceSummary)
        assertNull(pending?.fundingAccountLabel)
        assertNull(pending?.parsedFieldsText)
        assertNull(pending?.suggestedCategoryLabel)
        assertEquals(FlowDirection.OUTFLOW, ledger?.flowDirection)
        assertEquals(PaymentSource.ALIPAY, ledger?.paymentSource)
        assertEquals(PaymentSource.ALIPAY, ledger?.originalCaptureSource)
        assertEquals(EntryOrigin.LEGACY_CAPTURE, ledger?.entryOrigin)
        assertEquals(NOW, ledger?.updatedAtEpochMillis)
        assertNull(ledger?.deletedAtEpochMillis)
        assertEquals(FundingAccountSourceScope.ALIPAY, fundingAccount.sourceScope)
        assertEquals(PaymentSource.ALIPAY, fundingAccount.paymentSource)
        assertNotNull(fundingAccount.syncId)
        assertTrue(fundingAccount.syncId?.matches(
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        ) == true)
        val syncTables = mutableSetOf<String>()
        migratedDatabase.openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'account_sync_%'"
        ).use { cursor ->
            while (cursor.moveToNext()) syncTables += cursor.getString(0)
        }
        assertEquals(
            setOf(
                "account_sync_state",
                "account_sync_metadata",
                "account_sync_outbox",
                "account_sync_conflicts"
            ),
            syncTables
        )

        runBlocking {
            assertEquals(
                DefaultCategorizationRules.rules.map { it.id }.toSet(),
                migratedDatabase.categorizationRuleDao().listRules().map { it.id }.toSet()
            )
            assertEquals(
                DEFAULT_LEDGER_BOOK_ID,
                migratedDatabase.localSettingsDao().getById()?.activeLedgerId
            )
        }

        migratedDatabase.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationFromFourToSixRetainsLedgerFundingAndReportSemantics() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "migration-4-6-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val versionFour = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        createVersionFourSchema(versionFour)
        versionFour.execSQL(
            "INSERT INTO categories (id, name, kind, sort_order, is_system, created_at_epoch_millis) VALUES " +
                "('food', '餐饮', 'EXPENSE', 1, 1, $NOW), " +
                "('salary', '工资', 'INCOME', 2, 1, $NOW)"
        )
        versionFour.execSQL(
            "INSERT INTO funding_accounts (id, source, label, created_at_epoch_millis) VALUES " +
                "(7, 'ALIPAY', '支付宝余额', $NOW), " +
                "(8, 'WECHAT', '微信零钱', $NOW)"
        )
        insertVersionFourLedger(
            database = versionFour,
            id = "legacy-income",
            source = "WECHAT",
            kind = "INCOME",
            amountMinor = 1_000,
            title = "工资",
            categoryId = "salary",
            fundingAccountId = 8
        )
        insertVersionFourLedger(
            database = versionFour,
            id = "legacy-refund",
            source = "ALIPAY",
            kind = "REFUND",
            amountMinor = 250,
            title = "退款",
            categoryId = "food",
            fundingAccountId = 7
        )
        insertVersionFourLedger(
            database = versionFour,
            id = "legacy-expense",
            source = "ALIPAY",
            kind = "EXPENSE",
            amountMinor = 1_000,
            title = "午餐",
            categoryId = "food",
            fundingAccountId = 7
        )
        insertVersionFourLedger(
            database = versionFour,
            id = "legacy-transfer",
            source = "WECHAT",
            kind = "TRANSFER",
            amountMinor = 2_000,
            title = "账户转账",
            categoryId = null,
            fundingAccountId = 8
        )
        versionFour.execSQL("PRAGMA user_version = 4")
        versionFour.close()

        val migrated = Room.databaseBuilder(context, AutoAccountingDatabase::class.java, databaseName)
            .addMigrations(AutoAccountingDatabase.MIGRATION_4_5)
            .addMigrations(AutoAccountingDatabase.MIGRATION_5_6)
            .addMigrations(AutoAccountingDatabase.MIGRATION_6_7)
            .addMigrations(AutoAccountingDatabase.MIGRATION_7_8)
            .addMigrations(AutoAccountingDatabase.MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()

        val ledgerEntries = migrated.ledgerEntryDao().listLedgerEntries().sortedBy { it.id }
        val ledgerBooks = migrated.ledgerBookDao().getAll()
        val fundingAccounts = migrated.fundingAccountDao().getAllFundingAccounts().sortedBy { it.id }
        val byId = ledgerEntries.associateBy { it.id }
        val reportEntries = ledgerEntries.map { it.toLedgerUiEntry(ZoneOffset.UTC) }
        val summary = monthlySummary(reportEntries, "2024-12")

        assertEquals(setOf("legacy-income", "legacy-refund", "legacy-expense", "legacy-transfer"), byId.keys)
        assertEquals(listOf(DEFAULT_LEDGER_BOOK_ID), ledgerBooks.map { it.id })
        assertEquals(FlowDirection.INFLOW, byId.getValue("legacy-income").flowDirection)
        assertEquals(FlowDirection.INFLOW, byId.getValue("legacy-refund").flowDirection)
        assertEquals(FlowDirection.OUTFLOW, byId.getValue("legacy-expense").flowDirection)
        assertEquals(FlowDirection.OUTFLOW, byId.getValue("legacy-transfer").flowDirection)
        assertEquals(PaymentSource.ALIPAY, byId.getValue("legacy-expense").paymentSource)
        assertEquals(PaymentSource.ALIPAY, byId.getValue("legacy-expense").originalCaptureSource)
        assertEquals(EntryOrigin.LEGACY_CAPTURE, byId.getValue("legacy-expense").entryOrigin)
        assertTrue(byId.values.all { it.ledgerBookId == DEFAULT_LEDGER_BOOK_ID })
        assertEquals("午餐", byId.getValue("legacy-expense").merchantTitle)
        assertEquals("旧账目", byId.getValue("legacy-expense").note)
        assertEquals(NOW, byId.getValue("legacy-expense").confirmedAtEpochMillis)
        assertEquals(NOW, byId.getValue("legacy-expense").updatedAtEpochMillis)
        assertEquals(3_000, summary.expenseMinor)
        assertEquals(1_250, summary.incomeMinor)
        assertEquals(-1_750, summary.netMinor)
        assertEquals(
            1_000,
            categoryExpenseTotals(reportEntries, "2024-12").single { it.category == "餐饮" }.amountMinor
        )
        assertEquals(
            listOf(
                FundingAccountSourceScope.ALIPAY to PaymentSource.ALIPAY,
                FundingAccountSourceScope.WECHAT to PaymentSource.WECHAT
            ),
            fundingAccounts.map { it.sourceScope to it.paymentSource }
        )
        assertTrue(fundingAccounts.all { it.syncId != null })
        assertEquals(fundingAccounts.size, fundingAccounts.map { it.syncId }.toSet().size)

        migrated.close()
        context.deleteDatabase(databaseName)
        Unit
    }

    private fun createVersionFiveSchema(database: SQLiteDatabase) {
        database.execSQL(LEGACY_CREATE_CATEGORIES)
        database.execSQL(VERSION_FIVE_CREATE_FUNDING_ACCOUNTS)
        database.execSQL(LEGACY_CREATE_PENDING_ENTRIES)
        database.execSQL("ALTER TABLE pending_entries ADD COLUMN funding_account_label TEXT")
        database.execSQL("ALTER TABLE pending_entries ADD COLUMN parsed_fields_text TEXT")
        database.execSQL("ALTER TABLE pending_entries ADD COLUMN suggested_category_label TEXT")
        database.execSQL(VERSION_FIVE_CREATE_LEDGER_ENTRIES)
        database.execSQL(LEGACY_CREATE_IGNORED_ENTRIES)
        database.execSQL("ALTER TABLE ignored_entries ADD COLUMN capture_reason TEXT NOT NULL DEFAULT 'NOTIFICATION'")
        database.execSQL("ALTER TABLE ignored_entries ADD COLUMN confidence TEXT NOT NULL DEFAULT 'NEEDS_REVIEW'")
        database.execSQL("ALTER TABLE ignored_entries ADD COLUMN captured_at_epoch_millis INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE ignored_entries ADD COLUMN funding_account_label TEXT")
        database.execSQL("ALTER TABLE ignored_entries ADD COLUMN note TEXT")
        database.execSQL("ALTER TABLE ignored_entries ADD COLUMN evidence_summary TEXT")
        database.execSQL("ALTER TABLE ignored_entries ADD COLUMN parsed_fields_text TEXT")
        database.execSQL("ALTER TABLE ignored_entries ADD COLUMN suggested_category_label TEXT")
        database.execSQL(CREATE_CATEGORIZATION_RULES)
        database.execSQL(CREATE_LOCAL_SETTINGS)
        LEGACY_INDEXES
            .filterNot { it.contains("index_ledger_entries_") }
            .forEach(database::execSQL)
        VERSION_FIVE_LEDGER_INDEXES.forEach(database::execSQL)
    }

    private fun insertVersionFiveLedger(
        database: SQLiteDatabase,
        id: String,
        deletedAtEpochMillis: Long?
    ) {
        database.execSQL(
            """
            INSERT INTO ledger_entries (
                id, payment_source, original_capture_source, entry_origin,
                origin_pending_entry_id, flow_direction, transaction_kind,
                amount_minor, currency, merchant_title, transaction_time_epoch_millis,
                category_id, funding_account_id, note, evidence_summary, parsed_fields_text,
                confirmed_at_epoch_millis, updated_at_epoch_millis, deleted_at_epoch_millis
            ) VALUES (?, 'WECHAT', 'WECHAT', 'NOTIFICATION', ?, 'OUTFLOW', 'EXPENSE',
                1590, 'CNY', '便利店', ?, NULL, NULL, '旧账目', '微信支付收款凭证',
                '金额=15.90', ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                id,
                "pending-$id",
                NOW - 60_000,
                NOW,
                NOW,
                deletedAtEpochMillis
            )
        )
    }

    private fun createVersionFourSchema(database: SQLiteDatabase) {
        database.execSQL(LEGACY_CREATE_CATEGORIES)
        database.execSQL(LEGACY_CREATE_FUNDING_ACCOUNTS)
        database.execSQL(LEGACY_CREATE_PENDING_ENTRIES)
        database.execSQL(LEGACY_CREATE_LEDGER_ENTRIES)
        database.execSQL(LEGACY_CREATE_IGNORED_ENTRIES)
        database.execSQL("ALTER TABLE pending_entries ADD COLUMN funding_account_label TEXT")
        database.execSQL("ALTER TABLE pending_entries ADD COLUMN parsed_fields_text TEXT")
        database.execSQL("ALTER TABLE pending_entries ADD COLUMN suggested_category_label TEXT")
        database.execSQL("ALTER TABLE ignored_entries ADD COLUMN capture_reason TEXT NOT NULL DEFAULT 'NOTIFICATION'")
        database.execSQL("ALTER TABLE ignored_entries ADD COLUMN confidence TEXT NOT NULL DEFAULT 'NEEDS_REVIEW'")
        database.execSQL("ALTER TABLE ignored_entries ADD COLUMN captured_at_epoch_millis INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE ignored_entries ADD COLUMN funding_account_label TEXT")
        database.execSQL("ALTER TABLE ignored_entries ADD COLUMN note TEXT")
        database.execSQL("ALTER TABLE ignored_entries ADD COLUMN evidence_summary TEXT")
        database.execSQL("ALTER TABLE ignored_entries ADD COLUMN parsed_fields_text TEXT")
        database.execSQL("ALTER TABLE ignored_entries ADD COLUMN suggested_category_label TEXT")
        database.execSQL(CREATE_CATEGORIZATION_RULES)
        database.execSQL(CREATE_LOCAL_SETTINGS)
        LEGACY_INDEXES.forEach(database::execSQL)
    }

    private fun insertVersionFourLedger(
        database: SQLiteDatabase,
        id: String,
        source: String,
        kind: String,
        amountMinor: Long,
        title: String,
        categoryId: String?,
        fundingAccountId: Long
    ) {
        database.execSQL(
            """
            INSERT INTO ledger_entries (
                id, source, origin_pending_entry_id, transaction_kind, amount_minor, currency,
                merchant_title, transaction_time_epoch_millis, category_id, funding_account_id, note,
                confirmed_at_epoch_millis
            ) VALUES (?, ?, NULL, ?, ?, 'CNY', ?, ?, ?, ?, '旧账目', ?)
            """.trimIndent(),
            arrayOf<Any?>(
                id,
                source,
                kind,
                amountMinor,
                title,
                NOW - 60_000,
                categoryId,
                fundingAccountId,
                NOW
            )
        )
    }

    private companion object {
        const val NOW = 1_735_689_600_000L

        const val VERSION_FIVE_CREATE_FUNDING_ACCOUNTS =
            "CREATE TABLE IF NOT EXISTS `funding_accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `source` TEXT NOT NULL, `payment_source` TEXT, `label` TEXT NOT NULL, `created_at_epoch_millis` INTEGER NOT NULL)"
        const val VERSION_FIVE_CREATE_LEDGER_ENTRIES =
            "CREATE TABLE IF NOT EXISTS `ledger_entries` (`id` TEXT NOT NULL, `payment_source` TEXT, `original_capture_source` TEXT, `entry_origin` TEXT NOT NULL, `origin_pending_entry_id` TEXT, `flow_direction` TEXT NOT NULL, `transaction_kind` TEXT NOT NULL, `amount_minor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `merchant_title` TEXT NOT NULL, `transaction_time_epoch_millis` INTEGER NOT NULL, `category_id` TEXT, `funding_account_id` INTEGER, `note` TEXT, `evidence_summary` TEXT, `parsed_fields_text` TEXT, `confirmed_at_epoch_millis` INTEGER NOT NULL, `updated_at_epoch_millis` INTEGER NOT NULL, `deleted_at_epoch_millis` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`funding_account_id`) REFERENCES `funding_accounts`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"
        const val LEGACY_CREATE_CATEGORIES =
            "CREATE TABLE IF NOT EXISTS `categories` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT, `sort_order` INTEGER NOT NULL, `is_system` INTEGER NOT NULL, `created_at_epoch_millis` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        const val LEGACY_CREATE_FUNDING_ACCOUNTS =
            "CREATE TABLE IF NOT EXISTS `funding_accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `source` TEXT NOT NULL, `label` TEXT NOT NULL, `created_at_epoch_millis` INTEGER NOT NULL)"
        const val LEGACY_CREATE_PENDING_ENTRIES =
            "CREATE TABLE IF NOT EXISTS `pending_entries` (`id` TEXT NOT NULL, `source` TEXT NOT NULL, `capture_reason` TEXT NOT NULL, `confidence` TEXT NOT NULL, `transaction_kind` TEXT NOT NULL, `amount_minor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `merchant_title` TEXT NOT NULL, `transaction_time_epoch_millis` INTEGER NOT NULL, `captured_at_epoch_millis` INTEGER NOT NULL, `suggested_category_id` TEXT, `funding_account_id` INTEGER, `note` TEXT, `evidence_summary` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`suggested_category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`funding_account_id`) REFERENCES `funding_accounts`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"
        const val LEGACY_CREATE_LEDGER_ENTRIES =
            "CREATE TABLE IF NOT EXISTS `ledger_entries` (`id` TEXT NOT NULL, `source` TEXT NOT NULL, `origin_pending_entry_id` TEXT, `transaction_kind` TEXT NOT NULL, `amount_minor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `merchant_title` TEXT NOT NULL, `transaction_time_epoch_millis` INTEGER NOT NULL, `category_id` TEXT, `funding_account_id` INTEGER, `note` TEXT, `confirmed_at_epoch_millis` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`funding_account_id`) REFERENCES `funding_accounts`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"
        const val LEGACY_CREATE_IGNORED_ENTRIES =
            "CREATE TABLE IF NOT EXISTS `ignored_entries` (`id` TEXT NOT NULL, `original_pending_entry_id` TEXT NOT NULL, `source` TEXT NOT NULL, `transaction_kind` TEXT NOT NULL, `amount_minor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `merchant_title` TEXT NOT NULL, `transaction_time_epoch_millis` INTEGER NOT NULL, `suggested_category_id` TEXT, `funding_account_id` INTEGER, `ignored_at_epoch_millis` INTEGER NOT NULL, `expires_at_epoch_millis` INTEGER NOT NULL, `reason` TEXT NOT NULL, PRIMARY KEY(`id`))"
        const val CREATE_CATEGORIZATION_RULES =
            "CREATE TABLE IF NOT EXISTS `categorization_rules` (`id` TEXT NOT NULL, `merchant_contains` TEXT NOT NULL, `title_contains` TEXT NOT NULL, `source_label` TEXT NOT NULL, `transaction_kind` TEXT NOT NULL, `category` TEXT NOT NULL, `priority` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `updated_at_epoch_millis` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        const val CREATE_LOCAL_SETTINGS =
            "CREATE TABLE IF NOT EXISTS `local_settings` (`id` TEXT NOT NULL, `ai_consent_granted` INTEGER NOT NULL, `enhanced_context_granted` INTEGER NOT NULL, `continuous_bill_sync_completed` INTEGER NOT NULL, `continuous_monitoring_enabled` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        val LEGACY_INDEXES = listOf(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name` ON `categories` (`name`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_funding_accounts_source_label` ON `funding_accounts` (`source`, `label`)",
            "CREATE INDEX IF NOT EXISTS `index_pending_entries_source` ON `pending_entries` (`source`)",
            "CREATE INDEX IF NOT EXISTS `index_pending_entries_confidence` ON `pending_entries` (`confidence`)",
            "CREATE INDEX IF NOT EXISTS `index_pending_entries_transaction_time_epoch_millis` ON `pending_entries` (`transaction_time_epoch_millis`)",
            "CREATE INDEX IF NOT EXISTS `index_pending_entries_suggested_category_id` ON `pending_entries` (`suggested_category_id`)",
            "CREATE INDEX IF NOT EXISTS `index_pending_entries_funding_account_id` ON `pending_entries` (`funding_account_id`)",
            "CREATE INDEX IF NOT EXISTS `index_ledger_entries_source` ON `ledger_entries` (`source`)",
            "CREATE INDEX IF NOT EXISTS `index_ledger_entries_transaction_kind` ON `ledger_entries` (`transaction_kind`)",
            "CREATE INDEX IF NOT EXISTS `index_ledger_entries_transaction_time_epoch_millis` ON `ledger_entries` (`transaction_time_epoch_millis`)",
            "CREATE INDEX IF NOT EXISTS `index_ledger_entries_category_id` ON `ledger_entries` (`category_id`)",
            "CREATE INDEX IF NOT EXISTS `index_ledger_entries_funding_account_id` ON `ledger_entries` (`funding_account_id`)",
            "CREATE INDEX IF NOT EXISTS `index_ledger_entries_origin_pending_entry_id` ON `ledger_entries` (`origin_pending_entry_id`)",
            "CREATE INDEX IF NOT EXISTS `index_ignored_entries_source` ON `ignored_entries` (`source`)",
            "CREATE INDEX IF NOT EXISTS `index_ignored_entries_ignored_at_epoch_millis` ON `ignored_entries` (`ignored_at_epoch_millis`)",
            "CREATE INDEX IF NOT EXISTS `index_ignored_entries_expires_at_epoch_millis` ON `ignored_entries` (`expires_at_epoch_millis`)",
            "CREATE INDEX IF NOT EXISTS `index_ignored_entries_original_pending_entry_id` ON `ignored_entries` (`original_pending_entry_id`)"
        )
        val VERSION_FIVE_LEDGER_INDEXES = listOf(
            "CREATE INDEX IF NOT EXISTS `index_ledger_entries_payment_source` ON `ledger_entries` (`payment_source`)",
            "CREATE INDEX IF NOT EXISTS `index_ledger_entries_original_capture_source` ON `ledger_entries` (`original_capture_source`)",
            "CREATE INDEX IF NOT EXISTS `index_ledger_entries_entry_origin` ON `ledger_entries` (`entry_origin`)",
            "CREATE INDEX IF NOT EXISTS `index_ledger_entries_flow_direction` ON `ledger_entries` (`flow_direction`)",
            "CREATE INDEX IF NOT EXISTS `index_ledger_entries_transaction_kind` ON `ledger_entries` (`transaction_kind`)",
            "CREATE INDEX IF NOT EXISTS `index_ledger_entries_transaction_time_epoch_millis` ON `ledger_entries` (`transaction_time_epoch_millis`)",
            "CREATE INDEX IF NOT EXISTS `index_ledger_entries_category_id` ON `ledger_entries` (`category_id`)",
            "CREATE INDEX IF NOT EXISTS `index_ledger_entries_funding_account_id` ON `ledger_entries` (`funding_account_id`)",
            "CREATE INDEX IF NOT EXISTS `index_ledger_entries_origin_pending_entry_id` ON `ledger_entries` (`origin_pending_entry_id`)",
            "CREATE INDEX IF NOT EXISTS `index_ledger_entries_deleted_at_epoch_millis` ON `ledger_entries` (`deleted_at_epoch_millis`)"
        )
    }
}
