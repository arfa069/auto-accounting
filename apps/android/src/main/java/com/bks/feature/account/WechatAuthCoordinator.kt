package com.bks.feature.account

import android.content.Context
import android.content.Intent
import com.bks.MainActivity
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
    val purpose: WechatAuthPurpose
    val sessionFingerprint: String?

    data class Authorized(
        val code: String,
        override val purpose: WechatAuthPurpose,
        override val sessionFingerprint: String? = null
    ) : WechatAuthCallback

    data class Cancelled(
        override val purpose: WechatAuthPurpose,
        override val sessionFingerprint: String? = null
    ) : WechatAuthCallback

    data class Denied(
        override val purpose: WechatAuthPurpose,
        override val sessionFingerprint: String? = null
    ) : WechatAuthCallback

    data class Failed(
        override val purpose: WechatAuthPurpose,
        override val sessionFingerprint: String? = null
    ) : WechatAuthCallback
}

interface WechatAuthGateway {
    fun startAuthorization(
        purpose: WechatAuthPurpose,
        sessionFingerprint: String? = null
    ): WechatAuthLaunchResult
}

class WechatAuthCoordinator(
    private val gateway: WechatAuthGateway
) {
    fun startAuthorization(
        agreementAccepted: Boolean,
        purpose: WechatAuthPurpose,
        sessionFingerprint: String? = null
    ): WechatAuthLaunchResult {
        if (!agreementAccepted) return WechatAuthLaunchResult.AgreementRequired
        return gateway.startAuthorization(purpose, sessionFingerprint)
    }
}

class AndroidWechatAuthGateway(
    context: Context,
    private val appId: String,
    private val stateStore: WechatAuthStateStore = WechatAuthStateStore(context.applicationContext)
) : WechatAuthGateway {
    private val applicationContext = context.applicationContext

    override fun startAuthorization(
        purpose: WechatAuthPurpose,
        sessionFingerprint: String?
    ): WechatAuthLaunchResult {
        if (appId.isBlank()) return WechatAuthLaunchResult.NotConfigured

        // OpenSDK is created and registered only for an explicit, agreement-approved request.
        val api = WXAPIFactory.createWXAPI(applicationContext, appId, false)
        if (!api.registerApp(appId)) return WechatAuthLaunchResult.SendFailed
        if (!api.isWXAppInstalled) return WechatAuthLaunchResult.NotInstalled
        if (api.wxAppSupportAPI < Build.OPENID_SUPPORTED_SDK_INT) {
            return WechatAuthLaunchResult.VersionUnsupported
        }

        val requestState = stateStore.begin(purpose, sessionFingerprint)
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
    private val relayTokenGenerator: () -> String = ::secureWechatState,
    private val stateGenerator: () -> String = ::secureWechatState
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun begin(
        purpose: WechatAuthPurpose,
        sessionFingerprint: String? = null
    ): String {
        val state = stateGenerator()
        val relayToken = relayTokenGenerator()
        preferences.edit()
            .putString(KEY_STATE, state)
            .putString(KEY_PURPOSE, purpose.name)
            .putString(KEY_RELAY_TOKEN, relayToken)
            .putString(KEY_SESSION_FINGERPRINT, sessionFingerprint)
            .putLong(KEY_EXPIRES_AT, clock.millis() + REQUEST_TTL_MILLIS)
            .apply()
        return state
    }

    internal fun consumeSdkResponse(responseState: String?): WechatAuthRequestContext? {
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
            ?: return null
        val relayToken = preferences.getString(KEY_RELAY_TOKEN, null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val sessionFingerprint = preferences.getString(KEY_SESSION_FINGERPRINT, null)
        preferences.edit().remove(KEY_STATE).apply()
        return WechatAuthRequestContext(purpose, sessionFingerprint, relayToken)
    }

    internal fun consumeRelay(relayToken: String?): WechatAuthRequestContext? {
        val expectedRelayToken = preferences.getString(KEY_RELAY_TOKEN, null) ?: return null
        val expiresAt = preferences.getLong(KEY_EXPIRES_AT, 0L)
        if (clock.millis() > expiresAt) {
            clearAll()
            return null
        }
        val suppliedRelayToken = relayToken?.takeIf(String::isNotBlank) ?: return null
        if (!MessageDigest.isEqual(expectedRelayToken.toByteArray(), suppliedRelayToken.toByteArray())) {
            return null
        }
        val purpose = preferences.getString(KEY_PURPOSE, null)
            ?.let { runCatching { WechatAuthPurpose.valueOf(it) }.getOrNull() }
            ?: return null
        val context = WechatAuthRequestContext(
            purpose = purpose,
            sessionFingerprint = preferences.getString(KEY_SESSION_FINGERPRINT, null),
            relayToken = expectedRelayToken
        )
        clearAll()
        return context
    }

    fun clear(expectedState: String) {
        val storedState = preferences.getString(KEY_STATE, null) ?: return
        if (MessageDigest.isEqual(storedState.toByteArray(), expectedState.toByteArray())) {
            clearAll()
        }
    }

    private fun clearAll() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "wechat_auth_request"
        const val KEY_STATE = "state"
        const val KEY_PURPOSE = "purpose"
        const val KEY_RELAY_TOKEN = "relay_token"
        const val KEY_SESSION_FINGERPRINT = "session_fingerprint"
        const val KEY_EXPIRES_AT = "expires_at"
        const val REQUEST_TTL_MILLIS = 5 * 60_000L
    }
}

internal data class WechatAuthRequestContext(
    val purpose: WechatAuthPurpose,
    val sessionFingerprint: String?,
    val relayToken: String
)

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
    fun process(response: WechatSdkAuthResponse): WechatAuthCallbackDelivery? {
        val request = stateStore.consumeSdkResponse(response.state) ?: return null
        val callback = when (response.error) {
            WechatSdkAuthError.None -> response.code
                ?.takeIf(String::isNotBlank)
                ?.let { WechatAuthCallback.Authorized(it, request.purpose, request.sessionFingerprint) }
                ?: WechatAuthCallback.Failed(request.purpose, request.sessionFingerprint)
            WechatSdkAuthError.Cancelled -> WechatAuthCallback.Cancelled(request.purpose, request.sessionFingerprint)
            WechatSdkAuthError.Denied -> WechatAuthCallback.Denied(request.purpose, request.sessionFingerprint)
            WechatSdkAuthError.Other -> WechatAuthCallback.Failed(request.purpose, request.sessionFingerprint)
        }
        return WechatAuthCallbackDelivery(callback, request.relayToken)
    }
}

internal data class WechatAuthCallbackDelivery(
    val callback: WechatAuthCallback,
    val relayToken: String
)

object WechatAuthCallbackIntent {
    const val ACTION = "com.bks.action.WECHAT_AUTH_CALLBACK"
    private const val EXTRA_STATUS = "com.bks.extra.WECHAT_STATUS"
    private const val EXTRA_CODE = "com.bks.extra.WECHAT_CODE"
    private const val EXTRA_RELAY_TOKEN = "com.bks.extra.WECHAT_RELAY_TOKEN"

    internal fun create(context: Context, delivery: WechatAuthCallbackDelivery): Intent {
        val (status, code) = when (val callback = delivery.callback) {
            is WechatAuthCallback.Authorized -> "authorized" to callback.code
            is WechatAuthCallback.Cancelled -> "cancelled" to null
            is WechatAuthCallback.Denied -> "denied" to null
            is WechatAuthCallback.Failed -> "failed" to null
        }
        return Intent(context, MainActivity::class.java).apply {
            action = ACTION
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_RELAY_TOKEN, delivery.relayToken)
            code?.let { putExtra(EXTRA_CODE, it) }
        }
    }

    fun consume(context: Context, intent: Intent?): WechatAuthCallback? {
        if (intent?.action != ACTION) return null
        val request = WechatAuthStateStore(context.applicationContext)
            .consumeRelay(intent.getStringExtra(EXTRA_RELAY_TOKEN))
            ?: return intent.scrubWechatCallback().let { null }
        val callback = when (intent.getStringExtra(EXTRA_STATUS)) {
            "authorized" -> intent.getStringExtra(EXTRA_CODE)
                ?.takeIf(String::isNotBlank)
                ?.let { WechatAuthCallback.Authorized(it, request.purpose, request.sessionFingerprint) }
            "cancelled" -> WechatAuthCallback.Cancelled(request.purpose, request.sessionFingerprint)
            "denied" -> WechatAuthCallback.Denied(request.purpose, request.sessionFingerprint)
            "failed" -> WechatAuthCallback.Failed(request.purpose, request.sessionFingerprint)
            else -> null
        }
        intent.scrubWechatCallback()
        return callback
    }

    private fun Intent.scrubWechatCallback(): Intent = apply {
        removeExtra(EXTRA_CODE)
        removeExtra(EXTRA_STATUS)
        removeExtra(EXTRA_RELAY_TOKEN)
        action = null
    }
}

internal fun wechatSessionFingerprint(token: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

internal fun WechatAuthCallback.matchesSession(token: String): Boolean {
    val expected = sessionFingerprint ?: return false
    return MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        wechatSessionFingerprint(token).toByteArray(Charsets.UTF_8)
    )
}

private fun secureWechatState(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
