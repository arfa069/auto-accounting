package com.bks.benchmark

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.Trace
import androidx.room.withTransaction
import com.bks.data.local.BksDatabaseProvider
import com.bks.data.local.EntryOrigin
import com.bks.data.local.FlowDirection
import com.bks.data.local.LedgerEntryEntity
import com.bks.data.local.LocalLedgerRepository
import com.bks.data.local.PaymentSource
import com.bks.data.local.TransactionKind
import com.bks.feature.account.AccountCredentials
import com.bks.feature.account.AccountHttpObserver
import com.bks.feature.account.AccountHttpStage
import com.bks.feature.account.HttpUrlConnectionAccountTransport
import com.bks.feature.account.LocalModeSessionStore
import com.bks.feature.account.SecureAccountSessionStore
import com.bks.feature.ledger.toLedgerUiEntry
import com.bks.feature.settings.LocalDataBackupRepository
import com.bks.feature.settings.exportLedgerCsv
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class BenchmarkDataProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        return when (method) {
            METHOD_SEED -> seedData(extras)
            METHOD_SEED_SIGNED_IN_SESSION -> seedSignedInSession()
            METHOD_EXPORT -> exportData(extras)
            METHOD_NETWORK -> validateNetwork(requireNotNull(extras?.getString(ARG_ENDPOINT)))
            else -> error("Unsupported benchmark method: $method")
        }
    }

    private fun seedData(extras: Bundle?): Bundle {
        val requestedEntryCount = extras?.getInt(ARG_ENTRY_COUNT, DEFAULT_ENTRY_COUNT)
            ?: DEFAULT_ENTRY_COUNT
        require(requestedEntryCount in SUPPORTED_ENTRY_COUNTS) {
            "Unsupported benchmark entry count: $requestedEntryCount"
        }
        val entryCount = runBlocking(Dispatchers.IO) {
            val appContext = requireNotNull(context).applicationContext
            check(LocalModeSessionStore(appContext).confirmLocalMode())
            val database = BksDatabaseProvider.get(appContext)
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

    private fun seedSignedInSession(): Bundle {
        val appContext = requireNotNull(context).applicationContext
        val saved = SecureAccountSessionStore(appContext).save(
            AccountCredentials(
                phone = TEST_PHONE,
                token = UUID.randomUUID().toString()
            )
        )
        check(saved)
        return Bundle().apply { putBoolean(RESULT_SESSION_SAVED, true) }
    }

    private fun exportData(extras: Bundle?): Bundle {
        val requestedEntryCount = extras?.getInt(ARG_ENTRY_COUNT, DEFAULT_ENTRY_COUNT)
            ?: DEFAULT_ENTRY_COUNT
        seedData(Bundle().apply { putInt(ARG_ENTRY_COUNT, requestedEntryCount) })

        val result = runBlocking(Dispatchers.IO) {
            val appContext = requireNotNull(context).applicationContext
            val database = BksDatabaseProvider.get(appContext)
            val repository = LocalLedgerRepository(database)
            val entries = repository.listLedgerEntries()

            val csvStart = System.nanoTime()
            val csvTraceName = "Batch9.exportCsv.$requestedEntryCount"
            val csvTraceCookie = requestedEntryCount * TRACE_COOKIE_MULTIPLIER + TRACE_COOKIE_CSV
            Trace.beginAsyncSection(csvTraceName, csvTraceCookie)
            val csv = try {
                withContext(Dispatchers.Default) {
                    exportLedgerCsv(entries.map { it.toLedgerUiEntry() })
                }
            } finally {
                Trace.endAsyncSection(csvTraceName, csvTraceCookie)
            }
            val csvMillis = (System.nanoTime() - csvStart) / NANOS_PER_MILLISECOND

            val backupStart = System.nanoTime()
            val backupTraceName = "Batch9.exportBackup.$requestedEntryCount"
            val backupTraceCookie =
                requestedEntryCount * TRACE_COOKIE_MULTIPLIER + TRACE_COOKIE_BACKUP
            Trace.beginAsyncSection(backupTraceName, backupTraceCookie)
            val backup = try {
                LocalDataBackupRepository(database).exportEncryptedBackup(UUID.randomUUID().toString())
            } finally {
                Trace.endAsyncSection(backupTraceName, backupTraceCookie)
            }
            val backupMillis = (System.nanoTime() - backupStart) / NANOS_PER_MILLISECOND

            ExportResult(
                csvBytes = csv.toByteArray(Charsets.UTF_8).size,
                backupBytes = backup.toByteArray(Charsets.UTF_8).size,
                csvMillis = csvMillis,
                backupMillis = backupMillis
            )
        }
        return Bundle().apply {
            putInt(RESULT_ENTRY_COUNT, requestedEntryCount)
            putInt(RESULT_CSV_BYTES, result.csvBytes)
            putInt(RESULT_BACKUP_BYTES, result.backupBytes)
            putLong(RESULT_CSV_MILLIS, result.csvMillis)
            putLong(RESULT_BACKUP_MILLIS, result.backupMillis)
        }
    }

    private fun validateNetwork(endpoint: String): Bundle {
        val headerStages = ConcurrentHashMap<AccountHttpStage, Long>()
        val headerStart = SystemClock.elapsedRealtime()
        HttpUrlConnectionAccountTransport(
            observer = AccountHttpObserver { stage -> headerStages.putIfAbsent(stage, SystemClock.elapsedRealtime()) }
        ).let { transport ->
            runBlocking(Dispatchers.IO) {
                transport.post(
                    url = "$endpoint/batch9/slow-headers",
                    form = mapOf("probe" to "batch9")
                )
            }
        }

        val stages = ConcurrentHashMap<AccountHttpStage, Long>()
        val observer = AccountHttpObserver { stage -> stages.putIfAbsent(stage, SystemClock.elapsedRealtime()) }
        val completeStart = SystemClock.elapsedRealtime()
        Trace.beginSection("Batch9.network.complete")
        try {
            runBlocking(Dispatchers.IO) {
                HttpUrlConnectionAccountTransport(observer = observer).post(
                    url = "$endpoint/batch9/slow-body",
                    form = mapOf("probe" to "batch9")
                )
            }
        } finally {
            Trace.endSection()
        }
        val completeMillis = SystemClock.elapsedRealtime() - completeStart

        val cancellationStart = SystemClock.elapsedRealtime()
        Trace.beginSection("Batch9.network.cancel")
        val requestWasCancelled = try {
            runBlocking(Dispatchers.IO) {
                val cancelledStages = ConcurrentHashMap<AccountHttpStage, Long>()
                val requestStarted = CompletableDeferred<Unit>()
                val request = async(Dispatchers.IO) {
                    HttpUrlConnectionAccountTransport(
                        observer = AccountHttpObserver { stage ->
                            cancelledStages.putIfAbsent(stage, SystemClock.elapsedRealtime())
                            if (stage == AccountHttpStage.RequestStarted) {
                                requestStarted.complete(Unit)
                            }
                        }
                    ).post(
                        url = "$endpoint/batch9/cancel",
                        form = mapOf("probe" to "batch9")
                    )
                }
                requestStarted.await()
                delay(CANCELLATION_DELAY_MILLIS)
                request.cancelAndJoin()
                check(cancelledStages.containsKey(AccountHttpStage.Cancelled))
                request.isCancelled
            }
        } finally {
            Trace.endSection()
        }

        check(stages.containsKey(AccountHttpStage.ResponseBodyRead))
        return Bundle().apply {
            putLong(RESULT_RESPONSE_HEADERS_MILLIS, elapsedMillis(
                headerStart,
                requireNotNull(headerStages[AccountHttpStage.ResponseHeadersReceived])
            ))
            putLong(RESULT_RESPONSE_BODY_MILLIS, elapsedMillis(
                completeStart,
                requireNotNull(stages[AccountHttpStage.ResponseBodyRead])
            ))
            putLong(RESULT_COMPLETE_MILLIS, completeMillis)
            putLong(RESULT_CANCELLATION_MILLIS, SystemClock.elapsedRealtime() - cancellationStart)
            putBoolean(RESULT_CANCELLED, requestWasCancelled)
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
        const val METHOD_SEED_SIGNED_IN_SESSION = "seed_signed_in_session"
        const val METHOD_EXPORT = "export"
        const val METHOD_NETWORK = "network"
        const val RESULT_SEEDED = "seeded"
        const val RESULT_ENTRY_COUNT = "entry_count"
        const val RESULT_SESSION_SAVED = "session_saved"
        const val RESULT_CSV_BYTES = "csv_bytes"
        const val RESULT_BACKUP_BYTES = "backup_bytes"
        const val RESULT_CSV_MILLIS = "csv_millis"
        const val RESULT_BACKUP_MILLIS = "backup_millis"
        const val RESULT_RESPONSE_HEADERS_MILLIS = "response_headers_millis"
        const val RESULT_RESPONSE_BODY_MILLIS = "response_body_millis"
        const val RESULT_COMPLETE_MILLIS = "complete_millis"
        const val RESULT_CANCELLATION_MILLIS = "cancellation_millis"
        const val RESULT_CANCELLED = "cancelled"
        const val ARG_ENTRY_COUNT = "entry_count"
        const val ARG_ENDPOINT = "endpoint"
        private const val ENTRY_PREFIX = "基准账目"
        private const val DEFAULT_ENTRY_COUNT = 40
        private const val TEST_PHONE = "13800138000"
        private const val CANCELLATION_DELAY_MILLIS = 200L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val TRACE_COOKIE_MULTIPLIER = 10
        private const val TRACE_COOKIE_CSV = 1
        private const val TRACE_COOKIE_BACKUP = 2
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

        private fun elapsedMillis(startMillis: Long, endMillis: Long): Long = endMillis - startMillis

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

    private data class ExportResult(
        val csvBytes: Int,
        val backupBytes: Int,
        val csvMillis: Long,
        val backupMillis: Long
    )
}
