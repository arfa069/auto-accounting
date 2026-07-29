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

    @Test
    fun refreshedProfileIsAppliedOnlyAfterItIsPersisted() {
        val credentials = AccountCredentials(
            phone = "13800138000",
            token = "existing-token",
            nickname = "本机头像用户",
            avatarUrl = "data:image/jpeg;base64,/9j/"
        )
        var persisted: AccountCredentials? = null
        var applied: AccountCredentials? = null

        val committed = persistRefreshedAccountSession(
            credentials = credentials,
            persistSession = {
                persisted = it
                true
            },
            onSessionVerified = { applied = it }
        )

        assertTrue(committed)
        assertEquals(credentials, persisted)
        assertEquals(credentials, applied)
    }

    @Test
    fun refreshedProfilePersistenceFailureDoesNotApplyVolatileProfile() {
        var applied = false

        val committed = persistRefreshedAccountSession(
            credentials = AccountCredentials("13800138000", "existing-token"),
            persistSession = { false },
            onSessionVerified = { applied = true }
        )

        assertFalse(committed)
        assertFalse(applied)
    }
}
