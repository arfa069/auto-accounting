package com.autoaccounting.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountContractsTest {

    // ── Session response ──

    @Test
    fun sessionResponseRoundTripsPendingDeletion() {
        val expected = AccountSessionResponseContract(
            token = "session-token",
            deletionStatus = AccountDeletionStatusContract(
                pending = true,
                requestedAtMillis = 1_000,
                finalDeletionAtMillis = 604_801_000
            )
        )

        val encoded = AccountApiJsonContracts.encodeSessionResponse(expected)
        val decoded = AccountApiJsonContracts.parseSessionResponse(encoded)

        assertEquals(expected, decoded)
        assertFalse(encoded.contains("\"phone\""))
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

    @Test
    fun removedTopLevelPhoneFieldIsIgnored() {
        val decoded = AccountApiJsonContracts.parseSessionResponse(
            """{"ok":true,"phone":"13800138000","token":"tok","deletionPending":false}"""
        )

        assertNull(decoded.primaryIdentifier)
        assertTrue(decoded.identifiers.isEmpty())
        assertEquals("tok", decoded.token)
    }

    // ── WeChat account with phone=null ──

    @Test
    fun wechatAccountWithNullPhoneRoundTrips() {
        val expected = AccountSessionResponseContract(
            token = "wechat-token",
            wechatLinked = true,
            nickname = "TestUser",
            avatarUrl = "https://wx.example.com/avatar.jpg"
        )

        val decoded = AccountApiJsonContracts.parseSessionResponse(
            AccountApiJsonContracts.encodeSessionResponse(expected)
        )

        assertEquals(expected, decoded)
        assertNull(decoded.primaryIdentifier)
        assertTrue(decoded.wechatLinked)
    }

    // ── Session response full round-trip with all fields ──

    @Test
    fun sessionResponseFullRoundTrip() {
        val expected = AccountSessionResponseContract(
            primaryIdentifier = AccountIdentifierContract(AccountIdentifierTypeContract.PHONE, "13900139000"),
            identifiers = listOf(AccountIdentifierContract(AccountIdentifierTypeContract.PHONE, "13900139000")),
            token = "full-token",
            wechatLinked = true,
            nickname = "完整用户",
            avatarUrl = "https://example.com/full.jpg",
            deletionStatus = AccountDeletionStatusContract(
                pending = true,
                requestedAtMillis = 2_000,
                finalDeletionAtMillis = 604_802_000
            )
        )

        val decoded = AccountApiJsonContracts.parseSessionResponse(
            AccountApiJsonContracts.encodeSessionResponse(expected)
        )

        assertEquals(expected, decoded)
    }

    // ── WeChat exchange response: SIGNED_IN ──

    @Test
    fun wechatExchangeSignedInRoundTrips() {
        val session = AccountSessionResponseContract(
            primaryIdentifier = AccountIdentifierContract(AccountIdentifierTypeContract.PHONE, "13800138000"),
            identifiers = listOf(AccountIdentifierContract(AccountIdentifierTypeContract.PHONE, "13800138000")),
            token = "session-tok",
            wechatLinked = true,
            nickname = "WxUser",
            avatarUrl = "https://wx.example.com/a.jpg"
        )
        val expected = WechatExchangeResponseContract(
            result = WechatAuthResultContract.SignedIn(session)
        )

        val decoded = AccountApiJsonContracts.parseWechatExchangeResponse(
            AccountApiJsonContracts.encodeWechatExchangeResponse(expected)
        )

        assertEquals(expected, decoded)
    }

    @Test
    fun wechatExchangeSignedInWithNullPhoneRoundTrips() {
        val session = AccountSessionResponseContract(
            token = "wechat-tok",
            wechatLinked = true,
            nickname = null,
            avatarUrl = null
        )
        val expected = WechatExchangeResponseContract(
            result = WechatAuthResultContract.SignedIn(session)
        )

        val decoded = AccountApiJsonContracts.parseWechatExchangeResponse(
            AccountApiJsonContracts.encodeWechatExchangeResponse(expected)
        )

        assertEquals(expected, decoded)
    }

    // ── WeChat exchange response: REGISTRATION_REQUIRED ──

    @Test
    fun wechatExchangeRegistrationRequiredRoundTrips() {
        val expected = WechatExchangeResponseContract(
            result = WechatAuthResultContract.RegistrationRequired(
                wechatTicket = "wx-ticket-abc",
                nickname = "新用户",
                avatarUrl = "https://wx.example.com/new.jpg",
                ticketExpiresAtMillis = System.currentTimeMillis() + TICKET_VALIDITY_MILLIS
            )
        )

        val decoded = AccountApiJsonContracts.parseWechatExchangeResponse(
            AccountApiJsonContracts.encodeWechatExchangeResponse(expected)
        )

        assertEquals(expected, decoded)
    }

    @Test
    fun wechatExchangeRegistrationRequiredWithNullProfileRoundTrips() {
        val expected = WechatExchangeResponseContract(
            result = WechatAuthResultContract.RegistrationRequired(
                wechatTicket = "wx-ticket-no-profile",
                nickname = null,
                avatarUrl = null,
                ticketExpiresAtMillis = 1_000_000L
            )
        )

        val decoded = AccountApiJsonContracts.parseWechatExchangeResponse(
            AccountApiJsonContracts.encodeWechatExchangeResponse(expected)
        )

        assertEquals(expected, decoded)
        val result = decoded.result as WechatAuthResultContract.RegistrationRequired
        assertNull(result.nickname)
        assertNull(result.avatarUrl)
    }

    // ── WeChat exchange response: MERGE_REQUIRED ──

    @Test
    fun wechatExchangeMergeRequiredRoundTrips() {
        val expected = WechatExchangeResponseContract(
            result = WechatAuthResultContract.MergeRequired(
                mergeTicket = "merge-ticket-xyz",
                sourceNickname = "来源用户",
                sourceIdentifiers = listOf(
                    AccountIdentifierContract(AccountIdentifierTypeContract.PHONE, "13800138000")
                ),
                ticketExpiresAtMillis = 2_000_000L
            )
        )

        val decoded = AccountApiJsonContracts.parseWechatExchangeResponse(
            AccountApiJsonContracts.encodeWechatExchangeResponse(expected)
        )

        assertEquals(expected, decoded)
    }

    @Test
    fun wechatExchangeMergeRequiredWithNullFieldsRoundTrips() {
        val expected = WechatExchangeResponseContract(
            result = WechatAuthResultContract.MergeRequired(
                mergeTicket = "merge-ticket-null",
                sourceNickname = null,
                ticketExpiresAtMillis = 3_000_000L
            )
        )

        val decoded = AccountApiJsonContracts.parseWechatExchangeResponse(
            AccountApiJsonContracts.encodeWechatExchangeResponse(expected)
        )

        assertEquals(expected, decoded)
    }

    // ── WeChat exchange: missing required fields ──

    @Test
    fun wechatExchangeMissingStatusFails() {
        assertThrows(NoSuchElementException::class.java) {
            AccountApiJsonContracts.parseWechatExchangeResponse(
                """{"ok":true,"phone":"13800138000"}"""
            )
        }
    }

    @Test
    fun wechatExchangeUnknownStatusFails() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AccountApiJsonContracts.parseWechatExchangeResponse(
                """{"ok":true,"status":"UNKNOWN_STATE"}"""
            )
        }
        assertTrue(error.message.orEmpty().contains("Unknown WeChat auth status"))
    }

    @Test
    fun wechatExchangeRegistrationRequiredMissingTicketFails() {
        assertThrows(NoSuchElementException::class.java) {
            AccountApiJsonContracts.parseWechatExchangeResponse(
                """{"ok":true,"status":"REGISTRATION_REQUIRED","ticketExpiresAtMillis":1000}"""
            )
        }
    }

    @Test
    fun wechatExchangeRegistrationRequiredMissingExpiryFails() {
        assertThrows(NoSuchElementException::class.java) {
            AccountApiJsonContracts.parseWechatExchangeResponse(
                """{"ok":true,"status":"REGISTRATION_REQUIRED","wechatTicket":"t"}"""
            )
        }
    }

    @Test
    fun wechatExchangeMergeRequiredMissingTicketFails() {
        assertThrows(NoSuchElementException::class.java) {
            AccountApiJsonContracts.parseWechatExchangeResponse(
                """{"ok":true,"status":"MERGE_REQUIRED","ticketExpiresAtMillis":1000}"""
            )
        }
    }

    // ── Merge preview response ──

    @Test
    fun mergePreviewFullRoundTrips() {
        val expected = MergePreviewResponseContract(
            mergeTicket = "merge-preview-ticket",
            ticketExpiresAtMillis = 8_000_000L,
            currentIdentifiers = listOf(
                AccountIdentifierContract(AccountIdentifierTypeContract.PHONE, "13800138000")
            ),
            currentWechatLinked = true,
            currentNickname = "当前用户",
            sourceIdentifiers = listOf(
                AccountIdentifierContract(AccountIdentifierTypeContract.PHONE, "13900139000")
            ),
            sourceWechatLinked = false,
            sourceNickname = "来源用户"
        )

        val encoded = AccountApiJsonContracts.encodeMergePreviewResponse(expected)
        val decoded = AccountApiJsonContracts.parseMergePreviewResponse(encoded)

        assertEquals(expected, decoded)
        assertFalse(encoded.contains("sourcePhone"))
        assertFalse(encoded.contains("currentPhone"))
    }

    @Test
    fun mergePreviewNullFieldsRoundTrips() {
        val expected = MergePreviewResponseContract(
            mergeTicket = "merge-null-ticket",
            ticketExpiresAtMillis = 9_000_000L,
            currentWechatLinked = false,
            currentNickname = null,
            sourceWechatLinked = false,
            sourceNickname = null
        )

        val decoded = AccountApiJsonContracts.parseMergePreviewResponse(
            AccountApiJsonContracts.encodeMergePreviewResponse(expected)
        )

        assertEquals(expected, decoded)
    }

    @Test
    fun mergePreviewMissingTicketFails() {
        assertThrows(NoSuchElementException::class.java) {
            AccountApiJsonContracts.parseMergePreviewResponse(
                """{"ok":true,"ticketExpiresAtMillis":1000}"""
            )
        }
    }

    // ── Error codes coverage ──

    @Test
    fun wechatErrorCodesExist() {
        assertEquals("WECHAT_NOT_CONFIGURED", AccountErrorCodeContract.WECHAT_NOT_CONFIGURED.name)
        assertEquals("WECHAT_AUTH_FAILED", AccountErrorCodeContract.WECHAT_AUTH_FAILED.name)
        assertEquals("WECHAT_SERVICE_UNAVAILABLE", AccountErrorCodeContract.WECHAT_SERVICE_UNAVAILABLE.name)
        assertEquals("TICKET_EXPIRED", AccountErrorCodeContract.TICKET_EXPIRED.name)
        assertEquals("TICKET_ALREADY_USED", AccountErrorCodeContract.TICKET_ALREADY_USED.name)
        assertEquals("WECHAT_ALREADY_LINKED", AccountErrorCodeContract.WECHAT_ALREADY_LINKED.name)
        assertEquals("PHONE_ALREADY_LINKED", AccountErrorCodeContract.PHONE_ALREADY_LINKED.name)
        assertEquals("MERGE_BLOCKED", AccountErrorCodeContract.MERGE_BLOCKED.name)
        assertEquals("LAST_LOGIN_METHOD_CANNOT_UNLINK", AccountErrorCodeContract.LAST_LOGIN_METHOD_CANNOT_UNLINK.name)
    }

    @Test
    fun wechatErrorCodeRoundTripsInErrorResponse() {
        val expected = AccountErrorResponseContract(
            error = AccountErrorCodeContract.WECHAT_NOT_CONFIGURED.name,
            message = "微信登录未配置"
        )

        val decoded = AccountApiJsonContracts.parseErrorResponse(
            AccountApiJsonContracts.encodeErrorResponse(expected)
        )

        assertEquals(expected, decoded)
    }

    // ── Ticket validity constant ──

    @Test
    fun ticketValidityIsFiveMinutes() {
        assertEquals(5 * 60 * 1000L, TICKET_VALIDITY_MILLIS)
    }

    // ── Existing error codes preserved ──

    @Test
    fun existingErrorCodesStillExist() {
        assertEquals("INVALID_REQUEST", AccountErrorCodeContract.INVALID_REQUEST.name)
        assertEquals("PHONE_ALREADY_REGISTERED", AccountErrorCodeContract.PHONE_ALREADY_REGISTERED.name)
        assertEquals("PHONE_NOT_REGISTERED", AccountErrorCodeContract.PHONE_NOT_REGISTERED.name)
        assertEquals("VERIFICATION_CODE_WRONG", AccountErrorCodeContract.VERIFICATION_CODE_WRONG.name)
        assertEquals("VERIFICATION_CODE_EXPIRED", AccountErrorCodeContract.VERIFICATION_CODE_EXPIRED.name)
        assertEquals("SMS_TOO_FREQUENT", AccountErrorCodeContract.SMS_TOO_FREQUENT.name)
        assertEquals("SMS_PROVIDER_UNCONFIGURED", AccountErrorCodeContract.SMS_PROVIDER_UNCONFIGURED.name)
        assertEquals("SMS_SEND_FAILED", AccountErrorCodeContract.SMS_SEND_FAILED.name)
        assertEquals("LOGIN_FAILED", AccountErrorCodeContract.LOGIN_FAILED.name)
        assertEquals("TOKEN_INVALID", AccountErrorCodeContract.TOKEN_INVALID.name)
        assertEquals("ACCOUNT_LOCKED", AccountErrorCodeContract.ACCOUNT_LOCKED.name)
        assertEquals("ACCOUNT_DELETION_PENDING", AccountErrorCodeContract.ACCOUNT_DELETION_PENDING.name)
        assertEquals("ACCOUNT_DELETION_NOT_PENDING", AccountErrorCodeContract.ACCOUNT_DELETION_NOT_PENDING.name)
        assertEquals("IDENTIFIER_ALREADY_REGISTERED", AccountErrorCodeContract.IDENTIFIER_ALREADY_REGISTERED.name)
        assertEquals("IDENTIFIER_NOT_REGISTERED", AccountErrorCodeContract.IDENTIFIER_NOT_REGISTERED.name)
        assertEquals("IDENTIFIER_ALREADY_LINKED", AccountErrorCodeContract.IDENTIFIER_ALREADY_LINKED.name)
        assertEquals("IDENTIFIER_CONFLICT", AccountErrorCodeContract.IDENTIFIER_CONFLICT.name)
        assertEquals("EMAIL_PROVIDER_UNCONFIGURED", AccountErrorCodeContract.EMAIL_PROVIDER_UNCONFIGURED.name)
        assertEquals("EMAIL_SEND_FAILED", AccountErrorCodeContract.EMAIL_SEND_FAILED.name)
        assertEquals("CODE_SEND_TOO_FREQUENT", AccountErrorCodeContract.CODE_SEND_TOO_FREQUENT.name)
    }

    // ── Unified identifiers contract round-trip ──

    @Test
    fun sessionResponseWithUnifiedIdentifiersRoundTrips() {
        val primary = AccountIdentifierContract(AccountIdentifierTypeContract.USERNAME, "User_01", true)
        val phoneId = AccountIdentifierContract(AccountIdentifierTypeContract.PHONE, "13800138000", true)
        val emailId = AccountIdentifierContract(AccountIdentifierTypeContract.EMAIL, "user@example.com", true)
        val expected = AccountSessionResponseContract(
            primaryIdentifier = primary,
            identifiers = listOf(primary, phoneId, emailId),
            token = "unified-tok",
            wechatLinked = true,
            nickname = "UnifiedUser"
        )

        val json = AccountApiJsonContracts.encodeSessionResponse(expected)
        val decoded = AccountApiJsonContracts.parseSessionResponse(json)

        assertEquals(expected, decoded)
    }

    @Test
    fun identifierLinkPrepareRoundTrips() {
        val ticketIssued = IdentifierLinkPrepareResponseContract.LinkTicketIssued(
            linkTicket = "link-t-1",
            ticketExpiresAtMillis = 1000L
        )
        val json1 = AccountApiJsonContracts.encodeIdentifierLinkPrepareResponse(ticketIssued)
        assertEquals(ticketIssued, AccountApiJsonContracts.parseIdentifierLinkPrepareResponse(json1))

        val mergeReq = IdentifierLinkPrepareResponseContract.MergeRequired(
            mergeTicket = "merge-t-1",
            sourceIdentifiers = listOf(AccountIdentifierContract(AccountIdentifierTypeContract.PHONE, "13800138000", true)),
            sourceWechatLinked = true,
            ticketExpiresAtMillis = 2000L
        )
        val json2 = AccountApiJsonContracts.encodeIdentifierLinkPrepareResponse(mergeReq)
        assertEquals(mergeReq, AccountApiJsonContracts.parseIdentifierLinkPrepareResponse(json2))
    }

    @Test
    fun identifierLinkAlreadyLinkedRoundTrips() {
        assertEquals(
            IdentifierLinkPrepareResponseContract.AlreadyLinked,
            AccountApiJsonContracts.parseIdentifierLinkPrepareResponse(
                AccountApiJsonContracts.encodeIdentifierLinkPrepareResponse(
                    IdentifierLinkPrepareResponseContract.AlreadyLinked
                )
            )
        )
    }
}
