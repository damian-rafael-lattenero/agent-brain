package dev.lattenero.brain.http

import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.time.measureTime

/**
 * The 16/11 replay-cascade law: a chat request may legitimately run for
 * minutes (a giant chunk generating), so the client MUST cut it at exactly
 * the timeout [HttpClients.create] was given — never at some shared default
 * (the shared 60 s client fired first and made `chat_timeout_s` dead config).
 */
class HttpClientsTest {
    @Test
    fun `the client cuts a request at the timeout create() was given`() {
        // A server that accepts the connection and NEVER answers: the only
        // way out is the request timeout itself.
        val server = ServerSocket(0)
        val staller = thread(isDaemon = true) {
            while (!server.isClosed) {
                runCatching { server.accept().use { socket -> socket.getInputStream().read() } }
            }
        }
        try {
            val client = HttpClients.create(1)
            val elapsed = measureTime {
                assertThatThrownBy { runBlocking { client.get("http://127.0.0.1:${server.localPort}/") } }
            }
            // Cut at ~1 s. The shared default (60 s) would have hung 60x longer;
            // even a generous bound proves the knob reached the request, not
            // just the config file.
            assertThat(elapsed.inWholeSeconds).isLessThan(20L)
        } finally {
            server.close()
            staller.interrupt()
        }
    }
}
