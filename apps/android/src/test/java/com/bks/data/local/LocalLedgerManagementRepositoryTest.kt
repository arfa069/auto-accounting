package com.bks.data.local

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalLedgerManagementRepositoryTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: BksDatabase
    private lateinit var repository: LocalLedgerRepository
    private var nextId = 0
    private var currentTime = NOW

    @Before
    fun setUp() {
        currentTime = NOW
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            BksDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = LocalLedgerRepository(
            database = database,
            clock = { currentTime },
            idGenerator = { "generated-${++nextId}" }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun seedSystemCategoriesCreatesStableDefaults() = runBlocking {
        repository.seedSystemCategories()

        val categories = database.categoryDao().getAllCategories()

        assertEquals(49, categories.size)
        assertTrue(categories.any { it.id == "food" && it.name == "餐饮" })
        assertTrue(categories.any { it.id == "games" && it.name == "游戏" })
        assertTrue(categories.any { it.id == "payment_received" && it.name == "收款" })
        assertTrue(categories.any { it.id == "uncategorized" && it.kind == null })
    }

    @Test
    fun seedUpdatesLegacySystemLabelsWithoutReplacingRows() = runBlocking {
        database.categoryDao().insertIgnore(
            listOf(
                CategoryEntity(
                    id = "housing",
                    name = "居住",
                    kind = TransactionKind.EXPENSE,
                    sortOrder = 40,
                    isSystem = true,
                    createdAtEpochMillis = 42
                )
            )
        )

        repository.seedSystemCategories()

        val housing = database.categoryDao().getCategory("housing")
        assertEquals("住房", housing?.name)
        assertEquals(42L, housing?.createdAtEpochMillis)
    }

    @Test
    fun ledgerBookNamesAreTrimmedRequiredAndUnique() = runBlocking {
        repository.ensureDefaultLedgerBook()

        val created = repository.createLedgerBook("  家庭账本  ")
        val blankFailure = runCatching {
            repository.createLedgerBook("   ")
        }.exceptionOrNull()
        val duplicateFailure = runCatching {
            repository.createLedgerBook("家庭账本")
        }.exceptionOrNull()

        assertEquals("家庭账本", created.name)
        assertTrue(blankFailure is IllegalArgumentException)
        assertTrue(duplicateFailure is IllegalArgumentException)
        assertEquals(
            listOf(DEFAULT_LEDGER_BOOK_NAME, "家庭账本"),
            database.ledgerBookDao().getAll().map { it.name }
        )
    }

    @Test
    fun compatibilityApisFollowCurrentLedgerAfterDefaultLedgerIsDeleted() = runBlocking {
        repository.seedSystemCategories()
        repository.ensureDefaultLedgerBook()
        val currentLedger = repository.createLedgerBook("current")
        assertEquals(
            LedgerBookDeleteResult.Deleted,
            repository.deleteLedgerBook(DEFAULT_LEDGER_BOOK_ID)
        )
        repository.upsertPending(samplePending())

        val manual = repository.createManualEntry(sampleLedgerInput())
        val confirmed = repository.confirmPending(
            pendingEntryId = "pending-1",
            categoryId = "food"
        )
        repository.moveLedgerEntryToDeleted(manual.id)

        assertEquals(currentLedger.id, manual.ledgerBookId)
        assertEquals(currentLedger.id, confirmed.ledgerBookId)
        assertEquals(
            listOf(confirmed.id),
            repository.ledgerEntries.first().map { it.id }
        )
        assertEquals(
            listOf(manual.id),
            repository.deletedLedgerEntries.first().map { it.id }
        )
    }

    @Test
    fun lastActiveAndRecentlyDeletedLedgerBooksCannotBeDeleted() = runBlocking {
        repository.seedSystemCategories()
        repository.ensureDefaultLedgerBook()

        assertEquals(
            LedgerBookDeleteResult.LastLedgerBook,
            repository.deleteLedgerBook(DEFAULT_LEDGER_BOOK_ID)
        )

        val activeLedger = repository.createLedgerBook("有账目")
        repository.createManualEntry(activeLedger.id, sampleLedgerInput())
        assertEquals(
            LedgerBookDeleteResult.NotEmpty(
                activeEntryCount = 1,
                deletedEntryCount = 0
            ),
            repository.deleteLedgerBook(activeLedger.id)
        )

        val deletedLedger = repository.createLedgerBook("最近删除")
        val deletedEntry = repository.createManualEntry(deletedLedger.id, sampleLedgerInput())
        repository.moveLedgerEntryToDeleted(deletedEntry.id)
        assertEquals(
            LedgerBookDeleteResult.NotEmpty(
                activeEntryCount = 0,
                deletedEntryCount = 1
            ),
            repository.deleteLedgerBook(deletedLedger.id)
        )

        assertEquals(
            setOf(DEFAULT_LEDGER_BOOK_ID, activeLedger.id, deletedLedger.id),
            database.ledgerBookDao().getAll().map { it.id }.toSet()
        )
    }

    @Test
    fun deletingCurrentEmptyLedgerSelectsEarliestRemainingLedger() = runBlocking {
        repository.ensureDefaultLedgerBook()
        currentTime = NOW + 1
        repository.createLedgerBook("家庭")
        currentTime = NOW + 2
        val currentLedger = repository.createLedgerBook("旅行")

        val result = repository.deleteLedgerBook(currentLedger.id)

        assertEquals(LedgerBookDeleteResult.Deleted, result)
        assertEquals(DEFAULT_LEDGER_BOOK_ID, database.localSettingsDao().getById()?.activeLedgerId)
        assertEquals(DEFAULT_LEDGER_BOOK_ID, repository.activeLedgerBook.first()?.id)
        assertNull(database.ledgerBookDao().getById(currentLedger.id))
    }

    @Test
    fun activeLedgerBookIsRestoredAfterDatabaseReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "active-ledger-reopen-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        var fileId = 0
        val fileDatabase = Room.databaseBuilder(
            context,
            BksDatabase::class.java,
            databaseName
        ).allowMainThreadQueries().build()
        val fileRepository = LocalLedgerRepository(
            database = fileDatabase,
            clock = { NOW },
            idGenerator = { "reopen-ledger-${++fileId}" }
        )
        val selectedLedgerId = runBlocking {
            fileRepository.ensureDefaultLedgerBook()
            val selected = fileRepository.createLedgerBook("旅行")
            fileRepository.createLedgerBook("家庭")
            fileRepository.selectLedgerBook(selected.id)
            selected.id
        }
        fileDatabase.close()

        val reopenedDatabase = Room.databaseBuilder(
            context,
            BksDatabase::class.java,
            databaseName
        ).allowMainThreadQueries().build()
        val reopenedRepository = LocalLedgerRepository(reopenedDatabase)

        val activeLedger = runBlocking {
            reopenedRepository.activeLedgerBook.first()
        }

        assertEquals(selectedLedgerId, activeLedger?.id)
        assertEquals(selectedLedgerId, runBlocking {
            reopenedDatabase.localSettingsDao().getById()
        }?.activeLedgerId)
        reopenedDatabase.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun fundingAccountCreationNormalizesNamesAndScopesDuplicatesByPaymentSource() = runBlocking {
        val wechat = repository.createFundingAccount(
            label = "  零钱  ",
            paymentSource = PaymentSource.WECHAT
        )
        val alipay = repository.createFundingAccount(
            label = "零钱",
            paymentSource = PaymentSource.ALIPAY
        )
        val blankFailure = runCatching {
            repository.createFundingAccount("   ", PaymentSource.WECHAT)
        }.exceptionOrNull()
        val duplicateFailure = runCatching {
            repository.createFundingAccount("零钱", PaymentSource.WECHAT)
        }.exceptionOrNull()

        assertEquals("零钱", wechat.label)
        assertEquals(FundingAccountSourceScope.WECHAT, wechat.sourceScope)
        assertEquals(PaymentSource.WECHAT, wechat.paymentSource)
        assertEquals("零钱", alipay.label)
        assertEquals(FundingAccountSourceScope.ALIPAY, alipay.sourceScope)
        assertTrue(blankFailure is IllegalArgumentException)
        assertTrue(duplicateFailure is IllegalArgumentException)
        assertEquals(2, database.fundingAccountDao().getAllFundingAccounts().size)
    }

    @Test
    fun fundingAccountUpdatePreservesIdentityCreationTimeAndHistoricalEntrySource() = runBlocking {
        repository.seedSystemCategories()
        val account = repository.createFundingAccount("零钱", PaymentSource.WECHAT)
        val conflict = repository.createFundingAccount("余额", PaymentSource.ALIPAY)
        val ledgerEntry = repository.createManualEntry(
            sampleLedgerInput().copy(
                fundingAccountId = account.id,
                paymentSource = PaymentSource.WECHAT
            )
        )
        currentTime = NOW + 10_000

        val updated = repository.updateFundingAccount(
            fundingAccountId = account.id,
            label = "  日常账户  ",
            paymentSource = PaymentSource.ALIPAY
        )
        val conflictFailure = runCatching {
            repository.updateFundingAccount(
                fundingAccountId = account.id,
                label = conflict.label,
                paymentSource = PaymentSource.ALIPAY
            )
        }.exceptionOrNull()

        assertEquals(account.id, updated.id)
        assertEquals(account.createdAtEpochMillis, updated.createdAtEpochMillis)
        assertEquals("日常账户", updated.label)
        assertEquals(FundingAccountSourceScope.ALIPAY, updated.sourceScope)
        assertEquals(PaymentSource.ALIPAY, updated.paymentSource)
        assertTrue(conflictFailure is IllegalArgumentException)
        assertEquals(updated, database.fundingAccountDao().getById(account.id))
        assertEquals(
            PaymentSource.WECHAT,
            repository.getLedgerEntry(ledgerEntry.id)?.paymentSource
        )
        assertEquals(account.id, repository.getLedgerEntry(ledgerEntry.id)?.fundingAccountId)
    }

    @Test
    fun fundingAccountDeletionIsBlockedAtomicallyByEveryReferenceType() = runBlocking {
        repository.seedSystemCategories()

        val activeAccount = repository.createFundingAccount("活动账目", null)
        repository.createManualEntry(
            sampleLedgerInput().copy(fundingAccountId = activeAccount.id)
        )
        assertEquals(
            FundingAccountDeleteResult.Referenced(
                activeLedgerEntryCount = 1,
                deletedLedgerEntryCount = 0,
                pendingEntryCount = 0,
                ignoredEntryCount = 0
            ),
            repository.deleteFundingAccount(activeAccount.id)
        )

        val deletedAccount = repository.createFundingAccount("最近删除", null)
        val deletedEntry = repository.createManualEntry(
            sampleLedgerInput().copy(fundingAccountId = deletedAccount.id)
        )
        repository.moveLedgerEntryToDeleted(deletedEntry.id)
        assertEquals(
            FundingAccountDeleteResult.Referenced(
                activeLedgerEntryCount = 0,
                deletedLedgerEntryCount = 1,
                pendingEntryCount = 0,
                ignoredEntryCount = 0
            ),
            repository.deleteFundingAccount(deletedAccount.id)
        )

        val pendingAccount = repository.createFundingAccount("待确认", PaymentSource.WECHAT)
        repository.upsertPending(
            samplePending(
                id = "pending-account-reference",
                fundingAccountId = pendingAccount.id
            )
        )
        assertEquals(
            FundingAccountDeleteResult.Referenced(
                activeLedgerEntryCount = 0,
                deletedLedgerEntryCount = 0,
                pendingEntryCount = 1,
                ignoredEntryCount = 0
            ),
            repository.deleteFundingAccount(pendingAccount.id)
        )

        val ignoredAccount = repository.createFundingAccount("忽略记录", PaymentSource.WECHAT)
        repository.upsertIgnored(
            sampleIgnored(
                id = "ignored-account-reference",
                originalPendingEntryId = "ignored-account-source",
                fundingAccountId = ignoredAccount.id
            )
        )
        assertEquals(
            FundingAccountDeleteResult.Referenced(
                activeLedgerEntryCount = 0,
                deletedLedgerEntryCount = 0,
                pendingEntryCount = 0,
                ignoredEntryCount = 1
            ),
            repository.deleteFundingAccount(ignoredAccount.id)
        )

        listOf(activeAccount, deletedAccount, pendingAccount, ignoredAccount).forEach { account ->
            assertEquals(account, database.fundingAccountDao().getById(account.id))
        }
    }

    @Test
    fun unusedFundingAccountCanBeDeleted() = runBlocking {
        val account = repository.createFundingAccount("未使用", null)

        val result = repository.deleteFundingAccount(account.id)

        assertEquals(FundingAccountDeleteResult.Deleted, result)
        assertNull(database.fundingAccountDao().getById(account.id))
    }

    @Test
    fun clearLocalDataDeletesLedgerPendingIgnoredAndMetadata() = runBlocking {
        repository.seedSystemCategories()
        repository.ensureDefaultLedgerBook()
        val secondaryLedger = repository.createLedgerBook("即将清除")
        val fundingAccount = repository.ensureFundingAccount(PaymentSource.ALIPAY, "余额")
        repository.upsertPending(samplePending(id = "pending-clear", fundingAccountId = fundingAccount.id))
        val deletedLedger = repository.confirmPending(
            pendingEntryId = "pending-clear",
            ledgerBookId = secondaryLedger.id,
            categoryId = "food"
        )
        repository.moveLedgerEntryToDeleted(deletedLedger.id)
        repository.upsertPending(samplePending(id = "pending-left"))
        repository.upsertIgnored(sampleIgnored(id = "ignored-left", originalPendingEntryId = "ignored-source"))
        database.categorizationRuleDao().upsertAll(
            listOf(
                CategorizationRuleEntity(
                    id = "custom-rule",
                    merchantContains = "custom",
                    titleContains = "",
                    sourceLabel = "",
                    transactionKind = "",
                    category = "custom",
                    priority = 999,
                    enabled = true,
                    updatedAtEpochMillis = NOW
                )
            )
        )
        database.localSettingsDao().upsert(
            LocalSettingsEntity(
                aiConsentGranted = true,
                enhancedContextGranted = true,
                activeLedgerId = secondaryLedger.id
            )
        )

        repository.clearLocalData()

        assertTrue(database.ledgerEntryDao().listLedgerEntries().isEmpty())
        assertTrue(database.ledgerEntryDao().listAllLedgerEntries().isEmpty())
        assertTrue(database.pendingEntryDao().listPendingEntries().isEmpty())
        assertTrue(database.ignoredEntryDao().listRecoverable(NOW).isEmpty())
        assertTrue(database.ignoredEntryDao().listAll().isEmpty())
        assertTrue(database.fundingAccountDao().getAllFundingAccounts().isEmpty())
        val categories = database.categoryDao().getAllCategories()
        assertTrue(categories.any { it.id == "food" && it.name == "餐饮" })
        assertEquals(
            listOf(DEFAULT_LEDGER_BOOK_ID),
            database.ledgerBookDao().getAll().map { it.id }
        )
        assertEquals(
            DEFAULT_LEDGER_BOOK_ID,
            database.localSettingsDao().getById()?.activeLedgerId
        )
        assertEquals(
            DefaultCategorizationRules.rules.map { it.id }.toSet(),
            database.categorizationRuleDao().listRules().map { it.id }.toSet()
        )
        assertEquals(
            LocalSettingsEntity(
                aiConsentGranted = false,
                enhancedContextGranted = false,
                activeLedgerId = DEFAULT_LEDGER_BOOK_ID
            ),
            database.localSettingsDao().getById()
        )
        assertEquals(DEFAULT_LEDGER_BOOK_ID, repository.activeLedgerBook.first()?.id)
    }

    private fun samplePending(
        id: String = "pending-1",
        confidence: ConfidenceState = ConfidenceState.NEEDS_REVIEW,
        capturedAtEpochMillis: Long = NOW,
        fundingAccountId: Long? = null,
        source: PaymentSource = PaymentSource.WECHAT
    ): PendingEntryEntity = PendingEntryEntity(
        id = id,
        source = source,
        captureReason = CaptureReason.NOTIFICATION,
        confidence = confidence,
        transactionKind = TransactionKind.EXPENSE,
        amountMinor = 1590,
        currency = "CNY",
        merchantTitle = "便利店",
        transactionTimeEpochMillis = NOW - 60_000,
        capturedAtEpochMillis = capturedAtEpochMillis,
        suggestedCategoryId = null,
        fundingAccountId = fundingAccountId,
        fundingAccountLabel = "微信零钱",
        note = null,
        evidenceSummary = "微信支付收款凭证",
        parsedFieldsText = "商户=便利店\n金额=15.90"
    )

    private fun sampleLedgerInput(): LedgerEntryInput = LedgerEntryInput(
        flowDirection = FlowDirection.OUTFLOW,
        transactionKind = TransactionKind.EXPENSE,
        amountMinor = 1_590,
        transactionTimeEpochMillis = NOW - 60_000,
        merchantTitle = "便利店",
        categoryId = "food",
        fundingAccountId = null,
        newFundingAccountLabel = null,
        note = null,
        paymentSource = null
    )

    private fun sampleIgnored(
        id: String = "ignored-1",
        originalPendingEntryId: String = "pending-1",
        expiresAtEpochMillis: Long = NOW + LocalLedgerRepository.IGNORED_RETENTION_MILLIS,
        fundingAccountId: Long? = null
    ): IgnoredEntryEntity = IgnoredEntryEntity(
        id = id,
        originalPendingEntryId = originalPendingEntryId,
        source = PaymentSource.WECHAT,
        captureReason = CaptureReason.NOTIFICATION,
        confidence = ConfidenceState.NEEDS_REVIEW,
        transactionKind = TransactionKind.EXPENSE,
        amountMinor = 1590,
        currency = "CNY",
        merchantTitle = "便利店",
        transactionTimeEpochMillis = NOW - 60_000,
        capturedAtEpochMillis = NOW,
        suggestedCategoryId = null,
        fundingAccountId = fundingAccountId,
        fundingAccountLabel = "微信零钱",
        note = "午餐",
        evidenceSummary = "微信支付收款凭证",
        parsedFieldsText = "商户=便利店\n金额=15.90",
        ignoredAtEpochMillis = NOW,
        expiresAtEpochMillis = expiresAtEpochMillis,
        reason = IgnoreReason.USER_IGNORED
    )

    private companion object {
        const val NOW = 1_735_689_600_000L
    }
}
