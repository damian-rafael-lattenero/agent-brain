package dev.lattenero.brain

import dev.lattenero.brain.bus.BusPipeline
import dev.lattenero.brain.chat.ChatProvider
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kap.Meter
import kap.micrometer.MicrometerMeter
import dev.lattenero.brain.chat.ChatProviders
import dev.lattenero.brain.memory.CorrectionExtractor
import dev.lattenero.brain.memory.CorrectionStore
import dev.lattenero.brain.memory.CorrectionWorker
import dev.lattenero.brain.memory.EdgeBackfill
import dev.lattenero.brain.memory.EdgeBuilder
import dev.lattenero.brain.memory.MemoryExtractor
import dev.lattenero.brain.memory.MemoryGraphStore
import dev.lattenero.brain.memory.LegacyTranslation
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
import dev.lattenero.brain.eval.CorpusDoctor
import dev.lattenero.brain.eval.EvalLedger
import dev.lattenero.brain.eval.ShadowEval
import dev.lattenero.brain.eval.UsageEval
import dev.lattenero.brain.http.HttpClients
import dev.lattenero.brain.http.Readiness
import dev.lattenero.brain.rag.CountingTracer
import dev.lattenero.brain.rag.EmbedPipeline
import dev.lattenero.brain.rag.Indexer
import dev.lattenero.brain.rag.RagQueryLogger
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
// The constructor IS the composition root: each parameter is one owned
// component, so the list grows with the system instead of hiding parts in a
// bundle object.
@Suppress("LongParameterList")
class Brain private constructor(
    val config: Config,
    val db: Database,
    val httpClient: HttpClient,
    val chatHttpClient: HttpClient,
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
     *
     * 16/11 measured: chat calls are the LONG ones (a giant chunk generates
     * for minutes), so they cannot ride the shared [httpClient] — its
     * `http_timeout_s` request timeout (60 s) fired first and made
     * `chat_timeout_s` dead config: every "ollama chat timed out" in the
     * replay cascade was the 60 s client cut, not the 120 s knob. Chat and
     * judge therefore hold the dedicated [chatHttpClient] (built from
     * `chat_timeout_s`), an owned resource closed on release; the shared
     * client keeps the fast legs (embeddings, Main's outbound HTTP).
     */
    val judgeChat: ChatProvider = ChatProviders.judge(config, chatHttpClient) ?: chat
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

    /**
     * The application-specific eval's two parts: the production rag_query log
     * (written at the HTTP boundary; the in-process eval CLI never lands
     * there) and its judge-graded report — the one number the curated suites
     * cannot give.
     */
    val ragQueryLog: RagQueryLogger = RagQueryLogger(db)
    val usageEval: UsageEval = UsageEval(db, judgeChat, embedModel = provider.model, embedDim = config.embedDim)

    /**
     * The corpus doctor (plan v2 B6): the usage eval's closing half — the
     * graded failures of the rag_query log, judge-classified into repair
     * proposals. READ-ONLY: it proposes (report + artifacts), the owner
     * applies, the nightly verifies.
     */
    val corpusDoctor: CorpusDoctor = CorpusDoctor(db, judgeChat)

    /**
     * The English-corpus migration (the owner's call 19/11): the legacy
     * SPANISH memories translate to English in place, batch per judge call,
     * re-embedded with the local embedder. One-shot — new memories are born
     * English since the extractor's reinforced instruction (f9e93c2).
     */
    val legacyTranslation: LegacyTranslation = LegacyTranslation(
        db, judgeChat,
        embed = { text -> provider.embed(listOf(text)).single() },
        embedModel = provider.model,
    )

    /**
     * The shadow judge (plan v2 T1): the bot's completed turns, rubric-graded
     * daily. The cursor state file lives in the config's state dir, next to
     * the other honest state.
     */
    val shadowEval: ShadowEval = ShadowEval(
        db, judgeChat,
        cursorFile = ShadowEval.cursorFile(config),
        embedModel = provider.model, embedDim = config.embedDim,
    )

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

    /**
     * The Meter SPI (kap 5.4.0): the brain's in-process cycle counters ride the
     * standard door, rendered by the Prometheus registry into /metrics next to
     * the snapshot families. The registry is the only new state; every counter
     * is a fact of a worker cycle the DB or the log never shows.
     */
    val metricsRegistry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val meter: Meter = MicrometerMeter(metricsRegistry)
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
        meter = meter,
    )
    val memory: MemoryService = MemoryService(
        provider = provider,
        store = memoryStore,
        extractor = extractor,
        halfLifeDays = config.memoryHalfLifeDays,
        graph = memoryGraph,
        edges = edgeBuilder,
    )

    /**
     * U8, the owner's corrections: the durable rows, the extractor (the SAME
     * primary chat and breaker knobs as the memory extractor — no new config
     * surface) and the worker that proposes one rule sentence per pending
     * row. Nothing here writes a KB, a hook or the corpus: the acceptance is
     * the CLI's status flip, the proposal lives in the row.
     */
    val correctionStore: CorrectionStore = CorrectionStore(db)
    val correctionExtractor: CorrectionExtractor = CorrectionExtractor(
        chat = chat,
        timeout = config.chatTimeoutS.seconds,
        tracer = tracer,
        breaker = CircuitBreaker(
            maxFailures = config.embedBreakerFailures,
            retryAfter = config.embedBreakerResetS.seconds,
            // The same F79e lesson as the extract breaker above (the knobs are shared).
            successThreshold = ProviderResilience.DEFAULT_BREAKER_SUCCESS_THRESHOLD,
        ),
    )
    val correctionWorker: CorrectionWorker = CorrectionWorker(correctionStore, correctionExtractor)

    companion object {
        const val COMPONENT_DATABASE = "database"
        const val COMPONENT_HTTP = "http-client"
        const val COMPONENT_CHAT_HTTP = "chat-http-client"
        const val COMPONENT_PROVIDER = "embedding-provider"
        const val COMPONENT_CHAT = "chat-provider"

        /**
         * The whole composition as one [Resource]. Acquisition is ordered
         * (pool, HTTP client, chat HTTP client, providers, bus) and release is
         * its mirror: chat, embedding provider, chat HTTP client, HTTP client,
         * pool. The bus owns no resource —
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
                    val chatHttp = component(
                        name = COMPONENT_CHAT_HTTP,
                        onRelease = onRelease,
                        acquire = { HttpClients.create(config.chatTimeoutS) },
                        close = { it.close() },
                    )
                    chatHttp.andThen { chatClient ->
                        val provider = component(
                            name = COMPONENT_PROVIDER,
                            onRelease = onRelease,
                            acquire = { providerOverride ?: EmbeddingProviders.create(config, client) },
                            close = { it.close() },
                        )
                        val chat = component(
                            name = COMPONENT_CHAT,
                            onRelease = onRelease,
                            acquire = { chatOverride ?: ChatProviders.create(config, chatClient) },
                            close = { it.close() },
                        )
                        val bus = Resource(
                            acquire = { BusPipeline.of(config, db).evalGraph() },
                            release = { },
                        )
                        Resource.zip(provider, chat, bus) { providerValue, chatValue, busPipeline ->
                            Brain(config, db, client, chatClient, providerValue, chatValue, busPipeline)
                        }
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
