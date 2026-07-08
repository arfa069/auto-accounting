package com.autoaccounting.data.local

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
class LocalLedgerRepositoryTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: AutoAccountingDatabase
    private lateinit var repository: LocalLedgerRepository
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
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun seedSystemCategoriesCreatesStableDefaults() = runBlocking {
        repository.seedSystemCategories()

        val categories = database.categoryDao().getAllCategories()

        assertTrue(categories.any { it.id == "food" && it.name == "餐饮" })
        assertTrue(categories.any { it.id == "uncategorized" && it.kind == null })
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
    fun schemaVersionStartsAtOne() {
        assertEquals(1, AutoAccountingDatabase.SCHEMA_VERSION)
    }

    private fun samplePending(
        id: String = "pending-1",
        confidence: ConfidenceState = ConfidenceState.NEEDS_REVIEW,
        capturedAtEpochMillis: Long = NOW,
        fundingAccountId: Long? = null
    ): PendingEntryEntity = PendingEntryEntity(
        id = id,
        source = PaymentSource.WECHAT,
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
        note = null,
        evidenceSummary = "微信支付收款凭证"
    )

    private companion object {
        const val NOW = 1_735_689_600_000L
    }
}
