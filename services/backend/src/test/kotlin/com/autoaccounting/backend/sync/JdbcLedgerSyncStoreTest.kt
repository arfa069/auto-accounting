package com.autoaccounting.backend.sync

import com.autoaccounting.api.LedgerSyncEntityTypeContract
import com.autoaccounting.api.LedgerSyncMutationContract
import com.autoaccounting.api.LedgerSyncPayloadContract
import com.autoaccounting.backend.account.JdbcAccountStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JdbcLedgerSyncStoreTest {
    @Test
    fun persistsIdempotentMutationsAndIsolatesAccountsAcrossStoreRestart() {
        val databaseUrl = h2DatabaseUrl()
        val accountStore = JdbcAccountStore(databaseUrl)
        val accountA = createAccount(accountStore, "13800138000")
        val accountB = createAccount(accountStore, "13900139000")
        val firstStore = JdbcLedgerSyncStore(databaseUrl)
        val profileA = firstStore.getOrCreateProfile(accountA, 100)
        val profileB = firstStore.getOrCreateProfile(accountB, 100)
        val mutation = bookMutation("mutation-a", "book-a", "日常账本")

        val first = firstStore.push(accountA, "device-a", listOf(mutation), 200).single()
        val retried = firstStore.push(accountA, "device-a", listOf(mutation), 300).single()
        val restarted = JdbcLedgerSyncStore(databaseUrl)

        assertEquals(first, retried)
        assertNotEquals(profileA.profileKey, profileB.profileKey)
        assertEquals(profileA.profileKey, restarted.getOrCreateProfile(accountA, 400).profileKey)
        assertEquals("日常账本", (restarted.snapshot(accountA, 0, 10).single().payload as LedgerSyncPayloadContract.LedgerBook).name)
        assertEquals(0, restarted.recordCount(accountB))
    }

    @Test
    fun failedBatchRollsBackEveryMutation() {
        val databaseUrl = h2DatabaseUrl()
        val accountStore = JdbcAccountStore(databaseUrl)
        val accountId = createAccount(accountStore, "13800138000")
        val store = JdbcLedgerSyncStore(databaseUrl)
        val invalid = bookMutation("mutation-b", "x".repeat(200), "超长标识")

        val failure = runCatching {
            store.push(
                accountId,
                "device-a",
                listOf(bookMutation("mutation-a", "book-a", "日常账本"), invalid),
                200
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(0, store.recordCount(accountId))
        assertTrue(store.snapshot(accountId, 0, 10).isEmpty())
    }

    @Test
    fun deletingProfileCascadesAllSyncState() {
        val databaseUrl = h2DatabaseUrl()
        val accountStore = JdbcAccountStore(databaseUrl)
        val accountId = createAccount(accountStore, "13800138000")
        val store = JdbcLedgerSyncStore(databaseUrl)
        store.getOrCreateProfile(accountId, 100)
        store.push(accountId, "device-a", listOf(bookMutation("mutation-a", "book-a", "日常账本")), 200)

        store.deleteForAccount(accountId)

        assertEquals(0, store.recordCount(accountId))
        assertEquals(0, store.currentCursor(accountId))
    }

    @Test
    fun canonicalBusinessIdSurvivesJdbcIdempotentRetry() {
        val databaseUrl = h2DatabaseUrl()
        val accountStore = JdbcAccountStore(databaseUrl)
        val accountId = createAccount(accountStore, "13800138000")
        val store = JdbcLedgerSyncStore(databaseUrl)
        store.getOrCreateProfile(accountId, 100)
        store.push(accountId, "device-a", listOf(categoryMutation("m-1", "cloud-food")), 200)
        val mutation = categoryMutation("m-2", "local-food")

        val first = store.push(accountId, "device-b", listOf(mutation), 300).single()
        val retried = JdbcLedgerSyncStore(databaseUrl).push(accountId, "device-b", listOf(mutation), 400).single()

        assertTrue(first.accepted)
        assertEquals("cloud-food", first.canonicalEntityId)
        assertEquals(first, retried)
        assertEquals(1, store.recordCount(accountId))
    }

    @Test
    fun deletedBusinessCanonicalCreatesConflictInsteadOfBeingRecreatedUnderAnotherId() {
        val databaseUrl = h2DatabaseUrl()
        val accountStore = JdbcAccountStore(databaseUrl)
        val accountId = createAccount(accountStore, "13800138000")
        val store = JdbcLedgerSyncStore(databaseUrl)
        store.getOrCreateProfile(accountId, 100)
        store.push(accountId, "device-a", listOf(categoryMutation("m-1", "cloud-food")), 200)
        store.push(
            accountId,
            "device-a",
            listOf(
                LedgerSyncMutationContract(
                    mutationId = "m-2",
                    entityType = LedgerSyncEntityTypeContract.CATEGORY,
                    entityId = "cloud-food",
                    baseVersion = 1,
                    deleted = true,
                    payload = null
                )
            ),
            300
        )

        val result = store.push(
            accountId,
            "offline-device",
            listOf(categoryMutation("m-3", "stale-local-food")),
            400
        ).single()

        assertTrue(!result.accepted)
        assertEquals("cloud-food", result.canonicalEntityId)
        assertNotNull(result.conflictId)
        assertEquals(1, store.recordCount(accountId))
        assertTrue(store.snapshot(accountId, 0, 10).single().deleted)
    }

    private fun createAccount(store: JdbcAccountStore, phone: String): Long = requireNotNull(
        store.createAccountWithIdentifier(
            primaryIdentifierType = "PHONE",
            rawValue = phone,
            normalizedValue = phone,
            passwordSalt = "salt",
            passwordHash = "hash",
            verified = true,
            now = 1
        )
    ).accountId

    private fun bookMutation(mutationId: String, entityId: String, name: String) = LedgerSyncMutationContract(
        mutationId = mutationId,
        entityType = LedgerSyncEntityTypeContract.LEDGER_BOOK,
        entityId = entityId,
        baseVersion = 0,
        deleted = false,
        payload = LedgerSyncPayloadContract.LedgerBook(entityId, name, 100)
    )

    private fun categoryMutation(mutationId: String, entityId: String) = LedgerSyncMutationContract(
        mutationId = mutationId,
        entityType = LedgerSyncEntityTypeContract.CATEGORY,
        entityId = entityId,
        baseVersion = 0,
        deleted = false,
        payload = LedgerSyncPayloadContract.Category(entityId, "餐饮", "EXPENSE", 1, true, 100)
    )

    private fun h2DatabaseUrl(): String =
        "jdbc:h2:mem:ledger_sync_${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
}
