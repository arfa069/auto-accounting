package com.autoaccounting.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.autoaccounting.feature.ledger.categoryExpenseTotals
import com.autoaccounting.feature.ledger.monthlySummary
import com.autoaccounting.feature.ledger.toLedgerUiEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.ZoneOffset
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
class LocalLedgerRepositoryTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: AutoAccountingDatabase
    private lateinit var repository: LocalLedgerRepository
    private var nextId = 0
    private var currentTime = NOW

    @Before
    fun setUp() {
        currentTime = NOW
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AutoAccountingDatabase::class.java
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
    fun confirmPendingMovesCandidateIntoLedger() = runBlocking {
        repository.seedSystemCategories()
        val fundingAccount = repository.ensureFundingAccount(PaymentSource.WECHAT, "零钱")
        repository.upsertPending(samplePending(fundingAccountId = fundingAccount.id))

        val ledgerEntry = repository.confirmPending(
            pendingEntryId = "pending-1",
            categoryId = "food",
            note = "午餐"
        )

        assertNull(database.pendingEntryDao().getById("pending-1"))
        assertEquals("generated-1", ledgerEntry.id)
        assertEquals("pending-1", ledgerEntry.originPendingEntryId)
        assertEquals("food", ledgerEntry.categoryId)
        assertEquals("午餐", ledgerEntry.note)
        assertEquals(1, database.ledgerEntryDao().listLedgerEntries().size)
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
    fun manualAndConfirmedEntriesUseTheirExplicitLedgerBookTarget() = runBlocking {
        repository.seedSystemCategories()
        repository.ensureDefaultLedgerBook()
        val travelLedger = repository.createLedgerBook("旅行")
        val familyLedger = repository.createLedgerBook("家庭")
        repository.upsertPending(samplePending())

        val manual = repository.createManualEntry(
            ledgerBookId = travelLedger.id,
            input = sampleLedgerInput()
        )
        val confirmed = repository.confirmPending(
            pendingEntryId = "pending-1",
            ledgerBookId = travelLedger.id,
            categoryId = "food"
        )

        assertEquals(familyLedger.id, repository.activeLedgerBook.first()?.id)
        assertEquals(travelLedger.id, manual.ledgerBookId)
        assertEquals(travelLedger.id, confirmed.ledgerBookId)
        assertEquals(
            setOf(manual.id, confirmed.id),
            repository.ledgerEntries(travelLedger.id).first().map { it.id }.toSet()
        )
        assertTrue(repository.ledgerEntries(familyLedger.id).first().isEmpty())
    }

    @Test
    fun stateUsesActiveLedgerEntriesAndDatabaseBookCounts() = runBlocking {
        repository.seedSystemCategories()
        repository.ensureDefaultLedgerBook()
        val travelLedger = repository.createLedgerBook("旅行")
        val familyLedger = repository.createLedgerBook("家庭")
        val travelEntry = repository.createManualEntry(
            ledgerBookId = travelLedger.id,
            input = sampleLedgerInput()
        )
        repository.moveLedgerEntryToDeleted(travelEntry.id)
        val familyEntry = repository.createManualEntry(
            ledgerBookId = familyLedger.id,
            input = sampleLedgerInput()
        )

        val state = repository.state.first { it.activeLedgerBook?.id == familyLedger.id }

        assertEquals(listOf(familyEntry.id), state.ledgerEntries.map { it.id })
        assertTrue(state.deletedLedgerEntries.isEmpty())
        assertEquals(
            1,
            state.ledgerBooks.single { it.id == familyLedger.id }.activeEntryCount
        )
        assertEquals(
            1,
            state.ledgerBooks.single { it.id == travelLedger.id }.deletedEntryCount
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
    fun manualEntryIsWrittenDirectlyToTheLedger() = runBlocking {
        repository.seedSystemCategories()

        val created = repository.createManualEntry(
            LedgerEntryInput(
                flowDirection = FlowDirection.NEUTRAL,
                transactionKind = TransactionKind.TRANSFER,
                amountMinor = 20_000,
                transactionTimeEpochMillis = NOW - 60_000,
                merchantTitle = "账户间转账",
                categoryId = null,
                fundingAccountId = null,
                newFundingAccountLabel = null,
                note = "不计收支",
                paymentSource = null
            )
        )

        assertEquals("generated-1", created.id)
        assertEquals(EntryOrigin.MANUAL, created.entryOrigin)
        assertEquals(FlowDirection.NEUTRAL, created.flowDirection)
        assertNull(created.paymentSource)
        assertNull(created.originalCaptureSource)
        assertEquals("uncategorized", created.categoryId)
        assertEquals(created, repository.getLedgerEntry(created.id))
        assertTrue(repository.pendingEntries.first().isEmpty())
    }

    @Test
    fun editingManualEntryUpdatesAllUserFieldsAndTimestamp() = runBlocking {
        repository.seedSystemCategories()
        val created = repository.createManualEntry(
            LedgerEntryInput(
                flowDirection = FlowDirection.OUTFLOW,
                transactionKind = TransactionKind.EXPENSE,
                amountMinor = 1_000,
                transactionTimeEpochMillis = NOW - 120_000,
                merchantTitle = "旧标题",
                categoryId = null,
                fundingAccountId = null,
                newFundingAccountLabel = null,
                note = null,
                paymentSource = PaymentSource.WECHAT
            )
        )
        val fundingAccount = repository.ensureFundingAccount(PaymentSource.ALIPAY, "余额")
        currentTime = NOW + 1_000

        val updated = repository.updateLedgerEntry(
            created.id,
            LedgerEntryInput(
                flowDirection = FlowDirection.INFLOW,
                transactionKind = TransactionKind.REFUND,
                amountMinor = 2_345,
                transactionTimeEpochMillis = NOW - 60_000,
                merchantTitle = "退款到账",
                categoryId = "food",
                fundingAccountId = fundingAccount.id,
                newFundingAccountLabel = null,
                note = "  已核对  ",
                paymentSource = PaymentSource.ALIPAY
            )
        )

        assertEquals(FlowDirection.INFLOW, updated.flowDirection)
        assertEquals(TransactionKind.REFUND, updated.transactionKind)
        assertEquals(2_345, updated.amountMinor)
        assertEquals(NOW - 60_000, updated.transactionTimeEpochMillis)
        assertEquals("退款到账", updated.merchantTitle)
        assertEquals("food", updated.categoryId)
        assertEquals(fundingAccount.id, updated.fundingAccountId)
        assertEquals("已核对", updated.note)
        assertEquals(PaymentSource.ALIPAY, updated.paymentSource)
        assertEquals(currentTime, updated.updatedAtEpochMillis)
        assertEquals(created.entryOrigin, updated.entryOrigin)
        assertEquals(created.confirmedAtEpochMillis, updated.confirmedAtEpochMillis)
        assertEquals(updated, repository.getLedgerEntry(created.id))
    }

    @Test
    fun editingCapturedEntryPreservesOriginalCaptureProvenance() = runBlocking {
        repository.seedSystemCategories()
        repository.upsertPending(samplePending())
        val confirmed = repository.confirmPending("pending-1", categoryId = "food")

        val updated = repository.updateLedgerEntry(
            confirmed.id,
            LedgerEntryInput(
                flowDirection = FlowDirection.INFLOW,
                transactionKind = TransactionKind.REFUND,
                amountMinor = 1_200,
                transactionTimeEpochMillis = NOW - 30_000,
                merchantTitle = "退款到账",
                categoryId = "refund",
                fundingAccountId = null,
                newFundingAccountLabel = null,
                note = "已核对",
                paymentSource = PaymentSource.ALIPAY
            )
        )

        assertEquals(PaymentSource.ALIPAY, updated.paymentSource)
        assertEquals(PaymentSource.WECHAT, updated.originalCaptureSource)
        assertEquals(EntryOrigin.NOTIFICATION, updated.entryOrigin)
        assertEquals("pending-1", updated.originPendingEntryId)
        assertEquals("微信支付收款凭证", updated.evidenceSummary)
        assertEquals(confirmed.confirmedAtEpochMillis, updated.confirmedAtEpochMillis)
        assertEquals(NOW, updated.updatedAtEpochMillis)
    }

    @Test
    fun deletedEntryLeavesActiveLedgerAndCanBeRestoredWithSameId() = runBlocking {
        repository.seedSystemCategories()
        val created = repository.createManualEntry(sampleLedgerInput())

        val deleted = repository.moveLedgerEntryToDeleted(created.id)

        assertEquals(NOW, deleted.deletedAtEpochMillis)
        assertTrue(repository.listLedgerEntries().isEmpty())
        assertEquals(listOf(created.id), repository.deletedLedgerEntries.first().map { it.id })

        val restored = repository.restoreDeletedLedgerEntry(created.id)

        assertEquals(created.id, restored.id)
        assertNull(restored.deletedAtEpochMillis)
        assertEquals(listOf(created.id), repository.listLedgerEntries().map { it.id })
    }

    @Test
    fun expiredDeletedEntryCannotBeRestored() = runBlocking {
        repository.seedSystemCategories()
        val created = repository.createManualEntry(sampleLedgerInput())
        database.ledgerEntryDao().upsert(
            created.copy(
                deletedAtEpochMillis = NOW - LocalLedgerRepository.DELETED_RETENTION_MILLIS
            )
        )

        val failure = runCatching {
            repository.restoreDeletedLedgerEntry(created.id)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(
            NOW - LocalLedgerRepository.DELETED_RETENTION_MILLIS,
            repository.getLedgerEntry(created.id)?.deletedAtEpochMillis
        )
    }

    @Test
    fun permanentAndExpiredDeletionOnlyRemoveDeletedEntries() = runBlocking {
        repository.seedSystemCategories()
        val permanent = repository.createManualEntry(sampleLedgerInput())
        val expired = repository.createManualEntry(sampleLedgerInput())
        val active = repository.createManualEntry(sampleLedgerInput())
        repository.moveLedgerEntryToDeleted(permanent.id)
        repository.moveLedgerEntryToDeleted(expired.id)

        repository.permanentlyDeleteLedgerEntry(permanent.id)
        val purged = repository.purgeExpiredDeletedLedgerEntries(
            nowEpochMillis = NOW + LocalLedgerRepository.DELETED_RETENTION_MILLIS
        )

        assertEquals(1, purged)
        assertNull(repository.getLedgerEntry(permanent.id))
        assertNull(repository.getLedgerEntry(expired.id))
        assertEquals(active.id, repository.getLedgerEntry(active.id)?.id)
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
    fun confirmedLedgerEntriesCanBeObservedAfterDatabaseReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "ledger-reopen-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val fileDatabase = Room.databaseBuilder(
            context,
            AutoAccountingDatabase::class.java,
            databaseName
        ).allowMainThreadQueries().build()
        val fileRepository = LocalLedgerRepository(
            database = fileDatabase,
            clock = { NOW },
            idGenerator = { "ledger-generated" }
        )
        runBlocking {
            fileRepository.seedSystemCategories()
            fileRepository.upsertPending(samplePending(id = "pending-reopen"))
            fileRepository.confirmPending(
                pendingEntryId = "pending-reopen",
                categoryId = "food"
            )
        }
        fileDatabase.close()

        val reopenedDatabase = Room.databaseBuilder(
            context,
            AutoAccountingDatabase::class.java,
            databaseName
        ).allowMainThreadQueries().build()
        val reopenedRepository = LocalLedgerRepository(reopenedDatabase)

        val ledgerEntries = runBlocking {
            reopenedRepository.ledgerEntries.first()
        }

        assertEquals(listOf("ledger-generated"), ledgerEntries.map { it.id })
        assertEquals("pending-reopen", ledgerEntries.single().originPendingEntryId)
        runBlocking {
            assertNull(reopenedDatabase.pendingEntryDao().getById("pending-reopen"))
        }

        reopenedDatabase.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun activeLedgerBookIsRestoredAfterDatabaseReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "active-ledger-reopen-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        var fileId = 0
        val fileDatabase = Room.databaseBuilder(
            context,
            AutoAccountingDatabase::class.java,
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
            AutoAccountingDatabase::class.java,
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
    fun ignorePendingKeepsRecoverableSnapshotForThirtyDays() = runBlocking {
        repository.upsertPending(samplePending())

        val ignoredEntry = repository.ignorePending("pending-1")

        assertNull(database.pendingEntryDao().getById("pending-1"))
        assertEquals("pending-1", ignoredEntry.originalPendingEntryId)
        assertEquals(
            NOW + LocalLedgerRepository.IGNORED_RETENTION_MILLIS,
            ignoredEntry.expiresAtEpochMillis
        )
        assertEquals(
            listOf(ignoredEntry),
            database.ignoredEntryDao().listRecoverable(NOW)
        )
    }

    @Test
    fun recoverIgnoredRestoresPendingSnapshotAndRemovesIgnoredEntry() = runBlocking {
        repository.upsertIgnored(
            sampleIgnored(
                id = "ignored-1",
                originalPendingEntryId = "pending-1"
            )
        )

        val restored = repository.recoverIgnored("ignored-1")

        assertEquals("pending-1", restored.id)
        assertEquals(ConfidenceState.NEEDS_REVIEW, restored.confidence)
        assertEquals(CaptureReason.NOTIFICATION, restored.captureReason)
        assertEquals("微信支付收款凭证", restored.evidenceSummary)
        assertNull(database.ignoredEntryDao().getById("ignored-1"))
        assertEquals(restored, database.pendingEntryDao().getById("pending-1"))
    }

    @Test
    fun pendingEntriesAreOrderedForReviewQueue() = runBlocking {
        repository.upsertPending(
            samplePending(
                id = "quick",
                confidence = ConfidenceState.HIGH,
                capturedAtEpochMillis = NOW + 100
            )
        )
        repository.upsertPending(
            samplePending(
                id = "careful",
                confidence = ConfidenceState.DUPLICATE_SUSPECT,
                capturedAtEpochMillis = NOW
            )
        )

        val pendingEntries = repository.pendingEntries.first()

        assertEquals(listOf("careful", "quick"), pendingEntries.map { it.id })
    }

    @Test
    fun recoverableIgnoredEntriesCanBeObservedForReviewQueue() = runBlocking {
        repository.upsertIgnored(
            sampleIgnored(
                id = "recoverable",
                expiresAtEpochMillis = NOW + 1
            )
        )
        repository.upsertIgnored(
            sampleIgnored(
                id = "expired",
                originalPendingEntryId = "expired-pending",
                expiresAtEpochMillis = NOW
            )
        )

        val ignoredEntries = repository.recoverableIgnoredEntries(NOW).first()

        assertEquals(listOf("recoverable"), ignoredEntries.map { it.id })
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
    fun confirmPendingKeepsExistingAccountIdThenMatchesExactlyWithoutCreating() = runBlocking {
        repository.seedSystemCategories()
        val accountById = repository.createFundingAccount("支付宝余额", PaymentSource.ALIPAY)
        val exactWechat = repository.createFundingAccount("微信零钱", PaymentSource.WECHAT)
        repository.upsertPending(
            samplePending(
                id = "account-by-id",
                fundingAccountId = accountById.id,
                fundingAccountLabel = exactWechat.label
            )
        )
        repository.upsertPending(
            samplePending(
                id = "account-by-label",
                fundingAccountLabel = "  微信零钱  "
            )
        )
        repository.upsertPending(
            samplePending(
                id = "account-no-match",
                source = PaymentSource.ALIPAY,
                fundingAccountLabel = "新账户"
            )
        )
        val accountCountBefore = database.fundingAccountDao().getAllFundingAccounts().size

        val byId = repository.confirmPending("account-by-id", categoryId = "food")
        val byLabel = repository.confirmPending("account-by-label", categoryId = "food")
        val noMatch = repository.confirmPending("account-no-match", categoryId = "food")

        assertEquals(accountById.id, byId.fundingAccountId)
        assertEquals(exactWechat.id, byLabel.fundingAccountId)
        assertNull(noMatch.fundingAccountId)
        assertEquals(
            accountCountBefore,
            database.fundingAccountDao().getAllFundingAccounts().size
        )
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
                continuousBillSyncCompleted = true,
                continuousMonitoringEnabled = true,
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
                continuousBillSyncCompleted = false,
                continuousMonitoringEnabled = false,
                activeLedgerId = DEFAULT_LEDGER_BOOK_ID
            ),
            database.localSettingsDao().getById()
        )
        assertEquals(DEFAULT_LEDGER_BOOK_ID, repository.activeLedgerBook.first()?.id)
    }

    @Test
    fun schemaVersionIsCurrent() {
        assertEquals(7, AutoAccountingDatabase.SCHEMA_VERSION)
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

    private fun samplePending(
        id: String = "pending-1",
        confidence: ConfidenceState = ConfidenceState.NEEDS_REVIEW,
        capturedAtEpochMillis: Long = NOW,
        fundingAccountId: Long? = null,
        source: PaymentSource = PaymentSource.WECHAT,
        fundingAccountLabel: String? = "微信零钱"
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
        fundingAccountLabel = fundingAccountLabel,
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
