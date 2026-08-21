package com.bks.data.local

enum class PaymentSource {
    WECHAT,
    ALIPAY,
    OTHER
}

enum class FundingAccountSourceScope {
    WECHAT,
    ALIPAY,
    USER
}

enum class FlowDirection {
    INFLOW,
    OUTFLOW,
    NEUTRAL
}

enum class EntryOrigin {
    MANUAL,
    NOTIFICATION,
    ACCESSIBILITY_AUTO,
    BILL_SYNC,
    DUPLICATE_MERGE,
    LEGACY_CAPTURE
}

enum class TransactionKind {
    EXPENSE,
    INCOME,
    REFUND,
    TRANSFER,
    RED_PACKET,
    REPAYMENT,
    INVESTMENT,
    FEE,
    OTHER
}

enum class CaptureReason {
    NOTIFICATION,
    ACCESSIBILITY_AUTO,
    BILL_SYNC,
    DUPLICATE_MERGE,
    MANUAL_SAMPLE
}

enum class ConfidenceState {
    HIGH,
    NEEDS_REVIEW,
    DUPLICATE_SUSPECT
}

enum class IgnoreReason {
    USER_IGNORED,
    DUPLICATE,
    NOT_A_TRANSACTION
}

fun PaymentSource.toFundingAccountSourceScope(): FundingAccountSourceScope = when (this) {
    PaymentSource.WECHAT -> FundingAccountSourceScope.WECHAT
    PaymentSource.ALIPAY -> FundingAccountSourceScope.ALIPAY
    PaymentSource.OTHER -> FundingAccountSourceScope.USER
}

fun CaptureReason.toEntryOrigin(): EntryOrigin = when (this) {
    CaptureReason.NOTIFICATION -> EntryOrigin.NOTIFICATION
    CaptureReason.ACCESSIBILITY_AUTO -> EntryOrigin.ACCESSIBILITY_AUTO
    CaptureReason.BILL_SYNC -> EntryOrigin.BILL_SYNC
    CaptureReason.DUPLICATE_MERGE -> EntryOrigin.DUPLICATE_MERGE
    CaptureReason.MANUAL_SAMPLE -> EntryOrigin.LEGACY_CAPTURE
}

fun TransactionKind.defaultFlowDirection(): FlowDirection = when (this) {
    TransactionKind.INCOME,
    TransactionKind.REFUND -> FlowDirection.INFLOW
    else -> FlowDirection.OUTFLOW
}
