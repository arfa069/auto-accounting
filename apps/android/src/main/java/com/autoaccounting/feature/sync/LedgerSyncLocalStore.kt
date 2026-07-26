@file:Suppress("LongMethod", "TooManyFunctions", "ComplexMethod", "NestedBlockDepth")

package com.autoaccounting.feature.sync

import androidx.room.withTransaction
import com.autoaccounting.api.LedgerSyncConflictContract
import com.autoaccounting.api.LedgerSyncEntityTypeContract
import com.autoaccounting.api.LedgerSyncJsonContracts
import com.autoaccounting.api.LedgerSyncMutationContract
import com.autoaccounting.api.LedgerSyncMutationResultContract
import com.autoaccounting.api.LedgerSyncPayloadContract
import com.autoaccounting.api.LedgerSyncRecordContract
import com.autoaccounting.data.local.ACCOUNT_SYNC_STATE_ID
import com.autoaccounting.data.local.AccountSyncConflictEntity
import com.autoaccounting.data.local.AccountSyncMetadataEntity
import com.autoaccounting.data.local.AccountSyncOutboxEntity
import com.autoaccounting.data.local.AccountSyncStateEntity
import com.autoaccounting.data.local.AutoAccountingDatabase
import com.autoaccounting.data.local.CategorizationRuleEntity
import com.autoaccounting.data.local.CategoryEntity
import com.autoaccounting.data.local.EntryOrigin
import com.autoaccounting.data.local.FlowDirection
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.FundingAccountSourceScope
import com.autoaccounting.data.local.LedgerBookEntity
import com.autoaccounting.data.local.LedgerEntryEntity
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.TransactionKind
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class LedgerSyncLocalStore(
    private val database: AutoAccountingDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    val state: Flow<AccountSyncStateEntity?> = database.ledgerSyncDao().observeState()
    val outboxCount: Flow<Int> = database.ledgerSyncDao().observeOutboxCount()
    val conflicts: Flow<List<AccountSyncConflictEntity>> = database.ledgerSyncDao().observeConflicts()

    suspend fun currentState(): AccountSyncStateEntity =
        database.ledgerSyncDao().getState() ?: AccountSyncStateEntity()

    suspend fun formalRecordCount(): Int = database.withTransaction {
        database.categoryDao().getAllCategories().size +
            database.fundingAccountDao().getAllFundingAccounts().size +
            database.ledgerBookDao().getAll().size +
            database.ledgerEntryDao().listAllLedgerEntries().size +
            database.categorizationRuleDao().listRules().size
    }

    suspend fun pendingMutationCount(): Int = database.ledgerSyncDao().outboxCount()

    suspend fun enable(profileKey: String) = database.withTransaction {
        require(profileKey.isNotBlank())
        val current = database.ledgerSyncDao().getState()
        require(current?.profileKey == null || current.profileKey == profileKey) {
            "Local ledger is bound to another account"
        }
        ensureFundingSyncIds()
        database.ledgerSyncDao().upsertState(
            (current ?: AccountSyncStateEntity()).copy(profileKey = profileKey, enabled = true, lastError = null)
        )
    }

    suspend fun disableAndUnbind() = database.withTransaction {
        database.ledgerSyncDao().upsertState(AccountSyncStateEntity())
        database.ledgerSyncDao().deleteAllMetadata()
        database.ledgerSyncDao().deleteAllOutbox()
        database.ledgerSyncDao().deleteAllConflicts()
    }

    suspend fun pauseWithError(message: String) {
        val current = currentState()
        database.ledgerSyncDao().upsertState(current.copy(lastError = message))
    }

    suspend fun markSuccess(cursor: Long) {
        val current = currentState()
        database.ledgerSyncDao().upsertState(
            current.copy(cursor = cursor, lastSuccessAtMillis = clock(), lastError = null)
        )
    }

    suspend fun reconcile() = database.withTransaction {
        if (database.ledgerSyncDao().getState()?.enabled != true) return@withTransaction
        ensureFundingSyncIds()
        reconcileOutbox()
    }

    suspend fun listMutations(limit: Int): List<LedgerSyncMutationContract> =
        database.ledgerSyncDao().listOutbox(limit).map { it.toContract() }

    suspend fun applyPushResults(results: List<LedgerSyncMutationResultContract>) = database.withTransaction {
        results.forEach { result ->
            val outbox = database.ledgerSyncDao().getOutbox(result.mutationId) ?: return@forEach
            val canonicalId = result.canonicalEntityId ?: outbox.entityId
            val canonicalPayload = remapAcceptedCanonical(outbox, canonicalId)
            if (result.accepted) {
                database.ledgerSyncDao().upsertMetadata(
                    AccountSyncMetadataEntity(
                        entityType = outbox.entityType,
                        entityId = canonicalId,
                        serverVersion = requireNotNull(result.version),
                        syncedPayload = canonicalPayload,
                        deleted = outbox.deleted,
                        blockedByConflict = false
                    )
                )
            } else {
                val metadata = database.ledgerSyncDao().getMetadata(outbox.entityType, canonicalId)
                database.ledgerSyncDao().upsertMetadata(
                    (metadata ?: AccountSyncMetadataEntity(
                        outbox.entityType, canonicalId, outbox.baseVersion, canonicalPayload, true
                    )).copy(blockedByConflict = true)
                )
            }
            if (canonicalId != outbox.entityId) {
                database.ledgerSyncDao().deleteMetadata(outbox.entityType, outbox.entityId)
            }
            database.ledgerSyncDao().deleteOutbox(result.mutationId)
        }
    }

    private suspend fun remapAcceptedCanonical(
        outbox: AccountSyncOutboxEntity,
        canonicalId: String
    ): String? {
        if (canonicalId == outbox.entityId || outbox.payload == null) return outbox.payload
        val type = LedgerSyncEntityTypeContract.valueOf(outbox.entityType)
        val payload = LedgerSyncJsonContracts.parsePayload(type, outbox.payload)
        val remapped = when (payload) {
            is LedgerSyncPayloadContract.Category -> {
                database.categoryDao().getCategory(outbox.entityId)?.let { local ->
                    remapCategoryIdentity(local, canonicalId, local.name)
                }
                payload.copy(id = canonicalId)
            }
            is LedgerSyncPayloadContract.FundingAccount -> {
                database.fundingAccountDao().findBySyncId(outbox.entityId)?.let { local ->
                    check(database.fundingAccountDao().setSyncId(local.id, canonicalId) == 1)
                }
                payload.copy(syncId = canonicalId)
            }
            else -> payload
        }
        return LedgerSyncJsonContracts.encodePayload(type, remapped)
    }

    suspend fun applyRemote(
        records: List<LedgerSyncRecordContract>,
        conflicts: List<LedgerSyncConflictContract>
    ) = database.withTransaction {
        records.latestByEntity().sortedWith(remoteApplyComparator).forEach { record ->
            applyRecord(record)
            database.ledgerSyncDao().upsertMetadata(record.toMetadata())
        }
        conflicts.forEach { conflict ->
            database.ledgerSyncDao().upsertConflict(conflict.toEntity())
            val metadata = database.ledgerSyncDao().getMetadata(conflict.entityType.name, conflict.entityId)
            database.ledgerSyncDao().upsertMetadata(
                (metadata ?: AccountSyncMetadataEntity(
                    conflict.entityType.name,
                    conflict.entityId,
                    conflict.canonicalVersion,
                    conflict.canonicalPayload?.let { LedgerSyncJsonContracts.encodePayload(conflict.entityType, it) },
                    conflict.canonicalDeleted
                )).copy(blockedByConflict = true)
            )
        }
    }

    suspend fun replaceWithSnapshot(records: List<LedgerSyncRecordContract>) = database.withTransaction {
        replaceFormalData(records, resetActiveLedger = false)
    }

    suspend fun switchProfileWithSnapshot(
        profileKey: String,
        records: List<LedgerSyncRecordContract>
    ) = database.withTransaction {
        require(profileKey.isNotBlank())
        check(database.ledgerSyncDao().outboxCount() == 0) { "Pending sync mutations must be uploaded first" }
        database.ledgerSyncDao().upsertState(
            AccountSyncStateEntity(profileKey = profileKey, enabled = true)
        )
        replaceFormalData(records, resetActiveLedger = true)
    }

    private suspend fun replaceFormalData(
        records: List<LedgerSyncRecordContract>,
        resetActiveLedger: Boolean
    ) {
        val pendingEntries = database.pendingEntryDao().listPendingEntries()
        val ignoredEntries = database.ignoredEntryDao().listAll()
        database.ledgerEntryDao().deleteAll()
        database.fundingAccountDao().deleteAll()
        database.categoryDao().deleteAll()
        database.ledgerBookDao().deleteAll()
        database.categorizationRuleDao().deleteAll()
        database.ledgerSyncDao().deleteAllMetadata()
        database.ledgerSyncDao().deleteAllOutbox()
        database.ledgerSyncDao().deleteAllConflicts()
        records.latestByEntity().sortedWith(remoteApplyComparator).forEach { record ->
            applyRecord(record)
            database.ledgerSyncDao().upsertMetadata(record.toMetadata())
        }
        ensureAtLeastOneLedgerBook()
        restoreDeviceQueueReferences(pendingEntries, ignoredEntries)
        val firstLedger = database.ledgerBookDao().getAll().first()
        database.localSettingsDao().getById()?.let { settings ->
            if (resetActiveLedger || database.ledgerBookDao().getById(settings.activeLedgerId) == null) {
                database.localSettingsDao().upsert(settings.copy(activeLedgerId = firstLedger.id))
            }
        }
    }

    private suspend fun restoreDeviceQueueReferences(
        pendingEntries: List<com.autoaccounting.data.local.PendingEntryEntity>,
        ignoredEntries: List<com.autoaccounting.data.local.IgnoredEntryEntity>
    ) {
        val validCategoryIds = database.categoryDao().getAllCategories().mapTo(mutableSetOf()) { it.id }
        val validFundingIds = database.fundingAccountDao().getAllFundingAccounts().mapTo(mutableSetOf()) { it.id }
        pendingEntries.forEach { previous ->
            database.pendingEntryDao().getById(previous.id)?.let { current ->
                database.pendingEntryDao().upsert(
                    current.copy(
                        suggestedCategoryId = previous.suggestedCategoryId?.takeIf(validCategoryIds::contains),
                        fundingAccountId = previous.fundingAccountId?.takeIf(validFundingIds::contains)
                    )
                )
            }
        }
        ignoredEntries.forEach { previous ->
            database.ignoredEntryDao().getById(previous.id)?.let { current ->
                database.ignoredEntryDao().upsert(
                    current.copy(
                        suggestedCategoryId = previous.suggestedCategoryId?.takeIf(validCategoryIds::contains),
                        fundingAccountId = previous.fundingAccountId?.takeIf(validFundingIds::contains)
                    )
                )
            }
        }
    }

    suspend fun mergeSnapshot(records: List<LedgerSyncRecordContract>) = database.withTransaction {
        ensureFundingSyncIds()
        canonicalizeBusinessKeys(records)
        val local = currentPayloads().associateBy { it.first to it.second }
        records.latestByEntity().sortedWith(remoteApplyComparator).forEach { record ->
            val key = record.entityType to record.entityId
            val localPayload = local[key]?.third
            val remotePayload = record.payload?.let { LedgerSyncJsonContracts.encodePayload(record.entityType, it) }
            database.ledgerSyncDao().upsertMetadata(record.toMetadata())
            if (localPayload == null) {
                applyRecord(record)
            } else if (localPayload != remotePayload || record.deleted) {
                applyRecord(record)
                enqueue(record.entityType, record.entityId, baseVersion = 0, deleted = false, payload = localPayload)
            }
        }
        reconcileOutbox()
    }

    private suspend fun canonicalizeBusinessKeys(records: List<LedgerSyncRecordContract>) {
        records.forEach { record ->
            if (record.deleted) return@forEach
            when (val payload = record.payload) {
                is LedgerSyncPayloadContract.Category -> {
                    val local = database.categoryDao().findByName(payload.name)
                    if (local != null && local.id != payload.id) {
                        remapCategoryIdentity(local, payload.id, payload.name)
                        database.ledgerSyncDao().deleteOutboxForEntity(
                            LedgerSyncEntityTypeContract.CATEGORY.name,
                            local.id
                        )
                    }
                }
                is LedgerSyncPayloadContract.FundingAccount -> {
                    val scope = FundingAccountSourceScope.valueOf(payload.sourceScope)
                    val local = database.fundingAccountDao().findByScopeAndLabel(scope, payload.label)
                    if (local != null && local.syncId != payload.syncId) {
                        local.syncId?.let { oldSyncId ->
                            database.ledgerSyncDao().deleteOutboxForEntity(
                                LedgerSyncEntityTypeContract.FUNDING_ACCOUNT.name,
                                oldSyncId
                            )
                        }
                        check(database.fundingAccountDao().setSyncId(local.id, payload.syncId) == 1)
                    }
                }
                else -> Unit
            }
        }
    }

    private suspend fun remapCategoryIdentity(
        local: CategoryEntity,
        canonicalId: String,
        canonicalName: String
    ) {
        val temporaryName = uniqueCategoryName("${canonicalName}（本机重映射）")
        check(database.categoryDao().updateName(local.id, temporaryName) == 1)
        database.categoryDao().upsert(local.copy(id = canonicalId, name = canonicalName))
        database.categoryDao().remapLedgerEntries(local.id, canonicalId)
        database.categoryDao().remapPendingEntries(local.id, canonicalId)
        database.categoryDao().remapIgnoredEntries(local.id, canonicalId)
        database.categoryDao().deleteById(local.id)
    }

    suspend fun resolved(record: LedgerSyncRecordContract, conflictId: String) = database.withTransaction {
        applyRecord(record)
        database.ledgerSyncDao().upsertMetadata(record.toMetadata())
        database.ledgerSyncDao().deleteConflict(conflictId)
    }

    private suspend fun reconcileOutbox() {
        val current = currentPayloads()
        val currentKeys = current.mapTo(mutableSetOf()) { it.first.name to it.second }
        current.forEach { (type, id, payload) ->
            val metadata = database.ledgerSyncDao().getMetadata(type.name, id)
            if (
                metadata?.blockedByConflict != true &&
                metadata?.syncedPayload != payload &&
                database.ledgerSyncDao().findOutbox(type.name, id) == null
            ) {
                enqueue(type, id, metadata?.serverVersion ?: 0, deleted = false, payload = payload)
            }
        }
        database.ledgerSyncDao().getAllMetadata()
            .filter { !it.deleted && !it.blockedByConflict && (it.entityType to it.entityId) !in currentKeys }
            .forEach { metadata ->
                if (database.ledgerSyncDao().findOutbox(metadata.entityType, metadata.entityId) == null) {
                    enqueue(
                        LedgerSyncEntityTypeContract.valueOf(metadata.entityType),
                        metadata.entityId,
                        metadata.serverVersion,
                        deleted = true,
                        payload = null
                    )
                }
            }
    }

    private suspend fun currentPayloads(): List<Triple<LedgerSyncEntityTypeContract, String, String>> {
        val funding = database.fundingAccountDao().getAllFundingAccounts()
        val fundingById = funding.associateBy { it.id }
        return buildList {
            database.categoryDao().getAllCategories().forEach { entity -> add(encoded(entity.toPayload())) }
            funding.forEach { entity -> add(encoded(entity.toPayload())) }
            database.ledgerBookDao().getAll().forEach { entity -> add(encoded(entity.toPayload())) }
            database.ledgerEntryDao().listAllLedgerEntries().forEach { entity ->
                add(encoded(entity.toPayload(fundingById[entity.fundingAccountId]?.syncId)))
            }
            database.categorizationRuleDao().listRules().forEach { entity -> add(encoded(entity.toPayload())) }
        }
    }

    private fun encoded(payload: LedgerSyncPayloadContract): Triple<LedgerSyncEntityTypeContract, String, String> {
        val type = payload.entityType()
        val id = payload.entityId()
        return Triple(type, id, LedgerSyncJsonContracts.encodePayload(type, payload))
    }

    private suspend fun ensureFundingSyncIds() {
        database.fundingAccountDao().getAllFundingAccounts().filter { it.syncId == null }.forEach { account ->
            check(database.fundingAccountDao().setSyncId(account.id, idGenerator()) == 1)
        }
    }

    private suspend fun enqueue(
        type: LedgerSyncEntityTypeContract,
        id: String,
        baseVersion: Long,
        deleted: Boolean,
        payload: String?
    ) {
        database.ledgerSyncDao().upsertOutbox(
            AccountSyncOutboxEntity(
                mutationId = idGenerator(),
                entityType = type.name,
                entityId = id,
                baseVersion = baseVersion,
                deleted = deleted,
                payload = payload,
                createdAtMillis = clock()
            )
        )
    }

    private suspend fun applyRecord(record: LedgerSyncRecordContract) {
        if (record.deleted) {
            when (record.entityType) {
                LedgerSyncEntityTypeContract.LEDGER_ENTRY -> database.ledgerEntryDao().deleteById(record.entityId)
                LedgerSyncEntityTypeContract.CATEGORIZATION_RULE -> database.categorizationRuleDao().deleteById(record.entityId)
                LedgerSyncEntityTypeContract.LEDGER_BOOK -> database.ledgerBookDao().deleteById(record.entityId)
                LedgerSyncEntityTypeContract.FUNDING_ACCOUNT ->
                    database.fundingAccountDao().findBySyncId(record.entityId)?.let { database.fundingAccountDao().deleteById(it.id) }
                LedgerSyncEntityTypeContract.CATEGORY -> database.categoryDao().deleteById(record.entityId)
            }
            ensureAtLeastOneLedgerBook()
            return
        }
        when (val payload = requireNotNull(record.payload)) {
            is LedgerSyncPayloadContract.Category -> applyCategory(payload)
            is LedgerSyncPayloadContract.FundingAccount -> applyFundingAccount(payload)
            is LedgerSyncPayloadContract.LedgerBook -> applyLedgerBook(payload)
            is LedgerSyncPayloadContract.LedgerEntry -> applyLedgerEntry(payload)
            is LedgerSyncPayloadContract.CategorizationRule -> database.categorizationRuleDao().upsert(payload.toEntity())
        }
    }

    private suspend fun applyCategory(payload: LedgerSyncPayloadContract.Category) {
        val existing = database.categoryDao().getCategory(payload.id)
        val collision = database.categoryDao().findByName(payload.name)
        val name = if (collision != null && collision.id != payload.id) uniqueCategoryName(payload.name) else payload.name
        if (existing == null) {
            database.categoryDao().upsert(payload.toEntity(name))
        } else {
            check(
                database.categoryDao().updateSynced(
                    id = payload.id,
                    name = name,
                    kind = payload.kind?.let(TransactionKind::valueOf),
                    sortOrder = payload.sortOrder,
                    isSystem = payload.isSystem,
                    createdAtEpochMillis = payload.createdAtMillis
                ) == 1
            )
        }
    }

    private suspend fun applyFundingAccount(payload: LedgerSyncPayloadContract.FundingAccount) {
        val existing = database.fundingAccountDao().findBySyncId(payload.syncId)
        val sourceScope = FundingAccountSourceScope.valueOf(payload.sourceScope)
        val collision = database.fundingAccountDao().findByScopeAndLabel(sourceScope, payload.label)
        val label = if (collision != null && collision.syncId != payload.syncId) uniqueFundingLabel(sourceScope, payload.label) else payload.label
        if (existing == null) {
            check(database.fundingAccountDao().insertIgnore(payload.toEntity(label)) != -1L)
        } else {
            check(
                database.fundingAccountDao().updateSynced(
                    id = existing.id,
                    syncId = payload.syncId,
                    sourceScope = sourceScope,
                    paymentSource = payload.paymentSource?.let(PaymentSource::valueOf),
                    label = label,
                    createdAtEpochMillis = payload.createdAtMillis
                ) == 1
            )
        }
    }

    private suspend fun applyLedgerBook(payload: LedgerSyncPayloadContract.LedgerBook) {
        val collision = database.ledgerBookDao().findByName(payload.name)
        if (collision != null && collision.id != payload.id) {
            check(
                database.ledgerBookDao().updateSynced(
                    id = collision.id,
                    name = uniqueLedgerBookName("${collision.name}（本机）"),
                    createdAtEpochMillis = collision.createdAtEpochMillis
                ) == 1
            )
        }
        val existing = database.ledgerBookDao().getById(payload.id)
        if (existing == null) {
            database.ledgerBookDao().insert(payload.toEntity())
        } else {
            check(
                database.ledgerBookDao().updateSynced(
                    id = payload.id,
                    name = payload.name,
                    createdAtEpochMillis = payload.createdAtMillis
                ) == 1
            )
        }
    }

    private suspend fun applyLedgerEntry(payload: LedgerSyncPayloadContract.LedgerEntry) {
        if (database.ledgerBookDao().getById(payload.ledgerBookId) == null) return
        val existing = database.ledgerEntryDao().getById(payload.id)
        val fundingId = payload.fundingAccountSyncId?.let { database.fundingAccountDao().findBySyncId(it)?.id }
        database.ledgerEntryDao().upsert(payload.toEntity(existing, fundingId))
    }

    private suspend fun ensureAtLeastOneLedgerBook() {
        if (database.ledgerBookDao().count() > 0) return
        database.ledgerBookDao().insert(
            LedgerBookEntity("default-ledger", "默认账本", clock())
        )
    }

    private suspend fun uniqueCategoryName(base: String): String = uniqueName(base) {
        database.categoryDao().findByName(it) != null
    }

    private suspend fun uniqueFundingLabel(scope: FundingAccountSourceScope, base: String): String = uniqueName("$base（云端）") {
        database.fundingAccountDao().findByScopeAndLabel(scope, it) != null
    }

    private suspend fun uniqueLedgerBookName(base: String): String = uniqueName(base) {
        database.ledgerBookDao().findByName(it) != null
    }

    private suspend fun uniqueName(base: String, exists: suspend (String) -> Boolean): String {
        if (!exists(base)) return base
        var index = 2
        while (exists("$base $index")) index++
        return "$base $index"
    }
}

private val remoteApplyComparator = Comparator<LedgerSyncRecordContract> { left, right ->
    fun order(record: LedgerSyncRecordContract): Int = if (record.deleted) {
        when (record.entityType) {
            LedgerSyncEntityTypeContract.LEDGER_ENTRY -> 0
            LedgerSyncEntityTypeContract.CATEGORIZATION_RULE -> 1
            LedgerSyncEntityTypeContract.LEDGER_BOOK -> 2
            LedgerSyncEntityTypeContract.FUNDING_ACCOUNT -> 3
            LedgerSyncEntityTypeContract.CATEGORY -> 4
        }
    } else {
        when (record.entityType) {
            LedgerSyncEntityTypeContract.CATEGORY -> 0
            LedgerSyncEntityTypeContract.FUNDING_ACCOUNT -> 1
            LedgerSyncEntityTypeContract.LEDGER_BOOK -> 2
            LedgerSyncEntityTypeContract.LEDGER_ENTRY -> 3
            LedgerSyncEntityTypeContract.CATEGORIZATION_RULE -> 4
        }
    }
    compareValuesBy(left, right, { order(it) }, { it.revision })
}

private fun List<LedgerSyncRecordContract>.latestByEntity(): List<LedgerSyncRecordContract> =
    groupBy { it.entityType to it.entityId }
        .values
        .map { versions -> versions.maxBy { it.revision } }

private fun AccountSyncOutboxEntity.toContract(): LedgerSyncMutationContract {
    val type = LedgerSyncEntityTypeContract.valueOf(entityType)
    return LedgerSyncMutationContract(
        mutationId = mutationId,
        entityType = type,
        entityId = entityId,
        baseVersion = baseVersion,
        deleted = deleted,
        payload = payload?.let { LedgerSyncJsonContracts.parsePayload(type, it) }
    )
}

private fun LedgerSyncRecordContract.toMetadata() = AccountSyncMetadataEntity(
    entityType = entityType.name,
    entityId = entityId,
    serverVersion = version,
    syncedPayload = payload?.let { LedgerSyncJsonContracts.encodePayload(entityType, it) },
    deleted = deleted,
    blockedByConflict = false
)

private fun LedgerSyncConflictContract.toEntity() = AccountSyncConflictEntity(
    conflictId = conflictId,
    entityType = entityType.name,
    entityId = entityId,
    canonicalVersion = canonicalVersion,
    canonicalDeleted = canonicalDeleted,
    canonicalPayload = canonicalPayload?.let { LedgerSyncJsonContracts.encodePayload(entityType, it) },
    candidateDeleted = candidateDeleted,
    candidatePayload = candidatePayload?.let { LedgerSyncJsonContracts.encodePayload(entityType, it) },
    createdAtMillis = createdAtMillis
)

private fun CategoryEntity.toPayload() = LedgerSyncPayloadContract.Category(
    id, name, kind?.name, sortOrder, isSystem, createdAtEpochMillis
)

private fun FundingAccountEntity.toPayload() = LedgerSyncPayloadContract.FundingAccount(
    requireNotNull(syncId), sourceScope.name, paymentSource?.name, label, createdAtEpochMillis
)

private fun LedgerBookEntity.toPayload() = LedgerSyncPayloadContract.LedgerBook(id, name, createdAtEpochMillis)

private fun LedgerEntryEntity.toPayload(fundingSyncId: String?) = LedgerSyncPayloadContract.LedgerEntry(
    id, ledgerBookId, paymentSource?.name, originalCaptureSource?.name, entryOrigin.name,
    flowDirection.name, transactionKind.name, amountMinor, currency, merchantTitle,
    transactionTimeEpochMillis, categoryId, fundingSyncId, note, confirmedAtEpochMillis,
    updatedAtEpochMillis, deletedAtEpochMillis
)

private fun CategorizationRuleEntity.toPayload() = LedgerSyncPayloadContract.CategorizationRule(
    id, merchantContains, titleContains, sourceLabel, transactionKind, category,
    priority, enabled, updatedAtEpochMillis
)

private fun LedgerSyncPayloadContract.entityType(): LedgerSyncEntityTypeContract = when (this) {
    is LedgerSyncPayloadContract.Category -> LedgerSyncEntityTypeContract.CATEGORY
    is LedgerSyncPayloadContract.FundingAccount -> LedgerSyncEntityTypeContract.FUNDING_ACCOUNT
    is LedgerSyncPayloadContract.LedgerBook -> LedgerSyncEntityTypeContract.LEDGER_BOOK
    is LedgerSyncPayloadContract.LedgerEntry -> LedgerSyncEntityTypeContract.LEDGER_ENTRY
    is LedgerSyncPayloadContract.CategorizationRule -> LedgerSyncEntityTypeContract.CATEGORIZATION_RULE
}

private fun LedgerSyncPayloadContract.entityId(): String = when (this) {
    is LedgerSyncPayloadContract.Category -> id
    is LedgerSyncPayloadContract.FundingAccount -> syncId
    is LedgerSyncPayloadContract.LedgerBook -> id
    is LedgerSyncPayloadContract.LedgerEntry -> id
    is LedgerSyncPayloadContract.CategorizationRule -> id
}

private fun LedgerSyncPayloadContract.Category.toEntity(nameOverride: String = name) = CategoryEntity(
    id, nameOverride, kind?.let(TransactionKind::valueOf), sortOrder, isSystem, createdAtMillis
)

private fun LedgerSyncPayloadContract.FundingAccount.toEntity(labelOverride: String = label) = FundingAccountEntity(
    syncId = syncId,
    sourceScope = FundingAccountSourceScope.valueOf(sourceScope),
    paymentSource = paymentSource?.let(PaymentSource::valueOf),
    label = labelOverride,
    createdAtEpochMillis = createdAtMillis
)

private fun LedgerSyncPayloadContract.LedgerBook.toEntity() = LedgerBookEntity(id, name, createdAtMillis)

private fun LedgerSyncPayloadContract.LedgerEntry.toEntity(
    existing: LedgerEntryEntity?,
    fundingAccountId: Long?
) = LedgerEntryEntity(
    id = id,
    ledgerBookId = ledgerBookId,
    paymentSource = paymentSource?.let(PaymentSource::valueOf),
    originalCaptureSource = originalCaptureSource?.let(PaymentSource::valueOf),
    entryOrigin = EntryOrigin.valueOf(entryOrigin),
    originPendingEntryId = existing?.originPendingEntryId,
    flowDirection = FlowDirection.valueOf(flowDirection),
    transactionKind = TransactionKind.valueOf(transactionKind),
    amountMinor = amountMinor,
    currency = currency,
    merchantTitle = merchantTitle,
    transactionTimeEpochMillis = transactionTimeMillis,
    categoryId = categoryId,
    fundingAccountId = fundingAccountId,
    note = note,
    evidenceSummary = existing?.evidenceSummary,
    parsedFieldsText = existing?.parsedFieldsText,
    confirmedAtEpochMillis = confirmedAtMillis,
    updatedAtEpochMillis = updatedAtMillis,
    deletedAtEpochMillis = deletedAtMillis
)

private fun LedgerSyncPayloadContract.CategorizationRule.toEntity() = CategorizationRuleEntity(
    id, merchantContains, titleContains, sourceLabel, transactionKind, category,
    priority, enabled, updatedAtMillis
)
