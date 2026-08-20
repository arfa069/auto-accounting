package com.bks.feature.categorization

import com.bks.feature.account.AccountSession
import com.bks.feature.review.ReviewQueueEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCategorizationClientTest {
    @Test
    fun localModeOrMissingConsentDoesNotCallCloudAi() = runBlocking {
        val gateway = RecordingAiCategorizationGateway()
        val client = AiCategorizationClient(gateway)

        val localModeResult = client.suggestCategory(
            entry = sampleEntry(),
            session = AccountSession.LocalMode,
            settings = AiCategorizationSettings(aiConsentGranted = true),
            categoryCandidates = listOf("餐饮")
        )
        val noConsentResult = client.suggestCategory(
            entry = sampleEntry(),
            session = AccountSession.SignedIn(phone = "13800138000", token = "token-1"),
            settings = AiCategorizationSettings(aiConsentGranted = false),
            categoryCandidates = listOf("餐饮")
        )

        assertEquals(AiCategorizationSkipReason.REQUIRES_SIGNED_IN_ACCOUNT, localModeResult.skipReason)
        assertEquals(AiCategorizationSkipReason.REQUIRES_AI_CONSENT, noConsentResult.skipReason)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun consentedSignedInUserSendsMinimalPayloadByDefault() = runBlocking {
        val gateway = RecordingAiCategorizationGateway()
        val client = AiCategorizationClient(gateway)

        val result = client.suggestCategory(
            entry = sampleEntry(),
            session = AccountSession.SignedIn(phone = "13800138000", token = "token-1"),
            settings = AiCategorizationSettings(aiConsentGranted = true),
            categoryCandidates = listOf("餐饮", "交通")
        )

        assertEquals("餐饮", result.suggestion?.category)
        assertEquals("token-1", gateway.requests.single().token)
        val payload = gateway.requests.single().payload
        assertEquals("午餐", payload.merchantTitle)
        assertEquals("微信", payload.sourceLabel)
        assertEquals("支出", payload.transactionKind)
        assertEquals("0-50", payload.amountRangeLabel)
        assertEquals(listOf("餐饮", "交通"), payload.categoryCandidates)
        assertEquals(false, payload.enhancedContext)
        assertNull(payload.note)
        assertNull(payload.rawEvidenceText)
    }

    @Test
    fun enhancedContextOptInAddsOptionalContext() = runBlocking {
        val gateway = RecordingAiCategorizationGateway()
        val client = AiCategorizationClient(gateway)

        client.suggestCategory(
            entry = sampleEntry(),
            session = AccountSession.SignedIn(phone = "13800138000", token = "token-1"),
            settings = AiCategorizationSettings(
                aiConsentGranted = true,
                enhancedContextGranted = true
            ),
            categoryCandidates = listOf("餐饮")
        )

        val payload = gateway.requests.single().payload
        assertTrue(payload.enhancedContext)
        assertEquals("客户会议", payload.note)
        assertEquals("微信支付收款凭证 午餐 35.90", payload.rawEvidenceText)
    }

    @Test
    fun emptyCandidatesFailBeforeNetwork() = runBlocking {
        val gateway = RecordingAiCategorizationGateway()

        val result = AiCategorizationClient(gateway).suggestCategory(
            entry = sampleEntry(),
            session = AccountSession.SignedIn(phone = "13800138000", token = "token-1"),
            settings = AiCategorizationSettings(aiConsentGranted = true),
            categoryCandidates = listOf(" ")
        )

        assertEquals(AiCategorizationFailureReason.CATEGORY_CANDIDATES_REQUIRED, result.failureReason)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun gatewayFailureIsPreserved() = runBlocking {
        val gateway = RecordingAiCategorizationGateway(
            result = AiCategorizationGatewayResult.Failure(
                AiCategorizationFailureReason.ACCOUNT_DELETION_PENDING
            )
        )

        val result = AiCategorizationClient(gateway).suggestCategory(
            entry = sampleEntry(),
            session = AccountSession.SignedIn(phone = "13800138000", token = "token-1"),
            settings = AiCategorizationSettings(aiConsentGranted = true),
            categoryCandidates = listOf("餐饮")
        )

        assertEquals(AiCategorizationFailureReason.ACCOUNT_DELETION_PENDING, result.failureReason)
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

    private class RecordingAiCategorizationGateway(
        private val result: AiCategorizationGatewayResult = AiCategorizationGatewayResult.Success(
            AiCategorizationResponse(
                category = "餐饮",
                confidenceLabel = "高",
                explanation = "测试建议"
            )
        )
    ) : AiCategorizationGateway {
        val requests = mutableListOf<AiCategorizationGatewayRequest>()

        override suspend fun suggestCategory(
            token: String,
            payload: AiCategorizationPayload
        ): AiCategorizationGatewayResult {
            requests += AiCategorizationGatewayRequest(token, payload)
            return result
        }
    }
}
