package com.autoaccounting.feature.categorization

import org.junit.Assert.assertEquals
import org.junit.Test

class AiCategorizationSettingsTest {
    @Test
    fun consentChainReturnsToMinimalFieldsAfterAiIsReenabled() {
        var settings = reduceAiCategorizationSettings(
            AiCategorizationSettings(),
            AiCategorizationSettingsAction.EnableAi
        )
        assertEquals(AiCategorizationSettings(aiConsentGranted = true), settings)

        settings = reduceAiCategorizationSettings(
            settings,
            AiCategorizationSettingsAction.SetEnhancedContext(true)
        )
        assertEquals(true, settings.enhancedContextGranted)

        settings = reduceAiCategorizationSettings(
            settings,
            AiCategorizationSettingsAction.DisableAi
        )
        assertEquals(AiCategorizationSettings(), settings)

        settings = reduceAiCategorizationSettings(
            settings,
            AiCategorizationSettingsAction.EnableAi
        )
        assertEquals(AiCategorizationSettings(aiConsentGranted = true), settings)
    }

    @Test
    fun enhancedContextCannotBeEnabledWithoutAiConsent() {
        val settings = reduceAiCategorizationSettings(
            AiCategorizationSettings(),
            AiCategorizationSettingsAction.SetEnhancedContext(true)
        )

        assertEquals(AiCategorizationSettings(), settings)
    }
}
