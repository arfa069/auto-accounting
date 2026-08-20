package com.bks.backend.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
internal class InMemoryAccountWechatStore(
    state: InMemoryAccountState
) : InMemoryAccountStoreComponent(state), AccountWechatStore {
    override fun findWechatIdentityByOpenid(appId: String, openid: String): StoredWechatIdentity? {
        return wechatIdentities.values.find { it.appId == appId && it.openid == openid }
    }

    override fun findWechatIdentityByUnionid(unionid: String): StoredWechatIdentity? {
        if (unionid.isBlank()) return null
        return wechatIdentities.values.find { it.unionid == unionid }
    }

    override fun findWechatIdentityByAccountId(accountId: Long): StoredWechatIdentity? {
        return wechatIdentities[accountId]
    }

    override fun claimWechatIdentity(
        identity: StoredWechatIdentity
    ): WechatIdentityClaimResult = synchronized(state.lock) {
        val existingIdentity = wechatIdentities[identity.accountId]
            ?: findWechatIdentityByOpenid(identity.appId, identity.openid)
            ?: identity.unionid?.let(::findWechatIdentityByUnionid)
        if (existingIdentity != null) {
            return WechatIdentityClaimResult.Conflict(existingIdentity)
        }
        wechatIdentities[identity.accountId] = identity
        return WechatIdentityClaimResult.Claimed
    }

    override fun upsertWechatIdentity(identity: StoredWechatIdentity) {
        wechatIdentities[identity.accountId] = identity
    }

    override fun deleteWechatIdentity(accountId: Long) {
        wechatIdentities.remove(accountId)
    }

    override fun createOneTimeTicket(ticket: StoredOneTimeTicket) {
        oneTimeTickets[ticket.ticketHash] = ticket
    }

    override fun findOneTimeTicket(ticketHash: String): StoredOneTimeTicket? {
        return oneTimeTickets[ticketHash]
    }

    override fun markOneTimeTicketUsed(ticketHash: String, usedAtMillis: Long): Boolean {
        val ticket = oneTimeTickets[ticketHash] ?: return false
        if (ticket.usedAtMillis != null || ticket.expiresAtMillis < usedAtMillis) return false
        oneTimeTickets[ticketHash] = ticket.copy(usedAtMillis = usedAtMillis)
        return true
    }
}

