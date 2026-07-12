package com.autoaccounting.data.local

import androidx.room.withTransaction
import com.autoaccounting.feature.categorization.AiCategorizationSettings
import com.autoaccounting.feature.categorization.CategorizationRule
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class LocalUserPreferences(
    val aiSettings: AiCategorizationSettings = AiCategorizationSettings(),
    val continuousMonitoringState: ContinuousMonitoringState = ContinuousMonitoringState()
)

class LocalPreferencesRepository(
    private val database: AutoAccountingDatabase
) {
    val categorizationRules: Flow<List<CategorizationRule>> =
        database.categorizationRuleDao().observeRules().map { rules ->
            rules.map { it.toDomain() }
        }

    val userPreferences: Flow<LocalUserPreferences> =
        database.localSettingsDao().observeById().map { settings ->
            settings?.toDomain() ?: LocalUserPreferences()
        }

    suspend fun replaceCategorizationRules(rules: List<CategorizationRule>) = database.withTransaction {
        database.categorizationRuleDao().deleteAll()
        database.categorizationRuleDao().upsertAll(rules.map { it.toEntity() })
    }

    suspend fun seedDefaultCategorizationRules() = database.withTransaction {
        database.categorizationRuleDao().insertIgnore(DefaultCategorizationRules.rules)
    }

    suspend fun updateAiSettings(aiSettings: AiCategorizationSettings) = database.withTransaction {
        val validSettings = if (aiSettings.aiConsentGranted) {
            aiSettings
        } else {
            AiCategorizationSettings()
        }
        val current = currentSettingsEntity()
        database.localSettingsDao().upsert(
            current.copy(
                aiConsentGranted = validSettings.aiConsentGranted,
                enhancedContextGranted = validSettings.enhancedContextGranted
            )
        )
    }

    suspend fun updateContinuousMonitoringState(
        state: ContinuousMonitoringState
    ) = database.withTransaction {
        val current = currentSettingsEntity()
        database.localSettingsDao().upsert(
            current.copy(
                continuousBillSyncCompleted = true,
                continuousMonitoringEnabled = state.enabled
            )
        )
    }

    suspend fun clearLocalData() = database.withTransaction {
        database.categorizationRuleDao().deleteAll()
        database.categorizationRuleDao().insertIgnore(DefaultCategorizationRules.rules)
        database.localSettingsDao().deleteAll()
    }

    private suspend fun currentSettingsEntity(): LocalSettingsEntity =
        database.localSettingsDao().getById() ?: LocalSettingsEntity(
            aiConsentGranted = false,
            enhancedContextGranted = false,
            continuousBillSyncCompleted = false,
            continuousMonitoringEnabled = false
        )
}

private fun CategorizationRuleEntity.toDomain(): CategorizationRule = CategorizationRule(
    id = id,
    merchantContains = merchantContains,
    titleContains = titleContains,
    sourceLabel = sourceLabel,
    transactionKind = transactionKind,
    category = category,
    priority = priority,
    enabled = enabled,
    updatedAtEpochMillis = updatedAtEpochMillis
)

private fun CategorizationRule.toEntity(): CategorizationRuleEntity = CategorizationRuleEntity(
    id = id,
    merchantContains = merchantContains,
    titleContains = titleContains,
    sourceLabel = sourceLabel,
    transactionKind = transactionKind,
    category = category,
    priority = priority,
    enabled = enabled,
    updatedAtEpochMillis = updatedAtEpochMillis
)

private fun LocalSettingsEntity.toDomain(): LocalUserPreferences = LocalUserPreferences(
    aiSettings = AiCategorizationSettings(
        aiConsentGranted = aiConsentGranted,
        enhancedContextGranted = aiConsentGranted && enhancedContextGranted
    ),
    continuousMonitoringState = ContinuousMonitoringState(
        enabled = continuousMonitoringEnabled
    )
)
