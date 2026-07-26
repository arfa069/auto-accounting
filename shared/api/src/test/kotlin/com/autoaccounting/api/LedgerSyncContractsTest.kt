package com.autoaccounting.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class LedgerSyncContractsTest {
    @Test
    fun pushRequestRoundTripsAllSupportedPayloads() {
        val payloads = listOf(
            LedgerSyncPayloadContract.Category("category-1", "餐饮", "EXPENSE", 1, false, 1),
            LedgerSyncPayloadContract.FundingAccount("funding-1", "MANUAL", null, "现金", 2),
            LedgerSyncPayloadContract.LedgerBook("book-1", "日常", 3),
            LedgerSyncPayloadContract.LedgerEntry(
                id = "entry-1",
                ledgerBookId = "book-1",
                paymentSource = "WECHAT",
                originalCaptureSource = "WECHAT",
                entryOrigin = "MANUAL",
                flowDirection = "OUTFLOW",
                transactionKind = "EXPENSE",
                amountMinor = 1234,
                currency = "CNY",
                merchantTitle = "午餐",
                transactionTimeMillis = 4,
                categoryId = "category-1",
                fundingAccountSyncId = "funding-1",
                note = "工作餐",
                confirmedAtMillis = 5,
                updatedAtMillis = 6,
                deletedAtMillis = null
            ),
            LedgerSyncPayloadContract.CategorizationRule(
                "rule-1", "咖啡", "", "", "EXPENSE", "餐饮", 10, true, 7
            )
        )
        val request = LedgerSyncPushRequestContract(
            deviceId = "device-1",
            mutations = payloads.mapIndexed { index, payload ->
                LedgerSyncMutationContract(
                    mutationId = "mutation-$index",
                    entityType = LedgerSyncEntityTypeContract.entries[index],
                    entityId = "entity-$index",
                    baseVersion = 0,
                    deleted = false,
                    payload = payload
                )
            } + LedgerSyncMutationContract(
                mutationId = "delete-1",
                entityType = LedgerSyncEntityTypeContract.LEDGER_ENTRY,
                entityId = "deleted-entry",
                baseVersion = 2,
                deleted = true,
                payload = null
            )
        )

        val encoded = LedgerSyncJsonContracts.encodePushRequest(request)

        assertEquals(request, LedgerSyncJsonContracts.parsePushRequest(encoded))
        assertFalse(encoded.contains("evidenceSummary"))
        assertFalse(encoded.contains("parsedFieldsText"))
        assertFalse(encoded.contains("originPendingEntryId"))
    }

    @Test
    fun pullAndConflictResponsesRoundTrip() {
        val payload = LedgerSyncPayloadContract.LedgerBook("book-1", "日常", 3)
        val record = LedgerSyncRecordContract(
            entityType = LedgerSyncEntityTypeContract.LEDGER_BOOK,
            entityId = "book-1",
            version = 2,
            revision = 8,
            deleted = false,
            payload = payload
        )
        val response = LedgerSyncPullResponseContract(
            records = listOf(record),
            conflicts = listOf(
                LedgerSyncConflictContract(
                    conflictId = "conflict-1",
                    entityType = LedgerSyncEntityTypeContract.LEDGER_BOOK,
                    entityId = "book-1",
                    canonicalVersion = 2,
                    canonicalDeleted = false,
                    canonicalPayload = payload,
                    candidateDeleted = false,
                    candidatePayload = payload.copy(name = "旅行"),
                    createdAtMillis = 9
                )
            ),
            nextCursor = 8,
            hasMore = false
        )

        assertEquals(
            response,
            LedgerSyncJsonContracts.parsePullResponse(
                LedgerSyncJsonContracts.encodePullResponse(response)
            )
        )
        val resolve = LedgerSyncResolveConflictRequestContract(
            "conflict-1",
            expectedCanonicalVersion = 2,
            choice = LedgerSyncConflictChoiceContract.CANDIDATE
        )
        assertEquals(resolve, LedgerSyncJsonContracts.parseResolveRequest(LedgerSyncJsonContracts.encodeResolveRequest(resolve)))
    }

    @Test
    fun pushResultRoundTripsCanonicalBusinessKeyRemap() {
        val response = LedgerSyncPushResponseContract(
            results = listOf(
                LedgerSyncMutationResultContract(
                    mutationId = "mutation-1",
                    accepted = true,
                    version = 3,
                    revision = 8,
                    conflictId = null,
                    canonicalEntityId = "cloud-funding-id"
                )
            ),
            currentCursor = 8
        )

        assertEquals(
            response,
            LedgerSyncJsonContracts.parsePushResponse(LedgerSyncJsonContracts.encodePushResponse(response))
        )
    }

    @Test
    fun rejectsMismatchedPayloadAndInvalidDeleteShape() {
        val payload = LedgerSyncPayloadContract.LedgerBook("book-1", "日常", 3)
        assertThrows(IllegalArgumentException::class.java) {
            LedgerSyncJsonContracts.encodePayload(LedgerSyncEntityTypeContract.CATEGORY, payload)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LedgerSyncJsonContracts.parsePushRequest(
                """{"deviceId":"d","mutations":[{"mutationId":"m","entityType":"LEDGER_BOOK","entityId":"b","baseVersion":0,"deleted":true,"payload":{"id":"b","name":"x","createdAtMillis":1}}]}"""
            )
        }
    }
}
