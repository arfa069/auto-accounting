package com.bks.backend.sync

import com.bks.api.LedgerSyncEntityTypeContract
import com.bks.api.LedgerSyncMutationContract
import com.bks.api.LedgerSyncPayloadContract
import com.bks.api.LedgerSyncRecordContract

internal fun LedgerSyncPayloadContract.businessKey(): String? = when (this) {
    is LedgerSyncPayloadContract.Category -> "CATEGORY\u001f$name"
    is LedgerSyncPayloadContract.FundingAccount -> "FUNDING_ACCOUNT\u001f$sourceScope\u001f$label"
    else -> null
}

internal data class NormalizedLedgerSyncMutation(
    val mutation: LedgerSyncMutationContract,
    val canonicalEntityId: String?
)

internal fun normalizeBusinessMutations(
    mutations: List<LedgerSyncMutationContract>,
    findCanonical: (LedgerSyncEntityTypeContract, LedgerSyncPayloadContract) -> LedgerSyncRecordContract?
): List<NormalizedLedgerSyncMutation> {
    val remaps = MutationEntityIdRemaps()
    val batchCanonicals = mutableMapOf<Pair<LedgerSyncEntityTypeContract, String>, LedgerSyncRecordContract>()
    val canonicalized = mutations.map { mutation ->
        normalizeMutation(mutation, findCanonical, batchCanonicals, remaps)
    }
    return canonicalized.map { it.withReferenceRemaps(remaps) }
}

private fun normalizeMutation(
    mutation: LedgerSyncMutationContract,
    findCanonical: (LedgerSyncEntityTypeContract, LedgerSyncPayloadContract) -> LedgerSyncRecordContract?,
    batchCanonicals: MutableMap<Pair<LedgerSyncEntityTypeContract, String>, LedgerSyncRecordContract>,
    remaps: MutationEntityIdRemaps
): NormalizedLedgerSyncMutation {
    val payload = mutation.payload ?: return NormalizedLedgerSyncMutation(mutation, null)
    val businessKey = payload.businessKey()
    val canonical = findCanonical(mutation.entityType, payload)
        ?: businessKey?.let { batchCanonicals[mutation.entityType to it] }
    if (canonical == null || canonical.entityId == mutation.entityId) {
        businessKey?.let {
            batchCanonicals.putIfAbsent(
                mutation.entityType to it,
                LedgerSyncRecordContract(
                    entityType = mutation.entityType,
                    entityId = mutation.entityId,
                    version = mutation.baseVersion + 1,
                    revision = 0,
                    deleted = false,
                    payload = payload
                )
            )
        }
        return NormalizedLedgerSyncMutation(mutation, null)
    }
    val canonicalPayload = payload.withEntityId(canonical.entityId)
    remaps.record(mutation.entityType, mutation.entityId, canonical.entityId)
    return NormalizedLedgerSyncMutation(
        mutation.copy(
            entityId = canonical.entityId,
            baseVersion = if (canonical.payload == canonicalPayload) canonical.version else mutation.baseVersion,
            payload = canonicalPayload
        ),
        canonical.entityId
    )
}

private class MutationEntityIdRemaps {
    val category = mutableMapOf<String, String>()
    val fundingAccount = mutableMapOf<String, String>()

    fun record(type: LedgerSyncEntityTypeContract, sourceId: String, canonicalId: String) {
        when (type) {
            LedgerSyncEntityTypeContract.CATEGORY -> category[sourceId] = canonicalId
            LedgerSyncEntityTypeContract.FUNDING_ACCOUNT -> fundingAccount[sourceId] = canonicalId
            else -> Unit
        }
    }
}

private fun NormalizedLedgerSyncMutation.withReferenceRemaps(
    remaps: MutationEntityIdRemaps
): NormalizedLedgerSyncMutation {
    val entry = mutation.payload as? LedgerSyncPayloadContract.LedgerEntry ?: return this
    return copy(
        mutation = mutation.copy(
            payload = entry.copy(
                categoryId = entry.categoryId?.let { remaps.category[it] ?: it },
                fundingAccountSyncId = entry.fundingAccountSyncId?.let { remaps.fundingAccount[it] ?: it }
            )
        )
    )
}

private fun LedgerSyncPayloadContract.withEntityId(entityId: String): LedgerSyncPayloadContract = when (this) {
    is LedgerSyncPayloadContract.Category -> copy(id = entityId)
    is LedgerSyncPayloadContract.FundingAccount -> copy(syncId = entityId)
    else -> this
}
