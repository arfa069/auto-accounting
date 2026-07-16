package com.autoaccounting.feature.settings

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.autoaccounting.data.local.AutoAccountingDatabase
import com.autoaccounting.data.local.CaptureReason
import com.autoaccounting.data.local.CategorizationRuleEntity
import com.autoaccounting.data.local.CategoryEntity
import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.data.local.DEFAULT_LEDGER_BOOK_ID
import com.autoaccounting.data.local.DEFAULT_LEDGER_BOOK_NAME
import com.autoaccounting.data.local.EntryOrigin
import com.autoaccounting.data.local.FlowDirection
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.FundingAccountSourceScope
import com.autoaccounting.data.local.IgnoreReason
import com.autoaccounting.data.local.IgnoredEntryEntity
import com.autoaccounting.data.local.LedgerBookEntity
import com.autoaccounting.data.local.LedgerEntryEntity
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.data.local.LocalSettingsEntity
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.PendingEntryEntity
import com.autoaccounting.data.local.TransactionKind
import com.autoaccounting.feature.review.ReviewQueuePersistence
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalDataBackupRepositoryTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: AutoAccountingDatabase
    private lateinit var backupRepository: LocalDataBackupRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AutoAccountingDatabase::class.java
        ).allowMainThreadQueries().build()
        backupRepository = LocalDataBackupRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun versionFourBackupRoundTripRestoresAllLedgersReferencesAndUiState() = runBlocking {
        populateDatabase()
        val expected = readSnapshot()

        val backup = backupRepository.exportEncryptedBackup(PASSPHRASE)

        assertTrue(backup.startsWith("AUTO_ACCOUNTING_BACKUP_V4:"))
        assertFalse(backup.contains("Coffee Shop"))

        LocalLedgerRepository(database).clearLocalData()
        LocalPreferencesRepository(database).clearLocalData()
        backupRepository.importEncryptedBackup(backup, PASSPHRASE)

        assertEquals(expected, readSnapshot())

        val ledgerRepository = LocalLedgerRepository(database)
        val preferencesRepository = LocalPreferencesRepository(database)
        val reviewState = ReviewQueuePersistence(
            repository = ledgerRepository,
            nowProvider = { NOW },
            zoneId = ZoneId.of("UTC")
        ).observeState().first()

        assertEquals(listOf("pending-1"), reviewState.pendingEntries.map { it.id })
        assertEquals(listOf("ignored-1"), reviewState.ignoredEntries.map { it.id })
        assertEquals(
            listOf("ledger-1"),
            ledgerRepository.ledgerEntries(DEFAULT_LEDGER_BOOK_ID).first().map { it.id }
        )
        assertEquals(
            listOf("ledger-deleted"),
            ledgerRepository.deletedLedgerEntries(SECONDARY_LEDGER_BOOK_ID).first().map { it.id }
        )
        assertEquals(
            listOf(DEFAULT_LEDGER_BOOK_ID, SECONDARY_LEDGER_BOOK_ID),
            ledgerRepository.ledgerBooks.first().map { it.id }
        )
        assertEquals(
            SECONDARY_LEDGER_BOOK_ID,
            ledgerRepository.activeLedgerBook.first()?.id
        )
        assertEquals(listOf("rule-1"), preferencesRepository.categorizationRules.first().map { it.id })
        assertTrue(preferencesRepository.userPreferences.first().aiSettings.aiConsentGranted)
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            assertEquals(0, cursor.count)
        }
    }

    @Test
    fun wrongPassphraseFailsBeforeChangingPersistedData() = runBlocking {
        populateDatabase()
        val backup = backupRepository.exportEncryptedBackup(PASSPHRASE)

        val failure = runCatching {
            backupRepository.importEncryptedBackup(backup, "wrong-passphrase")
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals("pending-1", database.pendingEntryDao().getById("pending-1")?.id)
        assertEquals("ledger-1", database.ledgerEntryDao().getById("ledger-1")?.id)
        assertEquals("rule-1", database.categorizationRuleDao().listRules().single().id)
    }

    @Test
    fun validationDoesNotReplaceCurrentSnapshot() = runBlocking {
        populateDatabase()
        val backup = backupRepository.exportEncryptedBackup(PASSPHRASE)
        database.pendingEntryDao().deleteAll()

        backupRepository.validateEncryptedBackup(backup, PASSPHRASE)

        assertTrue(database.pendingEntryDao().listPendingEntries().isEmpty())
        assertEquals("ledger-1", database.ledgerEntryDao().getById("ledger-1")?.id)
    }

    @Test
    fun invalidBackupFieldsFailBeforeChangingPersistedData() = runBlocking {
        populateDatabase()
        val original = readSnapshot()
        val invalid = original.copy(
            ledgerEntries = original.ledgerEntries.mapIndexed { index, entry ->
                if (index == 0) entry.copy(amountMinor = -1) else entry
            }
        )

        val failure = runCatching {
            backupRepository.importEncryptedBackup(
                encryptPersistedLocalData(invalid, PASSPHRASE),
                PASSPHRASE
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(original, readSnapshot())
    }

    @Test
    fun duplicateCategoryNamesInVersionFourBackupFailBeforeChangingPersistedData() = runBlocking {
        populateDatabase()
        val original = readSnapshot()
        val existingCategory = original.categories.single()
        val invalid = original.copy(
            categories = listOf(existingCategory.copy(id = "duplicate-food")) + original.categories
        )
        val invalidBackup = encryptPersistedLocalData(invalid, PASSPHRASE)

        assertTrue(invalidBackup.startsWith("AUTO_ACCOUNTING_BACKUP_V4:"))

        val failure = runCatching {
            backupRepository.importEncryptedBackup(invalidBackup, PASSPHRASE)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals("Backup contains duplicate category names", failure?.message)
        assertEquals(original, readSnapshot())
    }

    @Test
    fun invalidRuleAndMissingReferencesFailDuringReadOnlyValidation() = runBlocking {
        populateDatabase()
        val original = readSnapshot()
        val invalid = original.copy(
            pendingEntries = original.pendingEntries.map {
                it.copy(suggestedCategoryId = "missing-category")
            },
            categorizationRules = original.categorizationRules.map { it.copy(category = "") }
        )

        val failure = runCatching {
            backupRepository.validateEncryptedBackup(
                encryptPersistedLocalData(invalid, PASSPHRASE),
                PASSPHRASE
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(original, readSnapshot())
    }

    @Test
    fun invalidLedgerBookAndActiveLedgerReferencesFailBeforeChangingPersistedData() = runBlocking {
        populateDatabase()
        val original = readSnapshot()
        val invalidSnapshots = listOf(
            original.copy(
                ledgerEntries = original.ledgerEntries.mapIndexed { index, entry ->
                    if (index == 0) entry.copy(ledgerBookId = "missing-ledger") else entry
                }
            ),
            original.copy(
                settings = requireNotNull(original.settings).copy(
                    activeLedgerId = "missing-ledger"
                )
            )
        )

        invalidSnapshots.forEach { invalid ->
            val failure = runCatching {
                backupRepository.importEncryptedBackup(
                    encryptPersistedLocalData(invalid, PASSPHRASE),
                    PASSPHRASE
                )
            }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals(original, readSnapshot())
        }
    }

    @Test
    fun unsupportedBackupFormatFailsBeforeChangingPersistedData() = runBlocking {
        populateDatabase()
        val original = readSnapshot()
        val unsupportedFormat = backupRepository.exportEncryptedBackup(PASSPHRASE)
            .replaceFirst("AUTO_ACCOUNTING_BACKUP_V4:", "AUTO_ACCOUNTING_BACKUP_V5:")

        val failure = runCatching {
            backupRepository.importEncryptedBackup(unsupportedFormat, PASSPHRASE)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(original, readSnapshot())
    }

    @Test
    fun versionTwoBackupImportsWithLegacyLedgerSemantics() = runBlocking {
        backupRepository.importEncryptedBackup(V2_BACKUP_FIXTURE, PASSPHRASE)

        val fundingAccount = database.fundingAccountDao().getAllFundingAccounts().single()
        val ledgerEntry = database.ledgerEntryDao().listLedgerEntries().single()

        assertEquals(FundingAccountSourceScope.WECHAT, fundingAccount.sourceScope)
        assertEquals(PaymentSource.WECHAT, fundingAccount.paymentSource)
        assertEquals(FlowDirection.INFLOW, ledgerEntry.flowDirection)
        assertEquals(PaymentSource.ALIPAY, ledgerEntry.paymentSource)
        assertEquals(PaymentSource.ALIPAY, ledgerEntry.originalCaptureSource)
        assertEquals(EntryOrigin.LEGACY_CAPTURE, ledgerEntry.entryOrigin)
        assertEquals(ledgerEntry.confirmedAtEpochMillis, ledgerEntry.updatedAtEpochMillis)
        assertEquals(null, ledgerEntry.deletedAtEpochMillis)
        assertEquals(DEFAULT_LEDGER_BOOK_ID, ledgerEntry.ledgerBookId)
        assertEquals(
            LedgerBookEntity(
                id = DEFAULT_LEDGER_BOOK_ID,
                name = DEFAULT_LEDGER_BOOK_NAME,
                createdAtEpochMillis = 0
            ),
            database.ledgerBookDao().getAll().single()
        )
        assertEquals(
            DEFAULT_LEDGER_BOOK_ID,
            database.localSettingsDao().getById()?.activeLedgerId
        )
    }

    @Test
    fun versionThreeBackupReplacesReferencedDataAndUsesDefaultLedger() = runBlocking {
        populateDatabase()

        backupRepository.importEncryptedBackup(V3_BACKUP_FIXTURE, PASSPHRASE)

        val ledgerEntry = database.ledgerEntryDao().listAllLedgerEntries().single()

        assertEquals("v3-ledger", ledgerEntry.id)
        assertEquals(DEFAULT_LEDGER_BOOK_ID, ledgerEntry.ledgerBookId)
        assertEquals("legacy evidence", ledgerEntry.evidenceSummary)
        assertEquals("amount=1.23", ledgerEntry.parsedFieldsText)
        assertEquals(
            LedgerBookEntity(
                id = DEFAULT_LEDGER_BOOK_ID,
                name = DEFAULT_LEDGER_BOOK_NAME,
                createdAtEpochMillis = 0
            ),
            database.ledgerBookDao().getAll().single()
        )
        assertEquals(
            DEFAULT_LEDGER_BOOK_ID,
            database.localSettingsDao().getById()?.activeLedgerId
        )
        assertTrue(database.categoryDao().getAllCategories().isEmpty())
        assertTrue(database.fundingAccountDao().getAllFundingAccounts().isEmpty())
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            assertEquals(0, cursor.count)
        }
    }

    private suspend fun populateDatabase() {
        database.ledgerBookDao().insertAll(
            listOf(
                LedgerBookEntity(
                    id = DEFAULT_LEDGER_BOOK_ID,
                    name = DEFAULT_LEDGER_BOOK_NAME,
                    createdAtEpochMillis = NOW - 10_000
                ),
                LedgerBookEntity(
                    id = SECONDARY_LEDGER_BOOK_ID,
                    name = "旅行账本",
                    createdAtEpochMillis = NOW - 5_000
                )
            )
        )
        database.categoryDao().upsertAll(
            listOf(
                CategoryEntity(
                    id = "food",
                    name = "Food",
                    kind = TransactionKind.EXPENSE,
                    sortOrder = 1,
                    isSystem = true,
                    createdAtEpochMillis = NOW
                )
            )
        )
        database.fundingAccountDao().upsertAll(
            listOf(
                FundingAccountEntity(
                    id = 7,
                    sourceScope = FundingAccountSourceScope.WECHAT,
                    paymentSource = PaymentSource.WECHAT,
                    label = "Wallet",
                    createdAtEpochMillis = NOW
                )
            )
        )
        database.pendingEntryDao().upsertAll(listOf(samplePending()))
        val ledgerEntry = LedgerEntryEntity(
                    id = "ledger-1",
                    ledgerBookId = DEFAULT_LEDGER_BOOK_ID,
                    paymentSource = PaymentSource.ALIPAY,
                    originalCaptureSource = PaymentSource.ALIPAY,
                    entryOrigin = EntryOrigin.LEGACY_CAPTURE,
                    originPendingEntryId = "confirmed-source",
                    flowDirection = FlowDirection.OUTFLOW,
                    transactionKind = TransactionKind.EXPENSE,
                    amountMinor = 2590,
                    currency = "CNY",
                    merchantTitle = "Metro",
                    transactionTimeEpochMillis = NOW - 2_000,
                    categoryId = "food",
                    fundingAccountId = 7,
                    note = "Commute",
                    evidenceSummary = "Alipay receipt",
                    parsedFieldsText = "amount=25.90",
                    confirmedAtEpochMillis = NOW,
                    updatedAtEpochMillis = NOW,
                    deletedAtEpochMillis = null
                )
        database.ledgerEntryDao().upsertAll(
            listOf(
                ledgerEntry,
                ledgerEntry.copy(
                    id = "ledger-deleted",
                    ledgerBookId = SECONDARY_LEDGER_BOOK_ID,
                    deletedAtEpochMillis = NOW + 1_000
                )
            )
        )
        database.ignoredEntryDao().upsertAll(
            listOf(
                IgnoredEntryEntity(
                    id = "ignored-1",
                    originalPendingEntryId = "ignored-source",
                    source = PaymentSource.WECHAT,
                    captureReason = CaptureReason.NOTIFICATION,
                    confidence = ConfidenceState.NEEDS_REVIEW,
                    transactionKind = TransactionKind.EXPENSE,
                    amountMinor = 990,
                    currency = "CNY",
                    merchantTitle = "Snack",
                    transactionTimeEpochMillis = NOW - 3_000,
                    capturedAtEpochMillis = NOW - 2_500,
                    suggestedCategoryId = "food",
                    fundingAccountId = 7,
                    fundingAccountLabel = "Wallet",
                    note = null,
                    evidenceSummary = "receipt",
                    parsedFieldsText = "amount=9.90",
                    ignoredAtEpochMillis = NOW - 1_000,
                    expiresAtEpochMillis = NOW + 10_000,
                    reason = IgnoreReason.USER_IGNORED,
                    suggestedCategoryLabel = "Food"
                )
            )
        )
        database.categorizationRuleDao().upsertAll(
            listOf(
                CategorizationRuleEntity(
                    id = "rule-1",
                    merchantContains = "Coffee",
                    titleContains = "",
                    sourceLabel = "WeChat",
                    transactionKind = "Expense",
                    category = "Food",
                    priority = 5,
                    enabled = true,
                    updatedAtEpochMillis = NOW
                )
            )
        )
        database.localSettingsDao().upsert(
            LocalSettingsEntity(
                aiConsentGranted = true,
                enhancedContextGranted = true,
                continuousBillSyncCompleted = true,
                continuousMonitoringEnabled = true,
                activeLedgerId = SECONDARY_LEDGER_BOOK_ID
            )
        )
    }

    private suspend fun readSnapshot(): PersistedLocalDataSnapshot =
        PersistedLocalDataSnapshot(
            categories = database.categoryDao().getAllCategories(),
            fundingAccounts = database.fundingAccountDao().getAllFundingAccounts(),
            pendingEntries = database.pendingEntryDao().listPendingEntries(),
            ledgerEntries = database.ledgerEntryDao().listAllLedgerEntries().sortedBy { it.id },
            ignoredEntries = database.ignoredEntryDao().listAll(),
            categorizationRules = database.categorizationRuleDao().listRules(),
            settings = database.localSettingsDao().getById(),
            ledgerBooks = database.ledgerBookDao().getAll()
        )

    private fun samplePending(): PendingEntryEntity = PendingEntryEntity(
        id = "pending-1",
        source = PaymentSource.WECHAT,
        captureReason = CaptureReason.NOTIFICATION,
        confidence = ConfidenceState.HIGH,
        transactionKind = TransactionKind.EXPENSE,
        amountMinor = 3590,
        currency = "CNY",
        merchantTitle = "Coffee Shop",
        transactionTimeEpochMillis = NOW - 1_000,
        capturedAtEpochMillis = NOW,
        suggestedCategoryId = "food",
        fundingAccountId = 7,
        fundingAccountLabel = "Wallet",
        note = "Meeting",
        evidenceSummary = "receipt",
        parsedFieldsText = "amount=35.90",
        suggestedCategoryLabel = "Food"
    )

    private companion object {
        const val NOW = 1_783_468_800_000L
        const val PASSPHRASE = "Aa123456!"
        const val SECONDARY_LEDGER_BOOK_ID = "travel-ledger"
        const val V2_BACKUP_FIXTURE =
            "AUTO_ACCOUNTING_BACKUP_V2:" +
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGwd+ybCCUSB4eWV/jCNk3d+5mIsNnmnR5Rar1Rfo" +
                "3eq4hqIRfuKYvQPAVpT9E4NW+9N7+9Ab5xVRSsRi9QcDMnIh2tdTJEbhGcvtz0P9sLWRk9FNBSpK" +
                "feMiF8Jn2/Go4SXEq45NeWLbAig6JZhxlxfUU9BOrsiXPhxVH9LvFB4jPoE3St8BL7NrOay8C0QY" +
                "qfZE9o5PO/QznRyp2RoYLlwPR4wZqkeD9vgdpgikdYcWNm4xvY0wptd3PXqzSQUbfIXyu+M16Tn" +
                "yZRS928CX4ZVwfZAx"
        const val V3_BACKUP_FIXTURE =
            "AUTO_ACCOUNTING_BACKUP_V3:" +
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGwd+ybCCUSB5eWV/jCNk3d65mIsNnmnR4xar1RjJ" +
                "q4ScopJ2G5Cf6mKsPKbMUMsWMO19w9AdsFASAoU29AcDPDMI+PFxHmCPNP7d/XTKsLWRlZdOGS5N" +
                "aK11F8JprNGe1Qnkn+YhHAW6YVE6Xph3xVHRSMcKrsicaC91WKmdd3ZBE7tuSt8BIv8OOs3fcmV" +
                "KzJA+9I8oWpbVgn/rqH58QD9qRowZqkvjm5dowDX0NMZWcQtVvY0xOegVBXqzSQUbfODyu+Naiga" +
                "o+NQz8fdFNQpUnzTFYS8etOriZ+Kzm8jtw63viHDKgoA="
    }
}
