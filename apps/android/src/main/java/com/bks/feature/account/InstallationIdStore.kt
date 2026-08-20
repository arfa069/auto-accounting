package com.bks.feature.account

import android.content.Context
import java.util.UUID

internal class InstallationIdStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        INSTALLATION_ID_PREFERENCES,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun getOrCreate(): String {
        val existing = preferences.getString(INSTALLATION_ID_KEY, null)
            ?.takeIf(::isUuid)
        if (existing != null) return existing

        val generated = UUID.randomUUID().toString()
        preferences.edit().putString(INSTALLATION_ID_KEY, generated).commit()
        return generated
    }

    private fun isUuid(value: String): Boolean = runCatching {
        UUID.fromString(value).toString() == value
    }.getOrDefault(false)

    private companion object {
        const val INSTALLATION_ID_PREFERENCES = "account_installation"
        const val INSTALLATION_ID_KEY = "installation_id"
    }
}
