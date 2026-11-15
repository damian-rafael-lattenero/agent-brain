package dev.lattenero.brain.memory

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/**
 * B4's memory graph — memory-to-memory edges as a closed taxonomy. `related`
 * carries the cosine similarity pgvector already computed (the band below the
 * dedup threshold: similar enough to walk, too distinct to merge); `cooccur`
 * and `temporal` carry [PLAIN_WEIGHT]. The wire string rides [wire], and the
 * database's CHECK constraint keeps the set closed at the boundary — the same
 * role the memory kind taxonomy's validation plays at the HTTP edge.
 */
enum class EdgeKind(val wire: String) {
    RELATED("related"),
    COOCUR("cooccur"),
    TEMPORAL("temporal"),
}

/** One memory-to-memory edge as a value; [srcId] and [dstId] are never equal (the no-self-loop law). */
data class MemoryEdge(val srcId: Long, val dstId: Long, val kind: EdgeKind, val weight: Double)

/**
 * The edge builders as pure functions over fetched facts — the same shape as
 * `MemoryDedup.plan`: the IO edge reads the rows, the law decides, the store
 * writes. Every builder is total and deterministic, and the laws (no
 * self-loops, the related band, the temporal window, the traversal ranking)
 * are pinned in `MemoryEdgesLawsTest`.
 *
 * The related band's UPPER edge is [MemoryDedup.SIMILARITY] — reused, never
 * restated: a pair at or above the dedup threshold is THE SAME memory (B2's
 * merges own that boundary), so it must be a merge, not an edge. The lower
 * edge [RELATED_FLOOR] is where nomic-embed-text's merely-related band was
 * measured (the evidence behind the dedup threshold: related pairs measure
 * 0.60..0.88, duplicates 0.91+); 0.70 keeps the traversal out of the noise.
 */
object MemoryEdges {
    /** A neighbor at or above this cosine is related enough to walk to (and below the dedup merge). */
    const val RELATED_FLOOR: Double = 0.70

    /** Top edges kept per new memory's related pass. */
    const val MAX_RELATED: Int = 5

    /** Consecutive memories of a session created within this window chain (10 minutes). */
    const val TEMPORAL_WINDOW_SECONDS: Double = 600.0

    /** The weight of cooccur and temporal edges: structure, not strength. */
    const val PLAIN_WEIGHT: Double = 1.0

    /** Instant-to-seconds conversion for the temporal window comparison. */
    private const val MILLIS_PER_SECOND: Double = 1000.0

    /** 1-hop neighbors kept per hit in the search traversal. */
    const val TRAVERSAL_PER_HIT: Int = 3

    /** The traversal's global cap across all hits. */
    const val TRAVERSAL_MAX: Int = 8

    /**
     * The related edges one NEW memory earns: neighbors whose cosine with it
     * lands in the band `[RELATED_FLOOR, dedup threshold)` — related, not the
     * same — best first, capped at [MAX_RELATED]. The weight IS the cosine
     * pgvector already computed. A neighbor at or above the dedup threshold is
     * a merge the store already handled (never an edge); a neighbor below the
     * floor is noise. The source itself is never a neighbor (no self-loops).
     */
    fun related(sourceId: Long, neighbors: List<Neighbor>, ceiling: Double = MemoryDedup.SIMILARITY): List<MemoryEdge> =
        neighbors.asSequence()
            .filter { it.id != sourceId && it.similarity >= RELATED_FLOOR && it.similarity < ceiling }
            .sortedWith(compareByDescending<Neighbor> { it.similarity }.thenBy { it.id })
            .take(MAX_RELATED)
            .map { MemoryEdge(sourceId, it.id, EdgeKind.RELATED, it.similarity) }
            .toList()

    /**
     * Memories produced by the same turn link pairwise (same session and turn
     * — a turn with none present yields no cooccur edges). Distinct ids,
     * stored once per unordered pair.
     */
    fun cooccur(ids: List<Long>): List<MemoryEdge> = ids.distinct().sorted().let { sorted ->
        sorted.indices.flatMap { i ->
            sorted.drop(i + 1).map { j -> MemoryEdge(sorted[i], j, EdgeKind.COOCUR, PLAIN_WEIGHT) }
        }
    }

    /**
     * Consecutive memories of a session chain when the gap between neighbors
     * is within [windowSeconds]: ordered by (created_at, id), each pair of
     * adjacent rows lands one edge if close enough. The chain — not the
     * clique — so a session's memory count never squares.
     */
    fun temporalChain(rows: List<DatedMemory>, windowSeconds: Double = TEMPORAL_WINDOW_SECONDS): List<MemoryEdge> =
        rows.distinctBy { it.id }
            .sortedWith(compareBy({ it.at }, { it.id }))
            .zipWithNext()
            .filter { (prev, cur) -> Duration.between(prev.at, cur.at).toMillis() / MILLIS_PER_SECOND <= windowSeconds }
            .map { (prev, cur) -> MemoryEdge(prev.id, cur.id, EdgeKind.TEMPORAL, PLAIN_WEIGHT) }
}

/** A scored neighbor of one memory: the cosine similarity pgvector computed. */
data class Neighbor(val id: Long, val similarity: Double)

/** One memory row's identity and creation instant — the temporal chain's input. */
data class DatedMemory(val id: Long, val at: Instant)

/**
 * One 1-hop neighbor of a top hit, as the traversal fetch reads it: the edge
 * that brought it ([edgeKind], [edgeWeight]), the neighbor's own row, its
 * relevance to the query (the same cosine the vector leg ranks with — null
 * when the neighbor's embedding model differs from the query's, because
 * cosine across models is meaningless, ADR-004), and the neighbor's
 * reinforcement tie-break ([Salience.weight] over created_at, the SQL twin of
 * [RankKey]'s second key).
 */
data class NeighborCandidate(
    val viaId: Long,
    val edgeKind: EdgeKind,
    val edgeWeight: Double,
    val memory: Memory,
    val relevance: Double?,
    val salienceWeight: Double,
) {
    /** The wire shape one candidate renders into (`related` in MemorySearchResponse). */
    fun toRelated(): RelatedMemory = RelatedMemory(
        id = memory.id,
        kind = memory.kind,
        content = memory.content,
        sessionId = memory.sessionId,
        createdAt = memory.createdAt,
        viaId = viaId,
        edgeKind = edgeKind.wire,
        weight = edgeWeight,
    )
}

/**
 * The traversal's ranking key, best first: the edge's weight is DOMINANT (a
 * strongly related neighbor beats a weakly related one regardless of the
 * rest), then the hit ranking's own law ([RankKey]: relevance first, the
 * reinforcement weight as tie-break), and the ids are the deterministic last
 * word. An unknown relevance (null, the cross-model case) cannot claim to be
 * more relevant than a known one.
 */
data class TraversalKey(
    val edgeWeight: Double,
    val relevance: Double?,
    val weight: Double,
    val id: Long,
) : Comparable<TraversalKey> {
    override fun compareTo(other: TraversalKey): Int =
        compareByDescending<TraversalKey> { it.edgeWeight } // the edge that brought me
            .thenByDescending { it.relevance ?: Double.NEGATIVE_INFINITY } // then the hit law: relevance
            .thenByDescending { it.weight } // ... tie-broken by reinforcement
            .thenBy { it.id } // deterministic
            .compare(this, other)
}

/**
 * One entry of `/memory/search`'s additive `related` field: the neighbor
 * memory and the edge that hangs it off the hit ([viaId], [edgeKind],
 * [weight] — the EDGE's weight, related = the cosine, cooccur/temporal =
 * [MemoryEdges.PLAIN_WEIGHT]).
 */
@Serializable
data class RelatedMemory(
    val id: Long,
    val kind: String,
    val content: String,
    val sessionId: String?,
    val createdAt: String,
    val viaId: Long,
    val edgeKind: String,
    val weight: Double,
)

/**
 * The 1-hop traversal as a pure fold: candidates come from the SQL fetch (one
 * edge row serves BOTH endpoints — symmetric), the ranking is [TraversalKey]'s
 * total order, a memory reachable from several hits appears ONCE (its best
 * rank wins, the later occurrences are skipped without consuming the other
 * hit's quota), each hit keeps at most [perHit] neighbors, and the whole list
 * is capped at [globalMax]. A hit's own id is never its neighbor (no
 * self-loops through the traversal either).
 */
fun traverse(
    hits: List<Long>,
    candidates: List<NeighborCandidate>,
    perHit: Int = MemoryEdges.TRAVERSAL_PER_HIT,
    globalMax: Int = MemoryEdges.TRAVERSAL_MAX,
): List<RelatedMemory> {
    val hitIds = hits.toSet()
    val ranked = candidates
        .filter { it.memory.id != it.viaId && it.memory.id !in hitIds }
        .sortedWith { a, b ->
            TraversalKey(a.edgeWeight, a.relevance, a.salienceWeight, a.memory.id)
                .compareTo(TraversalKey(b.edgeWeight, b.relevance, b.salienceWeight, b.memory.id))
        }
    val taken = mutableSetOf<Long>()
    val perVia = mutableMapOf<Long, Int>()
    return ranked.fold(mutableListOf<RelatedMemory>()) { result, candidate ->
        val underCaps = result.size < globalMax && (perVia[candidate.viaId] ?: 0) < perHit
        if (underCaps && candidate.memory.id !in taken) {
            taken += candidate.memory.id
            perVia.merge(candidate.viaId, 1, Int::plus)
            result += candidate.toRelated()
        }
        result
    }
}
