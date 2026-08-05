package com.autoaccounting.feature.sync

import com.autoaccounting.api.LedgerSyncEntityTypeContract
import com.autoaccounting.api.LedgerSyncRecordContract

internal val remoteApplyComparator = Comparator<LedgerSyncRecordContract> { left, right ->
    fun order(record: LedgerSyncRecordContract): Int = if (record.deleted) {
        when (record.entityType) {
            LedgerSyncEntityTypeContract.LEDGER_ENTRY -> 0
            LedgerSyncEntityTypeContract.CATEGORIZATION_RULE -> 1
            LedgerSyncEntityTypeContract.LEDGER_BOOK -> 2
            LedgerSyncEntityTypeContract.FUNDING_ACCOUNT -> 3
            LedgerSyncEntityTypeContract.CATEGORY -> 4
        }
    } else {
        when (record.entityType) {
            LedgerSyncEntityTypeContract.CATEGORY -> 0
            LedgerSyncEntityTypeContract.FUNDING_ACCOUNT -> 1
            LedgerSyncEntityTypeContract.LEDGER_BOOK -> 2
            LedgerSyncEntityTypeContract.LEDGER_ENTRY -> 3
            LedgerSyncEntityTypeContract.CATEGORIZATION_RULE -> 4
        }
    }
    compareValuesBy(left, right, { order(it) }, { it.revision })
}

internal fun List<LedgerSyncRecordContract>.latestByEntity(): List<LedgerSyncRecordContract> =
    groupBy { it.entityType to it.entityId }
        .values
        .map { versions -> versions.maxBy { it.revision } }
