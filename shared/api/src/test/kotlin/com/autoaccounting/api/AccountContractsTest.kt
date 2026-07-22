package com.autoaccounting.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountContractsTest {

    // ── Session response backward compatibility ──

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

    // ── Old phone-only JSON backward compatibility ──

    @Test
    fun legacyPhoneJsonWithoutNewFieldsStillParses() {
        // Simulate a JSON from a server that doesn't yet include wechat fields
        val legacyJson = """{"ok":true,"phone":"13800138000","token":"tok","deletionPending":false,"requestedAtMillis":null,"finalDeletionAtMillis":null}"""

        val decoded = AccountApiJsonContracts.parseSessionResponse(legacyJson)

        assertEquals("13800138000", decoded.phone)
        assertEquals("tok", decoded.token)
        assertFalse(decoded.wechatLinked)
        assertNull(decoded.nickname)
        assertNull(decoded.avatarUrl)
    }

    @Test
    fun newFieldsInResponseDoNotBreakLegacyPhoneClient() {
        // A response with all new fields should still carry phone correctly
        val response = AccountSessionResponseContract(
            phone = "13800138000",
            token = "tok",
            wechatLinked = true,
            nickname = "微信用户",
            avatarUrl = "https://wx.example.com/avatar.jpg"
        )

        val json = AccountApiJsonContracts.encodeSessionResponse(response)
        val decoded = AccountApiJsonContracts.parseSessionResponse(json)

        assertEquals("13800138000", decoded.phone)
        assertEquals("tok", decoded.token)
        assertTrue(decoded.wechatLinked)
        assertEquals("微信用户", decoded.nickname)
        assertEquals("https://wx.example.com/avatar.jpg", decoded.avatarUrl)
    }

    // ── WeChat account with phone=null ──

    @Test
    fun wechatAccountWithNullPhoneRoundTrips() {
        val expected = AccountSessionResponseContract(
            phone = null,
            token = "wechat-token",
            wechatLinked = true,
            nickname = "TestUser",
            avatarUrl = "https://wx.example.com/avatar.jpg"
        )

        val decoded = AccountApiJsonContracts.parseSessionResponse(
            AccountApiJsonContracts.encodeSessionResponse(expected)
        )

        assertEquals(expected, decoded)
        assertNull(decoded.phone)
        assertTrue(decoded.wechatLinked)
    }

    // ── Session response full round-trip with all fields ──

    @Test
    fun sessionResponseFullRoundTrip() {
        val expected = AccountSessionResponseContract(
            phone = "13900139000",
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
            phone = "13800138000",
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
            phone = null,
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
                sourcePhone = "138****8000",
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
                sourcePhone = null,
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

    // ── Phone link prepare response ──

    @Test
    fun phoneLinkPreparePhoneTicketRoundTrips() {
        val expected = PhoneLinkPrepareResponseContract.PhoneTicketIssued(
            phoneTicket = "phone-ticket-123",
            ticketExpiresAtMillis = 5_000_000L
        )

        val decoded = AccountApiJsonContracts.parsePhoneLinkPrepareResponse(
            AccountApiJsonContracts.encodePhoneLinkPrepareResponse(expected)
        )

        assertEquals(expected, decoded)
    }

    @Test
    fun phoneLinkPrepareMergeRequiredRoundTrips() {
        val expected = PhoneLinkPrepareResponseContract.MergeRequired(
            mergeTicket = "phone-merge-ticket",
            sourcePhone = "139****9000",
            sourceWechatLinked = true,
            ticketExpiresAtMillis = 6_000_000L
        )

        val decoded = AccountApiJsonContracts.parsePhoneLinkPrepareResponse(
            AccountApiJsonContracts.encodePhoneLinkPrepareResponse(expected)
        )

        assertEquals(expected, decoded)
    }

    @Test
    fun phoneLinkPrepareMergeRequiredNullPhoneRoundTrips() {
        val expected = PhoneLinkPrepareResponseContract.MergeRequired(
            mergeTicket = "phone-merge-null",
            sourcePhone = null,
            sourceWechatLinked = false,
            ticketExpiresAtMillis = 7_000_000L
        )

        val decoded = AccountApiJsonContracts.parsePhoneLinkPrepareResponse(
            AccountApiJsonContracts.encodePhoneLinkPrepareResponse(expected)
        )

        assertEquals(expected, decoded)
    }

    @Test
    fun phoneLinkPrepareUnknownStatusFails() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AccountApiJsonContracts.parsePhoneLinkPrepareResponse(
                """{"ok":true,"status":"INVALID"}"""
            )
        }
        assertTrue(error.message.orEmpty().contains("Unknown phone link prepare status"))
    }

    // ── Merge preview response ──

    @Test
    fun mergePreviewFullRoundTrips() {
        val expected = MergePreviewResponseContract(
            mergeTicket = "merge-preview-ticket",
            ticketExpiresAtMillis = 8_000_000L,
            currentPhone = "13800138000",
            currentWechatLinked = true,
            currentNickname = "当前用户",
            sourcePhone = "13900139000",
            sourceWechatLinked = false,
            sourceNickname = "来源用户"
        )

        val decoded = AccountApiJsonContracts.parseMergePreviewResponse(
            AccountApiJsonContracts.encodeMergePreviewResponse(expected)
        )

        assertEquals(expected, decoded)
    }

    @Test
    fun mergePreviewNullFieldsRoundTrips() {
        val expected = MergePreviewResponseContract(
            mergeTicket = "merge-null-ticket",
            ticketExpiresAtMillis = 9_000_000L,
            currentPhone = null,
            currentWechatLinked = false,
            currentNickname = null,
            sourcePhone = null,
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
    }
}
