package com.autoaccounting.feature.monitoring

import androidx.test.core.app.ApplicationProvider
import android.os.PowerManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundReliabilityTest {
    @Test
    fun manufacturerNamesMapToSupportedGuidance() {
        assertEquals(DeviceManufacturer.Xiaomi, deviceManufacturer("Xiaomi"))
        assertEquals(DeviceManufacturer.Huawei, deviceManufacturer("HUAWEI"))
        assertEquals(DeviceManufacturer.Oppo, deviceManufacturer("OPPO"))
        assertEquals(DeviceManufacturer.Vivo, deviceManufacturer("vivo"))
        assertEquals(DeviceManufacturer.Other, deviceManufacturer("Google"))
    }

    @Test
    fun guidanceUsesBrandSpecificPathAndGenericFallback() {
        assertEquals(
            "设置 → 应用和服务 → 应用启动管理",
            DeviceManufacturer.Huawei.autoStartGuidance
        )
        assertEquals(
            "请在系统应用设置中允许后台运行或自启动",
            DeviceManufacturer.Other.autoStartGuidance
        )
    }

    @Test
    fun autoStartRouteUsesManufacturerEntryBeforeApplicationDetailsFallback() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val intents = BackgroundReliability.autoStartIntents(
            context = context,
            manufacturer = DeviceManufacturer.Xiaomi
        )

        assertEquals(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            intents.first().component?.flattenToString()
        )
        assertEquals(
            "android.settings.APPLICATION_DETAILS_SETTINGS",
            intents.last().action
        )
        assertEquals("package:${context.packageName}", intents.last().dataString)
    }

    @Test
    fun everySupportedManufacturerHasAnAutoStartRouteAndApplicationDetailsFallback() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val expectedPackages = mapOf(
            DeviceManufacturer.Xiaomi to "com.miui.securitycenter",
            DeviceManufacturer.Huawei to "com.huawei.systemmanager",
            DeviceManufacturer.Oppo to "com.coloros.safecenter",
            DeviceManufacturer.Vivo to "com.vivo.permissionmanager"
        )

        expectedPackages.forEach { (manufacturer, packageName) ->
            val intents = BackgroundReliability.autoStartIntents(context, manufacturer)
            assertEquals(packageName, intents.first().component?.packageName)
            assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS", intents.last().action)
        }
        val otherIntents = BackgroundReliability.autoStartIntents(
            context,
            DeviceManufacturer.Other
        )
        assertEquals(1, otherIntents.size)
        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS", otherIntents.single().action)
    }

    @Test
    fun unavailablePreferredEntryFallsBackToApplicationDetails() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intents = BackgroundReliability.autoStartIntents(context, DeviceManufacturer.Xiaomi)

        val selected = firstResolvableSettingsIntent(intents) { intent ->
            intent.action == "android.settings.APPLICATION_DETAILS_SETTINGS"
        }

        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS", selected?.action)
    }

    @Test
    fun settingIntentListsKeepTheirSafeFallbacks() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertEquals(
            "android.settings.APPLICATION_DETAILS_SETTINGS",
            BackgroundReliability.backgroundRunningIntents(context).first().action
        )
        assertEquals(
            "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS",
            BackgroundReliability.batteryOptimizationIntents(context).first().action
        )
        assertEquals(
            "android.settings.BATTERY_SAVER_SETTINGS",
            BackgroundReliability.batterySaverIntents(context).first().action
        )
        assertTrue(
            BackgroundReliability.batteryOptimizationIntents(context).any {
                it.action == "android.settings.APPLICATION_DETAILS_SETTINGS"
            }
        )
    }

    @Test
    fun launchFailureFallsBackToApplicationDetailsAndSwallowsFinalFailure() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferred = BackgroundReliability.autoStartIntents(
            context,
            DeviceManufacturer.Xiaomi
        ).first()
        val fallback = BackgroundReliability.applicationDetailsIntent(context)
        val attemptedActions = mutableListOf<String?>()

        launchSettingsIntent(
            intents = listOf(preferred, fallback),
            fallback = fallback,
            canResolve = { true },
            launch = { intent ->
                attemptedActions += intent.action
                throw IllegalStateException("system settings unavailable")
            }
        )

        assertEquals(
            listOf(null, "android.settings.APPLICATION_DETAILS_SETTINGS"),
            attemptedActions
        )
    }

    @Test
    fun readMapsBatteryOptimizationAndPowerSaveState() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val powerManager = context.getSystemService(PowerManager::class.java)
        val shadowPowerManager = Shadows.shadowOf(powerManager)
        shadowPowerManager.setIgnoringBatteryOptimizations(context.packageName, true)
        shadowPowerManager.setIsPowerSaveMode(true)

        val state = BackgroundReliability.read(context)

        assertTrue(state.batteryOptimizationIgnored)
        assertTrue(state.powerSaveModeEnabled)
        shadowPowerManager.setIgnoringBatteryOptimizations(context.packageName, false)
        shadowPowerManager.setIsPowerSaveMode(false)
        val refreshedState = BackgroundReliability.read(context)
        assertFalse(refreshedState.batteryOptimizationIgnored)
        assertFalse(refreshedState.powerSaveModeEnabled)
    }
}
