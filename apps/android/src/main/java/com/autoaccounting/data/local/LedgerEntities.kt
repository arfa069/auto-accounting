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
        Index(value = ["source", "label"], unique = true)
    ]
)
data class FundingAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: PaymentSource,
    val label: String,
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
    val note: String?,
    @ColumnInfo(name = "evidence_summary") val evidenceSummary: String?
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
        )
    ],
    indices = [
        Index(value = ["source"]),
        Index(value = ["transaction_kind"]),
        Index(value = ["transaction_time_epoch_millis"]),
        Index(value = ["category_id"]),
        Index(value = ["funding_account_id"]),
        Index(value = ["origin_pending_entry_id"])
    ]
)
data class LedgerEntryEntity(
    @PrimaryKey val id: String,
    val source: PaymentSource,
    @ColumnInfo(name = "origin_pending_entry_id") val originPendingEntryId: String?,
    @ColumnInfo(name = "transaction_kind") val transactionKind: TransactionKind,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val currency: String,
    @ColumnInfo(name = "merchant_title") val merchantTitle: String,
    @ColumnInfo(name = "transaction_time_epoch_millis") val transactionTimeEpochMillis: Long,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "funding_account_id") val fundingAccountId: Long?,
    val note: String?,
    @ColumnInfo(name = "confirmed_at_epoch_millis") val confirmedAtEpochMillis: Long
)

@Entity(
    tableName = "ignored_entries",
    indices = [
        Index(value = ["source"]),
        Index(value = ["ignored_at_epoch_millis"]),
        Index(value = ["expires_at_epoch_millis"]),
        Index(value = ["original_pending_entry_id"])
    ]
)
data class IgnoredEntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "original_pending_entry_id") val originalPendingEntryId: String,
    val source: PaymentSource,
    @ColumnInfo(name = "transaction_kind") val transactionKind: TransactionKind,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val currency: String,
    @ColumnInfo(name = "merchant_title") val merchantTitle: String,
    @ColumnInfo(name = "transaction_time_epoch_millis") val transactionTimeEpochMillis: Long,
    @ColumnInfo(name = "suggested_category_id") val suggestedCategoryId: String?,
    @ColumnInfo(name = "funding_account_id") val fundingAccountId: Long?,
    @ColumnInfo(name = "ignored_at_epoch_millis") val ignoredAtEpochMillis: Long,
    @ColumnInfo(name = "expires_at_epoch_millis") val expiresAtEpochMillis: Long,
    val reason: IgnoreReason
)
