package com.autoaccounting.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class LedgerBookEntryCounts(
    val id: String,
    val name: String,
    val activeEntryCount: Int,
    val deletedEntryCount: Int
)

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(categories: List<CategoryEntity>)

    @Query(
        """
        UPDATE OR IGNORE categories
        SET name = :name, kind = :kind, sort_order = :sortOrder
        WHERE id = :id AND is_system = 1
        """
    )
    suspend fun updateSystemCategory(
        id: String,
        name: String,
        kind: TransactionKind?,
        sortOrder: Int
    )

    @Query("SELECT * FROM categories ORDER BY sort_order ASC, name ASC")
    suspend fun getAllCategories(): List<CategoryEntity>

    @Query("SELECT * FROM categories ORDER BY sort_order ASC, name ASC")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getCategory(id: String): CategoryEntity?

    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): CategoryEntity?

    @Query("DELETE FROM categories")
    suspend fun deleteAll()

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("UPDATE categories SET name = :name WHERE id = :id")
    suspend fun updateName(id: String, name: String): Int

    @Query(
        """
        UPDATE categories
        SET name = :name,
            kind = :kind,
            sort_order = :sortOrder,
            is_system = :isSystem,
            created_at_epoch_millis = :createdAtEpochMillis
        WHERE id = :id
        """
    )
    suspend fun updateSynced(
        id: String,
        name: String,
        kind: TransactionKind?,
        sortOrder: Int,
        isSystem: Boolean,
        createdAtEpochMillis: Long
    ): Int

    @Query("UPDATE ledger_entries SET category_id = :canonicalId WHERE category_id = :localId")
    suspend fun remapLedgerEntries(localId: String, canonicalId: String): Int

    @Query("UPDATE pending_entries SET suggested_category_id = :canonicalId WHERE suggested_category_id = :localId")
    suspend fun remapPendingEntries(localId: String, canonicalId: String): Int

    @Query("UPDATE ignored_entries SET suggested_category_id = :canonicalId WHERE suggested_category_id = :localId")
    suspend fun remapIgnoredEntries(localId: String, canonicalId: String): Int
}

@Dao
interface LedgerBookDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(ledgerBook: LedgerBookEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(ledgerBooks: List<LedgerBookEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ledgerBook: LedgerBookEntity)

    @Query("SELECT * FROM ledger_books WHERE id = :id")
    suspend fun getById(id: String): LedgerBookEntity?

    @Query("SELECT * FROM ledger_books WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): LedgerBookEntity?

    @Query(
        """
        UPDATE ledger_books
        SET name = :name,
            created_at_epoch_millis = :createdAtEpochMillis
        WHERE id = :id
        """
    )
    suspend fun updateSynced(
        id: String,
        name: String,
        createdAtEpochMillis: Long
    ): Int

    @Query("SELECT * FROM ledger_books ORDER BY created_at_epoch_millis ASC, id ASC")
    suspend fun getAll(): List<LedgerBookEntity>

    @Query("SELECT * FROM ledger_books ORDER BY created_at_epoch_millis ASC, id ASC")
    fun observeAll(): Flow<List<LedgerBookEntity>>

    @Query(
        """
        SELECT
            ledger_books.id AS id,
            ledger_books.name AS name,
            (
                SELECT COUNT(*) FROM ledger_entries
                WHERE ledger_entries.ledger_book_id = ledger_books.id
                    AND ledger_entries.deleted_at_epoch_millis IS NULL
            ) AS activeEntryCount,
            (
                SELECT COUNT(*) FROM ledger_entries
                WHERE ledger_entries.ledger_book_id = ledger_books.id
                    AND ledger_entries.deleted_at_epoch_millis IS NOT NULL
            ) AS deletedEntryCount
        FROM ledger_books
        ORDER BY ledger_books.created_at_epoch_millis ASC, ledger_books.id ASC
        """
    )
    fun observeEntryCounts(): Flow<List<LedgerBookEntryCounts>>

    @Query(
        """
        SELECT * FROM ledger_books
        ORDER BY
            CASE WHEN id = (
                SELECT active_ledger_id
                FROM local_settings
                WHERE id = :settingsId
            ) THEN 0 ELSE 1 END,
            created_at_epoch_millis ASC,
            id ASC
        LIMIT 1
        """
    )
    fun observeActive(
        settingsId: String = LOCAL_SETTINGS_ID
    ): Flow<LedgerBookEntity?>

    @Query("SELECT COUNT(*) FROM ledger_books")
    suspend fun count(): Int

    @Query("DELETE FROM ledger_books WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM ledger_books")
    suspend fun deleteAll()
}

@Dao
interface FundingAccountDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(account: FundingAccountEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(accounts: List<FundingAccountEntity>)

    @Query("SELECT * FROM funding_accounts WHERE source = :sourceScope AND label = :label LIMIT 1")
    suspend fun findByScopeAndLabel(
        sourceScope: FundingAccountSourceScope,
        label: String
    ): FundingAccountEntity?

    @Query("SELECT * FROM funding_accounts WHERE id = :id")
    suspend fun getById(id: Long): FundingAccountEntity?

    @Query("SELECT * FROM funding_accounts WHERE sync_id = :syncId LIMIT 1")
    suspend fun findBySyncId(syncId: String): FundingAccountEntity?

    @Query("UPDATE funding_accounts SET sync_id = :syncId WHERE id = :id")
    suspend fun setSyncId(id: Long, syncId: String): Int

    @Query(
        """
        UPDATE funding_accounts
        SET source = :sourceScope,
            payment_source = :paymentSource,
            label = :label
        WHERE id = :id
        """
    )
    suspend fun update(
        id: Long,
        sourceScope: FundingAccountSourceScope,
        paymentSource: PaymentSource?,
        label: String
    ): Int

    @Query(
        """
        UPDATE funding_accounts
        SET sync_id = :syncId,
            source = :sourceScope,
            payment_source = :paymentSource,
            label = :label,
            created_at_epoch_millis = :createdAtEpochMillis
        WHERE id = :id
        """
    )
    suspend fun updateSynced(
        id: Long,
        syncId: String,
        sourceScope: FundingAccountSourceScope,
        paymentSource: PaymentSource?,
        label: String,
        createdAtEpochMillis: Long
    ): Int

    @Query("DELETE FROM funding_accounts WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT * FROM funding_accounts ORDER BY source ASC, label ASC")
    suspend fun getAllFundingAccounts(): List<FundingAccountEntity>

    @Query("SELECT * FROM funding_accounts ORDER BY source ASC, label ASC")
    fun observeFundingAccounts(): Flow<List<FundingAccountEntity>>

    @Query("DELETE FROM funding_accounts")
    suspend fun deleteAll()
}

@Dao
interface PendingEntryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: PendingEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<PendingEntryEntity>)

    @Query("SELECT * FROM pending_entries WHERE id = :id")
    suspend fun getById(id: String): PendingEntryEntity?

    @Query(
        """
        SELECT * FROM pending_entries
        ORDER BY
            CASE confidence
                WHEN 'DUPLICATE_SUSPECT' THEN 0
                WHEN 'NEEDS_REVIEW' THEN 1
                ELSE 2
            END,
            captured_at_epoch_millis DESC
        """
    )
    fun observePendingEntries(): Flow<List<PendingEntryEntity>>

    @Query("SELECT * FROM pending_entries ORDER BY captured_at_epoch_millis DESC")
    suspend fun listPendingEntries(): List<PendingEntryEntity>

    @Query("SELECT COUNT(*) FROM pending_entries WHERE funding_account_id = :fundingAccountId")
    suspend fun countByFundingAccountId(fundingAccountId: Long): Int

    @Query("DELETE FROM pending_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM pending_entries")
    suspend fun deleteAll()
}

@Dao
interface LedgerEntryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LedgerEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<LedgerEntryEntity>)

    @Query("SELECT * FROM ledger_entries WHERE id = :id")
    suspend fun getById(id: String): LedgerEntryEntity?

    @Query(
        """
        SELECT * FROM ledger_entries
        WHERE deleted_at_epoch_millis IS NULL
          AND transaction_time_epoch_millis BETWEEN :startEpochMillis AND :endEpochMillis
        ORDER BY transaction_time_epoch_millis DESC
        """
    )
    fun observeLedgerEntriesBetween(
        startEpochMillis: Long,
        endEpochMillis: Long
    ): Flow<List<LedgerEntryEntity>>

    @Query(
        "SELECT * FROM ledger_entries WHERE deleted_at_epoch_millis IS NULL " +
            "ORDER BY transaction_time_epoch_millis DESC"
    )
    suspend fun listLedgerEntries(): List<LedgerEntryEntity>

    @Query("SELECT * FROM ledger_entries ORDER BY transaction_time_epoch_millis DESC")
    suspend fun listAllLedgerEntries(): List<LedgerEntryEntity>

    @Query(
        "SELECT * FROM ledger_entries WHERE deleted_at_epoch_millis IS NULL " +
            "AND transaction_time_epoch_millis BETWEEN :startEpochMillis AND :endEpochMillis " +
            "ORDER BY transaction_time_epoch_millis DESC"
    )
    suspend fun listLedgerEntriesBetween(
        startEpochMillis: Long,
        endEpochMillis: Long
    ): List<LedgerEntryEntity>

    @Query("SELECT * FROM ledger_entries ORDER BY transaction_time_epoch_millis DESC")
    fun observeAllLedgerEntries(): Flow<List<LedgerEntryEntity>>

    @Query(
        "SELECT * FROM ledger_entries WHERE deleted_at_epoch_millis IS NULL " +
            "ORDER BY transaction_time_epoch_millis DESC"
    )
    fun observeLedgerEntries(): Flow<List<LedgerEntryEntity>>

    @Query(
        "SELECT * FROM ledger_entries WHERE deleted_at_epoch_millis IS NOT NULL " +
            "ORDER BY deleted_at_epoch_millis DESC"
    )
    fun observeDeletedLedgerEntries(): Flow<List<LedgerEntryEntity>>

    @Query(
        "SELECT * FROM ledger_entries WHERE ledger_book_id = :ledgerBookId " +
            "AND deleted_at_epoch_millis IS NULL ORDER BY transaction_time_epoch_millis DESC"
    )
    fun observeLedgerEntriesForBook(ledgerBookId: String): Flow<List<LedgerEntryEntity>>

    @Query(
        "SELECT * FROM ledger_entries WHERE ledger_book_id = :ledgerBookId " +
            "AND deleted_at_epoch_millis IS NOT NULL ORDER BY deleted_at_epoch_millis DESC"
    )
    fun observeDeletedLedgerEntriesForBook(ledgerBookId: String): Flow<List<LedgerEntryEntity>>

    @Query(
        "SELECT COUNT(*) FROM ledger_entries WHERE ledger_book_id = :ledgerBookId " +
            "AND deleted_at_epoch_millis IS NULL"
    )
    suspend fun countActiveByLedgerBookId(ledgerBookId: String): Int

    @Query(
        "SELECT COUNT(*) FROM ledger_entries WHERE ledger_book_id = :ledgerBookId " +
            "AND deleted_at_epoch_millis IS NOT NULL"
    )
    suspend fun countDeletedByLedgerBookId(ledgerBookId: String): Int

    @Query(
        "SELECT COUNT(*) FROM ledger_entries WHERE funding_account_id = :fundingAccountId " +
            "AND deleted_at_epoch_millis IS NULL"
    )
    suspend fun countActiveByFundingAccountId(fundingAccountId: Long): Int

    @Query(
        "SELECT COUNT(*) FROM ledger_entries WHERE funding_account_id = :fundingAccountId " +
            "AND deleted_at_epoch_millis IS NOT NULL"
    )
    suspend fun countDeletedByFundingAccountId(fundingAccountId: Long): Int

    @Query("UPDATE ledger_entries SET deleted_at_epoch_millis = :deletedAt WHERE id = :id AND deleted_at_epoch_millis IS NULL")
    suspend fun moveToDeleted(id: String, deletedAt: Long): Int

    @Query("UPDATE ledger_entries SET deleted_at_epoch_millis = NULL WHERE id = :id AND deleted_at_epoch_millis IS NOT NULL")
    suspend fun restoreDeleted(id: String): Int

    @Query("DELETE FROM ledger_entries WHERE id = :id AND deleted_at_epoch_millis IS NOT NULL")
    suspend fun permanentlyDelete(id: String): Int

    @Query("DELETE FROM ledger_entries WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM ledger_entries WHERE deleted_at_epoch_millis IS NOT NULL AND deleted_at_epoch_millis <= :cutoff")
    suspend fun purgeDeletedBefore(cutoff: Long): Int

    @Query("SELECT * FROM ledger_entries WHERE deleted_at_epoch_millis IS NOT NULL AND deleted_at_epoch_millis <= :cutoff")
    suspend fun listDeletedBefore(cutoff: Long): List<LedgerEntryEntity>

    @Query("SELECT id FROM ledger_entries WHERE origin_pending_entry_id = :pendingEntryId LIMIT 1")
    suspend fun findIdByOriginPendingEntryId(pendingEntryId: String): String?

    @Query("DELETE FROM ledger_entries WHERE origin_pending_entry_id = :pendingEntryId")
    suspend fun deleteByOriginPendingEntryId(pendingEntryId: String)

    @Query("DELETE FROM ledger_entries")
    suspend fun deleteAll()
}

@Dao
interface IgnoredEntryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: IgnoredEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<IgnoredEntryEntity>)

    @Query("SELECT * FROM ignored_entries WHERE id = :id")
    suspend fun getById(id: String): IgnoredEntryEntity?

    @Query(
        """
        SELECT * FROM ignored_entries
        WHERE expires_at_epoch_millis > :nowEpochMillis
        ORDER BY ignored_at_epoch_millis DESC
        """
    )
    suspend fun listRecoverable(nowEpochMillis: Long): List<IgnoredEntryEntity>

    @Query(
        """
        SELECT * FROM ignored_entries
        WHERE expires_at_epoch_millis > :nowEpochMillis
        ORDER BY ignored_at_epoch_millis DESC
        """
    )
    fun observeRecoverable(nowEpochMillis: Long): Flow<List<IgnoredEntryEntity>>

    @Query("SELECT * FROM ignored_entries ORDER BY ignored_at_epoch_millis DESC")
    suspend fun listAll(): List<IgnoredEntryEntity>

    @Query("SELECT COUNT(*) FROM ignored_entries WHERE funding_account_id = :fundingAccountId")
    suspend fun countByFundingAccountId(fundingAccountId: Long): Int

    @Query("DELETE FROM ignored_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM ignored_entries")
    suspend fun deleteAll()
}

@Dao
interface CategorizationRuleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(rules: List<CategorizationRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rules: List<CategorizationRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: CategorizationRuleEntity)

    @Query("SELECT * FROM categorization_rules WHERE id = :id")
    suspend fun getById(id: String): CategorizationRuleEntity?

    @Query("DELETE FROM categorization_rules WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query(
        """
        SELECT * FROM categorization_rules
        ORDER BY priority DESC, updated_at_epoch_millis DESC
        """
    )
    fun observeRules(): Flow<List<CategorizationRuleEntity>>

    @Query(
        """
        SELECT * FROM categorization_rules
        ORDER BY priority DESC, updated_at_epoch_millis DESC
        """
    )
    suspend fun listRules(): List<CategorizationRuleEntity>

    @Query("DELETE FROM categorization_rules")
    suspend fun deleteAll()
}

@Dao
interface LocalSettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: LocalSettingsEntity)

    @Query("SELECT * FROM local_settings WHERE id = :id")
    suspend fun getById(id: String = LOCAL_SETTINGS_ID): LocalSettingsEntity?

    @Query("SELECT * FROM local_settings WHERE id = :id")
    fun observeById(id: String = LOCAL_SETTINGS_ID): Flow<LocalSettingsEntity?>

    @Query("DELETE FROM local_settings")
    suspend fun deleteAll()
}

@Dao
interface DefaultFundingAccountCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: DefaultFundingAccountCacheEntity)
    @Query("SELECT * FROM default_funding_account_cache WHERE accountKey = :accountKey")
    suspend fun get(accountKey: String): DefaultFundingAccountCacheEntity?
    @Query("DELETE FROM default_funding_account_cache")
    suspend fun deleteAll()
}
