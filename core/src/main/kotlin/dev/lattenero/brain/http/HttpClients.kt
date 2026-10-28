package dev.lattenero.brain.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object HttpClients {
    val json = Json { ignoreUnknownKeys = true }

    /** One outbound client per process, shared by every provider. */
    fun create(timeoutS: Int): HttpClient = HttpClient(CIO) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutS * 1_000L
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
        }
    }

    const val CONNECT_TIMEOUT_MS = 5_000L
}
