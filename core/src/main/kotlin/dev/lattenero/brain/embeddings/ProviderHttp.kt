package dev.lattenero.brain.embeddings

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.io.IOException

/** How much of an error body a provider message previews. */
const val PROVIDER_ERROR_TAIL = 200

private fun providerHttpError(label: String, url: String, status: HttpStatusCode, tail: String): String =
    "$label $url -> HTTP ${status.value}: $tail"

/**
 * The ONE provider HTTP translation (reuse-first: it replaced four copies
 * across the chat and embedding providers). Transport failures become
 * [ProviderUnavailable] named by [transportLabel]; a non-OK status becomes
 * the exception [onError] builds from the url, the status and the body
 * preview — providers overlay their special wording (the ollama 404 "not
 * pulled" hint, the Z.ai 401/403 "rejected the key", see [ollamaErrors] and
 * [glmErrors]) and fall back to the generic message otherwise. The error
 * body is read only on failure and previewed to [PROVIDER_ERROR_TAIL] chars.
 */
suspend fun postForProvider(
    client: HttpClient,
    url: String,
    transportLabel: String,
    onError: (url: String, status: HttpStatusCode, tail: String) -> ProviderException,
    request: HttpRequestBuilder.() -> Unit,
): HttpResponse {
    val response = postOrUnavailable(client, url, transportLabel, request)
    val status = response.status
    if (status == HttpStatusCode.OK) return response
    val tail = response.bodyAsText().take(PROVIDER_ERROR_TAIL)
    throw onError(url, status, tail)
}

/** The transport edge: connection refused and timeouts are [ProviderUnavailable], never raw socket errors. */
private suspend fun postOrUnavailable(
    client: HttpClient,
    url: String,
    transportLabel: String,
    request: HttpRequestBuilder.() -> Unit,
): HttpResponse =
    try {
        client.post(url) {
            contentType(ContentType.Application.Json)
            request()
        }
    } catch (e: HttpRequestTimeoutException) {
        throw ProviderUnavailable("$transportLabel timed out at $url", e)
    } catch (e: IOException) {
        throw ProviderUnavailable("$transportLabel unreachable at $url (${e.message})", e)
    }

/**
 * The ollama error translation (message-compatible with the four copies it
 * replaced): 404 means the model is not pulled; every other status the
 * generic "<label> <url> -> HTTP <n>" message.
 */
fun ollamaErrors(
    model: String,
    wrap: (String) -> ProviderException,
): (String, HttpStatusCode, String) -> ProviderException =
    { url, status, tail ->
        val message = when (status) {
            HttpStatusCode.NotFound -> "ollama model '$model' is not pulled (ollama pull $model): $tail"
            else -> providerHttpError("ollama", url, status, tail)
        }
        wrap(message)
    }

/** The Z.ai error translation: 401/403 name the rejected key; every other status the generic message. */
fun glmErrors(
    wrap: (String) -> ProviderException,
): (String, HttpStatusCode, String) -> ProviderException =
    { url, status, tail ->
        val message = when (status) {
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
                "glm rejected the key (HTTP ${status.value}): $tail"
            else -> providerHttpError("glm", url, status, tail)
        }
        wrap(message)
    }
