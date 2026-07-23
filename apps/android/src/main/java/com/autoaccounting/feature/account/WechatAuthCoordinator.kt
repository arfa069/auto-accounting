package com.autoaccounting.feature.account

import android.content.Context
import android.content.Intent
import com.autoaccounting.MainActivity
import com.tencent.mm.opensdk.constants.Build
import com.tencent.mm.opensdk.modelmsg.SendAuth
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64

enum class WechatAuthPurpose {
    SignInOrRegister,
    LinkCurrentAccount
}

sealed interface WechatAuthLaunchResult {
    data object Started : WechatAuthLaunchResult
    data object AgreementRequired : WechatAuthLaunchResult
    data object NotConfigured : WechatAuthLaunchResult
    data object NotInstalled : WechatAuthLaunchResult
    data object VersionUnsupported : WechatAuthLaunchResult
    data object SendFailed : WechatAuthLaunchResult
}

sealed interface WechatAuthCallback {
    data class Authorized(
        val code: String,
        val purpose: WechatAuthPurpose
    ) : WechatAuthCallback

    data class Cancelled(val purpose: WechatAuthPurpose) : WechatAuthCallback
    data class Denied(val purpose: WechatAuthPurpose) : WechatAuthCallback
    data class Failed(val purpose: WechatAuthPurpose) : WechatAuthCallback
}

interface WechatAuthGateway {
    fun startAuthorization(purpose: WechatAuthPurpose): WechatAuthLaunchResult
}

class WechatAuthCoordinator(
    private val gateway: WechatAuthGateway
) {
    fun startAuthorization(
        agreementAccepted: Boolean,
        purpose: WechatAuthPurpose
    ): WechatAuthLaunchResult {
        if (!agreementAccepted) return WechatAuthLaunchResult.AgreementRequired
        return gateway.startAuthorization(purpose)
    }
}

class AndroidWechatAuthGateway(
    context: Context,
    private val appId: String,
    private val stateStore: WechatAuthStateStore = WechatAuthStateStore(context.applicationContext)
) : WechatAuthGateway {
    private val applicationContext = context.applicationContext

    override fun startAuthorization(purpose: WechatAuthPurpose): WechatAuthLaunchResult {
        if (appId.isBlank()) return WechatAuthLaunchResult.NotConfigured

        // OpenSDK is created and registered only for an explicit, agreement-approved request.
        val api = WXAPIFactory.createWXAPI(applicationContext, appId, false)
        if (!api.registerApp(appId)) return WechatAuthLaunchResult.SendFailed
        if (!api.isWXAppInstalled) return WechatAuthLaunchResult.NotInstalled
        if (api.wxAppSupportAPI < Build.OPENID_SUPPORTED_SDK_INT) {
            return WechatAuthLaunchResult.VersionUnsupported
        }

        val requestState = stateStore.begin(purpose)
        val request = SendAuth.Req().apply {
            scope = WECHAT_AUTH_SCOPE
            state = requestState
        }
        if (!api.sendReq(request)) {
            stateStore.clear(requestState)
            return WechatAuthLaunchResult.SendFailed
        }
        return WechatAuthLaunchResult.Started
    }

    private companion object {
        const val WECHAT_AUTH_SCOPE = "snsapi_userinfo"
    }
}

class WechatAuthStateStore(
    context: Context,
    private val clock: Clock = Clock.systemUTC(),
    private val stateGenerator: () -> String = ::secureWechatState
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun begin(purpose: WechatAuthPurpose): String {
        val state = stateGenerator()
        preferences.edit()
            .putString(KEY_STATE, state)
            .putString(KEY_PURPOSE, purpose.name)
            .putLong(KEY_EXPIRES_AT, clock.millis() + REQUEST_TTL_MILLIS)
            .apply()
        return state
    }

    fun consume(responseState: String?): WechatAuthPurpose? {
        val expectedState = preferences.getString(KEY_STATE, null) ?: return null
        val expiresAt = preferences.getLong(KEY_EXPIRES_AT, 0L)
        if (clock.millis() > expiresAt) {
            clear(expectedState)
            return null
        }
        val suppliedState = responseState?.takeIf(String::isNotBlank) ?: return null
        if (!MessageDigest.isEqual(expectedState.toByteArray(), suppliedState.toByteArray())) {
            return null
        }
        val purpose = preferences.getString(KEY_PURPOSE, null)
            ?.let { runCatching { WechatAuthPurpose.valueOf(it) }.getOrNull() }
        clear(expectedState)
        return purpose
    }

    fun clear(expectedState: String) {
        val storedState = preferences.getString(KEY_STATE, null) ?: return
        if (MessageDigest.isEqual(storedState.toByteArray(), expectedState.toByteArray())) {
            preferences.edit().clear().apply()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "wechat_auth_request"
        const val KEY_STATE = "state"
        const val KEY_PURPOSE = "purpose"
        const val KEY_EXPIRES_AT = "expires_at"
        const val REQUEST_TTL_MILLIS = 5 * 60_000L
    }
}

internal enum class WechatSdkAuthError {
    None,
    Cancelled,
    Denied,
    Other
}

internal data class WechatSdkAuthResponse(
    val code: String?,
    val state: String?,
    val error: WechatSdkAuthError
)

internal class WechatAuthCallbackProcessor(
    private val stateStore: WechatAuthStateStore
) {
    fun process(response: WechatSdkAuthResponse): WechatAuthCallback? {
        val purpose = stateStore.consume(response.state) ?: return null
        return when (response.error) {
            WechatSdkAuthError.None -> response.code
                ?.takeIf(String::isNotBlank)
                ?.let { WechatAuthCallback.Authorized(it, purpose) }
                ?: WechatAuthCallback.Failed(purpose)
            WechatSdkAuthError.Cancelled -> WechatAuthCallback.Cancelled(purpose)
            WechatSdkAuthError.Denied -> WechatAuthCallback.Denied(purpose)
            WechatSdkAuthError.Other -> WechatAuthCallback.Failed(purpose)
        }
    }
}

object WechatAuthCallbackIntent {
    const val ACTION = "com.autoaccounting.action.WECHAT_AUTH_CALLBACK"
    private const val EXTRA_STATUS = "com.autoaccounting.extra.WECHAT_STATUS"
    private const val EXTRA_CODE = "com.autoaccounting.extra.WECHAT_CODE"
    private const val EXTRA_PURPOSE = "com.autoaccounting.extra.WECHAT_PURPOSE"

    fun create(context: Context, callback: WechatAuthCallback): Intent {
        val (status, purpose, code) = when (callback) {
            is WechatAuthCallback.Authorized -> Triple("authorized", callback.purpose, callback.code)
            is WechatAuthCallback.Cancelled -> Triple("cancelled", callback.purpose, null)
            is WechatAuthCallback.Denied -> Triple("denied", callback.purpose, null)
            is WechatAuthCallback.Failed -> Triple("failed", callback.purpose, null)
        }
        return Intent(context, MainActivity::class.java).apply {
            action = ACTION
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_PURPOSE, purpose.name)
            code?.let { putExtra(EXTRA_CODE, it) }
        }
    }

    fun consume(intent: Intent?): WechatAuthCallback? {
        if (intent?.action != ACTION) return null
        val purpose = intent.getStringExtra(EXTRA_PURPOSE)
            ?.let { runCatching { WechatAuthPurpose.valueOf(it) }.getOrNull() }
            ?: return null
        val callback = when (intent.getStringExtra(EXTRA_STATUS)) {
            "authorized" -> intent.getStringExtra(EXTRA_CODE)
                ?.takeIf(String::isNotBlank)
                ?.let { WechatAuthCallback.Authorized(it, purpose) }
            "cancelled" -> WechatAuthCallback.Cancelled(purpose)
            "denied" -> WechatAuthCallback.Denied(purpose)
            "failed" -> WechatAuthCallback.Failed(purpose)
            else -> null
        }
        intent.removeExtra(EXTRA_CODE)
        intent.removeExtra(EXTRA_STATUS)
        intent.removeExtra(EXTRA_PURPOSE)
        intent.action = null
        return callback
    }
}

private fun secureWechatState(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
