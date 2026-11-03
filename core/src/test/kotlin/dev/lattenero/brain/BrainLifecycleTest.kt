package dev.lattenero.brain

import dev.lattenero.brain.chat.FakeChatProvider
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.config.ConfigException
import dev.lattenero.brain.embeddings.FakeProvider
import io.ktor.client.request.get
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.SQLException
import java.util.Collections

/**
 * The A1 law: every owned component of the composition is released — in
 * reverse acquisition order — on a normal exit, on a failure inside the use
 * block, on cancellation, and when a later acquisition fails. The observer
 * records each release; the assertions also prove the pool and the HTTP
 * client are really closed, not just observed.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BrainLifecycleTest {
    private val releases = Collections.synchronizedList(mutableListOf<String>())

    @BeforeAll
    fun up() = TestEnv.needPostgres()

    @BeforeEach
    fun reset() = releases.clear()

    private fun resource(config: Config) =
        Brain.resource(config, FakeProvider(config.embedDim), FakeChatProvider(), releases::add)

    private val releaseOrder = listOf(
        Brain.COMPONENT_CHAT,
        Brain.COMPONENT_PROVIDER,
        Brain.COMPONENT_BUS,
        Brain.COMPONENT_HTTP,
        Brain.COMPONENT_DATABASE,
    )

    @Test
    fun `a normal exit releases every component in reverse acquisition order`() = runBlocking<Unit> {
        val brain = resource(TestDatabase.config("lifecycleok")).use { it }
        assertThat(releases).containsExactlyElementsOf(releaseOrder)
        assertClosed(brain)
    }

    @Test
    fun `a failure inside the use block still releases every component`() {
        var captured: Brain? = null
        assertThatThrownBy {
            runBlocking {
                resource(TestDatabase.config("lifecyclefail")).use { brain ->
                    captured = brain
                    throw IllegalStateException("boom")
                }
            }
        }.hasMessage("boom")
        assertThat(releases).containsExactlyElementsOf(releaseOrder)
        assertClosed(captured!!)
    }

    @Test
    fun `cancellation releases every component`() = runBlocking<Unit> {
        val started = CompletableDeferred<Brain>()
        val job = launch(Dispatchers.Default) {
            resource(TestDatabase.config("lifecyclecancel")).use { brain ->
                started.complete(brain)
                awaitCancellation()
            }
        }
        val brain = started.await()
        job.cancelAndJoin()
        assertThat(releases).containsExactlyElementsOf(releaseOrder)
        assertClosed(brain)
    }

    @Test
    fun `an acquisition failure releases the components already acquired`() {
        val config = TestDatabase.config("lifecycleacquire", extra = mapOf("AB_CHAT_PROVIDER" to "bogus"))
        assertThatThrownBy {
            // No chat override: the factory itself refuses the bogus provider.
            runBlocking { Brain.resource(config, FakeProvider(config.embedDim), onRelease = releases::add).use { } }
        }.isInstanceOf(ConfigException::class.java)
        assertThat(releases).containsExactly(
            Brain.COMPONENT_PROVIDER,
            Brain.COMPONENT_BUS,
            Brain.COMPONENT_HTTP,
            Brain.COMPONENT_DATABASE,
        )
    }

    private fun assertClosed(brain: Brain) {
        assertThatThrownBy { runBlocking { brain.db.ping() } }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { runBlocking { brain.httpClient.get("http://127.0.0.1:1/") } }
            .isInstanceOf(IllegalStateException::class.java)
    }
}
