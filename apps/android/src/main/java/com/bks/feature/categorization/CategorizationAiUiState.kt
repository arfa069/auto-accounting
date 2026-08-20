package com.bks.feature.categorization

data class CategorizationAiUiState(
    val settings: AiCategorizationSettings = AiCategorizationSettings(),
    val signedIn: Boolean = false,
    val cloudWritesPaused: Boolean = false,
    val settingsSyncInFlight: Boolean = false
)
