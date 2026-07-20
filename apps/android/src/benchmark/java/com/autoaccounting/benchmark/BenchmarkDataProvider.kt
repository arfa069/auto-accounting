package com.autoaccounting.benchmark

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.room.withTransaction
import com.autoaccounting.data.local.AutoAccountingDatabaseProvider
import com.autoaccounting.data.local.EntryOrigin
import com.autoaccounting.data.local.FlowDirection
import com.autoaccounting.data.local.LedgerEntryEntity
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.TransactionKind
import com.autoaccounting.feature.account.LocalModeSessionStore
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class BenchmarkDataProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(method == METHOD_SEED) { "Unsupported benchmark method: $method" }
        val requestedEntryCount = extras?.getInt(ARG_ENTRY_COUNT, DEFAULT_ENTRY_COUNT)
            ?: DEFAULT_ENTRY_COUNT
        require(requestedEntryCount in SUPPORTED_ENTRY_COUNTS) {
            "Unsupported benchmark entry count: $requestedEntryCount"
        }
        val entryCount = runBlocking(Dispatchers.IO) {
            val appContext = requireNotNull(context).applicationContext
            check(LocalModeSessionStore(appContext).confirmLocalMode())
            val database = AutoAccountingDatabaseProvider.get(appContext)
            val repository = LocalLedgerRepository(database)
            repository.seedSystemCategories()
            val ledgerBook = repository.ensureDefaultLedgerBook()
            if (repository.listLedgerEntries().none { it.merchantTitle.startsWith(ENTRY_PREFIX) }) {
                val now = System.currentTimeMillis()
                database.withTransaction {
                    database.ledgerEntryDao().upsertAll(
                        buildBenchmarkEntries(
                            entryCount = requestedEntryCount,
                            ledgerBookId = ledgerBook.id,
                            nowEpochMillis = now
                        )
                    )
                }
            }
            repository.listLedgerEntries().size
        }
        return Bundle().apply {
            putBoolean(RESULT_SEEDED, true)
            putInt(RESULT_ENTRY_COUNT, entryCount)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        const val METHOD_SEED = "seed"
        const val RESULT_SEEDED = "seeded"
        const val RESULT_ENTRY_COUNT = "entry_count"
        const val ARG_ENTRY_COUNT = "entry_count"
        private const val ENTRY_PREFIX = "基准账目"
        private const val DEFAULT_ENTRY_COUNT = 40
        private val SUPPORTED_ENTRY_COUNTS = setOf(DEFAULT_ENTRY_COUNT, 1_000, 10_000)
        private val EXPENSE_CATEGORY_IDS = listOf(
            "food",
            "shopping",
            "daily",
            "transport",
            "vegetables",
            "entertainment",
            "housing",
            "healthcare",
            "books",
            "study",
            "travel",
            "games"
        )
        private const val MILLIS_PER_HOUR = 60L * 60L * 1_000L
        private const val MILLIS_PER_DAY = 24L * MILLIS_PER_HOUR

        private fun buildBenchmarkEntries(
            entryCount: Int,
            ledgerBookId: String,
            nowEpochMillis: Long
        ): List<LedgerEntryEntity> = List(entryCount) { index ->
            LedgerEntryEntity(
                id = "benchmark-entry-${index + 1}",
                ledgerBookId = ledgerBookId,
                paymentSource = if (index % 2 == 0) PaymentSource.WECHAT else PaymentSource.ALIPAY,
                originalCaptureSource = null,
                entryOrigin = EntryOrigin.MANUAL,
                originPendingEntryId = null,
                flowDirection = FlowDirection.OUTFLOW,
                transactionKind = TransactionKind.EXPENSE,
                amountMinor = (index + 1L) * 123L,
                currency = "CNY",
                merchantTitle = "$ENTRY_PREFIX ${String.format(Locale.ROOT, "%02d", index + 1)}",
                transactionTimeEpochMillis = nowEpochMillis -
                    (index % 180) * MILLIS_PER_DAY -
                    ((index / 180) % 24) * MILLIS_PER_HOUR,
                categoryId = EXPENSE_CATEGORY_IDS[index % EXPENSE_CATEGORY_IDS.size],
                fundingAccountId = null,
                note = null,
                evidenceSummary = null,
                parsedFieldsText = null,
                confirmedAtEpochMillis = nowEpochMillis,
                updatedAtEpochMillis = nowEpochMillis,
                deletedAtEpochMillis = null
            )
        }
    }
}
