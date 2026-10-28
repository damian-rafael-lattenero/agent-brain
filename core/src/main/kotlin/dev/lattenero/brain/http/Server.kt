package dev.lattenero.brain.http

import dev.lattenero.brain.VERSION
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.embeddings.EmbeddingProvider
import dev.lattenero.brain.embeddings.ProviderHealth
import org.slf4j.LoggerFactory
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val log = LoggerFactory.getLogger("agent-brain")

@Serializable
data class DbCheck(
    val ok: Boolean,
    val applied: Int = 0,
    val pending: List<String> = emptyList(),
    val embedDim: Int? = null,
    val expectedEmbedDim: Int,
    val detail: String? = null,
)

@Serializable
data class Checks(val db: DbCheck, val embed: ProviderHealth)

@Serializable
data class ReadyResponse(val status: String, val version: String, val checks: Checks) {
    val ok: Boolean get() = status == STATUS_OK

    companion object {
        const val STATUS_OK = "ok"
        const val STATUS_DEGRADED = "degraded"
    }
}

@Serializable
data class HealthResponse(val status: String, val version: String, val config: JsonObject)

/**
 * /ready exercises every dependency for real: migrations applied, embed_dim
 * agreeing with the database, the provider actually embedding. A green
 * /ready on an empty stack is the P0 definition of done.
 */
class Readiness(
    private val config: Config,
    private val db: Database,
    private val migrator: Migrator,
    private val provider: EmbeddingProvider,
) {
    suspend fun check(): ReadyResponse {
        val dbCheck = try {
            val state = migrator.status()
            val pingable = db.ping()
            val dimOk = state.embedDim == config.embedDim
            DbCheck(
                ok = pingable && state.pending.isEmpty() && dimOk,
                applied = state.applied.size,
                pending = state.pending,
                embedDim = state.embedDim,
                expectedEmbedDim = config.embedDim,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DbCheck(ok = false, expectedEmbedDim = config.embedDim, detail = "${e::class.simpleName}: ${e.message}")
        }
        val embedCheck = provider.health()
        val ok = dbCheck.ok && embedCheck.ok
        return ReadyResponse(
            status = if (ok) ReadyResponse.STATUS_OK else ReadyResponse.STATUS_DEGRADED,
            version = VERSION,
            checks = Checks(dbCheck, embedCheck),
        )
    }
}

val responseJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

fun Application.brainModule(config: Config, readiness: Readiness) {
    install(ContentNegotiation) { json(responseJson) }
    routing {
        get("/health") {
            call.respond(HealthResponse(ReadyResponse.STATUS_OK, VERSION, config.snapshot()))
        }
        get("/ready") {
            val report = readiness.check()
            val status = if (report.ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respond(status, report)
        }
    }
}

object BrainServer {
    fun start(config: Config, readiness: Readiness, wait: Boolean): EmbeddedServer<*, *> {
        val server = embeddedServer(CIO, host = config.httpHost, port = config.httpPort) {
            brainModule(config, readiness)
        }
        log.info("agent-brain $VERSION listening on ${config.httpHost}:${config.httpPort}")
        return server.start(wait = wait)
    }
}
