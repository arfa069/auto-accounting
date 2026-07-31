package com.autoaccounting.backend.account

enum class AccountError(
    val message: String
) {
    INVALID_REQUEST("请求信息不完整或格式不正确"),
    PHONE_ALREADY_REGISTERED("该手机号已注册，请直接登录"),
    PHONE_NOT_REGISTERED("该手机号尚未注册，请先创建账号"),
    VERIFICATION_CODE_WRONG("验证码不正确，请重新输入"),
    VERIFICATION_CODE_EXPIRED("验证码已过期，请重新获取"),
    SMS_TOO_FREQUENT("获取太频繁，请稍后再试"),
    SMS_PROVIDER_UNCONFIGURED("短信服务未配置"),
    SMS_SEND_FAILED("验证码发送失败，请稍后重试"),
    IDENTIFIER_ALREADY_REGISTERED("该账号标识已注册，请直接登录"),
    IDENTIFIER_NOT_REGISTERED("该账号标识尚未注册，请先创建账号"),
    IDENTIFIER_ALREADY_LINKED("该账号标识已被其他账号绑定"),
    IDENTIFIER_CONFLICT("账号标识存在冲突"),
    EMAIL_PROVIDER_UNCONFIGURED("邮件服务未配置"),
    EMAIL_SEND_FAILED("邮件验证码发送失败，请稍后重试"),
    CODE_SEND_TOO_FREQUENT("获取太频繁，请稍后再试"),
    LOGIN_FAILED("手机号或密码不正确"),
    TOKEN_INVALID("登录状态已失效，请重新登录"),
    ACCOUNT_LOCKED("尝试次数过多，请稍后再试，或使用短信找回密码"),
    ACCOUNT_DELETION_PENDING("账号注销冷静期内，云端写入已暂停"),
    ACCOUNT_DELETION_NOT_PENDING("账号当前没有注销申请"),
    WECHAT_NOT_CONFIGURED("微信登录服务未配置"),
    WECHAT_AUTH_FAILED("微信授权失败，请重新尝试"),
    WECHAT_SERVICE_UNAVAILABLE("微信服务暂时不可用，请稍后再试"),
    TICKET_EXPIRED("操作超时，请重新发起微信授权"),
    TICKET_ALREADY_USED("此票据已被使用，请重新发起授权"),
    WECHAT_ALREADY_LINKED("此微信已被其他账号绑定"),
    PHONE_ALREADY_LINKED("此手机号已被其他账号绑定"),
    MERGE_BLOCKED("账号合并已被阻止"),
    LAST_LOGIN_METHOD_CANNOT_UNLINK("解绑失败：至少需要保留一种登录方式")
}


sealed interface AccountResult<out T> {
    data class Success<T>(val value: T) : AccountResult<T>
    data class Failure(val error: AccountError) : AccountResult<Nothing>
}

val AccountResult<*>.error: AccountError?
    get() = (this as? AccountResult.Failure)?.error

data class AccountToken(
    val accountId: Long = 0L,
    val accountUuid: String? = null,
    val primaryIdentifier: com.autoaccounting.api.AccountIdentifierContract? = null,
    val identifiers: List<com.autoaccounting.api.AccountIdentifierContract> = emptyList(),
    val phone: String? = null,
    val token: String,
    val deletionStatus: AccountDeletionStatus? = null,
    val wechatLinked: Boolean = false,
    val nickname: String? = null,
    val avatarUrl: String? = null
)

data class AccountDeletionStatus(
    val accountId: Long = 0L,
    val phone: String? = null,
    val requestedAtMillis: Long,
    val finalDeletionAtMillis: Long
)

internal data class WechatAuthTicketPayload(
    val ticketHash: String,
    val appId: String,
    val openid: String,
    val unionid: String?,
    val nickname: String?,
    val avatarUrl: String?
)
