package com.autoaccounting.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

const val LEDGER_SYNC_MAX_BATCH_SIZE = 100
const val LEDGER_SYNC_MAX_REQUEST_BYTES = 1024 * 1024

enum class LedgerSyncEntityTypeContract {
    CATEGORY,
    FUNDING_ACCOUNT,
    LEDGER_BOOK,
    LEDGER_ENTRY,
    CATEGORIZATION_RULE
}

enum class LedgerSyncConflictChoiceContract {
    CANONICAL,
    CANDIDATE
}

sealed interface LedgerSyncPayloadContract {
    data class Category(
        val id: String,
        val name: String,
        val kind: String?,
        val sortOrder: Int,
        val isSystem: Boolean,
        val createdAtMillis: Long
    ) : LedgerSyncPayloadContract

    data class FundingAccount(
        val syncId: String,
        val sourceScope: String,
        val paymentSource: String?,
        val label: String,
        val createdAtMillis: Long
    ) : LedgerSyncPayloadContract

    data class LedgerBook(
        val id: String,
        val name: String,
        val createdAtMillis: Long
    ) : LedgerSyncPayloadContract

    /** Device-only capture evidence is intentionally absent from this contract. */
    data class LedgerEntry(
        val id: String,
        val ledgerBookId: String,
        val paymentSource: String?,
        val originalCaptureSource: String?,
        val entryOrigin: String,
        val flowDirection: String,
        val transactionKind: String,
        val amountMinor: Long,
        val currency: String,
        val merchantTitle: String,
        val transactionTimeMillis: Long,
        val categoryId: String?,
        val fundingAccountSyncId: String?,
        val note: String?,
        val confirmedAtMillis: Long,
        val updatedAtMillis: Long,
        val deletedAtMillis: Long?
    ) : LedgerSyncPayloadContract

    data class CategorizationRule(
        val id: String,
        val merchantContains: String,
        val titleContains: String,
        val sourceLabel: String,
        val transactionKind: String,
        val category: String,
        val priority: Int,
        val enabled: Boolean,
        val updatedAtMillis: Long
    ) : LedgerSyncPayloadContract
}

data class LedgerSyncInitializeRequestContract(val deviceId: String)

data class LedgerSyncInitializeResponseContract(
    val profileKey: String,
    val recordCount: Int,
    val currentCursor: Long
)

data class LedgerSyncSnapshotRequestContract(
    val offset: Int = 0,
    val limit: Int = LEDGER_SYNC_MAX_BATCH_SIZE
)

data class LedgerSyncRecordContract(
    val entityType: LedgerSyncEntityTypeContract,
    val entityId: String,
    val version: Long,
    val revision: Long,
    val deleted: Boolean,
    val payload: LedgerSyncPayloadContract?
)

data class LedgerSyncSnapshotResponseContract(
    val records: List<LedgerSyncRecordContract>,
    val nextOffset: Int?,
    val currentCursor: Long
)

data class LedgerSyncMutationContract(
    val mutationId: String,
    val entityType: LedgerSyncEntityTypeContract,
    val entityId: String,
    val baseVersion: Long,
    val deleted: Boolean,
    val payload: LedgerSyncPayloadContract?
)

data class LedgerSyncPushRequestContract(
    val deviceId: String,
    val mutations: List<LedgerSyncMutationContract>
)

data class LedgerSyncMutationResultContract(
    val mutationId: String,
    val accepted: Boolean,
    val version: Long?,
    val revision: Long?,
    val conflictId: String?,
    val canonicalEntityId: String? = null
)

data class LedgerSyncPushResponseContract(
    val results: List<LedgerSyncMutationResultContract>,
    val currentCursor: Long
)

data class LedgerSyncPullRequestContract(
    val deviceId: String,
    val afterCursor: Long,
    val limit: Int = LEDGER_SYNC_MAX_BATCH_SIZE
)

data class LedgerSyncConflictContract(
    val conflictId: String,
    val entityType: LedgerSyncEntityTypeContract,
    val entityId: String,
    val canonicalVersion: Long,
    val canonicalDeleted: Boolean,
    val canonicalPayload: LedgerSyncPayloadContract?,
    val candidateDeleted: Boolean,
    val candidatePayload: LedgerSyncPayloadContract?,
    val createdAtMillis: Long
)

data class LedgerSyncPullResponseContract(
    val records: List<LedgerSyncRecordContract>,
    val conflicts: List<LedgerSyncConflictContract>,
    val nextCursor: Long,
    val hasMore: Boolean
)

data class LedgerSyncResolveConflictRequestContract(
    val conflictId: String,
    val expectedCanonicalVersion: Long,
    val choice: LedgerSyncConflictChoiceContract
)

data class LedgerSyncResolveConflictResponseContract(
    val record: LedgerSyncRecordContract
)

object LedgerSyncJsonContracts {
    private val json = Json

    fun encodeInitializeRequest(value: LedgerSyncInitializeRequestContract): String =
        buildJsonObject { put("deviceId", value.deviceId) }.toString()

    fun parseInitializeRequest(body: String): LedgerSyncInitializeRequestContract =
        root(body).let { LedgerSyncInitializeRequestContract(it.requiredString("deviceId")) }

    fun encodeInitializeResponse(value: LedgerSyncInitializeResponseContract): String =
        buildJsonObject {
            put("profileKey", value.profileKey)
            put("recordCount", value.recordCount)
            put("currentCursor", value.currentCursor)
        }.toString()

    fun parseInitializeResponse(body: String): LedgerSyncInitializeResponseContract = root(body).let {
        LedgerSyncInitializeResponseContract(
            profileKey = it.requiredString("profileKey"),
            recordCount = it.requiredInt("recordCount"),
            currentCursor = it.requiredLong("currentCursor")
        )
    }

    fun encodeSnapshotRequest(value: LedgerSyncSnapshotRequestContract): String =
        buildJsonObject {
            put("offset", value.offset)
            put("limit", value.limit)
        }.toString()

    fun parseSnapshotRequest(body: String): LedgerSyncSnapshotRequestContract = root(body).let {
        LedgerSyncSnapshotRequestContract(it.requiredInt("offset"), it.requiredInt("limit"))
    }

    fun encodeSnapshotResponse(value: LedgerSyncSnapshotResponseContract): String =
        buildJsonObject {
            put("records", recordsJson(value.records))
            putNullableInt("nextOffset", value.nextOffset)
            put("currentCursor", value.currentCursor)
        }.toString()

    fun parseSnapshotResponse(body: String): LedgerSyncSnapshotResponseContract = root(body).let {
        LedgerSyncSnapshotResponseContract(
            records = it.requiredArray("records").map(::parseRecord),
            nextOffset = it.optionalInt("nextOffset"),
            currentCursor = it.requiredLong("currentCursor")
        )
    }

    fun encodePushRequest(value: LedgerSyncPushRequestContract): String =
        buildJsonObject {
            put("deviceId", value.deviceId)
            put("mutations", buildJsonArray { value.mutations.forEach { add(mutationJson(it)) } })
        }.toString()

    fun parsePushRequest(body: String): LedgerSyncPushRequestContract = root(body).let {
        LedgerSyncPushRequestContract(
            deviceId = it.requiredString("deviceId"),
            mutations = it.requiredArray("mutations").map(::parseMutation)
        )
    }

    fun encodePushResponse(value: LedgerSyncPushResponseContract): String =
        buildJsonObject {
            put("results", buildJsonArray {
                value.results.forEach { result ->
                    add(buildJsonObject {
                        put("mutationId", result.mutationId)
                        put("accepted", result.accepted)
                        putNullableLong("version", result.version)
                        putNullableLong("revision", result.revision)
                        putNullableString("conflictId", result.conflictId)
                        putNullableString("canonicalEntityId", result.canonicalEntityId)
                    })
                }
            })
            put("currentCursor", value.currentCursor)
        }.toString()

    fun parsePushResponse(body: String): LedgerSyncPushResponseContract = root(body).let {
        LedgerSyncPushResponseContract(
            results = it.requiredArray("results").map { element ->
                element.jsonObject.let { result ->
                    LedgerSyncMutationResultContract(
                        mutationId = result.requiredString("mutationId"),
                        accepted = result.requiredBoolean("accepted"),
                        version = result.optionalLong("version"),
                        revision = result.optionalLong("revision"),
                        conflictId = result.optionalString("conflictId"),
                        canonicalEntityId = result.optionalString("canonicalEntityId")
                    )
                }
            },
            currentCursor = it.requiredLong("currentCursor")
        )
    }

    fun encodePullRequest(value: LedgerSyncPullRequestContract): String =
        buildJsonObject {
            put("deviceId", value.deviceId)
            put("afterCursor", value.afterCursor)
            put("limit", value.limit)
        }.toString()

    fun parsePullRequest(body: String): LedgerSyncPullRequestContract = root(body).let {
        LedgerSyncPullRequestContract(
            deviceId = it.requiredString("deviceId"),
            afterCursor = it.requiredLong("afterCursor"),
            limit = it.requiredInt("limit")
        )
    }

    fun encodePullResponse(value: LedgerSyncPullResponseContract): String =
        buildJsonObject {
            put("records", recordsJson(value.records))
            put("conflicts", buildJsonArray { value.conflicts.forEach { add(conflictJson(it)) } })
            put("nextCursor", value.nextCursor)
            put("hasMore", value.hasMore)
        }.toString()

    fun parsePullResponse(body: String): LedgerSyncPullResponseContract = root(body).let {
        LedgerSyncPullResponseContract(
            records = it.requiredArray("records").map(::parseRecord),
            conflicts = it.requiredArray("conflicts").map(::parseConflict),
            nextCursor = it.requiredLong("nextCursor"),
            hasMore = it.requiredBoolean("hasMore")
        )
    }

    fun encodeResolveRequest(value: LedgerSyncResolveConflictRequestContract): String =
        buildJsonObject {
            put("conflictId", value.conflictId)
            put("expectedCanonicalVersion", value.expectedCanonicalVersion)
            put("choice", value.choice.name)
        }.toString()

    fun parseResolveRequest(body: String): LedgerSyncResolveConflictRequestContract = root(body).let {
        LedgerSyncResolveConflictRequestContract(
            conflictId = it.requiredString("conflictId"),
            expectedCanonicalVersion = it.requiredLong("expectedCanonicalVersion"),
            choice = LedgerSyncConflictChoiceContract.valueOf(it.requiredString("choice"))
        )
    }

    fun encodeResolveResponse(value: LedgerSyncResolveConflictResponseContract): String =
        buildJsonObject { put("record", recordJson(value.record)) }.toString()

    fun parseResolveResponse(body: String): LedgerSyncResolveConflictResponseContract =
        LedgerSyncResolveConflictResponseContract(parseRecord(root(body).getValue("record")))

    fun encodePayload(type: LedgerSyncEntityTypeContract, payload: LedgerSyncPayloadContract): String {
        require(payloadType(payload) == type) { "Payload does not match entity type." }
        return payloadJson(payload).toString()
    }

    fun parsePayload(type: LedgerSyncEntityTypeContract, body: String): LedgerSyncPayloadContract =
        parsePayload(type, root(body))

    private fun recordsJson(records: List<LedgerSyncRecordContract>): JsonArray =
        buildJsonArray { records.forEach { add(recordJson(it)) } }

    private fun recordJson(record: LedgerSyncRecordContract): JsonObject = buildJsonObject {
        put("entityType", record.entityType.name)
        put("entityId", record.entityId)
        put("version", record.version)
        put("revision", record.revision)
        put("deleted", record.deleted)
        putPayload("payload", record.entityType, record.payload)
    }

    private fun parseRecord(element: JsonElement): LedgerSyncRecordContract = element.jsonObject.let {
        val type = LedgerSyncEntityTypeContract.valueOf(it.requiredString("entityType"))
        LedgerSyncRecordContract(
            entityType = type,
            entityId = it.requiredString("entityId"),
            version = it.requiredLong("version"),
            revision = it.requiredLong("revision"),
            deleted = it.requiredBoolean("deleted"),
            payload = it.parseOptionalPayload("payload", type)
        ).also(::validateRecord)
    }

    private fun mutationJson(mutation: LedgerSyncMutationContract): JsonObject = buildJsonObject {
        put("mutationId", mutation.mutationId)
        put("entityType", mutation.entityType.name)
        put("entityId", mutation.entityId)
        put("baseVersion", mutation.baseVersion)
        put("deleted", mutation.deleted)
        putPayload("payload", mutation.entityType, mutation.payload)
    }

    private fun parseMutation(element: JsonElement): LedgerSyncMutationContract = element.jsonObject.let {
        val type = LedgerSyncEntityTypeContract.valueOf(it.requiredString("entityType"))
        LedgerSyncMutationContract(
            mutationId = it.requiredString("mutationId"),
            entityType = type,
            entityId = it.requiredString("entityId"),
            baseVersion = it.requiredLong("baseVersion"),
            deleted = it.requiredBoolean("deleted"),
            payload = it.parseOptionalPayload("payload", type)
        ).also(::validateMutation)
    }

    private fun conflictJson(conflict: LedgerSyncConflictContract): JsonObject = buildJsonObject {
        put("conflictId", conflict.conflictId)
        put("entityType", conflict.entityType.name)
        put("entityId", conflict.entityId)
        put("canonicalVersion", conflict.canonicalVersion)
        put("canonicalDeleted", conflict.canonicalDeleted)
        putPayload("canonicalPayload", conflict.entityType, conflict.canonicalPayload)
        put("candidateDeleted", conflict.candidateDeleted)
        putPayload("candidatePayload", conflict.entityType, conflict.candidatePayload)
        put("createdAtMillis", conflict.createdAtMillis)
    }

    private fun parseConflict(element: JsonElement): LedgerSyncConflictContract = element.jsonObject.let {
        val type = LedgerSyncEntityTypeContract.valueOf(it.requiredString("entityType"))
        LedgerSyncConflictContract(
            conflictId = it.requiredString("conflictId"),
            entityType = type,
            entityId = it.requiredString("entityId"),
            canonicalVersion = it.requiredLong("canonicalVersion"),
            canonicalDeleted = it.requiredBoolean("canonicalDeleted"),
            canonicalPayload = it.parseOptionalPayload("canonicalPayload", type),
            candidateDeleted = it.requiredBoolean("candidateDeleted"),
            candidatePayload = it.parseOptionalPayload("candidatePayload", type),
            createdAtMillis = it.requiredLong("createdAtMillis")
        )
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putPayload(
        key: String,
        type: LedgerSyncEntityTypeContract,
        payload: LedgerSyncPayloadContract?
    ) {
        if (payload == null) {
            put(key, JsonNull)
        } else {
            require(payloadType(payload) == type) { "Payload does not match entity type." }
            put(key, payloadJson(payload))
        }
    }

    private fun payloadJson(payload: LedgerSyncPayloadContract): JsonObject = when (payload) {
        is LedgerSyncPayloadContract.Category -> buildJsonObject {
            put("id", payload.id); put("name", payload.name); putNullableString("kind", payload.kind)
            put("sortOrder", payload.sortOrder); put("isSystem", payload.isSystem)
            put("createdAtMillis", payload.createdAtMillis)
        }
        is LedgerSyncPayloadContract.FundingAccount -> buildJsonObject {
            put("syncId", payload.syncId); put("sourceScope", payload.sourceScope)
            putNullableString("paymentSource", payload.paymentSource); put("label", payload.label)
            put("createdAtMillis", payload.createdAtMillis)
        }
        is LedgerSyncPayloadContract.LedgerBook -> buildJsonObject {
            put("id", payload.id); put("name", payload.name); put("createdAtMillis", payload.createdAtMillis)
        }
        is LedgerSyncPayloadContract.LedgerEntry -> buildJsonObject {
            put("id", payload.id); put("ledgerBookId", payload.ledgerBookId)
            putNullableString("paymentSource", payload.paymentSource)
            putNullableString("originalCaptureSource", payload.originalCaptureSource)
            put("entryOrigin", payload.entryOrigin); put("flowDirection", payload.flowDirection)
            put("transactionKind", payload.transactionKind); put("amountMinor", payload.amountMinor)
            put("currency", payload.currency); put("merchantTitle", payload.merchantTitle)
            put("transactionTimeMillis", payload.transactionTimeMillis)
            putNullableString("categoryId", payload.categoryId)
            putNullableString("fundingAccountSyncId", payload.fundingAccountSyncId)
            putNullableString("note", payload.note); put("confirmedAtMillis", payload.confirmedAtMillis)
            put("updatedAtMillis", payload.updatedAtMillis); putNullableLong("deletedAtMillis", payload.deletedAtMillis)
        }
        is LedgerSyncPayloadContract.CategorizationRule -> buildJsonObject {
            put("id", payload.id); put("merchantContains", payload.merchantContains)
            put("titleContains", payload.titleContains); put("sourceLabel", payload.sourceLabel)
            put("transactionKind", payload.transactionKind); put("category", payload.category)
            put("priority", payload.priority); put("enabled", payload.enabled)
            put("updatedAtMillis", payload.updatedAtMillis)
        }
    }

    private fun parsePayload(
        type: LedgerSyncEntityTypeContract,
        value: JsonObject
    ): LedgerSyncPayloadContract = when (type) {
        LedgerSyncEntityTypeContract.CATEGORY -> LedgerSyncPayloadContract.Category(
            value.requiredString("id"), value.requiredString("name"), value.optionalString("kind"),
            value.requiredInt("sortOrder"), value.requiredBoolean("isSystem"), value.requiredLong("createdAtMillis")
        )
        LedgerSyncEntityTypeContract.FUNDING_ACCOUNT -> LedgerSyncPayloadContract.FundingAccount(
            value.requiredString("syncId"), value.requiredString("sourceScope"),
            value.optionalString("paymentSource"), value.requiredString("label"), value.requiredLong("createdAtMillis")
        )
        LedgerSyncEntityTypeContract.LEDGER_BOOK -> LedgerSyncPayloadContract.LedgerBook(
            value.requiredString("id"), value.requiredString("name"), value.requiredLong("createdAtMillis")
        )
        LedgerSyncEntityTypeContract.LEDGER_ENTRY -> LedgerSyncPayloadContract.LedgerEntry(
            id = value.requiredString("id"), ledgerBookId = value.requiredString("ledgerBookId"),
            paymentSource = value.optionalString("paymentSource"),
            originalCaptureSource = value.optionalString("originalCaptureSource"),
            entryOrigin = value.requiredString("entryOrigin"), flowDirection = value.requiredString("flowDirection"),
            transactionKind = value.requiredString("transactionKind"), amountMinor = value.requiredLong("amountMinor"),
            currency = value.requiredString("currency"), merchantTitle = value.requiredString("merchantTitle"),
            transactionTimeMillis = value.requiredLong("transactionTimeMillis"),
            categoryId = value.optionalString("categoryId"),
            fundingAccountSyncId = value.optionalString("fundingAccountSyncId"), note = value.optionalString("note"),
            confirmedAtMillis = value.requiredLong("confirmedAtMillis"),
            updatedAtMillis = value.requiredLong("updatedAtMillis"), deletedAtMillis = value.optionalLong("deletedAtMillis")
        )
        LedgerSyncEntityTypeContract.CATEGORIZATION_RULE -> LedgerSyncPayloadContract.CategorizationRule(
            value.requiredString("id"), value.requiredString("merchantContains"), value.requiredString("titleContains"),
            value.requiredString("sourceLabel"), value.requiredString("transactionKind"), value.requiredString("category"),
            value.requiredInt("priority"), value.requiredBoolean("enabled"), value.requiredLong("updatedAtMillis")
        )
    }

    private fun JsonObject.parseOptionalPayload(
        key: String,
        type: LedgerSyncEntityTypeContract
    ): LedgerSyncPayloadContract? = getValue(key).takeUnless { it is JsonNull }?.jsonObject?.let {
        parsePayload(type, it)
    }

    private fun payloadType(payload: LedgerSyncPayloadContract): LedgerSyncEntityTypeContract = when (payload) {
        is LedgerSyncPayloadContract.Category -> LedgerSyncEntityTypeContract.CATEGORY
        is LedgerSyncPayloadContract.FundingAccount -> LedgerSyncEntityTypeContract.FUNDING_ACCOUNT
        is LedgerSyncPayloadContract.LedgerBook -> LedgerSyncEntityTypeContract.LEDGER_BOOK
        is LedgerSyncPayloadContract.LedgerEntry -> LedgerSyncEntityTypeContract.LEDGER_ENTRY
        is LedgerSyncPayloadContract.CategorizationRule -> LedgerSyncEntityTypeContract.CATEGORIZATION_RULE
    }

    private fun validateMutation(value: LedgerSyncMutationContract) {
        require(value.mutationId.isNotBlank() && value.entityId.isNotBlank() && value.baseVersion >= 0)
        require(value.deleted == (value.payload == null)) { "Deleted mutations must omit payload and live mutations must include it." }
    }

    private fun validateRecord(value: LedgerSyncRecordContract) {
        require(value.entityId.isNotBlank() && value.version > 0 && value.revision > 0)
        require(value.deleted == (value.payload == null)) { "Deleted records must omit payload and live records must include it." }
    }

    private fun root(body: String): JsonObject = json.parseToJsonElement(body).jsonObject
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableString(key: String, value: String?) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableLong(key: String, value: Long?) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableInt(key: String, value: Int?) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun JsonObject.requiredString(key: String): String = getValue(key).jsonPrimitive.content
private fun JsonObject.requiredLong(key: String): Long = getValue(key).jsonPrimitive.long
private fun JsonObject.requiredInt(key: String): Int = requiredLong(key).toInt()
private fun JsonObject.requiredBoolean(key: String): Boolean = getValue(key).jsonPrimitive.content.toBooleanStrict()
private fun JsonObject.requiredArray(key: String): JsonArray = getValue(key).jsonArray
private fun JsonObject.optionalString(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
private fun JsonObject.optionalLong(key: String): Long? = get(key)?.jsonPrimitive?.longOrNull
private fun JsonObject.optionalInt(key: String): Int? = optionalLong(key)?.toInt()
