package com.autoaccounting.backend.sync

import com.autoaccounting.api.LedgerSyncConflictChoiceContract
import com.autoaccounting.api.LedgerSyncEntityTypeContract
import com.autoaccounting.api.LedgerSyncMutationContract
import com.autoaccounting.api.LedgerSyncPayloadContract
import com.autoaccounting.backend.account.AccountResult
import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.account.InMemoryAccountStore
import com.autoaccounting.backend.account.MutableClock
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerSyncServiceTest {
    @Test
    fun acceptsIdempotentMutationsAndPullsAccountIsolatedChanges() {
        val fixture = fixture()
        val mutation = bookMutation("m-1", "book-1", 0, "日常")

        val first = fixture.sync.push(fixture.accountId, "device-a", listOf(mutation)).success()
        val repeated = fixture.sync.push(fixture.accountId, "device-a", listOf(mutation)).success()

        assertEquals(first, repeated)
        assertEquals(1, fixture.sync.initialize(fixture.accountId).recordCount)
        val pull = fixture.sync.pull(fixture.accountId, "device-b", 0, 100).success()
        assertEquals("日常", (pull.records.single().payload as LedgerSyncPayloadContract.LedgerBook).name)
        assertFalse(pull.hasMore)

        val other = fixture.accountService.registerIdentifier("other_user", null, "Password123!") as AccountResult.Success
        assertEquals(0, fixture.sync.initialize(other.value.accountId).recordCount)
        assertNotEquals(
            fixture.sync.initialize(fixture.accountId).profileKey,
            fixture.sync.initialize(other.value.accountId).profileKey
        )
    }

    @Test
    fun concurrentMutationCreatesConflictAndResolutionRequiresCurrentVersion() {
        val fixture = fixture()
        fixture.sync.push(fixture.accountId, "device-a", listOf(bookMutation("m-1", "book-1", 0, "日常")))
        fixture.sync.push(fixture.accountId, "device-a", listOf(bookMutation("m-2", "book-1", 1, "家庭")))

        val conflicted = fixture.sync.push(
            fixture.accountId,
            "device-b",
            listOf(bookMutation("m-3", "book-1", 1, "旅行"))
        ).success()

        assertFalse(conflicted.results.single().accepted)
        val conflict = fixture.sync.pull(fixture.accountId, "device-b", 0, 100).success().conflicts.single()
        assertEquals("家庭", (conflict.canonicalPayload as LedgerSyncPayloadContract.LedgerBook).name)
        assertEquals("旅行", (conflict.candidatePayload as LedgerSyncPayloadContract.LedgerBook).name)
        assertTrue(
            fixture.sync.resolve(
                fixture.accountId,
                conflict.conflictId,
                expectedCanonicalVersion = 1,
                choice = LedgerSyncConflictChoiceContract.CANDIDATE
            ) is LedgerSyncServiceResult.ConflictStale
        )
        val resolved = fixture.sync.resolve(
            fixture.accountId,
            conflict.conflictId,
            expectedCanonicalVersion = 2,
            choice = LedgerSyncConflictChoiceContract.CANDIDATE
        ).success()
        assertEquals("旅行", (resolved.record.payload as LedgerSyncPayloadContract.LedgerBook).name)
        assertEquals(3, resolved.record.version)
    }

    @Test
    fun deletionCoolingOffPausesPushButAllowsPull() {
        val fixture = fixture()
        fixture.sync.push(fixture.accountId, "device-a", listOf(bookMutation("m-1", "book-1", 0, "日常")))
        fixture.accountService.requestAccountDeletion(fixture.token)

        assertTrue(
            fixture.sync.push(
                fixture.accountId,
                "device-a",
                listOf(bookMutation("m-2", "book-1", 1, "家庭"))
            ) is LedgerSyncServiceResult.DeletionPending
        )
        assertEquals(1, fixture.sync.pull(fixture.accountId, "device-a", 0, 100).success().records.size)
    }

    @Test
    fun cursorAheadOfServerRequiresFullSnapshotReset() {
        val fixture = fixture()
        fixture.sync.push(fixture.accountId, "device-a", listOf(bookMutation("m-1", "book-1", 0, "日常")))

        val result = fixture.sync.pull(fixture.accountId, "device-a", afterCursor = 99, limit = 100)

        assertTrue(result is LedgerSyncServiceResult.CursorExpired)
    }

    @Test
    fun categoryBusinessKeyUsesCloudCanonicalIdAndConflictsOnlyWhenAttributesDiffer() {
        val fixture = fixture()
        fixture.sync.push(
            fixture.accountId,
            "device-a",
            listOf(categoryMutation("m-1", "cloud-food", sortOrder = 1))
        )

        val identical = fixture.sync.push(
            fixture.accountId,
            "device-b",
            listOf(categoryMutation("m-2", "local-food", sortOrder = 1))
        ).success().results.single()
        val different = fixture.sync.push(
            fixture.accountId,
            "device-b",
            listOf(categoryMutation("m-3", "other-food", sortOrder = 9))
        ).success().results.single()

        assertTrue(identical.accepted)
        assertEquals("cloud-food", identical.canonicalEntityId)
        assertFalse(different.accepted)
        assertEquals("cloud-food", different.canonicalEntityId)
        assertEquals(1, fixture.sync.initialize(fixture.accountId).recordCount)
    }

    @Test
    fun sameBatchBusinessDuplicatesUseFirstMutationAsCanonical() {
        val fixture = fixture()

        val result = fixture.sync.push(
            fixture.accountId,
            "device-a",
            listOf(
                categoryMutation("m-1", "first-food", sortOrder = 1),
                categoryMutation("m-2", "second-food", sortOrder = 1)
            )
        ).success()

        assertTrue(result.results.all { it.accepted })
        assertEquals("first-food", result.results.last().canonicalEntityId)
        assertEquals(1, fixture.sync.initialize(fixture.accountId).recordCount)
    }

    @Test
    fun pullRefreshesConflictAgainstLatestCanonicalVersion() {
        val fixture = fixture()
        fixture.sync.push(fixture.accountId, "device-a", listOf(bookMutation("m-1", "book-1", 0, "日常")))
        fixture.sync.push(fixture.accountId, "device-b", listOf(bookMutation("m-2", "book-1", 0, "旅行")))
        fixture.sync.push(fixture.accountId, "device-a", listOf(bookMutation("m-3", "book-1", 1, "家庭")))

        val conflict = fixture.sync.pull(fixture.accountId, "device-b", 0, 100).success().conflicts.single()

        assertEquals(2L, conflict.canonicalVersion)
        assertEquals("家庭", (conflict.canonicalPayload as LedgerSyncPayloadContract.LedgerBook).name)
        assertTrue(
            fixture.sync.resolve(
                fixture.accountId,
                conflict.conflictId,
                conflict.canonicalVersion,
                LedgerSyncConflictChoiceContract.CANDIDATE
            ) is LedgerSyncServiceResult.Success
        )
    }

    @Test
    fun rejectsIdentifiersThatExceedPersistentColumnLimits() {
        val fixture = fixture()

        assertTrue(
            fixture.sync.push(
                fixture.accountId,
                "d".repeat(MAX_SYNC_DEVICE_ID_LENGTH + 1),
                listOf(bookMutation("m-1", "book-1", 0, "日常"))
            ) is LedgerSyncServiceResult.InvalidRequest
        )
        assertTrue(
            fixture.sync.push(
                fixture.accountId,
                "device-a",
                listOf(bookMutation("m".repeat(65), "book-1", 0, "日常"))
            ) is LedgerSyncServiceResult.InvalidRequest
        )
        assertTrue(
            fixture.sync.push(
                fixture.accountId,
                "device-a",
                listOf(bookMutation("m-1", "b".repeat(129), 0, "日常"))
            ) is LedgerSyncServiceResult.InvalidRequest
        )
        assertTrue(
            fixture.sync.resolve(
                fixture.accountId,
                "c".repeat(65),
                0,
                LedgerSyncConflictChoiceContract.CANONICAL
            ) is LedgerSyncServiceResult.InvalidRequest
        )
    }

    private fun fixture(): Fixture {
        val accountClock = MutableClock(1_000)
        val accountService = AccountService(store = InMemoryAccountStore(), clock = accountClock)
        val registered = accountService.registerIdentifier("sync_user", null, "Password123!") as AccountResult.Success
        val sync = LedgerSyncService(
            store = InMemoryLedgerSyncStore(),
            accountService = accountService,
            clock = Clock.fixed(Instant.ofEpochMilli(2_000), ZoneOffset.UTC)
        )
        return Fixture(accountService, sync, registered.value.accountId, registered.value.token)
    }

    private fun bookMutation(
        mutationId: String,
        entityId: String,
        baseVersion: Long,
        name: String
    ) = LedgerSyncMutationContract(
        mutationId = mutationId,
        entityType = LedgerSyncEntityTypeContract.LEDGER_BOOK,
        entityId = entityId,
        baseVersion = baseVersion,
        deleted = false,
        payload = LedgerSyncPayloadContract.LedgerBook(entityId, name, 100)
    )

    private fun categoryMutation(mutationId: String, entityId: String, sortOrder: Int) =
        LedgerSyncMutationContract(
            mutationId = mutationId,
            entityType = LedgerSyncEntityTypeContract.CATEGORY,
            entityId = entityId,
            baseVersion = 0,
            deleted = false,
            payload = LedgerSyncPayloadContract.Category(
                entityId,
                "餐饮",
                "EXPENSE",
                sortOrder,
                isSystem = true,
                createdAtMillis = 100
            )
        )

    @Suppress("UNCHECKED_CAST")
    private fun <T> LedgerSyncServiceResult<T>.success(): T =
        (this as LedgerSyncServiceResult.Success<T>).value

    private data class Fixture(
        val accountService: AccountService,
        val sync: LedgerSyncService,
        val accountId: Long,
        val token: String
    )
}
