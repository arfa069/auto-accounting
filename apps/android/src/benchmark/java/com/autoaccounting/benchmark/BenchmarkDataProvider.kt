package com.autoaccounting.benchmark

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.autoaccounting.data.local.AutoAccountingDatabaseProvider
import com.autoaccounting.data.local.FlowDirection
import com.autoaccounting.data.local.LedgerEntryInput
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
        val entryCount = runBlocking(Dispatchers.IO) {
            val appContext = requireNotNull(context).applicationContext
            check(LocalModeSessionStore(appContext).confirmLocalMode())
            val repository = LocalLedgerRepository(AutoAccountingDatabaseProvider.get(appContext))
            repository.seedSystemCategories()
            val ledgerBook = repository.ensureDefaultLedgerBook()
            if (repository.listLedgerEntries().none { it.merchantTitle.startsWith(ENTRY_PREFIX) }) {
                val now = System.currentTimeMillis()
                repeat(ENTRY_COUNT) { index ->
                    repository.createManualEntry(
                        ledgerBookId = ledgerBook.id,
                        input = LedgerEntryInput(
                            flowDirection = FlowDirection.OUTFLOW,
                            transactionKind = TransactionKind.EXPENSE,
                            amountMinor = (index + 1L) * 123L,
                            transactionTimeEpochMillis = now - index * 60_000L,
                            merchantTitle = "$ENTRY_PREFIX ${String.format(Locale.ROOT, "%02d", index + 1)}",
                            categoryId = LocalLedgerRepository.DEFAULT_CATEGORY_ID,
                            fundingAccountId = null,
                            newFundingAccountLabel = null,
                            note = null,
                            paymentSource = if (index % 2 == 0) {
                                PaymentSource.WECHAT
                            } else {
                                PaymentSource.ALIPAY
                            }
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
        private const val ENTRY_PREFIX = "基准账目"
        private const val ENTRY_COUNT = 40
    }
}
