package com.autoaccounting.feature.monitoring

import android.content.Context
import android.content.SharedPreferences

object ContinuousMonitoringServiceHealth {
    fun isServiceConnected(
        context: Context,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val preferences = preferences(context)
        if (!preferences.getBoolean(ACCESSIBILITY_SERVICE_CONNECTED_KEY, false)) {
            return false
        }
        val lastHeartbeatEpochMillis = preferences.getLong(
            ACCESSIBILITY_SERVICE_HEARTBEAT_EPOCH_MILLIS_KEY,
            0
        )
        return nowEpochMillis - lastHeartbeatEpochMillis in 0..SERVICE_CONNECTION_TIMEOUT_MILLIS
    }

    fun markServiceConnected(
        context: Context,
        connected: Boolean,
        nowEpochMillis: Long = System.currentTimeMillis()
    ) {
        preferences(context).edit()
            .putBoolean(ACCESSIBILITY_SERVICE_CONNECTED_KEY, connected)
            .putLong(
                ACCESSIBILITY_SERVICE_HEARTBEAT_EPOCH_MILLIS_KEY,
                nowEpochMillis.takeIf { connected } ?: 0
            )
            .apply()
    }

    fun registerListener(
        context: Context,
        onChanged: (Boolean) -> Unit
    ): SharedPreferences.OnSharedPreferenceChangeListener {
        val preferences = preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ACCESSIBILITY_SERVICE_CONNECTED_KEY) {
                onChanged(isServiceConnected(context))
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun unregisterListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        preferences(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun preferences(context: Context): SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private const val PREFERENCES_NAME = "continuous_monitoring_service_health"
    private const val ACCESSIBILITY_SERVICE_CONNECTED_KEY = "accessibility_service_connected"
    private const val ACCESSIBILITY_SERVICE_HEARTBEAT_EPOCH_MILLIS_KEY =
        "accessibility_service_heartbeat_epoch_millis"
}

internal const val SERVICE_HEARTBEAT_INTERVAL_MILLIS = 30_000L
internal const val SERVICE_CONNECTION_TIMEOUT_MILLIS = 90_000L
