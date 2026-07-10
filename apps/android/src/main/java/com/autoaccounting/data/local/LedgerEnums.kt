package com.autoaccounting.data.local

enum class PaymentSource {
    WECHAT,
    ALIPAY
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
