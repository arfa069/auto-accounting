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
    fun newDatabaseStartsWithEditableDefaultRules() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "default-rules-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(
            context,
            AutoAccountingDatabase::class.java,
            databaseName
        )
            .addCallback(DEFAULT_CATEGORIZATION_RULES_CALLBACK)
            .allowMainThreadQueries()
            .build()

        val rules = runBlocking { database.categorizationRuleDao().listRules() }

        assertEquals(
            DefaultCategorizationRules.rules.map { it.id }.toSet(),
            rules.map { it.id }.toSet()
        )
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun editedAndDeletedDefaultRulesAreNotRestoredOnReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "edited-default-rules-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = openDatabaseWithDefaults(context, databaseName)
        val repository = LocalPreferencesRepository(database)
        runBlocking {
            val rules = repository.categorizationRules.first()
                .filterNot { it.id == "default-shopping" }
                .map { rule ->
                    if (rule.id == "default-food") rule.copy(category = "购物") else rule
                }
            repository.replaceCategorizationRules(rules)
        }
        database.close()

        val reopened = openDatabaseWithDefaults(context, databaseName)
        val persistedRules = runBlocking {
            LocalPreferencesRepository(reopened).categorizationRules.first()
        }

        assertFalse(persistedRules.any { it.id == "default-shopping" })
        assertEquals("购物", persistedRules.first { it.id == "default-food" }.category)
        reopened.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationSeedDoesNotOverwriteEditedRuleWithStableId() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AutoAccountingDatabase::class.java
        ).allowMainThreadQueries().build()
        val edited = DefaultCategorizationRules.rules
            .first { it.id == "default-food" }
            .copy(category = "购物", updatedAtEpochMillis = 99)

        runBlocking {
            database.categorizationRuleDao().upsertAll(listOf(edited))
            DefaultCategorizationRules.insertMissing(database.openHelper.writableDatabase)
            val rules = database.categorizationRuleDao().listRules()

            assertEquals(7, rules.size)
            assertEquals("购物", rules.first { it.id == "default-food" }.category)
            assertEquals(99, rules.first { it.id == "default-food" }.updatedAtEpochMillis)
        }

        database.close()
    }

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
        assertTrue(persistedPreferences.continuousMonitoringState.enabled)

        reopenedDatabase.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun disablingAiPersistsWithEnhancedContextRevoked() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "preferences-ai-disabled-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val database = openDatabase(context, databaseName)
        val repository = LocalPreferencesRepository(database)

        runBlocking {
            repository.updateAiSettings(
                AiCategorizationSettings(
                    aiConsentGranted = false,
                    enhancedContextGranted = true
                )
            )
        }
        database.close()

        val reopenedDatabase = openDatabase(context, databaseName)
        val preferences = runBlocking {
            LocalPreferencesRepository(reopenedDatabase).userPreferences.first()
        }

        assertFalse(preferences.aiSettings.aiConsentGranted)
        assertFalse(preferences.aiSettings.enhancedContextGranted)
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
    fun clearLocalDataRestoresDefaultRulesAndDeletesSettings() {
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

            assertEquals(
                DefaultCategorizationRules.rules.map { it.id }.toSet(),
                repository.categorizationRules.first().map { it.id }.toSet()
            )
            assertFalse(repository.userPreferences.first().aiSettings.aiConsentGranted)
        }

        database.close()
    }

    @Test
    fun monitoringPreferenceNoLongerRequiresCompletedBillSync() {
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

        assertTrue(preferences.continuousMonitoringState.enabled)

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

    private fun openDatabaseWithDefaults(
        context: Context,
        databaseName: String
    ): AutoAccountingDatabase = Room.databaseBuilder(
        context,
        AutoAccountingDatabase::class.java,
        databaseName
    )
        .addCallback(DEFAULT_CATEGORIZATION_RULES_CALLBACK)
        .allowMainThreadQueries()
        .build()

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
