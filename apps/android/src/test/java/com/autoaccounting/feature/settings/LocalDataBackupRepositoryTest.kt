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
import com.autoaccounting.data.local.EntryOrigin
import com.autoaccounting.data.local.FlowDirection
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.FundingAccountSourceScope
import com.autoaccounting.data.local.IgnoreReason
import com.autoaccounting.data.local.IgnoredEntryEntity
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
    fun encryptedBackupRoundTripRestoresPersistedRepositoriesAndUiState() = runBlocking {
        populateDatabase()
        val expected = readSnapshot()

        val backup = backupRepository.exportEncryptedBackup(PASSPHRASE)

        assertTrue(backup.startsWith("AUTO_ACCOUNTING_BACKUP_V3:"))
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
        assertEquals(listOf("ledger-1"), ledgerRepository.ledgerEntries.first().map { it.id })
        assertEquals(listOf("ledger-deleted"), ledgerRepository.deletedLedgerEntries.first().map { it.id })
        assertEquals(listOf("rule-1"), preferencesRepository.categorizationRules.first().map { it.id })
        assertTrue(preferencesRepository.userPreferences.first().aiSettings.aiConsentGranted)
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
    fun unsupportedBackupVersionFailsBeforeChangingPersistedData() = runBlocking {
        populateDatabase()
        val original = readSnapshot()
        val unsupportedVersion = backupRepository.exportEncryptedBackup(PASSPHRASE)
            .replaceFirst("AUTO_ACCOUNTING_BACKUP_V3:", "AUTO_ACCOUNTING_BACKUP_V4:")

        val failure = runCatching {
            backupRepository.importEncryptedBackup(unsupportedVersion, PASSPHRASE)
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
    }

    private suspend fun populateDatabase() {
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
                continuousMonitoringEnabled = true
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
            settings = database.localSettingsDao().getById()
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
        const val V2_BACKUP_FIXTURE =
            "AUTO_ACCOUNTING_BACKUP_V2:" +
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGwd+ybCCUSB4eWV/jCNk3d+5mIsNnmnR5Rar1Rfo" +
                "3eq4hqIRfuKYvQPAVpT9E4NW+9N7+9Ab5xVRSsRi9QcDMnIh2tdTJEbhGcvtz0P9sLWRk9FNBSpK" +
                "feMiF8Jn2/Go4SXEq45NeWLbAig6JZhxlxfUU9BOrsiXPhxVH9LvFB4jPoE3St8BL7NrOay8C0QY" +
                "qfZE9o5PO/QznRyp2RoYLlwPR4wZqkeD9vgdpgikdYcWNm4xvY0wptd3PXqzSQUbfIXyu+M16Tn" +
                "yZRS928CX4ZVwfZAx"
    }
}
