package com.autoaccounting.data.local

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * Domain repository interface for funding accounts shared across ledger books.
 */
interface FundingAccountRepository {
    val fundingAccounts: Flow<List<FundingAccountEntity>>

    suspend fun ensureFundingAccount(source: PaymentSource, label: String): FundingAccountEntity
    suspend fun createFundingAccount(label: String, paymentSource: PaymentSource?): FundingAccountEntity
    suspend fun updateFundingAccount(
        fundingAccountId: Long,
        label: String,
        paymentSource: PaymentSource?
    ): FundingAccountEntity
    suspend fun deleteFundingAccount(fundingAccountId: Long): FundingAccountDeleteResult
}

internal class RoomFundingAccountRepository(
    private val database: AutoAccountingDatabase,
    private val clock: () -> Long,
    private val idGenerator: () -> String
) : FundingAccountRepository {
    private val syncRecorder = LocalSyncMutationRecorder(database, clock, idGenerator)
    override val fundingAccounts = database.fundingAccountDao().observeFundingAccounts()

    override suspend fun ensureFundingAccount(
        source: PaymentSource,
        label: String
    ): FundingAccountEntity = database.withTransaction {
        val normalizedLabel = label.trim()
        require(normalizedLabel.isNotEmpty()) { "Funding account label is required" }
        val sourceScope = fundingAccountScope(source)
        val existing = database.fundingAccountDao().findByScopeAndLabel(sourceScope, normalizedLabel)
        if (existing != null) {
            return@withTransaction existing
        }

        val newAccount = FundingAccountEntity(
            syncId = idGenerator(),
            sourceScope = sourceScope,
            paymentSource = source,
            label = normalizedLabel,
            createdAtEpochMillis = clock()
        )
        val id = database.fundingAccountDao().insertIgnore(newAccount)
        if (id == -1L) {
            requireNotNull(database.fundingAccountDao().findByScopeAndLabel(sourceScope, normalizedLabel))
        } else {
            newAccount.copy(id = id).also { syncRecorder.record(it) }
        }
    }

    override suspend fun createFundingAccount(
        label: String,
        paymentSource: PaymentSource?
    ): FundingAccountEntity = database.withTransaction {
        val normalizedLabel = normalizeFundingAccountLabel(label)
        val sourceScope = fundingAccountScope(paymentSource)
        require(database.fundingAccountDao().findByScopeAndLabel(sourceScope, normalizedLabel) == null) {
            "Funding account already exists for this payment source"
        }
        val account = FundingAccountEntity(
            syncId = idGenerator(),
            sourceScope = sourceScope,
            paymentSource = paymentSource,
            label = normalizedLabel,
            createdAtEpochMillis = clock()
        )
        val id = database.fundingAccountDao().insertIgnore(account)
        require(id != -1L) { "Funding account already exists for this payment source" }
        account.copy(id = id).also { syncRecorder.record(it) }
    }

    override suspend fun updateFundingAccount(
        fundingAccountId: Long,
        label: String,
        paymentSource: PaymentSource?
    ): FundingAccountEntity = database.withTransaction {
        val existing = requireNotNull(database.fundingAccountDao().getById(fundingAccountId)) {
            "Funding account not found: $fundingAccountId"
        }
        val normalizedLabel = normalizeFundingAccountLabel(label)
        val sourceScope = fundingAccountScope(paymentSource)
        val duplicate = database.fundingAccountDao().findByScopeAndLabel(
            sourceScope,
            normalizedLabel
        )
        require(duplicate == null || duplicate.id == existing.id) {
            "Funding account already exists for this payment source"
        }
        check(
            database.fundingAccountDao().update(
                id = existing.id,
                sourceScope = sourceScope,
                paymentSource = paymentSource,
                label = normalizedLabel
            ) == 1
        )
        existing.copy(
            sourceScope = sourceScope,
            paymentSource = paymentSource,
            label = normalizedLabel
        ).also { syncRecorder.record(it) }
    }

    override suspend fun deleteFundingAccount(
        fundingAccountId: Long
    ): FundingAccountDeleteResult = database.withTransaction {
        if (database.fundingAccountDao().getById(fundingAccountId) == null) {
            return@withTransaction FundingAccountDeleteResult.NotFound
        }
        val activeLedgerEntryCount =
            database.ledgerEntryDao().countActiveByFundingAccountId(fundingAccountId)
        val deletedLedgerEntryCount =
            database.ledgerEntryDao().countDeletedByFundingAccountId(fundingAccountId)
        val pendingEntryCount =
            database.pendingEntryDao().countByFundingAccountId(fundingAccountId)
        val ignoredEntryCount =
            database.ignoredEntryDao().countByFundingAccountId(fundingAccountId)
        if (
            activeLedgerEntryCount > 0 ||
            deletedLedgerEntryCount > 0 ||
            pendingEntryCount > 0 ||
            ignoredEntryCount > 0
        ) {
            return@withTransaction FundingAccountDeleteResult.Referenced(
                activeLedgerEntryCount = activeLedgerEntryCount,
                deletedLedgerEntryCount = deletedLedgerEntryCount,
                pendingEntryCount = pendingEntryCount,
                ignoredEntryCount = ignoredEntryCount
            )
        }
        val target = requireNotNull(database.fundingAccountDao().getById(fundingAccountId))
        check(database.fundingAccountDao().deleteById(fundingAccountId) == 1)
        target.syncId?.let {
            syncRecorder.recordDelete(
                com.autoaccounting.api.LedgerSyncEntityTypeContract.FUNDING_ACCOUNT,
                it
            )
        }
        FundingAccountDeleteResult.Deleted
    }

    internal suspend fun resolve(input: LedgerEntryInput): Long? {
        require(input.fundingAccountId == null || input.newFundingAccountLabel.isNullOrBlank()) {
            "Choose an existing funding account or create a new one, not both"
        }
        input.fundingAccountId?.let { fundingAccountId ->
            requireNotNull(database.fundingAccountDao().getById(fundingAccountId)) {
                "Funding account not found: $fundingAccountId"
            }
            return fundingAccountId
        }
        val label = input.newFundingAccountLabel?.trim().orEmpty()
        if (label.isEmpty()) {
            return null
        }
        val scope = fundingAccountScope(input.paymentSource)
        val existing = database.fundingAccountDao().findByScopeAndLabel(scope, label)
        if (existing != null) {
            return existing.id
        }
        val account = FundingAccountEntity(
            syncId = idGenerator(),
            sourceScope = scope,
            paymentSource = input.paymentSource,
            label = label,
            createdAtEpochMillis = clock()
        )
        val id = database.fundingAccountDao().insertIgnore(account)
        return if (id == -1L) {
            requireNotNull(database.fundingAccountDao().findByScopeAndLabel(scope, label)).id
        } else {
            syncRecorder.record(account.copy(id = id))
            id
        }
    }

    internal suspend fun resolvePending(pending: PendingEntryEntity): Long? {
        pending.fundingAccountId?.let { fundingAccountId ->
            if (database.fundingAccountDao().getById(fundingAccountId) != null) {
                return fundingAccountId
            }
        }
        val normalizedLabel = pending.fundingAccountLabel?.trim().orEmpty()
        if (normalizedLabel.isEmpty()) {
            return null
        }
        return database.fundingAccountDao().findByScopeAndLabel(
            sourceScope = fundingAccountScope(pending.source),
            label = normalizedLabel
        )?.id
    }

    private fun normalizeFundingAccountLabel(label: String): String =
        label.trim().also {
            require(it.isNotEmpty()) { "Funding account label is required" }
        }

    private fun fundingAccountScope(
        paymentSource: PaymentSource?
    ): FundingAccountSourceScope =
        paymentSource?.toFundingAccountSourceScope() ?: FundingAccountSourceScope.USER
}
