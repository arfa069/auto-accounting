package com.autoaccounting.data.local

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.autoaccounting.feature.categorization.AiCategorizationSettings
import com.autoaccounting.feature.categorization.CategorizationRule
import com.autoaccounting.feature.categorization.applyCategorizationSuggestion
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import com.autoaccounting.feature.review.ReviewQueueEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalPreferencesRepositoryTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Test
    fun rulesAndSettingsSurviveDatabaseReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "preferences-reopen-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)

        val database = openDatabase(context, databaseName)
        val repository = LocalPreferencesRepository(database)
        runBlocking {
            repository.replaceCategorizationRules(
                listOf(
                    sampleRule(
                        id = "ride",
                        merchantContains = "metro",
                        category = "transport",
                        priority = 10,
                        updatedAtEpochMillis = 20
                    )
                )
            )
            repository.updateAiSettings(
                AiCategorizationSettings(
                    aiConsentGranted = true,
                    enhancedContextGranted = true
                )
            )
            repository.updateContinuousMonitoringState(
                ContinuousMonitoringState(
                    billSyncCompleted = true,
                    enabled = true
                )
            )
        }
        database.close()

        val reopenedDatabase = openDatabase(context, databaseName)
        val reopenedRepository = LocalPreferencesRepository(reopenedDatabase)

        val persistedRules = runBlocking { reopenedRepository.categorizationRules.first() }
        val persistedPreferences = runBlocking { reopenedRepository.userPreferences.first() }

        assertEquals(listOf("ride"), persistedRules.map { it.id })
        val persistedRule = persistedRules.single()
        assertEquals("metro", persistedRule.merchantContains)
        assertEquals("transport", persistedRule.category)
        assertEquals(10, persistedRule.priority)
        assertEquals(20, persistedRule.updatedAtEpochMillis)
        assertTrue(persistedPreferences.aiSettings.aiConsentGranted)
        assertTrue(persistedPreferences.aiSettings.enhancedContextGranted)
        assertTrue(persistedPreferences.continuousMonitoringState.billSyncCompleted)
        assertTrue(persistedPreferences.continuousMonitoringState.enabled)

        reopenedDatabase.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun savedRuleAppliesToLaterPendingEntryAfterRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "rule-match-reopen-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)

        val database = openDatabase(context, databaseName)
        runBlocking {
            LocalPreferencesRepository(database).replaceCategorizationRules(
                listOf(
                    sampleRule(
                        id = "coffee",
                        merchantContains = "coffee",
                        sourceLabel = "wechat",
                        transactionKind = "expense",
                        category = "food"
                    )
                )
            )
        }
        database.close()

        val reopenedDatabase = openDatabase(context, databaseName)
        val rules = runBlocking {
            LocalPreferencesRepository(reopenedDatabase).categorizationRules.first()
        }
        val entry = ReviewQueueEntry(
            id = "pending-coffee",
            title = "Coffee Shop",
            category = "",
            sourceLabel = "wechat",
            kindLabel = "expense"
        )

        val suggestedEntry = entry.applyCategorizationSuggestion(rules)

        assertEquals("food", suggestedEntry.category)

        reopenedDatabase.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun clearLocalDataDeletesRulesAndSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AutoAccountingDatabase::class.java
        ).allowMainThreadQueries().build()
        val repository = LocalPreferencesRepository(database)

        runBlocking {
            repository.replaceCategorizationRules(listOf(sampleRule()))
            repository.updateAiSettings(AiCategorizationSettings(aiConsentGranted = true))

            repository.clearLocalData()

            assertTrue(repository.categorizationRules.first().isEmpty())
            assertFalse(repository.userPreferences.first().aiSettings.aiConsentGranted)
        }

        database.close()
    }

    @Test
    fun monitoringPreferenceDoesNotEnableWithoutCompletedBillSync() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AutoAccountingDatabase::class.java
        ).allowMainThreadQueries().build()
        val repository = LocalPreferencesRepository(database)

        val preferences = runBlocking {
            database.localSettingsDao().upsert(
                LocalSettingsEntity(
                    aiConsentGranted = false,
                    enhancedContextGranted = false,
                    continuousBillSyncCompleted = false,
                    continuousMonitoringEnabled = true
                )
            )
            repository.userPreferences.first()
        }

        assertFalse(preferences.continuousMonitoringState.enabled)

        database.close()
    }

    private fun openDatabase(
        context: Context,
        databaseName: String
    ): AutoAccountingDatabase = Room.databaseBuilder(
        context,
        AutoAccountingDatabase::class.java,
        databaseName
    ).allowMainThreadQueries().build()

    private fun sampleRule(
        id: String = "rule-1",
        merchantContains: String = "merchant",
        sourceLabel: String = "",
        transactionKind: String = "",
        category: String = "food",
        priority: Int = 0,
        updatedAtEpochMillis: Long = 0
    ): CategorizationRule = CategorizationRule(
        id = id,
        merchantContains = merchantContains,
        sourceLabel = sourceLabel,
        transactionKind = transactionKind,
        category = category,
        priority = priority,
        updatedAtEpochMillis = updatedAtEpochMillis
    )
}
