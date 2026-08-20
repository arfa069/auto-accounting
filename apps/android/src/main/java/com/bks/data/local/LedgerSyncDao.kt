package com.bks.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerSyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: AccountSyncStateEntity)

    @Query("SELECT * FROM account_sync_state WHERE id = :id")
    suspend fun getState(id: String = ACCOUNT_SYNC_STATE_ID): AccountSyncStateEntity?

    @Query("SELECT * FROM account_sync_state WHERE id = :id")
    fun observeState(id: String = ACCOUNT_SYNC_STATE_ID): Flow<AccountSyncStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadata(metadata: AccountSyncMetadataEntity)

    @Query("SELECT * FROM account_sync_metadata WHERE entity_type = :entityType AND entity_id = :entityId")
    suspend fun getMetadata(entityType: String, entityId: String): AccountSyncMetadataEntity?

    @Query("SELECT * FROM account_sync_metadata")
    suspend fun getAllMetadata(): List<AccountSyncMetadataEntity>

    @Query("DELETE FROM account_sync_metadata")
    suspend fun deleteAllMetadata()

    @Query("DELETE FROM account_sync_metadata WHERE entity_type = :entityType AND entity_id = :entityId")
    suspend fun deleteMetadata(entityType: String, entityId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOutbox(item: AccountSyncOutboxEntity)

    @Query("SELECT * FROM account_sync_outbox WHERE entity_type = :entityType AND entity_id = :entityId LIMIT 1")
    suspend fun findOutbox(entityType: String, entityId: String): AccountSyncOutboxEntity?

    @Query("SELECT * FROM account_sync_outbox ORDER BY created_at_millis, mutation_id LIMIT :limit")
    suspend fun listOutbox(limit: Int): List<AccountSyncOutboxEntity>

    @Query("SELECT * FROM account_sync_outbox WHERE mutation_id = :mutationId")
    suspend fun getOutbox(mutationId: String): AccountSyncOutboxEntity?

    @Query("SELECT COUNT(*) FROM account_sync_outbox")
    fun observeOutboxCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM account_sync_outbox")
    suspend fun outboxCount(): Int

    @Query("DELETE FROM account_sync_outbox WHERE mutation_id = :mutationId")
    suspend fun deleteOutbox(mutationId: String): Int

    @Query("DELETE FROM account_sync_outbox WHERE entity_type = :entityType AND entity_id = :entityId")
    suspend fun deleteOutboxForEntity(entityType: String, entityId: String): Int

    @Query("DELETE FROM account_sync_outbox")
    suspend fun deleteAllOutbox()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConflict(conflict: AccountSyncConflictEntity)

    @Query("SELECT * FROM account_sync_conflicts ORDER BY created_at_millis, conflict_id")
    fun observeConflicts(): Flow<List<AccountSyncConflictEntity>>

    @Query("SELECT * FROM account_sync_conflicts WHERE conflict_id = :conflictId")
    suspend fun getConflict(conflictId: String): AccountSyncConflictEntity?

    @Query("DELETE FROM account_sync_conflicts WHERE conflict_id = :conflictId")
    suspend fun deleteConflict(conflictId: String): Int

    @Query("DELETE FROM account_sync_conflicts")
    suspend fun deleteAllConflicts()
}
