package com.autoaccounting.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class AccountErrorCodeContract {
    INVALID_REQUEST,
    PHONE_ALREADY_REGISTERED,
    PHONE_NOT_REGISTERED,
    VERIFICATION_CODE_WRONG,
    VERIFICATION_CODE_EXPIRED,
    SMS_TOO_FREQUENT,
    SMS_PROVIDER_UNCONFIGURED,
    SMS_SEND_FAILED,
    LOGIN_FAILED,
    TOKEN_INVALID,
    ACCOUNT_LOCKED,
    ACCOUNT_DELETION_PENDING,
    ACCOUNT_DELETION_NOT_PENDING,
    WECHAT_NOT_CONFIGURED,
    WECHAT_AUTH_FAILED,
    WECHAT_SERVICE_UNAVAILABLE,
    TICKET_EXPIRED,
    TICKET_ALREADY_USED,
    WECHAT_ALREADY_LINKED,
    PHONE_ALREADY_LINKED,
    MERGE_BLOCKED,
    LAST_LOGIN_METHOD_CANNOT_UNLINK
}

/** Ticket validity for WeChat auth, phone link, and merge tickets: 5 minutes. */
const val TICKET_VALIDITY_MILLIS: Long = 5 * 60 * 1000L

data class AccountSessionResponseContract(
    val phone: String? = null,
    val token: String? = null,
    val wechatLinked: Boolean = false,
    val nickname: String? = null,
    val avatarUrl: String? = null,
    val deletionStatus: AccountDeletionStatusContract = AccountDeletionStatusContract()
)

data class AccountDeletionStatusContract(
    val pending: Boolean = false,
    val requestedAtMillis: Long? = null,
    val finalDeletionAtMillis: Long? = null
) {
    init {
        require(
            !pending || (requestedAtMillis != null && finalDeletionAtMillis != null)
        ) { "Pending deletion requires both timestamps." }
    }
}

data class AccountErrorResponseContract(
    val error: String,
    val message: String
)

/**
 * Sealed class representing the three stable WeChat authentication states.
 * Each state carries exactly the fields it needs; missing required fields
 * cause explicit parse failures.
 */
sealed class WechatAuthResultContract {
    /** WeChat identity is already linked to an account; session is issued. */
    data class SignedIn(
        val session: AccountSessionResponseContract
    ) : WechatAuthResultContract()

    /** WeChat identity is not linked; client must register or link. */
    data class RegistrationRequired(
        val wechatTicket: String,
        val nickname: String?,
        val avatarUrl: String?,
        val ticketExpiresAtMillis: Long
    ) : WechatAuthResultContract()

    /** WeChat identity belongs to another account; merge is available. */
    data class MergeRequired(
        val mergeTicket: String,
        val sourceNickname: String?,
        val sourcePhone: String?,
        val ticketExpiresAtMillis: Long
    ) : WechatAuthResultContract()
}

/**
 * Response for the WeChat exchange endpoint.
 */
data class WechatExchangeResponseContract(
    val result: WechatAuthResultContract
)

/**
 * Response for the phone link prepare endpoint.
 * When the phone is unregistered, a phone ticket is returned.
 * When the phone belongs to another account, a merge ticket is returned.
 */
sealed class PhoneLinkPrepareResponseContract {
    data class PhoneTicketIssued(
        val phoneTicket: String,
        val ticketExpiresAtMillis: Long
    ) : PhoneLinkPrepareResponseContract()

    data class MergeRequired(
        val mergeTicket: String,
        val sourcePhone: String?,
        val sourceWechatLinked: Boolean,
        val ticketExpiresAtMillis: Long
    ) : PhoneLinkPrepareResponseContract()
}

/**
 * Response for the merge prepare endpoints.
 * Shows a preview of both accounts before the user confirms.
 */
data class MergePreviewResponseContract(
    val mergeTicket: String,
    val ticketExpiresAtMillis: Long,
    val currentPhone: String?,
    val currentWechatLinked: Boolean,
    val currentNickname: String?,
    val sourcePhone: String?,
    val sourceWechatLinked: Boolean,
    val sourceNickname: String?
)

object AccountApiJsonContracts {
    private val json = Json

    // -- Session response --

    fun encodeSessionResponse(response: AccountSessionResponseContract): String {
        return buildJsonObject {
            put("ok", true)
            if (response.phone != null) put("phone", response.phone) else put("phone", JsonNull)
            response.token?.let { put("token", it) }
            put("wechatLinked", response.wechatLinked)
            if (response.nickname != null) put("nickname", response.nickname) else put("nickname", JsonNull)
            if (response.avatarUrl != null) put("avatarUrl", response.avatarUrl) else put("avatarUrl", JsonNull)
            putDeletionStatus(response.deletionStatus)
        }.toString()
    }

    fun parseSessionResponse(body: String): AccountSessionResponseContract {
        val root = parseSuccessfulRoot(body)
        return AccountSessionResponseContract(
            phone = root["phone"]?.jsonPrimitive?.contentOrNull,
            token = root["token"]?.jsonPrimitive?.contentOrNull,
            wechatLinked = root["wechatLinked"]?.jsonPrimitive?.booleanOrNull ?: false,
            nickname = root["nickname"]?.jsonPrimitive?.contentOrNull,
            avatarUrl = root["avatarUrl"]?.jsonPrimitive?.contentOrNull,
            deletionStatus = root.parseDeletionStatus()
        )
    }

    // -- WeChat exchange response --

    fun encodeWechatExchangeResponse(response: WechatExchangeResponseContract): String {
        return buildJsonObject {
            put("ok", true)
            when (val r = response.result) {
                is WechatAuthResultContract.SignedIn -> {
                    put("status", "SIGNED_IN")
                    if (r.session.phone != null) put("phone", r.session.phone) else put("phone", JsonNull)
                    r.session.token?.let { put("token", it) }
                    put("wechatLinked", r.session.wechatLinked)
                    if (r.session.nickname != null) put("nickname", r.session.nickname) else put("nickname", JsonNull)
                    if (r.session.avatarUrl != null) put("avatarUrl", r.session.avatarUrl) else put("avatarUrl", JsonNull)
                    putDeletionStatus(r.session.deletionStatus)
                }
                is WechatAuthResultContract.RegistrationRequired -> {
                    put("status", "REGISTRATION_REQUIRED")
                    put("wechatTicket", r.wechatTicket)
                    if (r.nickname != null) put("nickname", r.nickname) else put("nickname", JsonNull)
                    if (r.avatarUrl != null) put("avatarUrl", r.avatarUrl) else put("avatarUrl", JsonNull)
                    put("ticketExpiresAtMillis", r.ticketExpiresAtMillis)
                }
                is WechatAuthResultContract.MergeRequired -> {
                    put("status", "MERGE_REQUIRED")
                    put("mergeTicket", r.mergeTicket)
                    if (r.sourceNickname != null) put("sourceNickname", r.sourceNickname) else put("sourceNickname", JsonNull)
                    if (r.sourcePhone != null) put("sourcePhone", r.sourcePhone) else put("sourcePhone", JsonNull)
                    put("ticketExpiresAtMillis", r.ticketExpiresAtMillis)
                }
            }
        }.toString()
    }

    fun parseWechatExchangeResponse(body: String): WechatExchangeResponseContract {
        val root = parseSuccessfulRoot(body)
        val status = root.requiredString("status")
        val result = when (status) {
            "SIGNED_IN" -> WechatAuthResultContract.SignedIn(
                session = AccountSessionResponseContract(
                    phone = root["phone"]?.jsonPrimitive?.contentOrNull,
                    token = root["token"]?.jsonPrimitive?.contentOrNull,
                    wechatLinked = root["wechatLinked"]?.jsonPrimitive?.booleanOrNull ?: false,
                    nickname = root["nickname"]?.jsonPrimitive?.contentOrNull,
                    avatarUrl = root["avatarUrl"]?.jsonPrimitive?.contentOrNull,
                    deletionStatus = root.parseDeletionStatus()
                )
            )
            "REGISTRATION_REQUIRED" -> WechatAuthResultContract.RegistrationRequired(
                wechatTicket = root.requiredString("wechatTicket"),
                nickname = root["nickname"]?.jsonPrimitive?.contentOrNull,
                avatarUrl = root["avatarUrl"]?.jsonPrimitive?.contentOrNull,
                ticketExpiresAtMillis = root.requiredLong("ticketExpiresAtMillis")
            )
            "MERGE_REQUIRED" -> WechatAuthResultContract.MergeRequired(
                mergeTicket = root.requiredString("mergeTicket"),
                sourceNickname = root["sourceNickname"]?.jsonPrimitive?.contentOrNull,
                sourcePhone = root["sourcePhone"]?.jsonPrimitive?.contentOrNull,
                ticketExpiresAtMillis = root.requiredLong("ticketExpiresAtMillis")
            )
            else -> throw IllegalArgumentException("Unknown WeChat auth status: $status")
        }
        return WechatExchangeResponseContract(result)
    }

    // -- Phone link prepare response --

    fun encodePhoneLinkPrepareResponse(response: PhoneLinkPrepareResponseContract): String {
        return buildJsonObject {
            put("ok", true)
            when (response) {
                is PhoneLinkPrepareResponseContract.PhoneTicketIssued -> {
                    put("status", "PHONE_TICKET_ISSUED")
                    put("phoneTicket", response.phoneTicket)
                    put("ticketExpiresAtMillis", response.ticketExpiresAtMillis)
                }
                is PhoneLinkPrepareResponseContract.MergeRequired -> {
                    put("status", "MERGE_REQUIRED")
                    put("mergeTicket", response.mergeTicket)
                    if (response.sourcePhone != null) put("sourcePhone", response.sourcePhone) else put("sourcePhone", JsonNull)
                    put("sourceWechatLinked", response.sourceWechatLinked)
                    put("ticketExpiresAtMillis", response.ticketExpiresAtMillis)
                }
            }
        }.toString()
    }

    fun parsePhoneLinkPrepareResponse(body: String): PhoneLinkPrepareResponseContract {
        val root = parseSuccessfulRoot(body)
        return when (val status = root.requiredString("status")) {
            "PHONE_TICKET_ISSUED" -> PhoneLinkPrepareResponseContract.PhoneTicketIssued(
                phoneTicket = root.requiredString("phoneTicket"),
                ticketExpiresAtMillis = root.requiredLong("ticketExpiresAtMillis")
            )
            "MERGE_REQUIRED" -> PhoneLinkPrepareResponseContract.MergeRequired(
                mergeTicket = root.requiredString("mergeTicket"),
                sourcePhone = root["sourcePhone"]?.jsonPrimitive?.contentOrNull,
                sourceWechatLinked = root["sourceWechatLinked"]?.jsonPrimitive?.booleanOrNull ?: false,
                ticketExpiresAtMillis = root.requiredLong("ticketExpiresAtMillis")
            )
            else -> throw IllegalArgumentException("Unknown phone link prepare status: $status")
        }
    }

    // -- Merge preview response --

    fun encodeMergePreviewResponse(response: MergePreviewResponseContract): String {
        return buildJsonObject {
            put("ok", true)
            put("mergeTicket", response.mergeTicket)
            put("ticketExpiresAtMillis", response.ticketExpiresAtMillis)
            if (response.currentPhone != null) put("currentPhone", response.currentPhone) else put("currentPhone", JsonNull)
            put("currentWechatLinked", response.currentWechatLinked)
            if (response.currentNickname != null) put("currentNickname", response.currentNickname) else put("currentNickname", JsonNull)
            if (response.sourcePhone != null) put("sourcePhone", response.sourcePhone) else put("sourcePhone", JsonNull)
            put("sourceWechatLinked", response.sourceWechatLinked)
            if (response.sourceNickname != null) put("sourceNickname", response.sourceNickname) else put("sourceNickname", JsonNull)
        }.toString()
    }

    fun parseMergePreviewResponse(body: String): MergePreviewResponseContract {
        val root = parseSuccessfulRoot(body)
        return MergePreviewResponseContract(
            mergeTicket = root.requiredString("mergeTicket"),
            ticketExpiresAtMillis = root.requiredLong("ticketExpiresAtMillis"),
            currentPhone = root["currentPhone"]?.jsonPrimitive?.contentOrNull,
            currentWechatLinked = root["currentWechatLinked"]?.jsonPrimitive?.booleanOrNull ?: false,
            currentNickname = root["currentNickname"]?.jsonPrimitive?.contentOrNull,
            sourcePhone = root["sourcePhone"]?.jsonPrimitive?.contentOrNull,
            sourceWechatLinked = root["sourceWechatLinked"]?.jsonPrimitive?.booleanOrNull ?: false,
            sourceNickname = root["sourceNickname"]?.jsonPrimitive?.contentOrNull
        )
    }

    fun encodeDeletionStatusResponse(status: AccountDeletionStatusContract): String {
        return buildJsonObject {
            put("ok", true)
            putDeletionStatus(status)
        }.toString()
    }

    fun parseDeletionStatusResponse(body: String): AccountDeletionStatusContract {
        return parseSuccessfulRoot(body).parseDeletionStatus()
    }

    fun encodeSuccessResponse(): String = """{"ok":true}"""

    fun parseSuccessResponse(body: String) {
        parseSuccessfulRoot(body)
    }

    fun encodeErrorResponse(response: AccountErrorResponseContract): String {
        return buildJsonObject {
            put("ok", false)
            put("error", response.error)
            put("message", response.message)
        }.toString()
    }

    fun parseErrorResponse(body: String): AccountErrorResponseContract {
        val root = json.parseToJsonElement(body).jsonObject
        require(!root.requiredBoolean("ok")) { "Expected an account error response." }
        return AccountErrorResponseContract(
            error = root.requiredString("error"),
            message = root.requiredString("message")
        )
    }

    private fun parseSuccessfulRoot(body: String): JsonObject {
        val root = json.parseToJsonElement(body).jsonObject
        require(root.requiredBoolean("ok")) { "Expected a successful account response." }
        return root
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putDeletionStatus(
        status: AccountDeletionStatusContract
    ) {
        put("deletionPending", status.pending)
        if (status.requestedAtMillis == null) {
            put("requestedAtMillis", JsonNull)
        } else {
            put("requestedAtMillis", status.requestedAtMillis)
        }
        if (status.finalDeletionAtMillis == null) {
            put("finalDeletionAtMillis", JsonNull)
        } else {
            put("finalDeletionAtMillis", status.finalDeletionAtMillis)
        }
    }

    private fun JsonObject.parseDeletionStatus(): AccountDeletionStatusContract {
        val pending = this["deletionPending"]?.jsonPrimitive?.boolean ?: false
        return AccountDeletionStatusContract(
            pending = pending,
            requestedAtMillis = this["requestedAtMillis"]?.jsonPrimitive?.longOrNull,
            finalDeletionAtMillis = this["finalDeletionAtMillis"]?.jsonPrimitive?.longOrNull
        )
    }
}

private fun JsonObject.requiredBoolean(name: String): Boolean {
    return getValue(name).jsonPrimitive.boolean
}

private fun JsonObject.requiredString(name: String): String {
    return getValue(name).jsonPrimitive.content
}

private fun JsonObject.requiredLong(name: String): Long {
    return getValue(name).jsonPrimitive.content.toLong()
}
