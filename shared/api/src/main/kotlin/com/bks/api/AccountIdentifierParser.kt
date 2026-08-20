package com.bks.api

import java.util.Locale

enum class AccountIdentifierTypeContract {
    USERNAME,
    EMAIL,
    PHONE
}

data class AccountIdentifierContract(
    val type: AccountIdentifierTypeContract,
    val value: String,
    val verified: Boolean = true
)

data class AccountIdentifierParseResult(
    val type: AccountIdentifierTypeContract,
    val normalizedValue: String,
    val displayValue: String
) {
    fun toContract(verified: Boolean = true): AccountIdentifierContract =
        AccountIdentifierContract(type = type, value = displayValue, verified = verified)
}

object AccountIdentifierParser {
    private val USERNAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]{3,19}$")
    private val PHONE_REGEX = Regex("^\\d{11}$")

    fun parse(rawInput: String): AccountIdentifierParseResult {
        val trimmed = rawInput.trim()
        require(trimmed.isNotEmpty()) { "Identifier cannot be empty" }

        if (trimmed.contains("@")) {
            require(isValidEmail(trimmed)) { "Invalid email format" }
            val lower = trimmed.lowercase(Locale.ROOT)
            return AccountIdentifierParseResult(
                type = AccountIdentifierTypeContract.EMAIL,
                normalizedValue = lower,
                displayValue = lower
            )
        }

        if (trimmed.all { it.isDigit() }) {
            require(trimmed.length == 11 && PHONE_REGEX.matches(trimmed)) { "Invalid phone number format" }
            return AccountIdentifierParseResult(
                type = AccountIdentifierTypeContract.PHONE,
                normalizedValue = trimmed,
                displayValue = trimmed
            )
        }

        require(USERNAME_REGEX.matches(trimmed)) { "Invalid username format" }
        return AccountIdentifierParseResult(
            type = AccountIdentifierTypeContract.USERNAME,
            normalizedValue = trimmed.lowercase(Locale.ROOT),
            displayValue = trimmed
        )
    }

    private fun isValidEmail(email: String): Boolean {
        if (email.length > 254) return false
        val parts = email.split("@")
        if (parts.size != 2) return false
        val local = parts[0]
        val domain = parts[1]
        if (local.isEmpty() || domain.isEmpty()) return false
        if (local.startsWith(".") || local.endsWith(".") || local.contains("..")) return false
        if (domain.startsWith(".") || domain.endsWith(".") || domain.contains("..")) return false

        val domainLabels = domain.split(".")
        if (domainLabels.size < 2) return false
        for (label in domainLabels) {
            if (label.isEmpty() || label.startsWith("-") || label.endsWith("-")) return false
            if (!label.all { it.isLetterOrDigit() || it == '-' }) return false
        }

        if (!local.all { it.isLetterOrDigit() || "!#$%&'*+/=?^_`{|}~.-".contains(it) }) return false

        return true
    }
}
