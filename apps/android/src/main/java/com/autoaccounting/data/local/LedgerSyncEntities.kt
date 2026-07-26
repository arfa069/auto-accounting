package com.autoaccounting.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "account_sync_state")
data class AccountSyncStateEntity(
    @PrimaryKey val id: String = ACCOUNT_SYNC_STATE_ID,
    @ColumnInfo(name = "profile_key") val profileKey: String? = null,
    val enabled: Boolean = false,
    val cursor: Long = 0,
    @ColumnInfo(name = "last_success_at_millis") val lastSuccessAtMillis: Long? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null
)

@Entity(
    tableName = "account_sync_metadata",
    primaryKeys = ["entity_type", "entity_id"]
)
data class AccountSyncMetadataEntity(
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "server_version") val serverVersion: Long,
    @ColumnInfo(name = "synced_payload") val syncedPayload: String?,
    val deleted: Boolean,
    @ColumnInfo(name = "blocked_by_conflict") val blockedByConflict: Boolean = false
)

@Entity(
    tableName = "account_sync_outbox",
    indices = [Index(value = ["entity_type", "entity_id"])]
)
data class AccountSyncOutboxEntity(
    @PrimaryKey @ColumnInfo(name = "mutation_id") val mutationId: String,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "base_version") val baseVersion: Long,
    val deleted: Boolean,
    val payload: String?,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long
)

@Entity(tableName = "account_sync_conflicts")
data class AccountSyncConflictEntity(
    @PrimaryKey @ColumnInfo(name = "conflict_id") val conflictId: String,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "canonical_version") val canonicalVersion: Long,
    @ColumnInfo(name = "canonical_deleted") val canonicalDeleted: Boolean,
    @ColumnInfo(name = "canonical_payload") val canonicalPayload: String?,
    @ColumnInfo(name = "candidate_deleted") val candidateDeleted: Boolean,
    @ColumnInfo(name = "candidate_payload") val candidatePayload: String?,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long
)

const val ACCOUNT_SYNC_STATE_ID = "account-sync"
