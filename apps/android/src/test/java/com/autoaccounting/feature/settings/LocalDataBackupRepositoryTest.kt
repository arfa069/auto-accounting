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
import com.autoaccounting.data.local.FundingAccountEntity
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

        assertTrue(backup.startsWith("AUTO_ACCOUNTING_BACKUP_V2:"))
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
                    source = PaymentSource.WECHAT,
                    label = "Wallet",
                    createdAtEpochMillis = NOW
                )
            )
        )
        database.pendingEntryDao().upsertAll(listOf(samplePending()))
        database.ledgerEntryDao().upsertAll(
            listOf(
                LedgerEntryEntity(
                    id = "ledger-1",
                    source = PaymentSource.ALIPAY,
                    originPendingEntryId = "confirmed-source",
                    transactionKind = TransactionKind.EXPENSE,
                    amountMinor = 2590,
                    currency = "CNY",
                    merchantTitle = "Metro",
                    transactionTimeEpochMillis = NOW - 2_000,
                    categoryId = "food",
                    fundingAccountId = 7,
                    note = "Commute",
                    confirmedAtEpochMillis = NOW
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
            ledgerEntries = database.ledgerEntryDao().listLedgerEntries(),
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
    }
}
