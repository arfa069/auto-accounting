package com.autoaccounting.feature.sync

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.autoaccounting.api.LedgerSyncConflictContract
import com.autoaccounting.api.LedgerSyncEntityTypeContract
import com.autoaccounting.api.LedgerSyncMutationResultContract
import com.autoaccounting.api.LedgerSyncPayloadContract
import com.autoaccounting.api.LedgerSyncRecordContract
import com.autoaccounting.data.local.AutoAccountingDatabase
import com.autoaccounting.data.local.CaptureReason
import com.autoaccounting.data.local.CategoryEntity
import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.FundingAccountSourceScope
import com.autoaccounting.data.local.EntryOrigin
import com.autoaccounting.data.local.FlowDirection
import com.autoaccounting.data.local.IgnoreReason
import com.autoaccounting.data.local.IgnoredEntryEntity
import com.autoaccounting.data.local.LedgerBookEntity
import com.autoaccounting.data.local.LedgerEntryEntity
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.LocalSettingsEntity
import com.autoaccounting.data.local.LocalSyncMutationRecorder
import com.autoaccounting.data.local.PendingEntryEntity
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.TransactionKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class LedgerSyncLocalStoreTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: AutoAccountingDatabase
    private lateinit var store: LedgerSyncLocalStore
    private var nextId = 0

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AutoAccountingDatabase::class.java
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
    fun identicalCloudRecordDoesNotCreateConflictMutationDuringInitialMerge() = runBlocking {
        database.ledgerBookDao().insert(localBook())
        store.enable("profile-a")

        store.mergeSnapshot(listOf(remoteBook(version = 4, name = "本机账本")))
        store.reconcile()

        assertTrue(store.listMutations(100).isEmpty())
        assertEquals(4L, database.ledgerSyncDao().getMetadata("LEDGER_BOOK", "book-local")?.serverVersion)
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

    @Test
    fun sameCategoryBusinessKeyUsesCloudIdAndQueuesDifferentLocalAttributesAsCandidate() = runBlocking {
        database.categoryDao().upsert(
            CategoryEntity(
                id = "local-food",
                name = "餐饮",
                kind = TransactionKind.EXPENSE,
                sortOrder = 9,
                isSystem = false,
                createdAtEpochMillis = NOW
            )
        )
        store.enable("profile-a")
        val cloudRecord = LedgerSyncRecordContract(
            entityType = LedgerSyncEntityTypeContract.CATEGORY,
            entityId = "cloud-food",
            version = 3,
            revision = 4,
            deleted = false,
            payload = LedgerSyncPayloadContract.Category(
                id = "cloud-food",
                name = "餐饮",
                kind = TransactionKind.EXPENSE.name,
                sortOrder = 1,
                isSystem = true,
                createdAtMillis = NOW
            )
        )

        store.mergeSnapshot(listOf(cloudRecord))

        assertNull(database.categoryDao().getCategory("local-food"))
        assertEquals(1, database.categoryDao().getCategory("cloud-food")?.sortOrder)
        val mutation = store.listMutations(100).single()
        assertEquals("cloud-food", mutation.entityId)
        assertEquals(0L, mutation.baseVersion)
        assertEquals(9, (mutation.payload as LedgerSyncPayloadContract.Category).sortOrder)
    }

    @Test
    fun acceptedCanonicalIdRemapsLocalCategoryWithoutCreatingDeleteTombstone() = runBlocking {
        database.categoryDao().upsert(
            CategoryEntity("local-food", "餐饮", TransactionKind.EXPENSE, 1, true, NOW)
        )
        store.enable("profile-a")
        store.reconcile()
        val mutation = store.listMutations(100).single()

        store.applyPushResults(
            listOf(
                LedgerSyncMutationResultContract(
                    mutationId = mutation.mutationId,
                    accepted = true,
                    version = 2,
                    revision = 2,
                    conflictId = null,
                    canonicalEntityId = "cloud-food"
                )
            )
        )
        store.reconcile()

        assertNull(database.categoryDao().getCategory("local-food"))
        assertNotNull(database.categoryDao().getCategory("cloud-food"))
        assertNotNull(database.ledgerSyncDao().getMetadata("CATEGORY", "cloud-food"))
        assertTrue(store.listMutations(100).isEmpty())
    }

    @Test
    fun remoteConflictKeepsCanonicalRecordVisibleAndStoresCandidate() = runBlocking {
        store.enable("profile-a")
        val canonical = remoteBook(version = 2, name = "云端账本")
        val candidate = LedgerSyncPayloadContract.LedgerBook("book-local", "本机账本", NOW)

        store.applyRemote(
            records = listOf(canonical),
            conflicts = listOf(
                LedgerSyncConflictContract(
                    conflictId = "conflict-1",
                    entityType = LedgerSyncEntityTypeContract.LEDGER_BOOK,
                    entityId = "book-local",
                    canonicalVersion = 2,
                    canonicalDeleted = false,
                    canonicalPayload = canonical.payload,
                    candidateDeleted = false,
                    candidatePayload = candidate,
                    createdAtMillis = NOW
                )
            )
        )

        assertEquals("云端账本", database.ledgerBookDao().getById("book-local")?.name)
        assertEquals("conflict-1", store.conflicts.first().single().conflictId)
        assertTrue(database.ledgerSyncDao().getMetadata("LEDGER_BOOK", "book-local")?.blockedByConflict == true)
    }

    @Test
    fun remoteParentUpdatesPreserveExistingLedgerEntryReferences() = runBlocking {
        database.categoryDao().upsert(
            CategoryEntity("food", "旧分类", TransactionKind.EXPENSE, 9, false, NOW)
        )
        val fundingId = database.fundingAccountDao().insertIgnore(
            FundingAccountEntity(
                syncId = "funding-sync",
                sourceScope = FundingAccountSourceScope.WECHAT,
                paymentSource = PaymentSource.WECHAT,
                label = "旧资金账户",
                createdAtEpochMillis = NOW
            )
        )
        database.ledgerBookDao().insert(localBook())
        database.ledgerEntryDao().upsert(
            LedgerEntryEntity(
                id = "entry-1",
                ledgerBookId = "book-local",
                paymentSource = PaymentSource.WECHAT,
                originalCaptureSource = null,
                entryOrigin = EntryOrigin.MANUAL,
                originPendingEntryId = null,
                flowDirection = FlowDirection.OUTFLOW,
                transactionKind = TransactionKind.EXPENSE,
                amountMinor = 100,
                currency = "CNY",
                merchantTitle = "保留引用",
                transactionTimeEpochMillis = NOW,
                categoryId = "food",
                fundingAccountId = fundingId,
                note = null,
                evidenceSummary = null,
                parsedFieldsText = null,
                confirmedAtEpochMillis = NOW,
                updatedAtEpochMillis = NOW,
                deletedAtEpochMillis = null
            )
        )

        store.applyRemote(
            records = listOf(
                LedgerSyncRecordContract(
                    LedgerSyncEntityTypeContract.CATEGORY,
                    "food",
                    2,
                    1,
                    false,
                    LedgerSyncPayloadContract.Category(
                        "food", "云端分类", TransactionKind.EXPENSE.name, 1, true, NOW - 1
                    )
                ),
                LedgerSyncRecordContract(
                    LedgerSyncEntityTypeContract.FUNDING_ACCOUNT,
                    "funding-sync",
                    2,
                    2,
                    false,
                    LedgerSyncPayloadContract.FundingAccount(
                        "funding-sync",
                        FundingAccountSourceScope.WECHAT.name,
                        PaymentSource.WECHAT.name,
                        "云端资金账户",
                        NOW - 1
                    )
                ),
                remoteBook(version = 2, name = "云端账本")
            ),
            conflicts = emptyList()
        )

        val entry = database.ledgerEntryDao().getById("entry-1")
        assertEquals("food", entry?.categoryId)
        assertEquals(fundingId, entry?.fundingAccountId)
        assertEquals("book-local", entry?.ledgerBookId)
        assertEquals("云端分类", database.categoryDao().getCategory("food")?.name)
        assertEquals(
            "云端资金账户",
            database.fundingAccountDao().findBySyncId("funding-sync")?.label
        )
        assertEquals("云端账本", database.ledgerBookDao().getById("book-local")?.name)
    }

    @Test
    fun newerRemoteTombstoneWinsOverOlderCreateFromSamePullPage() = runBlocking {
        database.ledgerBookDao().insert(localBook())
        store.enable("profile-a")
        val payload = LedgerSyncPayloadContract.LedgerEntry(
            id = "entry-1",
            ledgerBookId = "book-local",
            paymentSource = null,
            originalCaptureSource = null,
            entryOrigin = EntryOrigin.MANUAL.name,
            flowDirection = FlowDirection.OUTFLOW.name,
            transactionKind = TransactionKind.EXPENSE.name,
            amountMinor = 100,
            currency = "CNY",
            merchantTitle = "已永久删除账目",
            transactionTimeMillis = NOW,
            categoryId = null,
            fundingAccountSyncId = null,
            note = null,
            confirmedAtMillis = NOW,
            updatedAtMillis = NOW,
            deletedAtMillis = null
        )

        store.applyRemote(
            records = listOf(
                LedgerSyncRecordContract(
                    LedgerSyncEntityTypeContract.LEDGER_ENTRY,
                    "entry-1",
                    version = 1,
                    revision = 10,
                    deleted = false,
                    payload = payload
                ),
                LedgerSyncRecordContract(
                    LedgerSyncEntityTypeContract.LEDGER_ENTRY,
                    "entry-1",
                    version = 2,
                    revision = 11,
                    deleted = true,
                    payload = null
                )
            ),
            conflicts = emptyList()
        )

        assertNull(database.ledgerEntryDao().getById("entry-1"))
        assertTrue(database.ledgerSyncDao().getMetadata("LEDGER_ENTRY", "entry-1")?.deleted == true)
    }

    @Test
    fun accountSwitchRollsBackBindingAndFormalDataWhenSnapshotCannotApply() = runBlocking {
        database.ledgerBookDao().insert(localBook())
        store.enable("profile-a")
        val invalidSnapshot = listOf(
            LedgerSyncRecordContract(
                entityType = LedgerSyncEntityTypeContract.CATEGORY,
                entityId = "invalid-category",
                version = 1,
                revision = 1,
                deleted = false,
                payload = LedgerSyncPayloadContract.Category(
                    id = "invalid-category",
                    name = "无效分类",
                    kind = "NOT_A_TRANSACTION_KIND",
                    sortOrder = 1,
                    isSystem = false,
                    createdAtMillis = NOW
                )
            )
        )

        val failure = runCatching {
            store.switchProfileWithSnapshot("profile-b", invalidSnapshot)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals("profile-a", store.currentState().profileKey)
        assertNotNull(database.ledgerBookDao().getById("book-local"))
        assertNull(database.categoryDao().getCategory("invalid-category"))
    }

    @Test
    fun accountSwitchPreservesDeviceQueuesClearsInvalidReferencesAndSelectsEarliestTargetLedger() = runBlocking {
        database.ledgerBookDao().insert(localBook())
        database.categoryDao().upsert(
            CategoryEntity("shared-category", "餐饮", TransactionKind.EXPENSE, 1, false, NOW)
        )
        val oldFundingId = database.fundingAccountDao().insertIgnore(
            FundingAccountEntity(
                sourceScope = FundingAccountSourceScope.WECHAT,
                paymentSource = PaymentSource.WECHAT,
                label = "旧账户零钱",
                createdAtEpochMillis = NOW
            )
        )
        database.localSettingsDao().upsert(
            LocalSettingsEntity(
                aiConsentGranted = true,
                enhancedContextGranted = true,
                continuousBillSyncCompleted = true,
                continuousMonitoringEnabled = true,
                activeLedgerId = "book-local"
            )
        )
        database.pendingEntryDao().upsert(pendingEntry("pending-1", oldFundingId))
        database.ignoredEntryDao().upsert(ignoredEntry("ignored-1", oldFundingId))
        store.enable("profile-a")
        val snapshot = listOf(
            LedgerSyncRecordContract(
                LedgerSyncEntityTypeContract.CATEGORY,
                "shared-category",
                1,
                1,
                false,
                LedgerSyncPayloadContract.Category(
                    "shared-category", "餐饮", TransactionKind.EXPENSE.name, 1, false, NOW
                )
            ),
            LedgerSyncRecordContract(
                LedgerSyncEntityTypeContract.LEDGER_BOOK,
                "target-later",
                1,
                2,
                false,
                LedgerSyncPayloadContract.LedgerBook("target-later", "稍后账本", 200)
            ),
            LedgerSyncRecordContract(
                LedgerSyncEntityTypeContract.LEDGER_BOOK,
                "target-first",
                1,
                3,
                false,
                LedgerSyncPayloadContract.LedgerBook("target-first", "最早账本", 100)
            )
        )

        store.switchProfileWithSnapshot("profile-b", snapshot)

        val pending = database.pendingEntryDao().getById("pending-1")
        val ignored = database.ignoredEntryDao().getById("ignored-1")
        assertEquals("shared-category", pending?.suggestedCategoryId)
        assertNull(pending?.fundingAccountId)
        assertEquals("shared-category", ignored?.suggestedCategoryId)
        assertNull(ignored?.fundingAccountId)
        val settings = database.localSettingsDao().getById()
        assertTrue(settings?.continuousMonitoringEnabled == true)
        assertTrue(settings?.aiConsentGranted == true)
        assertEquals("target-first", settings?.activeLedgerId)
    }

    private fun localBook() = LedgerBookEntity("book-local", "本机账本", NOW)

    private fun pendingEntry(id: String, fundingAccountId: Long) = PendingEntryEntity(
        id = id,
        source = PaymentSource.WECHAT,
        captureReason = CaptureReason.NOTIFICATION,
        confidence = ConfidenceState.NEEDS_REVIEW,
        transactionKind = TransactionKind.EXPENSE,
        amountMinor = 100,
        currency = "CNY",
        merchantTitle = "待确认",
        transactionTimeEpochMillis = NOW,
        capturedAtEpochMillis = NOW,
        suggestedCategoryId = "shared-category",
        fundingAccountId = fundingAccountId,
        fundingAccountLabel = "旧账户零钱",
        note = null,
        evidenceSummary = "设备证据",
        parsedFieldsText = null
    )

    private fun ignoredEntry(id: String, fundingAccountId: Long) = IgnoredEntryEntity(
        id = id,
        originalPendingEntryId = "pending-origin",
        source = PaymentSource.WECHAT,
        captureReason = CaptureReason.NOTIFICATION,
        confidence = ConfidenceState.NEEDS_REVIEW,
        transactionKind = TransactionKind.EXPENSE,
        amountMinor = 100,
        currency = "CNY",
        merchantTitle = "已忽略",
        transactionTimeEpochMillis = NOW,
        capturedAtEpochMillis = NOW,
        suggestedCategoryId = "shared-category",
        fundingAccountId = fundingAccountId,
        fundingAccountLabel = "旧账户零钱",
        note = null,
        evidenceSummary = "设备证据",
        parsedFieldsText = null,
        ignoredAtEpochMillis = NOW,
        expiresAtEpochMillis = NOW + 1,
        reason = IgnoreReason.USER_IGNORED
    )

    private fun remoteBook(version: Long, name: String) = LedgerSyncRecordContract(
        entityType = LedgerSyncEntityTypeContract.LEDGER_BOOK,
        entityId = "book-local",
        version = version,
        revision = version,
        deleted = false,
        payload = LedgerSyncPayloadContract.LedgerBook("book-local", name, NOW)
    )

    private companion object {
        const val NOW = 1_750_000_000_000L
    }
}
