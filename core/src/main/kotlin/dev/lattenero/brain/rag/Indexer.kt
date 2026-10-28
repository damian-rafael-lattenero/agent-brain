package dev.lattenero.brain.rag

import kap.Kap
import kap.KapTypeSafe
import kap.andThen
import kap.evalGraph
import kap.map
import kap.traced
import kap.traverseSettled
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.time.TimeSource

private val log = LoggerFactory.getLogger("agent-brain.index")

@Serializable
data class IndexFailure(val uri: String, val reason: String)

@Serializable
data class PhaseStat(val count: Long, val failures: Long, val totalMs: Long)

@Serializable
data class IndexReport(
    val documents: Int,
    val indexed: Int,
    val unchanged: Int,
    val failed: List<IndexFailure>,
    val pruned: Int,
    val chunks: Int,
    val embedCalls: Int,
    val skipped: List<String>,
    val durationMs: Long,
    val embedModel: String,
    val phases: Map<String, PhaseStat>,
)

/**
 * One document is one KAP graph: upsert (phase 1) → chunk + embed batches
 * in bounded parallel (phase 2) → replace its chunks in one transaction
 * (phase 3); an unchanged document short-circuits after phase 1. Documents
 * run with bounded parallelism under `traverseSettled`, so one failing
 * document is reported, not fatal for the rest (CONVERT discipline).
 */
class Indexer(
    private val store: RagStore,
    private val embed: EmbedPipeline,
    private val tracer: CountingTracer,
    private val chunker: Chunker = Chunker(),
    private val docConcurrency: Int = 2,
    private val corpus: Corpus = Corpus(),
) {
    sealed interface Outcome {
        val uri: String

        data class Unchanged(override val uri: String) : Outcome
        data class Indexed(override val uri: String, val chunks: Int, val batches: Int) : Outcome
    }

    fun indexDocument(doc: Corpus.Document): Kap<Outcome> =
        Kap { store.upsertDocument(doc.source, doc.uri, doc.title, doc.content, doc.sha) }
            .traced(PHASE_UPSERT, tracer)
            .andThen { upsert ->
                when (upsert) {
                    is RagStore.Upsert.Unchanged -> Kap.of(Outcome.Unchanged(doc.uri))
                    is RagStore.Upsert.Changed -> {
                        val chunks = chunker.chunk(doc.title, doc.content)
                        val batches = (chunks.size + embedBatchSizeHint() - 1) / embedBatchSizeHint()
                        embed.embedAll(chunks.map { it.content })
                            .map { vectors -> chunks.zip(vectors) { c, v -> RagStore.ChunkRow(c.ordinal, c.heading, c.content, v) } }
                            .andThen { rows -> Kap { store.replaceChunks(upsert.docId, embed.model, rows) }.traced(PHASE_STORE, tracer) }
                            .map { Outcome.Indexed(doc.uri, chunks.size, batches) }
                    }
                }
            }

    /** Indexing and pruning touch disjoint rows, so the typed builder runs them as two parallel phases. */
    @KapTypeSafe
    data class Run(val outcomes: List<Result<Outcome>>, val pruned: Int)

    /** Indexes the documents; `pruneKept` maps a source glob to the uris that still exist under it. */
    suspend fun index(documents: List<Corpus.Document>, skipped: List<String> = emptyList(), pruneKept: Map<String, List<String>> = emptyMap()): IndexReport {
        val mark = TimeSource.Monotonic.markNow()
        val run = kap(::Run)
            .with(IndexerRunKap.outcomes from documents.traverseSettled(docConcurrency) { indexDocument(it) })
            .with { pruned from prune(pruneKept) }
            .evalGraph()
        val results = run.outcomes
        var indexed = 0
        var unchanged = 0
        var chunks = 0
        var embedCalls = 0
        val failed = ArrayList<IndexFailure>()
        results.zip(documents).forEach { (result, doc) ->
            result.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is Outcome.Unchanged -> unchanged += 1
                        is Outcome.Indexed -> {
                            indexed += 1
                            chunks += outcome.chunks
                            embedCalls += outcome.batches
                        }
                    }
                },
                onFailure = { error ->
                    log.warn("index failed for {}: {}: {}", doc.uri, error::class.simpleName, error.message)
                    failed += IndexFailure(doc.uri, "${error::class.simpleName}: ${error.message}")
                },
            )
        }
        return IndexReport(
            documents = documents.size,
            indexed = indexed,
            unchanged = unchanged,
            failed = failed,
            pruned = run.pruned,
            chunks = chunks,
            embedCalls = embedCalls,
            skipped = skipped,
            durationMs = mark.elapsedNow().inWholeMilliseconds,
            embedModel = embed.model,
            phases = tracer.phases.mapValues { (_, p) -> PhaseStat(p.count.get(), p.failures.get(), p.totalMillis.get()) },
        )
    }

    suspend fun indexGlobs(globs: List<String>): IndexReport {
        val scan = corpus.scan(globs)
        val kept = globs.associateWith { glob -> scan.documents.filter { it.source == glob }.map { it.uri } }
        return index(scan.documents, scan.skipped, kept)
    }

    private suspend fun prune(pruneKept: Map<String, List<String>>): Int {
        var pruned = 0
        for ((source, kept) in pruneKept) pruned += store.deleteDocumentsNotIn(source, kept)
        return pruned
    }

    private fun embedBatchSizeHint(): Int = embedBatchSize

    var embedBatchSize: Int = DEFAULT_BATCH_HINT

    companion object {
        const val PHASE_UPSERT = "upsert"
        const val PHASE_STORE = "store"
        const val DEFAULT_BATCH_HINT = 32
    }
}
