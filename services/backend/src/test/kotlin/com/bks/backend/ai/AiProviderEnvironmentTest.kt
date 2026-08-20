package com.bks.backend.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderEnvironmentTest {
    @Test
    fun missingAndUnknownProtocolFailClosed() = runBlocking {
        val missing = aiProviderFromEnvironment(emptyMap())
        val unknown = aiProviderFromEnvironment(
            mapOf("BKS_AI_PROTOCOL" to "unknown")
        )
        val rule = aiProviderFromEnvironment(
            mapOf("BKS_AI_PROTOCOL" to "rule")
        )

        assertProviderFailure(missing, AiProviderException.Unavailable)
        assertProviderFailure(unknown, AiProviderException.ConfigurationInvalid)
        assertProviderFailure(rule, AiProviderException.ConfigurationInvalid)
    }

    @Test
    fun supportedProtocolsUseTheirProtocolAdapters() {
        val responses = aiProviderFromEnvironment(
            validConfig("openai-responses", "https://example.com/v1/responses")
        )
        val chatCompletions = aiProviderFromEnvironment(
            validConfig(
                "openai-chat-completions",
                "https://example.com/v1/chat/completions"
            )
        )
        val messages = aiProviderFromEnvironment(
            validConfig("anthropic-messages", "https://example.com/v1/messages")
        )

        assertTrue(responses is OpenAiProvider)
        assertTrue(chatCompletions is DeepSeekProvider)
        assertTrue(messages is AnthropicProvider)
    }

    @Test
    fun selectedProtocolRequiresCommonConfiguration() = runBlocking {
        val provider = aiProviderFromEnvironment(
            mapOf("BKS_AI_PROTOCOL" to "openai-chat-completions")
        )

        assertProviderFailure(provider, AiProviderException.ConfigurationInvalid)
    }

    @Test
    fun legacyProviderVariablesAreNotRead() = runBlocking {
        val provider = aiProviderFromEnvironment(
            mapOf(
                "BKS_AI_PROTOCOL" to "openai-chat-completions",
                "BKS_DEEPSEEK_API_KEY" to "legacy-key",
                "BKS_DEEPSEEK_MODEL" to "deepseek-v4-flash",
                "BKS_DEEPSEEK_BASE_URL" to "https://api.deepseek.com"
            )
        )

        assertProviderFailure(provider, AiProviderException.ConfigurationInvalid)
    }

    @Test
    fun commonConfigurationRejectsUnsafeOrInjectedValues() = runBlocking {
        val unsafeEndpoint = aiProviderFromEnvironment(
            validConfig(
                protocol = "openai-chat-completions",
                endpoint = "http://example.com/chat/completions"
            )
        )
        val injectedCredential = aiProviderFromEnvironment(
            validConfig(
                protocol = "openai-chat-completions",
                endpoint = "https://example.com/chat/completions"
            ) + ("BKS_AI_API_KEY" to "test-key\nInjected: value")
        )
        val invalidAuthStyle = aiProviderFromEnvironment(
            validConfig(
                protocol = "openai-chat-completions",
                endpoint = "https://example.com/chat/completions"
            ) + ("BKS_AI_AUTH_STYLE" to "custom")
        )

        assertProviderFailure(unsafeEndpoint, AiProviderException.ConfigurationInvalid)
        assertProviderFailure(injectedCredential, AiProviderException.ConfigurationInvalid)
        assertProviderFailure(invalidAuthStyle, AiProviderException.ConfigurationInvalid)
    }

    @Test
    fun protocolRejectsUnsupportedCapabilityCombination() = runBlocking {
        val responsesWithJsonObject = aiProviderFromEnvironment(
            validConfig(
                protocol = "openai-responses",
                endpoint = "https://example.com/v1/responses"
            ) + ("BKS_AI_OUTPUT_MODE" to "json-object")
        )
        val messagesWithReasoning = aiProviderFromEnvironment(
            validConfig(
                protocol = "anthropic-messages",
                endpoint = "https://example.com/v1/messages"
            ) + ("BKS_AI_REASONING_MODE" to "enabled")
        )

        assertProviderFailure(responsesWithJsonObject, AiProviderException.ConfigurationInvalid)
        assertProviderFailure(messagesWithReasoning, AiProviderException.ConfigurationInvalid)
    }

    private fun validConfig(protocol: String, endpoint: String): Map<String, String> =
        mapOf(
            "BKS_AI_PROTOCOL" to protocol,
            "BKS_AI_ENDPOINT" to endpoint,
            "BKS_AI_API_KEY" to "test-key",
            "BKS_AI_MODEL" to "test-model"
        )

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
