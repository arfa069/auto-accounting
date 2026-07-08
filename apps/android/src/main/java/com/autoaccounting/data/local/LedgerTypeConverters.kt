package com.autoaccounting.data.local

import androidx.room.TypeConverter

class LedgerTypeConverters {
    @TypeConverter
    fun paymentSourceToString(value: PaymentSource?): String? = value?.name

    @TypeConverter
    fun stringToPaymentSource(value: String?): PaymentSource? = value?.let(PaymentSource::valueOf)

    @TypeConverter
    fun transactionKindToString(value: TransactionKind?): String? = value?.name

    @TypeConverter
    fun stringToTransactionKind(value: String?): TransactionKind? = value?.let(TransactionKind::valueOf)

    @TypeConverter
    fun captureReasonToString(value: CaptureReason?): String? = value?.name

    @TypeConverter
    fun stringToCaptureReason(value: String?): CaptureReason? = value?.let(CaptureReason::valueOf)

    @TypeConverter
    fun confidenceStateToString(value: ConfidenceState?): String? = value?.name

    @TypeConverter
    fun stringToConfidenceState(value: String?): ConfidenceState? = value?.let(ConfidenceState::valueOf)

    @TypeConverter
    fun ignoreReasonToString(value: IgnoreReason?): String? = value?.name

    @TypeConverter
    fun stringToIgnoreReason(value: String?): IgnoreReason? = value?.let(IgnoreReason::valueOf)
}
