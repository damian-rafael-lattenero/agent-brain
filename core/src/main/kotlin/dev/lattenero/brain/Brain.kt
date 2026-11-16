package dev.lattenero.brain

import dev.lattenero.brain.bus.BusPipeline
import dev.lattenero.brain.chat.ChatProvider
import dev.lattenero.brain.chat.ChatProviders
import dev.lattenero.brain.memory.EdgeBackfill
import dev.lattenero.brain.memory.EdgeBuilder
import dev.lattenero.brain.memory.MemoryExtractor
import dev.lattenero.brain.memory.MemoryGraphStore
import dev.lattenero.brain.memory.MemoryReconciler
import dev.lattenero.brain.memory.MemoryService
import dev.lattenero.brain.memory.MemoryStore
import dev.lattenero.brain.memory.MemoryWorker
import dev.lattenero.brain.memory.VerdictJudge
import dev.lattenero.brain.memory.WorkerParams
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.embeddings.EmbeddingProvider
import dev.lattenero.brain.embeddings.EmbeddingProviders
import dev.lattenero.brain.embeddings.ProviderResilience
import dev.lattenero.brain.eval.EvalLedger
import dev.lattenero.brain.http.HttpClients
import dev.lattenero.brain.http.Readiness
import dev.lattenero.brain.rag.CountingTracer
import dev.lattenero.brain.rag.EmbedPipeline
import dev.lattenero.brain.rag.Indexer
import dev.lattenero.brain.rag.RagService
import dev.lattenero.brain.rag.RagStore
import kap.CircuitBreaker
import kap.Resource
import kap.evalGraph
import kap.zip
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.HttpClient

const val VERSION = "0.3.0"

/**
 * The composition root: every long-lived object is built here, once, from
 * the injected [Config]. The lifecycle is the [resource] graph: the owned
 * components are zipped so their release runs in reverse acquisition order
 * on success, on failure and on cancellation (the Resource contract wraps
 * every release in NonCancellable). Tests may hand in their own provider
 * (fake, or one that fails on purpose).
 */
class Brain private constructor(
    val config: Config,
    val db: Database,
    val httpClient: HttpClient,
    val provider: EmbeddingProvider,
    val chat: ChatProvider,
    val bus: BusPipeline,
) {
    val migrator: Migrator = Migrator(db, config.embedDim)
    val readiness: Readiness = Readiness(config, db, migrator, provider)
    val ragStore: RagStore = RagStore(db)

    /**
     * E3's split-provider: verdicts, rerank and dream ride [judgeChat] — a
     * different, stronger model when `judge_provider` is configured (they are
     * low-frequency, quality-critical calls), the primary chat otherwise. The
     * extractor stays on the primary: the high-volume private path.
     * Not an owned resource: providers hold no client of their own (the shared
     * [httpClient] outlives them) and close() is a no-op for both shapes.
     */
    val judgeChat: ChatProvider = ChatProviders.judge(config, httpClient) ?: chat
    val rag: RagService = RagService(provider, ragStore, judgeChat)
    val tracer: CountingTracer = CountingTracer()
    val embedPipeline: EmbedPipeline = EmbedPipeline(
        provider = provider,
        batchSize = config.embedBatch,
        concurrency = config.embedConcurrency,
        timeout = config.httpTimeoutS.seconds,
        tracer = tracer,
        breaker = CircuitBreaker(
            maxFailures = config.embedBreakerFailures,
            retryAfter = config.embedBreakerResetS.seconds,
            // The Python F79e lesson: the circuit closes on the SECOND
            // consecutive HalfOpen success, not the first (kap 5.0's
            // successThreshold; see ProviderResilience.DEFAULT_BREAKER_SUCCESS_THRESHOLD).
            successThreshold = ProviderResilience.DEFAULT_BREAKER_SUCCESS_THRESHOLD,
        ),
    )
    val indexer: Indexer = Indexer(
        store = ragStore,
        embed = embedPipeline,
        tracer = tracer,
        docConcurrency = config.indexDocConcurrency,
    )
    val memoryStore: MemoryStore = MemoryStore(db)

    /** B4's memory graph: the memory_edges table's IO, the edge builder and the backfill over it. */
    val memoryGraph: MemoryGraphStore = MemoryGraphStore(db)
    val edgeBuilder: EdgeBuilder = EdgeBuilder(memoryGraph, config.memoryHalfLifeDays)
    val edgeBackfill: EdgeBackfill = EdgeBackfill(db)

    /** The eval ledger: reads for `/eval/latest` and the CLI, writes from every eval run. */
    val evalLedger: EvalLedger = EvalLedger(db, config.embedDim)

    val extractor: MemoryExtractor = MemoryExtractor(
        chat = chat,
        timeout = config.chatTimeoutS.seconds,
        tracer = tracer,
        breaker = CircuitBreaker(
            maxFailures = config.embedBreakerFailures,
            retryAfter = config.embedBreakerResetS.seconds,
            // The same F79e lesson as the embed breaker above (the knobs are shared).
            successThreshold = ProviderResilience.DEFAULT_BREAKER_SUCCESS_THRESHOLD,
        ),
    )
    /** The verdict machinery: ONE chat call per judged pair, one timeout, no retry (the write path never blocks). */
    val verdictJudge: VerdictJudge = VerdictJudge(judgeChat, config.judgeTimeoutS.seconds)
    val memoryReconciler: MemoryReconciler = MemoryReconciler(memoryStore, verdictJudge)
    val memoryWorker: MemoryWorker = MemoryWorker(
        store = memoryStore,
        extractor = extractor,
        embed = embedPipeline,
        reconciler = memoryReconciler,
        params = WorkerParams(
            extractionConcurrency = config.extractionConcurrency,
            halfLifeDays = config.memoryHalfLifeDays,
        ),
        edges = edgeBuilder,
    )
    val memory: MemoryService = MemoryService(
        provider = provider,
        store = memoryStore,
        extractor = extractor,
        halfLifeDays = config.memoryHalfLifeDays,
        graph = memoryGraph,
        edges = edgeBuilder,
    )

    companion object {
        const val COMPONENT_DATABASE = "database"
        const val COMPONENT_HTTP = "http-client"
        const val COMPONENT_PROVIDER = "embedding-provider"
        const val COMPONENT_CHAT = "chat-provider"

        /**
         * The whole composition as one [Resource]. Acquisition is ordered
         * (pool, HTTP client, providers, bus) and release is its mirror: chat,
         * embedding provider, HTTP client, pool. The bus owns no resource —
         * its long-lived loops are Processes under the serve scope (Phase A2)
         * and its counters are kap `Ref` state — so its step in the chain
         * releases nothing and records nothing in the observer; it only needs
         * a suspend context to build the pipeline. [onRelease] observes each
         * component after its close ran; it is transparent and cannot change
         * the lifecycle.
         */
        fun resource(
            config: Config,
            providerOverride: EmbeddingProvider? = null,
            chatOverride: ChatProvider? = null,
            onRelease: (component: String) -> Unit = {},
        ): Resource<Brain> {
            val database = component(
                name = COMPONENT_DATABASE,
                onRelease = onRelease,
                acquire = { Database(config) },
                close = { it.close() },
            )
            val http = component(
                name = COMPONENT_HTTP,
                onRelease = onRelease,
                acquire = { HttpClients.create(config.httpTimeoutS) },
                close = { it.close() },
            )
            return Resource.zip(database, http) { db, client -> db to client }
                .andThen { (db, client) ->
                    val provider = component(
                        name = COMPONENT_PROVIDER,
                        onRelease = onRelease,
                        acquire = { providerOverride ?: EmbeddingProviders.create(config, client) },
                        close = { it.close() },
                    )
                    val chat = component(
                        name = COMPONENT_CHAT,
                        onRelease = onRelease,
                        acquire = { chatOverride ?: ChatProviders.create(config, client) },
                        close = { it.close() },
                    )
                    val bus = Resource(
                        acquire = { BusPipeline.of(config, db).evalGraph() },
                        release = { },
                    )
                    Resource.zip(provider, chat, bus) { providerValue, chatValue, busPipeline ->
                        Brain(config, db, client, providerValue, chatValue, busPipeline)
                    }
                }
        }

        /** One owned component: [close] always runs, the observer only records it. */
        private fun <A> component(
            name: String,
            onRelease: (String) -> Unit,
            acquire: () -> A,
            close: (A) -> Unit,
        ): Resource<A> = Resource(
            acquire = { acquire() },
            release = { value ->
                try {
                    close(value)
                } finally {
                    onRelease(name)
                }
            },
        )
    }
}
