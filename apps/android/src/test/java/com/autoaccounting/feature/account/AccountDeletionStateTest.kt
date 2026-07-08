package com.autoaccounting.feature.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeletionStateTest {
    @Test
    fun requestDeletionStartsSevenDayCoolingOffAndPausesCloudWrites() {
        val state = reduceAccountDeletionState(
            AccountDeletionUiState(),
            AccountDeletionUiAction.RequestDeletion(nowEpochMillis = 1_000)
        )

        assertTrue(state.isPending)
        assertFalse(state.cloudWritesAllowed)
        assertEquals(1_000L, state.requestedAtEpochMillis)
        assertEquals(604_801_000L, state.finalDeletionAtEpochMillis)
    }

    @Test
    fun cancelDeletionRestoresCloudWrites() {
        val pending = AccountDeletionUiState(
            requestedAtEpochMillis = 1_000,
            finalDeletionAtEpochMillis = 604_801_000
        )

        val state = reduceAccountDeletionState(pending, AccountDeletionUiAction.CancelDeletion)

        assertFalse(state.isPending)
        assertTrue(state.cloudWritesAllowed)
    }
}
