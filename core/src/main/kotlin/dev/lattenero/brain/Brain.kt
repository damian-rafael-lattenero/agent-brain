package dev.lattenero.brain

import dev.lattenero.brain.bus.BusPipeline
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
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.HttpClient

const val VERSION = "0.1.0"

/**
 * The composition root: every long-lived object is built here, once, from
 * the injected [Config], and closed in reverse order. Tests may hand in
 * their own provider (fake, or one that fails on purpose).
 */
class Brain(val config: Config, providerOverride: EmbeddingProvider? = null) : AutoCloseable {
    val db: Database = Database(config)
    val httpClient: HttpClient = HttpClients.create(config.httpTimeoutS)
    val migrator: Migrator = Migrator(db, config.embedDim)
    val provider: EmbeddingProvider = providerOverride ?: EmbeddingProviders.create(config, httpClient)
    val readiness: Readiness = Readiness(config, db, migrator, provider)
    val bus: BusPipeline = BusPipeline(config, db)
    val ragStore: RagStore = RagStore(db)
    val rag: RagService = RagService(provider, ragStore)
    val tracer: CountingTracer = CountingTracer()
    val embedPipeline: EmbedPipeline = EmbedPipeline(
        provider = provider,
        batchSize = config.embedBatch,
        concurrency = config.embedConcurrency,
        timeout = config.httpTimeoutS.seconds,
        tracer = tracer,
        breaker = CircuitBreaker(maxFailures = config.embedBreakerFailures, resetTimeout = config.embedBreakerResetS.seconds),
    )
    val indexer: Indexer = Indexer(ragStore, embedPipeline, tracer, docConcurrency = config.indexDocConcurrency)
        .also { it.embedBatchSize = config.embedBatch }

    override fun close() {
        bus.stop()
        provider.close()
        httpClient.close()
        db.close()
    }
}
