package com.autoaccounting.feature.sync

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.autoaccounting.api.LedgerSyncEntityTypeContract
import com.autoaccounting.api.LedgerSyncPayloadContract
import com.autoaccounting.api.LedgerSyncRecordContract
import com.autoaccounting.data.local.AutoAccountingDatabase
import com.autoaccounting.data.local.CaptureReason
import com.autoaccounting.data.local.CategoryEntity
import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.FundingAccountSourceScope
import com.autoaccounting.data.local.IgnoredEntryEntity
import com.autoaccounting.data.local.IgnoreReason
import com.autoaccounting.data.local.LedgerBookEntity
import com.autoaccounting.data.local.LocalSettingsEntity
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.PendingEntryEntity
import com.autoaccounting.data.local.TransactionKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
class LedgerSyncProfileSwitchTest {
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

    private companion object {
        const val NOW = 1_750_000_000_000L
    }
}
