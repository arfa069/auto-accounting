package com.bks.feature.sync

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bks.api.LedgerSyncEntityTypeContract
import com.bks.api.LedgerSyncMutationResultContract
import com.bks.api.LedgerSyncPayloadContract
import com.bks.data.local.BksDatabase
import com.bks.data.local.CategoryEntity
import com.bks.data.local.FundingAccountEntity
import com.bks.data.local.FundingAccountSourceScope
import com.bks.data.local.LedgerBookEntity
import com.bks.data.local.LocalLedgerRepository
import com.bks.data.local.LocalSyncMutationRecorder
import com.bks.data.local.PaymentSource
import com.bks.data.local.TransactionKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LedgerSyncOutboxTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: BksDatabase
    private lateinit var store: LedgerSyncLocalStore
    private var nextId = 0

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            BksDatabase::class.java
        ).allowMainThreadQueries().build()
        store = LedgerSyncLocalStore(
            database = database,
            clock = { NOW },
            idGenerator = { "sync-${++nextId}" }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun enablingBackfillsFundingSyncIdBeforeOutboxBootstrap() = runBlocking {
        database.ledgerBookDao().insert(localBook())
        database.fundingAccountDao().insertIgnore(
            FundingAccountEntity(
                sourceScope = FundingAccountSourceScope.WECHAT,
                paymentSource = PaymentSource.WECHAT,
                label = "零钱",
                createdAtEpochMillis = NOW
            )
        )

        store.enable("profile-a")

        assertEquals("sync-1", database.fundingAccountDao().getAllFundingAccounts().single().syncId)
        assertEquals(0, store.pendingMutationCount())

        store.reconcile()

        val mutations = store.listMutations(100)
        assertEquals(
            setOf(LedgerSyncEntityTypeContract.FUNDING_ACCOUNT, LedgerSyncEntityTypeContract.LEDGER_BOOK),
            mutations.map { it.entityType }.toSet()
        )
        assertTrue(mutations.all { it.baseVersion == 0L })
    }

    @Test
    fun repeatedLocalEditUpdatesSingleDurableMutationToLatestPayload() = runBlocking {
        store.enable("profile-a")
        val recorder = LocalSyncMutationRecorder(database, { NOW }) { "mutation-stable" }
        val first = CategoryEntity("food", "餐饮", TransactionKind.EXPENSE, 1, true, NOW)
        val latest = first.copy(sortOrder = 9)

        recorder.record(first)
        recorder.record(latest)

        val mutation = store.listMutations(100).single()
        assertEquals("mutation-stable", mutation.mutationId)
        assertEquals(9, (mutation.payload as LedgerSyncPayloadContract.Category).sortOrder)
    }

    @Test
    fun reseedingUnchangedSyncedSystemCategoriesDoesNotQueueMutations() = runBlocking {
        val repository = LocalLedgerRepository(
            database = database,
            clock = { NOW },
            idGenerator = { "repository-${++nextId}" }
        )
        repository.seedSystemCategories()
        store.enable("profile-a")
        store.reconcile()
        val initialMutations = store.listMutations(100)
        assertEquals(49, initialMutations.size)
        assertTrue(initialMutations.all { it.entityType == LedgerSyncEntityTypeContract.CATEGORY })
        store.applyPushResults(
            initialMutations.mapIndexed { index, mutation ->
                LedgerSyncMutationResultContract(
                    mutationId = mutation.mutationId,
                    accepted = true,
                    version = 1,
                    revision = index + 1L,
                    conflictId = null
                )
            }
        )

        repository.seedSystemCategories()

        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun acceptedPushRemovesOutboxAndPersistsServerVersion() = runBlocking {
        database.ledgerBookDao().insert(localBook())
        store.enable("profile-a")
        store.reconcile()
        val mutation = store.listMutations(100).single()

        store.applyPushResults(
            listOf(
                LedgerSyncMutationResultContract(
                    mutationId = mutation.mutationId,
                    accepted = true,
                    version = 3,
                    revision = 7,
                    conflictId = null
                )
            )
        )

        assertEquals(0, store.pendingMutationCount())
        val metadata = database.ledgerSyncDao().getMetadata("LEDGER_BOOK", "book-local")
        assertEquals(3L, metadata?.serverVersion)
        assertFalse(metadata?.blockedByConflict ?: true)
    }

    private fun localBook() = LedgerBookEntity("book-local", "本机账本", NOW)

    private companion object {
        const val NOW = 1_750_000_000_000L
    }
}
