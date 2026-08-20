package com.bks.feature.account

import android.content.Context

internal const val LOCAL_MODE_SESSION_PREFERENCES = "local_mode_session"

internal class LocalModeSessionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        LOCAL_MODE_SESSION_PREFERENCES,
        Context.MODE_PRIVATE
    )

    fun restoreSession(): AccountSession? =
        AccountSession.LocalMode.takeIf {
            preferences.getBoolean(LOCAL_MODE_CONFIRMED_KEY, false)
        }

    fun confirmLocalMode(): Boolean = preferences.edit()
        .putBoolean(LOCAL_MODE_CONFIRMED_KEY, true)
        .commit()

    private companion object {
        const val LOCAL_MODE_CONFIRMED_KEY = "confirmed"
    }
}

internal fun signOutToLocalMode(sessionStore: LocalModeSessionStore): AccountSession {
    sessionStore.confirmLocalMode()
    return AccountSession.LocalMode
}
