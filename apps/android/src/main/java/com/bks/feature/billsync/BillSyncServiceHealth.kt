package com.bks.feature.billsync

import android.content.Context
import android.content.SharedPreferences

object BillSyncServiceHealth {
    fun isServiceConnected(context: Context): Boolean =
        preferences(context).getBoolean(SERVICE_CONNECTED_KEY, false)

    fun markServiceConnected(context: Context, connected: Boolean) {
        preferences(context).edit()
            .putBoolean(SERVICE_CONNECTED_KEY, connected)
            .apply()
    }

    fun registerListener(
        context: Context,
        onChanged: (Boolean) -> Unit
    ): SharedPreferences.OnSharedPreferenceChangeListener {
        val preferences = preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SERVICE_CONNECTED_KEY) {
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

    private fun preferences(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private const val PREFERENCES_NAME = "bill_sync_service_health"
    private const val SERVICE_CONNECTED_KEY = "service_connected"
}
