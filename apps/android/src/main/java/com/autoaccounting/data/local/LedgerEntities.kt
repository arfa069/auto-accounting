package com.autoaccounting.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["name"], unique = true)
    ]
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: TransactionKind?,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "is_system") val isSystem: Boolean,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long
)

@Entity(
    tableName = "funding_accounts",
    indices = [
        Index(value = ["source", "label"], unique = true),
        Index(value = ["sync_id"], unique = true)
    ]
)
data class FundingAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sync_id") val syncId: String? = null,
    @ColumnInfo(name = "source") val sourceScope: FundingAccountSourceScope,
    @ColumnInfo(name = "payment_source") val paymentSource: PaymentSource?,
    val label: String,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long
)

@Entity(
    tableName = "ledger_books",
    indices = [
        Index(value = ["name"], unique = true)
    ]
)
data class LedgerBookEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long
)

@Entity(
    tableName = "pending_entries",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["suggested_category_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = FundingAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["funding_account_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["source"]),
        Index(value = ["confidence"]),
        Index(value = ["transaction_time_epoch_millis"]),
        Index(value = ["suggested_category_id"]),
        Index(value = ["funding_account_id"])
    ]
)
data class PendingEntryEntity(
    @PrimaryKey val id: String,
    val source: PaymentSource,
    @ColumnInfo(name = "capture_reason") val captureReason: CaptureReason,
    val confidence: ConfidenceState,
    @ColumnInfo(name = "transaction_kind") val transactionKind: TransactionKind,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val currency: String,
    @ColumnInfo(name = "merchant_title") val merchantTitle: String,
    @ColumnInfo(name = "transaction_time_epoch_millis") val transactionTimeEpochMillis: Long,
    @ColumnInfo(name = "captured_at_epoch_millis") val capturedAtEpochMillis: Long,
    @ColumnInfo(name = "suggested_category_id") val suggestedCategoryId: String?,
    @ColumnInfo(name = "funding_account_id") val fundingAccountId: Long?,
    @ColumnInfo(name = "funding_account_label") val fundingAccountLabel: String?,
    val note: String?,
    @ColumnInfo(name = "evidence_summary") val evidenceSummary: String?,
    @ColumnInfo(name = "parsed_fields_text") val parsedFieldsText: String?,
    @ColumnInfo(name = "suggested_category_label") val suggestedCategoryLabel: String? = null
)

@Entity(
    tableName = "ledger_entries",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = FundingAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["funding_account_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = LedgerBookEntity::class,
            parentColumns = ["id"],
            childColumns = ["ledger_book_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(
            value = [
                "ledger_book_id",
                "deleted_at_epoch_millis",
                "transaction_time_epoch_millis"
            ],
            name = "index_ledger_entries_book_deleted_transaction_time"
        ),
        Index(value = ["payment_source"]),
        Index(value = ["original_capture_source"]),
        Index(value = ["entry_origin"]),
        Index(value = ["flow_direction"]),
        Index(value = ["transaction_kind"]),
        Index(value = ["transaction_time_epoch_millis"]),
        Index(value = ["category_id"]),
        Index(value = ["funding_account_id"]),
        Index(value = ["origin_pending_entry_id"]),
        Index(value = ["deleted_at_epoch_millis"])
    ]
)
data class LedgerEntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(
        name = "ledger_book_id",
        defaultValue = "'default-ledger'"
    ) val ledgerBookId: String = DEFAULT_LEDGER_BOOK_ID,
    @ColumnInfo(name = "payment_source") val paymentSource: PaymentSource?,
    @ColumnInfo(name = "original_capture_source") val originalCaptureSource: PaymentSource?,
    @ColumnInfo(name = "entry_origin") val entryOrigin: EntryOrigin,
    @ColumnInfo(name = "origin_pending_entry_id") val originPendingEntryId: String?,
    @ColumnInfo(name = "flow_direction") val flowDirection: FlowDirection,
    @ColumnInfo(name = "transaction_kind") val transactionKind: TransactionKind,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val currency: String,
    @ColumnInfo(name = "merchant_title") val merchantTitle: String,
    @ColumnInfo(name = "transaction_time_epoch_millis") val transactionTimeEpochMillis: Long,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "funding_account_id") val fundingAccountId: Long?,
    val note: String?,
    @ColumnInfo(name = "evidence_summary") val evidenceSummary: String?,
    @ColumnInfo(name = "parsed_fields_text") val parsedFieldsText: String?,
    @ColumnInfo(name = "confirmed_at_epoch_millis") val confirmedAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "deleted_at_epoch_millis") val deletedAtEpochMillis: Long?
)

@Entity(
    tableName = "ignored_entries",
    indices = [
        Index(value = ["source"]),
        Index(value = ["ignored_at_epoch_millis"]),
        Index(value = ["expires_at_epoch_millis"]),
        Index(value = ["original_pending_entry_id"]),
        Index(value = ["funding_account_id"])
    ]
)
data class IgnoredEntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "original_pending_entry_id") val originalPendingEntryId: String,
    val source: PaymentSource,
    @ColumnInfo(name = "capture_reason", defaultValue = "'NOTIFICATION'") val captureReason: CaptureReason,
    @ColumnInfo(defaultValue = "'NEEDS_REVIEW'") val confidence: ConfidenceState,
    @ColumnInfo(name = "transaction_kind") val transactionKind: TransactionKind,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val currency: String,
    @ColumnInfo(name = "merchant_title") val merchantTitle: String,
    @ColumnInfo(name = "transaction_time_epoch_millis") val transactionTimeEpochMillis: Long,
    @ColumnInfo(name = "captured_at_epoch_millis", defaultValue = "0") val capturedAtEpochMillis: Long,
    @ColumnInfo(name = "suggested_category_id") val suggestedCategoryId: String?,
    @ColumnInfo(name = "funding_account_id") val fundingAccountId: Long?,
    @ColumnInfo(name = "funding_account_label") val fundingAccountLabel: String?,
    val note: String?,
    @ColumnInfo(name = "evidence_summary") val evidenceSummary: String?,
    @ColumnInfo(name = "parsed_fields_text") val parsedFieldsText: String?,
    @ColumnInfo(name = "ignored_at_epoch_millis") val ignoredAtEpochMillis: Long,
    @ColumnInfo(name = "expires_at_epoch_millis") val expiresAtEpochMillis: Long,
    val reason: IgnoreReason,
    @ColumnInfo(name = "suggested_category_label") val suggestedCategoryLabel: String? = null
)

@Entity(tableName = "categorization_rules")
data class CategorizationRuleEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "merchant_contains") val merchantContains: String,
    @ColumnInfo(name = "title_contains") val titleContains: String,
    @ColumnInfo(name = "source_label") val sourceLabel: String,
    @ColumnInfo(name = "transaction_kind") val transactionKind: String,
    val category: String,
    val priority: Int,
    val enabled: Boolean,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long
)

@Entity(tableName = "local_settings")
data class LocalSettingsEntity(
    @PrimaryKey val id: String = LOCAL_SETTINGS_ID,
    @ColumnInfo(name = "ai_consent_granted") val aiConsentGranted: Boolean,
    @ColumnInfo(name = "enhanced_context_granted") val enhancedContextGranted: Boolean,
    @ColumnInfo(name = "continuous_bill_sync_completed") val continuousBillSyncCompleted: Boolean,
    @ColumnInfo(name = "continuous_monitoring_enabled") val continuousMonitoringEnabled: Boolean,
    @ColumnInfo(
        name = "active_ledger_id",
        defaultValue = "'default-ledger'"
    ) val activeLedgerId: String = DEFAULT_LEDGER_BOOK_ID,
    @ColumnInfo(name = "default_funding_account_sync_id") val defaultFundingAccountSyncId: String? = null
)

@Entity(tableName = "default_funding_account_cache")
data class DefaultFundingAccountCacheEntity(
    @PrimaryKey val accountKey: String,
    @ColumnInfo(name = "sync_id") val syncId: String?,
    @ColumnInfo(name = "pending_upload") val pendingUpload: Boolean,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long
)

const val LOCAL_SETTINGS_ID = "local"
const val DEFAULT_LEDGER_BOOK_ID = "default-ledger"
const val DEFAULT_LEDGER_BOOK_NAME = "默认账本"
