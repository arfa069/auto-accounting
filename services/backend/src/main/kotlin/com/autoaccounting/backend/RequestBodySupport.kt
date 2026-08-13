package com.autoaccounting.backend

import io.ktor.http.Parameters
import io.ktor.http.parseQueryString
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

internal const val FORM_REQUEST_MAX_BYTES = 384 * 1024

internal suspend fun ApplicationCall.receiveText(maxBytes: Int): String {
    if (request.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull()?.let { it > maxBytes } == true) {
        throw PayloadTooLargeException(maxBytes.toLong())
    }
    val channel = receiveChannel()
    val bytes = channel.readRemaining(maxBytes.toLong() + 1).readByteArray()
    if (bytes.size > maxBytes) {
        channel.cancel()
        throw PayloadTooLargeException(maxBytes.toLong())
    }
    return bytes.toString(Charsets.UTF_8)
}

internal suspend fun ApplicationCall.receiveParameters(): Parameters =
    parseQueryString(receiveText(FORM_REQUEST_MAX_BYTES))
