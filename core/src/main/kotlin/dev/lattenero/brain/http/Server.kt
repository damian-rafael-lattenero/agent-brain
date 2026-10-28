package dev.lattenero.brain.http

import dev.lattenero.brain.VERSION
import dev.lattenero.brain.bus.BusPipeline
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.embeddings.EmbeddingProvider
import dev.lattenero.brain.embeddings.ProviderHealth
import dev.lattenero.brain.memory.MemoryRequestException
import dev.lattenero.brain.memory.MemorySaveRequest
import dev.lattenero.brain.memory.MemorySearchRequest
import dev.lattenero.brain.memory.MemoryService
import dev.lattenero.brain.rag.IndexRequest
import dev.lattenero.brain.rag.Indexer
import dev.lattenero.brain.rag.QueryRequest
import dev.lattenero.brain.rag.RagQueryException
import dev.lattenero.brain.rag.RagService
import org.slf4j.LoggerFactory
import io.ktor.http.HttpStatusCode
import kap.KapTypeSafe
import kap.evalGraph
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
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
@KapTypeSafe
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
    /** The two probes are independent, so the typed builder runs them in parallel. */
    suspend fun check(): ReadyResponse {
        val checks = kap(::Checks)
            .with { db from dbCheck() }
            .with { embed from provider.health() }
            .evalGraph()
        val ok = checks.db.ok && checks.embed.ok
        return ReadyResponse(
            status = if (ok) ReadyResponse.STATUS_OK else ReadyResponse.STATUS_DEGRADED,
            version = VERSION,
            checks = checks,
        )
    }

    private suspend fun dbCheck(): DbCheck = try {
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
}

val responseJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

@Serializable
data class ErrorResponse(val error: String)

fun Application.brainModule(config: Config, readiness: Readiness, bus: BusPipeline? = null, rag: RagService? = null, indexer: Indexer? = null, memory: MemoryService? = null) {
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
        if (bus != null) {
            get("/bus/status") { call.respond(bus.status()) }
        }
        if (rag != null) {
            post("/rag/query") {
                val request = try {
                    call.receive<QueryRequest>()
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("body must be {\"query\": string, \"k\"?: int}: ${e.message}"))
                }
                try {
                    call.respond(rag.query(request))
                } catch (e: RagQueryException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad query"))
                }
            }
            get("/rag/stats") { call.respond(rag.stats()) }
        }
        if (memory != null) {
            post("/memory/search") {
                val request = try {
                    call.receive<MemorySearchRequest>()
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("body must be {\"query\": string, \"k\"?: int}: ${e.message}"))
                }
                try {
                    call.respond(memory.search(request))
                } catch (e: MemoryRequestException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad request"))
                }
            }
            post("/memory/save") {
                val request = try {
                    call.receive<MemorySaveRequest>()
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("body must be {\"kind\": string, \"content\": string}: ${e.message}"))
                }
                try {
                    call.respond(HttpStatusCode.Created, memory.save(request))
                } catch (e: MemoryRequestException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad request"))
                }
            }
            get("/memory/recent") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: MemoryService.DEFAULT_K
                val kind = call.request.queryParameters["kind"]
                try {
                    call.respond(memory.recent(limit, kind))
                } catch (e: MemoryRequestException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad request"))
                }
            }
            get("/memory/quarantine") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: MemoryService.DEFAULT_K
                call.respond(memory.quarantined(limit))
            }
            get("/memory/status") { call.respond(memory.status()) }
        }
        if (indexer != null) {
            post("/rag/index") {
                val request = try {
                    call.receive<IndexRequest>()
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("body must be {\"globs\": [string]}: ${e.message}"))
                }
                val globs = request.globs.ifEmpty { readCorpusFile(config) }
                if (globs.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("no globs given and ${config.corpusFile} is empty or missing"))
                try {
                    call.respond(indexer.indexGlobs(globs))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad globs"))
                }
            }
        }
    }
}

fun readCorpusFile(config: Config): List<String> =
    if (java.nio.file.Files.exists(config.corpusFile)) {
        dev.lattenero.brain.rag.Corpus().parseGlobs(java.nio.file.Files.readString(config.corpusFile))
    } else {
        emptyList()
    }

object BrainServer {
    fun start(config: Config, readiness: Readiness, bus: BusPipeline?, rag: RagService?, indexer: Indexer?, memory: MemoryService?, wait: Boolean): EmbeddedServer<*, *> {
        val server = embeddedServer(CIO, host = config.httpHost, port = config.httpPort) {
            brainModule(config, readiness, bus, rag, indexer, memory)
        }
        log.info("agent-brain $VERSION listening on ${config.httpHost}:${config.httpPort}")
        return server.start(wait = wait)
    }
}
