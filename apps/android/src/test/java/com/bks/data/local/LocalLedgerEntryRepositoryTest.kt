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
class LocalLedgerEntryRepositoryTest {
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
        assertEquals("generated-2", ledgerEntry.id)
        assertEquals("pending-1", ledgerEntry.originPendingEntryId)
        assertEquals("food", ledgerEntry.categoryId)
        assertEquals("午餐", ledgerEntry.note)
        assertEquals(1, database.ledgerEntryDao().listLedgerEntries().size)
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
    fun expiredDeletionUsesInjectedClockByDefault() = runBlocking {
        repository.seedSystemCategories()
        val expired = repository.createManualEntry(sampleLedgerInput())
        repository.moveLedgerEntryToDeleted(expired.id)
        currentTime = NOW + LocalLedgerRepository.DELETED_RETENTION_MILLIS

        val purged = repository.purgeExpiredDeletedLedgerEntries()

        assertEquals(1, purged)
        assertNull(repository.getLedgerEntry(expired.id))
    }

    @Test
    fun confirmedLedgerEntriesCanBeObservedAfterDatabaseReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "ledger-reopen-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val fileDatabase = Room.databaseBuilder(
            context,
            BksDatabase::class.java,
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
            BksDatabase::class.java,
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
    fun confirmPendingKeepsExistingAccountIdThenMatchesExactlyWithoutCreating() = runBlocking {
        repository.seedSystemCategories()
        val accountById = repository.createFundingAccount("支付宝余额", PaymentSource.ALIPAY)
        val exactWechat = repository.createFundingAccount("微信零钱", PaymentSource.WECHAT)
        repository.upsertPending(
            samplePending(
                id = "account-by-id",
                fundingAccountId = accountById.id
            ).copy(fundingAccountLabel = exactWechat.label)
        )
        repository.upsertPending(
            samplePending(
                id = "account-by-label"
            ).copy(fundingAccountLabel = "  微信零钱  ")
        )
        repository.upsertPending(
            samplePending(
                id = "account-no-match",
                source = PaymentSource.ALIPAY
            ).copy(fundingAccountLabel = "新账户")
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
