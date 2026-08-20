package com.bks.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAiContractsTest {
    @Test
    fun requestJsonRoundTripsMinimalAndEnhancedFields() {
        val request = AiCategorizationRequestContract(
            merchantTitle = "午餐",
            sourceLabel = "微信",
            transactionKind = "支出",
            amountRangeLabel = "0-50",
            categoryCandidates = listOf("餐饮", "交通"),
            enhancedContext = true,
            note = "同事聚餐",
            rawEvidenceText = "付款通知"
        )

        val encoded = ApiJsonContracts.encodeAiCategorizationRequest(request)

        assertEquals(request, ApiJsonContracts.parseAiCategorizationRequest(encoded))
        assertFalse(encoded.contains("amountMinor"))
    }

    @Test
    fun requestEncoderDropsOptionalContextWithoutEnhancedAuthorization() {
        val encoded = ApiJsonContracts.encodeAiCategorizationRequest(
            AiCategorizationRequestContract(
                merchantTitle = "午餐",
                sourceLabel = "微信",
                transactionKind = "支出",
                amountRangeLabel = "0-50",
                categoryCandidates = listOf("餐饮"),
                enhancedContext = false,
                note = "不得发送",
                rawEvidenceText = "不得发送"
            )
        )

        assertFalse(encoded.contains("note"))
        assertFalse(encoded.contains("rawEvidenceText"))
    }

    @Test
    fun requestParserDefaultsEnhancedContextForOlderPayloads() {
        val parsed = ApiJsonContracts.parseAiCategorizationRequest(
            """{
                "merchantTitle":"午餐",
                "sourceLabel":"微信",
                "transactionKind":"支出",
                "amountRangeLabel":"0-50",
                "categoryCandidates":["餐饮"]
            }""".trimIndent()
        )

        assertFalse(parsed.enhancedContext)
        assertEquals(null, parsed.note)
        assertEquals(null, parsed.rawEvidenceText)
    }

    @Test
    fun responseAndErrorJsonRoundTrip() {
        val response = AiCategorizationResponseContract(
            ok = true,
            category = "餐饮",
            confidence = "高",
            explanation = "商户与餐饮候选匹配"
        )
        val error = AiCategorizationErrorContract(
            error = "PROVIDER_RATE_LIMITED",
            message = "云端 AI 请求过于频繁，请稍后重试"
        )

        assertEquals(
            response,
            ApiJsonContracts.parseAiCategorizationResponse(
                ApiJsonContracts.encodeAiCategorizationResponse(response)
            )
        )
        assertEquals(
            error,
            ApiJsonContracts.parseAiCategorizationError(
                ApiJsonContracts.encodeAiCategorizationError(error)
            )
        )
        assertTrue(ApiJsonContracts.encodeAiCategorizationError(error).contains("\"ok\":false"))
    }


    @Test
    fun cloudConfigJsonRoundTripsConsentState() {
        val contract = CloudConfigContract(
            ok = true,
            aiConsentGranted = true,
            enhancedContextGranted = true,
            featureFlags = mapOf("beta" to true)
        )

        assertEquals(
            contract,
            ApiJsonContracts.parseCloudConfigResponse(
                ApiJsonContracts.encodeCloudConfigResponse(contract)
            )
        )
    }

    @Test
    fun nonStringContractFieldsAreRejected() {
        val responseFailure = runCatching {
            ApiJsonContracts.parseAiCategorizationResponse(
                """{"ok":true,"category":"餐饮","confidence":"高","explanation":123}"""
            )
        }
        val candidatesFailure = runCatching {
            ApiJsonContracts.parseAiCategoryCandidates("""["餐饮",123]""")
        }

        assertTrue(responseFailure.isFailure)
        assertTrue(candidatesFailure.isFailure)
    }

    @Test
    fun categoryCandidateJsonRoundTripsCommasAndUnicode() {
        val candidates = listOf("餐饮,咖啡", "交通", "医疗")

        assertEquals(
            candidates,
            ApiJsonContracts.parseAiCategoryCandidates(
                ApiJsonContracts.encodeAiCategoryCandidates(candidates)
            )
        )
    }
}
