package com.autoaccounting.backend.account

internal const val SMS_SCOPE_PHONE = "phone"
internal const val SMS_SCOPE_DEVICE = "device"
internal const val SMS_SCOPE_IP = "ip"
internal const val SMS_RATE_LIMIT_MILLIS = 60_000L
internal const val SMS_HOUR_MILLIS = 60 * 60_000L
internal const val SMS_DAY_MILLIS = 24 * 60 * 60_000L
internal const val SMS_CODE_TTL_MILLIS = 5 * 60_000L
internal const val MAX_SMS_CODE_FAILURES = 3
internal const val MAX_LOGIN_FAILURES = 5
internal const val MAX_NICKNAME_LENGTH = 20
internal const val MAX_AVATAR_BYTES = 256 * 1024
internal const val MAX_AVATAR_BASE64_LENGTH = 350_000
internal val AVATAR_DATA_PREFIXES = listOf("data:image/jpeg;base64,", "data:image/png;base64,")
internal val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
internal const val SMS_PURPOSE_DEFAULT = "DEFAULT"
internal const val SMS_PURPOSE_WECHAT_LINK = "WECHAT_LINK"
internal const val SMS_PURPOSE_WECHAT_UNLINK = "WECHAT_UNLINK"
internal const val PURPOSE_REGISTER = "REGISTER"
internal const val PURPOSE_RECOVERY = "RECOVERY"
internal const val PURPOSE_IDENTIFIER_LINK = "IDENTIFIER_LINK"
internal const val LOGIN_LOCK_MILLIS = 15 * 60_000L
internal const val ACCOUNT_DELETION_COOLING_OFF_MILLIS = 7 * 24 * 60 * 60 * 1_000L

@Suppress("LongParameterList")
internal class AccountServiceContext(
    val store: AccountStore,
    val smsProvider: SmsProvider,
    val smsCodeGenerator: () -> String,
    val emailProvider: EmailProvider,
    val emailCodeGenerator: () -> String,
    val tokenGenerator: () -> String,
    val verificationCodeHasher: VerificationCodeHasher,
    val clock: MutableClock,
    val wechatOAuthClient: WechatOAuthClient
)

internal abstract class AccountServiceComponent(
    context: AccountServiceContext
) {
    protected val store = context.store
    protected val smsProvider = context.smsProvider
    protected val smsCodeGenerator = context.smsCodeGenerator
    protected val emailProvider = context.emailProvider
    protected val emailCodeGenerator = context.emailCodeGenerator
    protected val tokenGenerator = context.tokenGenerator
    protected val verificationCodeHasher = context.verificationCodeHasher
    protected val clock = context.clock
    protected val wechatOAuthClient = context.wechatOAuthClient
}
