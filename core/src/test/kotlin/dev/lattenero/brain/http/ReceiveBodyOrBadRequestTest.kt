package dev.lattenero.brain.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * The receive law of the HTTP routes: a body that fails to decode answers a
 * 400 naming the expected shape, but a cancelled receive propagates the
 * cancellation — a disconnected client must never read a misleading 400 and
 * the route's coroutine must unwind.
 */
class ReceiveBodyOrBadRequestTest {
    @Test
    fun `a cancellation propagates and never answers a 400`() {
        assertThatThrownBy {
            runBlocking {
                receiveBodyOrBadRequest(
                    "body must be {\"query\": string}",
                    receive = { throw CancellationException("client gone") },
                    badRequest = { fail<Unit>("a cancelled call must not answer a 400: $it") },
                )
            }
        }.isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `a decode failure answers the 400 with the hint and the reason`() {
        runBlocking {
            var answered: String? = null
            val request: String? = receiveBodyOrBadRequest(
                "body must be {\"query\": string}",
                receive = { throw IllegalArgumentException("k must be positive") },
                badRequest = { answered = it },
            )
            assertThat(request).isNull()
            assertThat(answered).isEqualTo("body must be {\"query\": string}: k must be positive")
        }
    }

    @Test
    fun `a body that decodes is returned untouched`() {
        runBlocking {
            val request = receiveBodyOrBadRequest(
                "hint",
                receive = { 42 },
                badRequest = { fail<Unit>("must not answer a good body: $it") },
            )
            assertThat(request).isEqualTo(42)
        }
    }
}
