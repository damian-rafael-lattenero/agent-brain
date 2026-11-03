package dev.lattenero.brain

import dev.lattenero.brain.bus.BusPipeline
import dev.lattenero.brain.chat.ChatProvider
import dev.lattenero.brain.chat.ChatProviders
import dev.lattenero.brain.memory.MemoryExtractor
import dev.lattenero.brain.memory.MemoryService
import dev.lattenero.brain.memory.MemoryStore
import dev.lattenero.brain.memory.MemoryWorker
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.embeddings.EmbeddingProvider
import dev.lattenero.brain.embeddings.EmbeddingProviders
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

const val VERSION = "0.1.0"

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
    val rag: RagService = RagService(provider, ragStore)
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
            // successThreshold; see EmbedPipeline.DEFAULT_BREAKER_SUCCESS_THRESHOLD).
            successThreshold = EmbedPipeline.DEFAULT_BREAKER_SUCCESS_THRESHOLD,
        ),
    )
    val indexer: Indexer = Indexer(
        store = ragStore,
        embed = embedPipeline,
        tracer = tracer,
        docConcurrency = config.indexDocConcurrency,
    )
    val memoryStore: MemoryStore = MemoryStore(db)
    val extractor: MemoryExtractor = MemoryExtractor(
        chat = chat,
        timeout = config.chatTimeoutS.seconds,
        tracer = tracer,
        breaker = CircuitBreaker(
            maxFailures = config.embedBreakerFailures,
            retryAfter = config.embedBreakerResetS.seconds,
            // The same F79e lesson as the embed breaker above (the knobs are shared).
            successThreshold = EmbedPipeline.DEFAULT_BREAKER_SUCCESS_THRESHOLD,
        ),
    )
    val memoryWorker: MemoryWorker = MemoryWorker(memoryStore, extractor, embedPipeline)
    val memory: MemoryService = MemoryService(provider, memoryStore, extractor, config.memoryHalfLifeDays)

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
