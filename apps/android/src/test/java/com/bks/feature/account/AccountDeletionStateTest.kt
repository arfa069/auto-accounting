package com.bks.feature.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeletionStateTest {
    @Test
    fun serverPendingStatePausesCloudWritesAndKeepsServerDeadline() {
        val state = AccountDeletionUiState(
            requestedAtEpochMillis = 1_000,
            finalDeletionAtEpochMillis = 604_801_000
        )

        assertTrue(state.isPending)
        assertFalse(state.cloudWritesAllowed)
        assertEquals(1_000L, state.requestedAtEpochMillis)
        assertEquals(604_801_000L, state.finalDeletionAtEpochMillis)
    }

    @Test
    fun serverNonPendingStateAllowsCloudWrites() {
        val state = AccountDeletionUiState()

        assertFalse(state.isPending)
        assertTrue(state.cloudWritesAllowed)
    }
}
