package com.autoaccounting.feature.categorization

import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.review.ReviewQueueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCategorizationClientTest {
    @Test
    fun localModeOrMissingConsentDoesNotCallCloudAi() {
        val gateway = RecordingAiCategorizationGateway()
        val client = AiCategorizationClient(gateway)

        val localModeResult = client.suggestCategory(
            entry = sampleEntry(),
            session = AccountSession.LocalMode,
            settings = AiCategorizationSettings(aiConsentGranted = true)
        )
        val noConsentResult = client.suggestCategory(
            entry = sampleEntry(),
            session = AccountSession.SignedIn(phone = "13800138000", token = "token-1"),
            settings = AiCategorizationSettings(aiConsentGranted = false)
        )

        assertEquals(AiCategorizationSkipReason.REQUIRES_SIGNED_IN_ACCOUNT, localModeResult.skipReason)
        assertEquals(AiCategorizationSkipReason.REQUIRES_AI_CONSENT, noConsentResult.skipReason)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun consentedSignedInUserSendsMinimalPayloadByDefault() {
        val gateway = RecordingAiCategorizationGateway()
        val client = AiCategorizationClient(gateway)

        val result = client.suggestCategory(
            entry = sampleEntry(),
            session = AccountSession.SignedIn(phone = "13800138000", token = "token-1"),
            settings = AiCategorizationSettings(aiConsentGranted = true)
        )

        assertEquals("餐饮", result.suggestion?.category)
        assertEquals("token-1", gateway.requests.single().token)
        val payload = gateway.requests.single().payload
        assertEquals("午餐", payload.merchantTitle)
        assertEquals("微信", payload.sourceLabel)
        assertEquals("支出", payload.transactionKind)
        assertEquals("0-50", payload.amountRangeLabel)
        assertNull(payload.note)
        assertNull(payload.rawEvidenceText)
    }

    @Test
    fun enhancedContextOptInAddsOptionalContext() {
        val gateway = RecordingAiCategorizationGateway()
        val client = AiCategorizationClient(gateway)

        client.suggestCategory(
            entry = sampleEntry(),
            session = AccountSession.SignedIn(phone = "13800138000", token = "token-1"),
            settings = AiCategorizationSettings(
                aiConsentGranted = true,
                enhancedContextGranted = true
            )
        )

        val payload = gateway.requests.single().payload
        assertEquals("客户会议", payload.note)
        assertEquals("微信支付收款凭证 午餐 35.90", payload.rawEvidenceText)
    }

    private fun sampleEntry(): ReviewQueueEntry = ReviewQueueEntry(
        id = "pending-lunch",
        title = "午餐",
        amountMinor = 3590,
        transactionTimeText = "2026-07-08 12:20",
        category = "",
        fundingAccountLabel = "微信零钱",
        sourceLabel = "微信",
        kindLabel = "支出",
        captureReasonLabel = "通知捕获",
        note = "客户会议",
        rawEvidenceText = "微信支付收款凭证 午餐 35.90"
    )

    private class RecordingAiCategorizationGateway : AiCategorizationGateway {
        val requests = mutableListOf<AiCategorizationGatewayRequest>()

        override fun suggestCategory(
            token: String,
            payload: AiCategorizationPayload
        ): AiCategorizationResponse {
            requests += AiCategorizationGatewayRequest(token, payload)
            return AiCategorizationResponse(
                category = "餐饮",
                confidenceLabel = "高",
                explanation = "商户标题像餐饮消费"
            )
        }
    }
}
