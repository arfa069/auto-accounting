package com.bks.backend.ai

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal data class ProviderHttpResponse(
    val statusCode: Int,
    val body: String
)

internal fun interface ProviderHttpTransport {
    suspend fun post(
        uri: URI,
        headers: Map<String, String>,
        body: String,
        requestTimeout: Duration
    ): ProviderHttpResponse
}

internal class JdkProviderHttpTransport(
    private val ioDispatcher: CoroutineDispatcher,
    private val sendRequest: (HttpRequest) -> HttpResponse<String>
) : ProviderHttpTransport {
    constructor(
        connectTimeout: Duration,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        ioDispatcher = ioDispatcher,
        sendRequest = createJdkHttpSender(connectTimeout)
    )

    override suspend fun post(
        uri: URI,
        headers: Map<String, String>,
        body: String,
        requestTimeout: Duration
    ): ProviderHttpResponse = runInterruptible(ioDispatcher) {
        val requestBuilder = HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
        headers.forEach(requestBuilder::header)
        val request = requestBuilder
            .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
            .build()
        try {
            val response = sendRequest(request)
            ProviderHttpResponse(response.statusCode(), response.body())
        } catch (_: HttpTimeoutException) {
            throw AiProviderException.TimedOut
        } catch (_: IOException) {
            throw AiProviderException.UpstreamFailure
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("AI provider request interrupted").apply {
                initCause(interrupted)
            }
        }
    }
}

private fun createJdkHttpSender(
    connectTimeout: Duration
): (HttpRequest) -> HttpResponse<String> {
    val client = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    return { request ->
        client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
    }
}
