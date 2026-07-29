package com.autoaccounting.backend.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderEnvironmentTest {
    @Test
    fun ruleProviderIsRejectedByEnvironmentFactory() = runBlocking {
        val provider = aiProviderFromEnvironment(
            mapOf("AUTO_ACCOUNTING_AI_PROVIDER" to "rule")
        )

        assertProviderFailure(provider, AiProviderException.ConfigurationInvalid)
    }

    @Test
    fun missingAndUnknownProviderFailClosed() = runBlocking {
        val missing = aiProviderFromEnvironment(emptyMap())
        val unknown = aiProviderFromEnvironment(
            mapOf("AUTO_ACCOUNTING_AI_PROVIDER" to "unknown")
        )

        assertProviderFailure(missing, AiProviderException.Unavailable)
        assertProviderFailure(unknown, AiProviderException.ConfigurationInvalid)
    }

    @Test
    fun openAiSelectionWithMissingConfigurationFailsClosed() = runBlocking {
        val provider = aiProviderFromEnvironment(
            mapOf("AUTO_ACCOUNTING_AI_PROVIDER" to "openai")
        )

        assertProviderFailure(provider, AiProviderException.ConfigurationInvalid)
    }

    @Test
    fun openAiRejectsUnsafeHttpBaseUrl() = runBlocking {
        val provider = aiProviderFromEnvironment(
            mapOf(
                "AUTO_ACCOUNTING_AI_PROVIDER" to "openai",
                "AUTO_ACCOUNTING_OPENAI_API_KEY" to "test-key",
                "AUTO_ACCOUNTING_OPENAI_BASE_URL" to "http://example.com/v1"
            )
        )

        assertProviderFailure(provider, AiProviderException.ConfigurationInvalid)
    }

    @Test
    fun openAiRejectsInvalidTimeoutConfiguration() = runBlocking {
        val provider = aiProviderFromEnvironment(
            mapOf(
                "AUTO_ACCOUNTING_AI_PROVIDER" to "openai",
                "AUTO_ACCOUNTING_OPENAI_API_KEY" to "test-key",
                "AUTO_ACCOUNTING_OPENAI_CONNECT_TIMEOUT_MILLIS" to "0"
            )
        )

        assertProviderFailure(provider, AiProviderException.ConfigurationInvalid)
    }

    private suspend fun assertProviderFailure(
        provider: AiProvider,
        expected: AiProviderException
    ) {
        val failure = runCatching { provider.suggest(samplePayload()) }.exceptionOrNull()
        assertTrue(failure === expected)
    }

    private fun samplePayload() = AiCategorizationPayload(
        merchantTitle = "午餐",
        sourceLabel = "微信",
        transactionKind = "支出",
        amountRangeLabel = "0-50",
        categoryCandidates = listOf("餐饮")
    )
}
