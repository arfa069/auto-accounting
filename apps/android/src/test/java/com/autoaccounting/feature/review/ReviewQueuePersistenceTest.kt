package com.autoaccounting.feature.review

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.autoaccounting.data.local.AutoAccountingDatabase
import com.autoaccounting.data.local.CaptureReason
import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.data.local.IgnoreReason
import com.autoaccounting.data.local.IgnoredEntryEntity
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.PendingEntryEntity
import com.autoaccounting.data.local.TransactionKind
import java.time.ZoneId
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
class ReviewQueuePersistenceTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: AutoAccountingDatabase
    private lateinit var repository: LocalLedgerRepository
    private lateinit var persistence: ReviewQueuePersistence
    private var nextId = 0

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AutoAccountingDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = LocalLedgerRepository(
            database = database,
            clock = { NOW },
            idGenerator = { "generated-${++nextId}" }
        )
        persistence = ReviewQueuePersistence(
            repository = repository,
            nowProvider = { NOW },
            zoneId = ZoneId.of("UTC")
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun observeStateRestoresPendingAndIgnoredMetadataForReviewQueue() = runBlocking {
        repository.seedSystemCategories()
        val pendingFundingAccount =
            repository.createFundingAccount("支付宝余额", PaymentSource.ALIPAY)
        val ignoredFundingAccount =
            repository.createFundingAccount("微信零钱", PaymentSource.WECHAT)
        repository.upsertPending(
            samplePending(
                id = "pending-duplicate",
                confidence = ConfidenceState.DUPLICATE_SUSPECT,
                captureReason = CaptureReason.BILL_SYNC,
                suggestedCategoryId = "food",
                fundingAccountId = pendingFundingAccount.id
            )
        )
        repository.upsertIgnored(
            sampleIgnored(
                id = "ignored-lunch",
                fundingAccountId = ignoredFundingAccount.id
            )
        )

        val state = persistence.observeState().first()

        assertEquals(listOf("pending-duplicate"), state.pendingEntries.map { it.id })
        val pending = state.pendingEntries.single()
        assertEquals("餐饮", pending.category)
        assertEquals("支付宝", pending.sourceLabel)
        assertEquals("账单同步", pending.captureReasonLabel)
        assertEquals(ConfidenceState.DUPLICATE_SUSPECT, pending.confidence)
        assertEquals("支付宝账单 午餐 35.90", pending.rawEvidenceText)
        assertEquals(pendingFundingAccount.id, pending.fundingAccountId)
        assertEquals("支付宝余额", pending.fundingAccountLabel)
        assertEquals(listOf("商户=午餐", "金额=35.90"), pending.parsedFields)
        assertEquals(1, state.duplicateSuspectCount)
        assertEquals(1, state.todaysNewlyCapturedCount)

        val ignored = state.recoverableIgnoredEntries.single()
        assertEquals("ignored-lunch", ignored.id)
        assertEquals("pending-ignored", ignored.originalPendingId)
        assertEquals("微信", ignored.entry.sourceLabel)
        assertEquals("通知捕获", ignored.entry.captureReasonLabel)
        assertEquals("微信支付收款凭证 午餐 35.90", ignored.entry.rawEvidenceText)
        assertEquals(ignoredFundingAccount.id, ignored.entry.fundingAccountId)
        assertEquals("微信零钱", ignored.entry.fundingAccountLabel)
        assertEquals(listOf("商户=午餐", "金额=35.90"), ignored.entry.parsedFields)
    }

    @Test
    fun persistTransitionMovesPendingToIgnoredAndRecoversThroughRepository() = runBlocking {
        val fundingAccount =
            repository.createFundingAccount("支付宝余额", PaymentSource.ALIPAY)
        repository.upsertPending(samplePending(fundingAccountId = fundingAccount.id))
        val previous = persistence.observeState().first()

        val ignored = reduceReviewQueue(previous, ReviewQueueAction.Ignore("pending-lunch"))
        persistence.persistTransition(previous, ignored)

        assertNull(database.pendingEntryDao().getById("pending-lunch"))
        val ignoredEntity = database.ignoredEntryDao().listRecoverable(NOW).single()
        assertEquals("pending-lunch", ignoredEntity.originalPendingEntryId)
        assertEquals(ConfidenceState.NEEDS_REVIEW, ignoredEntity.confidence)
        assertEquals("支付宝账单 午餐 35.90", ignoredEntity.evidenceSummary)
        assertEquals(fundingAccount.id, ignoredEntity.fundingAccountId)
        assertEquals("支付宝余额", ignoredEntity.fundingAccountLabel)
        assertEquals("商户=午餐\n金额=35.90", ignoredEntity.parsedFieldsText)
        assertTrue(database.ledgerEntryDao().listLedgerEntries().isEmpty())

        val recovered = reduceReviewQueue(
            ignored.copy(lastAction = null),
            ReviewQueueAction.RecoverIgnored(ignoredEntity.id)
        )
        persistence.persistTransition(ignored, recovered)

        val restored = database.pendingEntryDao().getById("pending-lunch")
        assertEquals("pending-lunch", restored?.id)
        assertEquals(CaptureReason.BILL_SYNC, restored?.captureReason)
        assertEquals(fundingAccount.id, restored?.fundingAccountId)
        assertEquals("支付宝余额", restored?.fundingAccountLabel)
        assertEquals("商户=午餐\n金额=35.90", restored?.parsedFieldsText)
        assertNull(database.ignoredEntryDao().getById(ignoredEntity.id))
    }

    @Test
    fun persistTransitionConfirmsThroughRepositoryAndUndoRestoresPending() = runBlocking {
        repository.seedSystemCategories()
        val fundingAccount =
            repository.createFundingAccount("支付宝余额", PaymentSource.ALIPAY)
        repository.upsertPending(
            samplePending(
                suggestedCategoryId = "food",
                fundingAccountId = fundingAccount.id
            )
        )
        val previous = persistence.observeState().first()

        val confirmed = reduceReviewQueue(previous, ReviewQueueAction.Confirm("pending-lunch"))
        persistence.persistTransition(previous, confirmed)

        assertNull(database.pendingEntryDao().getById("pending-lunch"))
        val ledgerEntry = database.ledgerEntryDao().listLedgerEntries().single()
        assertEquals("pending-lunch", ledgerEntry.originPendingEntryId)
        assertEquals("food", ledgerEntry.categoryId)
        assertEquals(fundingAccount.id, ledgerEntry.fundingAccountId)
        assertEquals(
            fundingAccount.id,
            persistence.ledgerEntriesForDedupe().single().fundingAccountId
        )

        val undone = reduceReviewQueue(confirmed, ReviewQueueAction.UndoLastAction)
        persistence.persistTransition(confirmed, undone)

        assertTrue(database.ledgerEntryDao().listLedgerEntries().isEmpty())
        val restored = database.pendingEntryDao().getById("pending-lunch")
        assertEquals("pending-lunch", restored?.id)
        assertEquals(fundingAccount.id, restored?.fundingAccountId)
    }

    @Test
    fun persistingOnePendingActionDoesNotClearAnotherPendingFundingAccountId() = runBlocking {
        val fundingAccount =
            repository.createFundingAccount("支付宝银行卡", PaymentSource.ALIPAY)
        repository.upsertPending(samplePending(id = "pending-target"))
        repository.upsertPending(
            samplePending(
                id = "pending-unrelated",
                fundingAccountLabel = "待识别账户"
            )
        )
        val previous = persistence.observeState().first()
        repository.upsertPending(
            samplePending(
                id = "pending-unrelated",
                fundingAccountId = fundingAccount.id,
                fundingAccountLabel = fundingAccount.label
            )
        )

        val next = reduceReviewQueue(
            previous,
            ReviewQueueAction.Ignore("pending-target")
        )
        persistence.persistTransition(previous, next)

        val unrelated = database.pendingEntryDao().getById("pending-unrelated")
        assertEquals(fundingAccount.id, unrelated?.fundingAccountId)
        assertEquals(fundingAccount.label, unrelated?.fundingAccountLabel)
    }

    @Test
    fun customSuggestedCategorySurvivesPendingPersistence() = runBlocking {
        val previous = ReviewQueueState(
            nowEpochMillis = NOW,
            todayStartEpochMillis = NOW - 1
        )
        val next = reduceReviewQueue(
            previous,
            ReviewQueueAction.AddPending(
                ReviewQueueEntry(
                    id = "pending-custom-category",
                    title = "Coffee Shop",
                    category = "work-meal",
                    sourceLabel = "wechat",
                    kindLabel = "expense",
                    capturedAtEpochMillis = NOW
                )
            )
        )

        persistence.persistTransition(previous, next)

        val restored = persistence.observeState().first().pendingEntries.single()
        assertEquals("work-meal", restored.category)
    }

    private fun samplePending(
        id: String = "pending-lunch",
        confidence: ConfidenceState = ConfidenceState.NEEDS_REVIEW,
        captureReason: CaptureReason = CaptureReason.BILL_SYNC,
        suggestedCategoryId: String? = null,
        fundingAccountId: Long? = null,
        fundingAccountLabel: String? = "支付宝余额"
    ): PendingEntryEntity = PendingEntryEntity(
        id = id,
        source = PaymentSource.ALIPAY,
        captureReason = captureReason,
        confidence = confidence,
        transactionKind = TransactionKind.EXPENSE,
        amountMinor = 3590,
        currency = "CNY",
        merchantTitle = "午餐",
        transactionTimeEpochMillis = NOW - 60_000,
        capturedAtEpochMillis = NOW,
        suggestedCategoryId = suggestedCategoryId,
        fundingAccountId = fundingAccountId,
        fundingAccountLabel = fundingAccountLabel,
        note = "客户会议",
        evidenceSummary = "支付宝账单 午餐 35.90",
        parsedFieldsText = "商户=午餐\n金额=35.90"
    )

    private fun sampleIgnored(
        id: String,
        fundingAccountId: Long? = null
    ): IgnoredEntryEntity = IgnoredEntryEntity(
        id = id,
        originalPendingEntryId = "pending-ignored",
        source = PaymentSource.WECHAT,
        captureReason = CaptureReason.NOTIFICATION,
        confidence = ConfidenceState.NEEDS_REVIEW,
        transactionKind = TransactionKind.EXPENSE,
        amountMinor = 3590,
        currency = "CNY",
        merchantTitle = "午餐",
        transactionTimeEpochMillis = NOW - 120_000,
        capturedAtEpochMillis = NOW - 60_000,
        suggestedCategoryId = null,
        fundingAccountId = fundingAccountId,
        fundingAccountLabel = "微信零钱",
        note = null,
        evidenceSummary = "微信支付收款凭证 午餐 35.90",
        parsedFieldsText = "商户=午餐\n金额=35.90",
        ignoredAtEpochMillis = NOW - 30_000,
        expiresAtEpochMillis = NOW + LocalLedgerRepository.IGNORED_RETENTION_MILLIS,
        reason = IgnoreReason.USER_IGNORED
    )

    private companion object {
        const val NOW = 1_783_468_800_000L
    }
}
