package com.autoaccounting.data.local

import androidx.room.TypeConverter

private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
    enumValues<T>().firstOrNull { it.name == this }

class LedgerTypeConverters {
    @TypeConverter
    fun paymentSourceToString(value: PaymentSource?): String? = value?.name

    @TypeConverter
    fun stringToPaymentSource(value: String?): PaymentSource? = value?.toEnumOrNull<PaymentSource>()

    @TypeConverter
    fun fundingAccountSourceScopeToString(value: FundingAccountSourceScope?): String? = value?.name

    @TypeConverter
    fun stringToFundingAccountSourceScope(value: String?): FundingAccountSourceScope? =
        value?.toEnumOrNull<FundingAccountSourceScope>()

    @TypeConverter
    fun flowDirectionToString(value: FlowDirection?): String? = value?.name

    @TypeConverter
    fun stringToFlowDirection(value: String?): FlowDirection? = value?.toEnumOrNull<FlowDirection>()

    @TypeConverter
    fun entryOriginToString(value: EntryOrigin?): String? = value?.name

    @TypeConverter
    fun stringToEntryOrigin(value: String?): EntryOrigin? = value?.toEnumOrNull<EntryOrigin>()

    @TypeConverter
    fun transactionKindToString(value: TransactionKind?): String? = value?.name

    @TypeConverter
    fun stringToTransactionKind(value: String?): TransactionKind? = value?.toEnumOrNull<TransactionKind>()

    @TypeConverter
    fun captureReasonToString(value: CaptureReason?): String? = value?.name

    @TypeConverter
    fun stringToCaptureReason(value: String?): CaptureReason? = value?.toEnumOrNull<CaptureReason>()

    @TypeConverter
    fun confidenceStateToString(value: ConfidenceState?): String? = value?.name

    @TypeConverter
    fun stringToConfidenceState(value: String?): ConfidenceState? = value?.toEnumOrNull<ConfidenceState>()

    @TypeConverter
    fun ignoreReasonToString(value: IgnoreReason?): String? = value?.name

    @TypeConverter
    fun stringToIgnoreReason(value: String?): IgnoreReason? = value?.toEnumOrNull<IgnoreReason>()
}
