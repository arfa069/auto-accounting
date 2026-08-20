package com.bks.backend.account

import java.security.SecureRandom
import com.bks.api.WechatExchangeResponseContract

@Suppress("TooManyFunctions", "LongParameterList")
class AccountService(
    store: AccountStore = InMemoryAccountStore(),
    smsProvider: SmsProvider = NoopSmsProvider,
    smsCodeGenerator: () -> String = { "%06d".format(SecureRandom().nextInt(1_000_000)) },
    emailProvider: EmailProvider = NoopEmailProvider,
    emailCodeGenerator: () -> String = { "%06d".format(SecureRandom().nextInt(1_000_000)) },
    tokenGenerator: () -> String = { secureToken() },
    verificationCodeHasher: VerificationCodeHasher = VerificationCodeHasher.random(),
    clock: MutableClock = MutableClock(),
    wechatOAuthClient: WechatOAuthClient = DefaultWechatOAuthClient()
) {
    private val context = AccountServiceContext(
        store, smsProvider, smsCodeGenerator, emailProvider, emailCodeGenerator,
        tokenGenerator, verificationCodeHasher, clock, wechatOAuthClient
    )
    private val sessionService = AccountSessionService(context)
    private val verificationCodeService = VerificationCodeService(context, sessionService)
    private val identifierService = IdentifierAccountService(context, verificationCodeService, sessionService)
    private val wechatService = WechatAccountService(context, verificationCodeService, sessionService)
    private val lifecycleService = AccountLifecycleService(context, sessionService)
    fun exchangeWechatCode(
        code: String,
        bearerToken: String? = null,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<WechatExchangeResponseContract> = wechatService.exchangeWechatCode(code, bearerToken, deviceId, ipAddress)

    fun registerWithWechat(
        wechatTicket: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> = wechatService.registerWithWechat(wechatTicket, deviceId, ipAddress)

    fun linkWechatWithPassword(
        wechatTicket: String,
        identifier: String,
        password: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> = wechatService.linkWechatWithPassword(wechatTicket, identifier, password, deviceId, ipAddress)

    fun linkWechatWithCode(
        wechatTicket: String,
        identifier: String,
        code: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> = wechatService.linkWechatWithCode(wechatTicket, identifier, code, deviceId, ipAddress)

    fun unlinkWechatWithPassword(
        bearerToken: String,
        password: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> = wechatService.unlinkWechatWithPassword(bearerToken, password, deviceId, ipAddress)

    fun unlinkWechatWithCode(
        bearerToken: String,
        identifier: String,
        code: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> = wechatService.unlinkWechatWithCode(bearerToken, identifier, code, deviceId, ipAddress)

    fun prepareMergeWithIdentifierPassword(
        bearerToken: String,
        identifier: String,
        password: String
    ): AccountResult<com.bks.api.MergePreviewResponseContract> = wechatService.prepareMergeWithIdentifierPassword(bearerToken, identifier, password)

    fun confirmMerge(
        bearerToken: String,
        mergeTicket: String,
        confirmText: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> = wechatService.confirmMerge(bearerToken, mergeTicket, confirmText, deviceId, ipAddress)

    fun issueVerificationCode(
        identifier: String,
        deviceId: String,
        ipAddress: String,
        purpose: String = SMS_PURPOSE_DEFAULT,
        contextKey: String? = null,
        bearerToken: String? = null
    ): AccountResult<Unit> = verificationCodeService.issueVerificationCode(identifier, deviceId, ipAddress, purpose, contextKey, bearerToken)

    fun registerIdentifier(
        identifier: String,
        code: String?,
        password: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> = identifierService.registerIdentifier(identifier, code, password, deviceId, ipAddress)

    fun loginIdentifier(
        identifier: String,
        password: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> = identifierService.loginIdentifier(identifier, password, deviceId, ipAddress)

    fun recoverPasswordByIdentifier(
        identifier: String,
        code: String,
        newPassword: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> = identifierService.recoverPasswordByIdentifier(identifier, code, newPassword, deviceId, ipAddress)

    fun prepareIdentifierLink(
        bearerToken: String,
        identifier: String,
        deviceId: String = "",
        ipAddress: String = "",
        replaceExisting: Boolean = false
    ): AccountResult<com.bks.api.IdentifierLinkPrepareResponseContract> = identifierService.prepareIdentifierLink(bearerToken, identifier, deviceId, ipAddress, replaceExisting)

    fun confirmIdentifierLink(
        bearerToken: String,
        linkTicket: String,
        code: String,
        deviceId: String = "",
        ipAddress: String = "",
        password: String? = null
    ): AccountResult<AccountToken> = identifierService.confirmIdentifierLink(bearerToken, linkTicket, code, deviceId, ipAddress, password)

    fun verifyToken(token: String): AccountResult<AccountToken> = sessionService.verifyToken(token)

    fun updateNickname(token: String, nickname: String): AccountResult<AccountToken> = lifecycleService.updateNickname(token, nickname)

    fun updateAvatar(token: String, avatarDataUrl: String): AccountResult<AccountToken> = lifecycleService.updateAvatar(token, avatarDataUrl)

    fun signOut(token: String): AccountResult<Unit> = lifecycleService.signOut(token)

    fun registeredDevices(accountId: Long): List<StoredRegisteredDevice> = lifecycleService.registeredDevices(accountId)

    fun requestAccountDeletion(token: String): AccountResult<AccountDeletionStatus> = lifecycleService.requestAccountDeletion(token)

    fun getAccountDeletionStatus(token: String): AccountResult<AccountDeletionStatus?> = lifecycleService.getAccountDeletionStatus(token)

    fun cancelAccountDeletion(token: String): AccountResult<Unit> = lifecycleService.cancelAccountDeletion(token)

    fun writeCloudConfiguration(accountId: Long): AccountResult<Unit> = lifecycleService.writeCloudConfiguration(accountId)

    fun canWriteCloudData(accountId: Long): Boolean = lifecycleService.canWriteCloudData(accountId)

    fun accountsDueForDeletion(): List<Long> = lifecycleService.accountsDueForDeletion()

    fun finalizeAccountDeletion(accountId: Long): Boolean = lifecycleService.finalizeAccountDeletion(accountId)

    fun verifyVerificationCode(
        identifierType: String,
        normalizedIdentifier: String,
        code: String,
        expectedPurpose: String = SMS_PURPOSE_DEFAULT,
        expectedContextKey: String? = null
    ): AccountResult<StoredVerificationCode> = verificationCodeService.verifyVerificationCode(identifierType, normalizedIdentifier, code, expectedPurpose, expectedContextKey)

    fun advanceTimeBy(millis: Long) = context.clock.advanceBy(millis)
    companion object {
        const val ACCOUNT_DELETION_COOLING_OFF_MILLIS = 7 * 24 * 60 * 60 * 1_000L

        fun fromEnvironment(env: Map<String, String> = System.getenv()): AccountService {
            val jdbcConfig = JdbcAccountStore.configFromEnvironment(env)
                ?: error("BKS_DATABASE_URL is required for backend account persistence.")
            val authPepper = env["BKS_AUTH_PEPPER"].orEmpty()
            require(authPepper.length >= 32) {
                "BKS_AUTH_PEPPER must contain at least 32 characters."
            }
            return AccountService(
                store = JdbcAccountStore(jdbcConfig.jdbcUrl, jdbcConfig.username, jdbcConfig.password),
                smsProvider = SmsProvider.fromEnvironment(env),
                emailProvider = SmtpEmailProvider.fromEnvironment(env),
                verificationCodeHasher = VerificationCodeHasher.fromSecret(authPepper),
                wechatOAuthClient = WechatOAuthClient.fromEnvironment(env)
            )
        }
    }
}
