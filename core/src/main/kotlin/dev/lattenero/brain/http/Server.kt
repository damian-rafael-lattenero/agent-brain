package dev.lattenero.brain.http

import dev.lattenero.brain.VERSION
import dev.lattenero.brain.bus.BusPipeline
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.embeddings.EmbeddingProvider
import dev.lattenero.brain.embeddings.ProviderHealth
import dev.lattenero.brain.eval.EvalLedger
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
import kap.ProcessHandle
import kap.evalGraph
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.http.ContentType
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
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

/**
 * Receive and decode the request body, answering a decode failure with a 400
 * that names the expected shape ([hint]). Cancellation is not a decode
 * failure: the CE arm rethrows first, so a disconnected client never reads a
 * misleading 400 and the route's coroutine unwinds cleanly. [receive] and
 * [badRequest] are the effect edges, so the law is provable without an HTTP
 * engine.
 */
internal suspend fun <T> receiveBodyOrBadRequest(
    hint: String,
    receive: suspend () -> T,
    badRequest: suspend (String) -> Unit,
): T? = try {
    receive()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    badRequest("$hint: ${e.message}")
    null
}

/** The same law at the routes, one line per endpoint: decode, or answer the 400. */
private suspend inline fun <reified T> ApplicationCall.receiveBodyOrBadRequest(hint: String): T? =
    receiveBodyOrBadRequest(hint, { receive<T>() }, { respond(HttpStatusCode.BadRequest, ErrorResponse(it)) })

/**
 * The same law for the handler body (was: five copies in the routes below):
 * run [block], answering [failureType] as a 400 carrying the exception's
 * message (or [hint] when it has none). Cancellation is not a bad request:
 * the CE arm rethrows first, so a cancelled route unwinds and a disconnected
 * client never reads a misleading 400, and any other exception is a bug that
 * propagates to the envelope, not a 400. [badRequest] is the effect edge, so
 * the law is provable without an HTTP engine.
 */
internal suspend fun runOrBadRequest(
    failureType: Class<out Throwable>,
    hint: String,
    badRequest: suspend (String) -> Unit,
    block: suspend () -> Unit,
): Unit =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (failureType.isInstance(e)) badRequest(e.message ?: hint) else throw e
    }

/** The same law at the routes, one line per endpoint: run the handler, or answer the 400. */
private suspend fun ApplicationCall.runOrBadRequest(
    failureType: Class<out Throwable>,
    hint: String = "bad request",
    block: suspend () -> Unit,
): Unit = runOrBadRequest(
    failureType,
    hint,
    { respond(HttpStatusCode.BadRequest, ErrorResponse(it)) },
    block,
)

fun Application.brainModule(
    config: Config,
    readiness: Readiness,
    bus: BusPipeline? = null,
    rag: RagService? = null,
    indexer: Indexer? = null,
    memory: MemoryService? = null,
    busProcess: ProcessHandle<*>? = null,
    evalLedger: EvalLedger? = null,
) {
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
        // E4: the same snapshots /bus/status, /rag/stats and /memory/status
        // answer, projected to the Prometheus text format. Pure projection —
        // no counter of its own, no cached value; a scrape re-reads the truth.
        get("/metrics") {
            val snapshot = metricsSnapshot(bus, rag, memory, busProcess)
            call.respondText(renderMetrics(snapshot), ContentType.parse(METRICS_CONTENT_TYPE))
        }
        if (bus != null) {
            get("/bus/status") { call.respond(bus.status(busProcess)) }
        }
        if (rag != null) {
            post("/rag/query") {
                val request =
                    call.receiveBodyOrBadRequest<QueryRequest>("body must be {\"query\": string, \"k\"?: int}")
                        ?: return@post
                call.runOrBadRequest(RagQueryException::class.java, "bad query") {
                    call.respond(rag.query(request))
                }
            }
            get("/rag/stats") { call.respond(rag.stats()) }
        }
        if (memory != null) {
            post("/memory/search") {
                val request =
                    call.receiveBodyOrBadRequest<MemorySearchRequest>("body must be {\"query\": string, \"k\"?: int}")
                        ?: return@post
                call.runOrBadRequest(MemoryRequestException::class.java) {
                    call.respond(memory.search(request))
                }
            }
            post("/memory/save") {
                val request =
                    call.receiveBodyOrBadRequest<MemorySaveRequest>(
                        "body must be {\"kind\": string, \"content\": string}",
                    ) ?: return@post
                call.runOrBadRequest(MemoryRequestException::class.java) {
                    call.respond(HttpStatusCode.Created, memory.save(request))
                }
            }
            get("/memory/recent") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: MemoryService.DEFAULT_K
                val kind = call.request.queryParameters["kind"]
                call.runOrBadRequest(MemoryRequestException::class.java) {
                    call.respond(memory.recent(limit, kind))
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
                val request =
                    call.receiveBodyOrBadRequest<IndexRequest>("body must be {\"globs\": [string]}")
                        ?: return@post
                val globs = request.globs.ifEmpty { readCorpusFile(config) }
                if (globs.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("no globs given and ${config.corpusFile} is empty or missing"))
                call.runOrBadRequest(IllegalArgumentException::class.java, "bad globs") {
                    call.respond(indexer.indexGlobs(globs))
                }
            }
        }
        // The eval ledger as a read route: a caller chooses a model per turn by
        // reading the last matrix (`?matrix=true`) instead of re-running evals.
        if (evalLedger != null) {
            get("/eval/latest") {
                val suite = call.request.queryParameters["suite"]
                val matrix = call.request.queryParameters["matrix"]?.toBooleanStrictOrNull() ?: false
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: EvalLedger.DEFAULT_LIMIT
                call.respond(evalLedger.latest(suite = suite, matrix = matrix, limit = limit))
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
    fun start(
        config: Config,
        readiness: Readiness,
        bus: BusPipeline?,
        rag: RagService?,
        indexer: Indexer?,
        memory: MemoryService?,
        wait: Boolean,
        busProcess: ProcessHandle<*>? = null,
        evalLedger: EvalLedger? = null,
    ): EmbeddedServer<*, *> {
        val server = embeddedServer(CIO, host = config.httpHost, port = config.httpPort) {
            brainModule(config, readiness, bus, rag, indexer, memory, busProcess, evalLedger)
        }
        log.info("agent-brain $VERSION listening on ${config.httpHost}:${config.httpPort}")
        return server.start(wait = wait)
    }
}
