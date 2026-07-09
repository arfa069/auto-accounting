package com.autoaccounting.feature.categorization

import com.autoaccounting.api.AiCategorizationRequestContract
import com.autoaccounting.api.AiCategorizationResponseContract
import com.autoaccounting.api.ApiJsonContracts
import com.autoaccounting.api.CloudConfigContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAiContractTest {
    @Test
    fun aiPayloadStillMatchesBackendRequestContract() {
        val payload = AiCategorizationPayload(
            merchantTitle = "午餐",
            sourceLabel = "微信",
            transactionKind = "支出",
            amountRangeLabel = "0-50",
            categoryCandidates = listOf("餐饮", "交通"),
            note = "客户会议",
            rawEvidenceText = "微信支付收款凭证 午餐 35.90"
        )

        assertEquals(
            AiCategorizationRequestContract(
                merchantTitle = "午餐",
                sourceLabel = "微信",
                transactionKind = "支出",
                amountRangeLabel = "0-50",
                categoryCandidates = listOf("餐饮", "交通"),
                note = "客户会议",
                rawEvidenceText = "微信支付收款凭证 午餐 35.90"
            ),
            payload.toBackendContract()
        )
    }

    @Test
    fun backendAiResponseJsonStillMapsIntoAndroidSuggestionModel() {
        val responseContract = ApiJsonContracts.parseAiCategorizationResponse(
            ApiJsonContracts.encodeAiCategorizationResponse(
                AiCategorizationResponseContract(
                    ok = true,
                    category = "餐饮",
                    confidence = "中",
                    explanation = "基于商户标题生成建议"
                )
            )
        )

        val androidModel = AiCategorizationResponse(
            category = responseContract.category,
            confidenceLabel = responseContract.confidence,
            explanation = responseContract.explanation
        )

        assertEquals("餐饮", androidModel.category)
        assertEquals("中", androidModel.confidenceLabel)
        assertTrue(androidModel.explanation.contains("商户标题"))
    }

    @Test
    fun backendCloudConfigResponseStillMapsIntoAndroidAiSettings() {
        val configContract = ApiJsonContracts.parseCloudConfigResponse(
            ApiJsonContracts.encodeCloudConfigResponse(
                CloudConfigContract(
                    ok = true,
                    aiConsentGranted = true,
                    enhancedContextGranted = false,
                    featureFlags = mapOf("beta" to true)
                )
            )
        )

        val settings = AiCategorizationSettings(
            aiConsentGranted = configContract.aiConsentGranted,
            enhancedContextGranted = configContract.enhancedContextGranted
        )

        assertTrue(settings.aiConsentGranted)
        assertTrue(!settings.enhancedContextGranted)
        assertEquals(mapOf("beta" to true), configContract.featureFlags)
    }

    private fun AiCategorizationPayload.toBackendContract(): AiCategorizationRequestContract {
        return AiCategorizationRequestContract(
            merchantTitle = merchantTitle,
            sourceLabel = sourceLabel,
            transactionKind = transactionKind,
            amountRangeLabel = amountRangeLabel,
            categoryCandidates = categoryCandidates,
            note = note,
            rawEvidenceText = rawEvidenceText
        )
    }
}
