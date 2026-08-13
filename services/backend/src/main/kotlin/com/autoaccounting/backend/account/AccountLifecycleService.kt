package com.autoaccounting.backend.account


import com.autoaccounting.api.AccountDeletionStatusContract
import com.autoaccounting.api.AccountSessionResponseContract
import com.autoaccounting.api.TICKET_VALIDITY_MILLIS
import com.autoaccounting.api.WechatAuthResultContract
import com.autoaccounting.api.WechatExchangeResponseContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64

@Suppress("TooManyFunctions")
internal class AccountLifecycleService(
    context: AccountServiceContext,
    private val sessionService: AccountSessionService
) : AccountServiceComponent(context) {


    fun updateNickname(token: String, nickname: String): AccountResult<AccountToken> {
        val normalizedNickname = nickname.trim()
        if (normalizedNickname.isBlank() || normalizedNickname.length > MAX_NICKNAME_LENGTH) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        val verified = sessionService.verifyToken(token)
        if (verified is AccountResult.Failure) return verified
        val current = (verified as AccountResult.Success).value
        store.upsertProfile(
            StoredAccountProfile(
                accountId = current.accountId,
                nickname = normalizedNickname,
                avatarUrl = current.avatarUrl,
                updatedAtMillis = clock.millis()
            )
        )
        return sessionService.verifyToken(token)
    }

    fun updateAvatar(token: String, avatarDataUrl: String): AccountResult<AccountToken> {
        if (!isValidAvatarDataUrl(avatarDataUrl)) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        val verified = sessionService.verifyToken(token)
        if (verified is AccountResult.Failure) return verified
        val current = (verified as AccountResult.Success).value
        store.upsertProfile(
            StoredAccountProfile(
                accountId = current.accountId,
                nickname = current.nickname,
                avatarUrl = avatarDataUrl,
                updatedAtMillis = clock.millis()
            )
        )
        return sessionService.verifyToken(token)
    }

    private fun isValidAvatarDataUrl(value: String): Boolean {
        val prefix = AVATAR_DATA_PREFIXES.firstOrNull(value::startsWith) ?: return false
        val encoded = value.substring(prefix.length)
        if (encoded.isBlank() || encoded.length > MAX_AVATAR_BASE64_LENGTH) return false
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return false
        if (bytes.isEmpty() || bytes.size > MAX_AVATAR_BYTES) return false
        return when (prefix) {
            "data:image/jpeg;base64," -> bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
            "data:image/png;base64," -> bytes.size >= PNG_SIGNATURE.size &&
                PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }
            else -> false
        }
    }

    fun signOut(token: String): AccountResult<Unit> {
        val verified = sessionService.verifyToken(token)
        if (verified is AccountResult.Failure) return verified
        store.deleteSession(hashToken(token))
        return AccountResult.Success(Unit)
    }

    fun registeredDevices(accountId: Long): List<StoredRegisteredDevice> {
        return store.registeredDevices(accountId)
    }

    fun requestAccountDeletion(token: String): AccountResult<AccountDeletionStatus> {
        val verified = sessionService.verifiedAccount(token)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val account = store.findAccount(verified.accountId)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val requestedAt = account.deletionRequestedAtMillis ?: clock.millis()
        store.updateAccountDeletionRequestedAt(account.accountId, requestedAt)
        val phone = verified.phone
        return AccountResult.Success(account.deletionStatus(phone, requestedAt))
    }

    fun getAccountDeletionStatus(token: String): AccountResult<AccountDeletionStatus?> {
        val verified = sessionService.verifiedAccount(token)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val account = store.findAccount(verified.accountId)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val phone = sessionService.phoneIdentifier(account.accountId)
        return AccountResult.Success(
            account.deletionRequestedAtMillis?.let { requestedAt ->
                account.deletionStatus(phone, requestedAt)
            }
        )
    }

    fun cancelAccountDeletion(token: String): AccountResult<Unit> {
        val verified = sessionService.verifiedAccount(token)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val account = store.findAccount(verified.accountId)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        if (account.deletionRequestedAtMillis == null) {
            return AccountResult.Failure(AccountError.ACCOUNT_DELETION_NOT_PENDING)
        }
        if (!store.cancelAccountDeletion(account.accountId)) {
            return AccountResult.Failure(AccountError.ACCOUNT_DELETION_NOT_PENDING)
        }
        return AccountResult.Success(Unit)
    }

    fun writeCloudConfiguration(accountId: Long): AccountResult<Unit> {
        val account = store.findAccount(accountId)
            ?: return AccountResult.Failure(AccountError.PHONE_NOT_REGISTERED)
        if (account.deletionRequestedAtMillis != null) {
            return AccountResult.Failure(AccountError.ACCOUNT_DELETION_PENDING)
        }
        return AccountResult.Success(Unit)
    }

    fun canWriteCloudData(accountId: Long): Boolean {
        val account = store.findAccount(accountId) ?: return false
        return account.deletionRequestedAtMillis == null
    }

    fun accountsDueForDeletion(): List<Long> {
        val now = clock.millis()
        val cutoffMillis = now - ACCOUNT_DELETION_COOLING_OFF_MILLIS
        return store.accountsPendingDeletion()
            .filter { account ->
                account.deletionClaimedAtMillis != null ||
                    store.claimAccountDeletion(account.accountId, cutoffMillis, now)
            }
            .map { it.accountId }
    }

    fun finalizeAccountDeletion(accountId: Long): Boolean {
        val account = store.findAccount(accountId) ?: return false
        val requestedAt = account.deletionRequestedAtMillis ?: return false
        if (account.deletionClaimedAtMillis == null) return false
        if (clock.millis() < requestedAt + ACCOUNT_DELETION_COOLING_OFF_MILLIS) return false
        store.deleteAccount(accountId)
        return true
    }




}

