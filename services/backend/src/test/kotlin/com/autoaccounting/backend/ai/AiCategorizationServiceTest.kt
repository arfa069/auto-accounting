package com.autoaccounting.backend.ai

import com.autoaccounting.api.AiCategorizationRequestContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCategorizationServiceTest {
    @Test
    fun emptyCandidatesDoNotCallProviderOrWriteLog() = runBlocking {
        val provider = RecordingProvider()
        val service = AiCategorizationService(provider)

        val failure = runCatching {
            service.suggest(1L, request().copy(categoryCandidates = emptyList()), false)
        }.exceptionOrNull() as AiCategorizationException

        assertEquals(AiCategorizationError.CATEGORY_CANDIDATES_REQUIRED, failure.error)
        assertEquals(0, provider.calls)
        assertTrue(service.logs.isEmpty())
    }

    @Test
    fun exactAmountOrUnknownRangeIsRejectedBeforeProviderCall() = runBlocking {
        val provider = RecordingProvider()
        val service = AiCategorizationService(provider)

        listOf("35.90", "3590", "0-100").forEach { invalidRange ->
            val failure = runCatching {
                service.suggest(
                    1L,
                    request().copy(amountRangeLabel = invalidRange),
                    enhancedContextAuthorized = false
                )
            }.exceptionOrNull() as AiCategorizationException

            assertEquals(AiCategorizationError.INVALID_REQUEST, failure.error)
        }
        assertEquals(0, provider.calls)
        assertTrue(service.logs.isEmpty())
    }

    @Test
    fun suggestionOutsideCandidateWhitelistIsRejectedWithoutLog() = runBlocking {
        val provider = RecordingProvider(
            result = AiCategorizationSuggestion("房租", "高", "不在候选中")
        )
        val service = AiCategorizationService(provider)

        val failure = runCatching {
            service.suggest(1L, request(), false)
        }.exceptionOrNull() as AiCategorizationException

        assertEquals(AiCategorizationError.PROVIDER_INVALID_RESPONSE, failure.error)
        assertEquals(1, provider.calls)
        assertTrue(service.logs.isEmpty())
    }

    @Test
    fun invalidConfidenceAndExplanationAreRejectedWithoutLog() = runBlocking {
        val invalidSuggestions = listOf(
            AiCategorizationSuggestion("餐饮", "very-high", "候选分类匹配"),
            AiCategorizationSuggestion("餐饮", "高", ""),
            AiCategorizationSuggestion("餐饮", "高", "x".repeat(MAX_EXPLANATION_LENGTH + 1))
        )

        invalidSuggestions.forEach { suggestion ->
            val service = AiCategorizationService(RecordingProvider(result = suggestion))
            val failure = runCatching {
                service.suggest(1L, request(), false)
            }.exceptionOrNull() as AiCategorizationException

            assertEquals(AiCategorizationError.PROVIDER_INVALID_RESPONSE, failure.error)
            assertTrue(service.logs.isEmpty())
        }
    }

    @Test
    fun providerFailuresAreStableAndNeverWriteSuccessLogs() = runBlocking {
        val cases = listOf(
            AiProviderException.Unavailable to AiCategorizationError.PROVIDER_UNAVAILABLE,
            AiProviderException.ConfigurationInvalid to
                AiCategorizationError.PROVIDER_CONFIGURATION_INVALID,
            AiProviderException.TimedOut to AiCategorizationError.PROVIDER_TIMEOUT,
            AiProviderException.RateLimited to AiCategorizationError.PROVIDER_RATE_LIMITED,
            AiProviderException.UpstreamFailure to AiCategorizationError.PROVIDER_ERROR,
            AiProviderException.InvalidResponse to AiCategorizationError.PROVIDER_INVALID_RESPONSE
        )

        cases.forEach { (providerFailure, expected) ->
            val service = AiCategorizationService(
                provider = RecordingProvider(failure = providerFailure)
            )
            val failure = runCatching {
                service.suggest(1L, request(), false)
            }.exceptionOrNull() as AiCategorizationException
            assertEquals(expected, failure.error)
            assertTrue(service.logs.isEmpty())
            assertFalse(failure.message.orEmpty().contains("secret", ignoreCase = true))
        }
    }

    @Test
    fun providerCancellationIsNotConvertedOrLogged() = runBlocking {
        val cancellation = CancellationException("cancelled")
        val service = AiCategorizationService(
            provider = object : AiProvider {
                override suspend fun suggest(
                    payload: AiCategorizationPayload
                ): AiCategorizationSuggestion {
                    throw cancellation
                }
            }
        )

        val failure = runCatching {
            service.suggest(1L, request(), false)
        }.exceptionOrNull()

        assertSame(cancellation, failure)
        assertTrue(service.logs.isEmpty())
    }

    @Test
    fun enhancedContextRequiresStoredAuthorization() = runBlocking {
        val provider = RecordingProvider()
        val service = AiCategorizationService(provider)
        val enhanced = request().copy(
            enhancedContext = true,
            note = "同事聚餐",
            rawEvidenceText = "付款通知"
        )

        val failure = runCatching {
            service.suggest(1L, enhanced, enhancedContextAuthorized = false)
        }.exceptionOrNull() as AiCategorizationException

        assertEquals(AiCategorizationError.ENHANCED_CONTEXT_NOT_AUTHORIZED, failure.error)
        assertEquals(0, provider.calls)
        assertTrue(service.logs.isEmpty())
    }

    @Test
    fun enhancedContextReachesProviderButNeverPersistentLog() = runBlocking {
        val provider = RecordingProvider(
            result = AiCategorizationSuggestion("餐饮", "高", "同事聚餐；付款通知")
        )
        val service = AiCategorizationService(provider)

        service.suggest(
            accountId = 1L,
            request = request().copy(
                enhancedContext = true,
                note = "同事聚餐",
                rawEvidenceText = "付款通知"
            ),
            enhancedContextAuthorized = true
        )

        assertEquals("同事聚餐", provider.lastPayload?.note)
        assertEquals("付款通知", provider.lastPayload?.rawEvidenceText)
        val stored = service.logs.single()
        assertEquals("0-50", stored.amountRangeLabel)
        assertFalse(stored.toString().contains("同事聚餐"))
        assertFalse(stored.toString().contains("付款通知"))
        assertEquals("增强上下文请求：解释未持久化", stored.explanation)
    }

    @Test
    fun nonEnhancedRequestCannotSmuggleOptionalContext() = runBlocking {
        val provider = RecordingProvider()
        val service = AiCategorizationService(provider)

        val failure = runCatching {
            service.suggest(
                1L,
                request().copy(note = "private", rawEvidenceText = "private raw"),
                enhancedContextAuthorized = true
            )
        }.exceptionOrNull() as AiCategorizationException

        assertEquals(AiCategorizationError.INVALID_REQUEST, failure.error)
        assertEquals(0, provider.calls)
    }

    private fun request() = AiCategorizationRequestContract(
        merchantTitle = "午餐",
        sourceLabel = "微信",
        transactionKind = "支出",
        amountRangeLabel = "0-50",
        categoryCandidates = listOf("餐饮", "交通")
    )

    private class RecordingProvider(
        private val result: AiCategorizationSuggestion =
            AiCategorizationSuggestion("餐饮", "高", "候选分类匹配"),
        private val failure: AiProviderException? = null
    ) : AiProvider {
        var calls: Int = 0
        var lastPayload: AiCategorizationPayload? = null

        override suspend fun suggest(payload: AiCategorizationPayload): AiCategorizationSuggestion {
            calls += 1
            lastPayload = payload
            failure?.let { throw it }
            return result
        }
    }
}
