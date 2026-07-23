package com.autoaccounting.feature.account

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSessionCommitTest {
    @Test
    fun persistenceFailureClearsLocalSessionAndAttemptsToRevokeReplacementToken() = runBlocking {
        val repository = TestAccountRepository()
        var clearCalls = 0

        val committed = persistAccountSessionOrRevoke(
            credentials = AccountCredentials(null, "replacement-token", wechatLinked = true),
            accountRepository = repository,
            persistSession = { false },
            clearPersistedSession = {
                clearCalls += 1
                true
            }
        )

        assertFalse(committed)
        assertEquals(1, clearCalls)
        assertEquals(1, repository.signOutCalls)
    }

    @Test
    fun successfulPersistenceDoesNotClearOrRevoke() = runBlocking {
        val repository = TestAccountRepository()
        var clearCalls = 0

        val committed = persistAccountSessionOrRevoke(
            credentials = AccountCredentials("13800138000", "replacement-token"),
            accountRepository = repository,
            persistSession = { true },
            clearPersistedSession = {
                clearCalls += 1
                true
            }
        )

        assertTrue(committed)
        assertEquals(0, clearCalls)
        assertEquals(0, repository.signOutCalls)
    }
}
