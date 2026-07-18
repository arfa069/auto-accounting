package com.autoaccounting.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountContractsTest {
    @Test
    fun sessionResponseRoundTripsPendingDeletion() {
        val expected = AccountSessionResponseContract(
            phone = "13800138000",
            token = "session-token",
            deletionStatus = AccountDeletionStatusContract(
                pending = true,
                requestedAtMillis = 1_000,
                finalDeletionAtMillis = 604_801_000
            )
        )

        val decoded = AccountApiJsonContracts.parseSessionResponse(
            AccountApiJsonContracts.encodeSessionResponse(expected)
        )

        assertEquals(expected, decoded)
    }

    @Test
    fun nonPendingDeletionUsesNullTimestamps() {
        val decoded = AccountApiJsonContracts.parseDeletionStatusResponse(
            AccountApiJsonContracts.encodeDeletionStatusResponse(AccountDeletionStatusContract())
        )

        assertFalse(decoded.pending)
        assertNull(decoded.requestedAtMillis)
        assertNull(decoded.finalDeletionAtMillis)
    }

    @Test
    fun errorResponseRoundTripsStableCodeAndMessage() {
        val expected = AccountErrorResponseContract(
            error = AccountErrorCodeContract.TOKEN_INVALID.name,
            message = "登录状态已失效，请重新登录"
        )

        val decoded = AccountApiJsonContracts.parseErrorResponse(
            AccountApiJsonContracts.encodeErrorResponse(expected)
        )

        assertEquals(expected, decoded)
    }

    @Test
    fun malformedPendingDeletionIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AccountApiJsonContracts.parseDeletionStatusResponse(
                """{"ok":true,"deletionPending":true,"requestedAtMillis":1000,"finalDeletionAtMillis":null}"""
            )
        }

        assertTrue(error.message.orEmpty().contains("timestamps"))
    }
}
