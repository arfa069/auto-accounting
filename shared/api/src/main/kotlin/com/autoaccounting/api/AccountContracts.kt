package com.autoaccounting.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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
    ACCOUNT_DELETION_NOT_PENDING
}

data class AccountSessionResponseContract(
    val phone: String,
    val token: String? = null,
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

object AccountApiJsonContracts {
    private val json = Json

    fun encodeSessionResponse(response: AccountSessionResponseContract): String {
        return buildJsonObject {
            put("ok", true)
            put("phone", response.phone)
            response.token?.let { put("token", it) }
            putDeletionStatus(response.deletionStatus)
        }.toString()
    }

    fun parseSessionResponse(body: String): AccountSessionResponseContract {
        val root = parseSuccessfulRoot(body)
        return AccountSessionResponseContract(
            phone = root.requiredString("phone"),
            token = root["token"]?.jsonPrimitive?.contentOrNull,
            deletionStatus = root.parseDeletionStatus()
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
