package dev.lattenero.brain.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * The handler law of the HTTP routes: the handler's typed failure answers a
 * 400 carrying its message (the hint when it has none), but a cancellation
 * propagates — a cancelled route must never answer a misleading 400 — and
 * any other exception is a bug, not a bad request.
 */
class RunOrBadRequestTest {
    @Test
    fun `a cancellation propagates and never answers a 400`() {
        assertThatThrownBy {
            runBlocking {
                runOrBadRequest(
                    IllegalArgumentException::class.java,
                    "bad request",
                    badRequest = { fail<Unit>("a cancelled route must not answer a 400: $it") },
                ) { throw CancellationException("client gone") }
            }
        }.isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `the typed failure answers the 400 with its message, the hint as fallback`() {
        runBlocking {
            val answered = mutableListOf<String>()
            runOrBadRequest(IllegalArgumentException::class.java, "bad globs", badRequest = { answered += it }) {
                throw IllegalArgumentException("glob must exist")
            }
            runOrBadRequest(IllegalArgumentException::class.java, "bad globs", badRequest = { answered += it }) {
                throw IllegalArgumentException()
            }
            assertThat(answered).containsExactly("glob must exist", "bad globs")
        }
    }

    @Test
    fun `another exception is not a bad request and rethrows`() {
        assertThatThrownBy {
            runBlocking {
                runOrBadRequest(
                    IllegalArgumentException::class.java,
                    "bad request",
                    badRequest = { fail<Unit>("must not answer a 400: $it") },
                ) { throw IllegalStateException("a bug") }
            }
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a handler that runs is untouched`() {
        runBlocking {
            var ran = false
            runOrBadRequest(IllegalArgumentException::class.java, "bad request", badRequest = { fail<Unit>(it) }) {
                ran = true
            }
            assertThat(ran).isTrue()
        }
    }
}
