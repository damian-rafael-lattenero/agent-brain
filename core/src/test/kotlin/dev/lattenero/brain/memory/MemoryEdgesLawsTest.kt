package dev.lattenero.brain.memory

import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.numericDouble
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * The B4 law battery: the edge builders and the traversal are total pure
 * functions, and these are their algebraic commitments — kotest properties
 * over random inputs plus the exact boundary cases. The related band's
 * ceiling is READ from [MemoryDedup.SIMILARITY], never a restated literal:
 * dedup and the graph share the same merge boundary by construction.
 */
class MemoryEdgesLawsTest {
    private fun similarity(): Arb<Double> = Arb.numericDouble(-1.0, 1.0)
    private fun ids(): Arb<List<Long>> = Arb.list(Arb.long(1, 100), 0..8)

    /** LAW 1 — the related band: edges exist iff RELATED_FLOOR <= cosine < the dedup threshold. */
    @Test
    fun `law 1 - the related band is floor inclusive and the dedup threshold exclusive`() = runBlocking<Unit> {
        checkAll(Arb.numericDouble(0.01, 1.0), Arb.numericDouble(0.01, 1.0), Arb.long(1, 1000)) { below, above, neighborId ->
            val source = 42L
            // Strictly below the floor: nothing. At the floor: one edge (the band is floor-inclusive).
            val under = MemoryEdges.related(source, listOf(Neighbor(neighborId, MemoryEdges.RELATED_FLOOR - below)))
            assertThat(under).isEmpty()
            val atFloor = MemoryEdges.related(source, listOf(Neighbor(neighborId, MemoryEdges.RELATED_FLOOR)))
            assertThat(atFloor).hasSize(1)
            assertThat(atFloor.single().weight).isEqualTo(MemoryEdges.RELATED_FLOOR)
            assertThat(atFloor.single().weight).isLessThan(MemoryDedup.SIMILARITY)
            // At or above the dedup threshold the pair is a MERGE (B2's boundary), never an edge.
            assertThat(MemoryEdges.related(source, listOf(Neighbor(neighborId, MemoryDedup.SIMILARITY)))).isEmpty()
            assertThat(MemoryEdges.related(source, listOf(Neighbor(neighborId, MemoryDedup.SIMILARITY + above)))).isEmpty()
        }
    }

    /** LAW 2 — related edges carry the cosine itself, rank best-first, and cap at MAX_RELATED. */
    @Test
    fun `law 2 - related is the capped best-first view of the in-band neighbors`() = runBlocking<Unit> {
        checkAll(ids(), Arb.list(similarity(), 0..20), Arb.long(1, 50)) { idList, sims, capNoise ->
            val source = 1000L + capNoise
            // One neighbor per id (the SQL fetch reads one row per id).
            val neighbors = idList.distinct().zip(sims) { id, sim -> Neighbor(id, sim) }.filter { it.id != source }
            val edges = MemoryEdges.related(source, neighbors)
            val inBand = neighbors.filter { it.similarity >= MemoryEdges.RELATED_FLOOR && it.similarity < MemoryDedup.SIMILARITY }
            val expected = inBand.sortedWith(compareByDescending<Neighbor> { it.similarity }.thenBy { it.id })
                .take(MemoryEdges.MAX_RELATED)
                .map { source to it.id to it.similarity }
            assertThat(edges.map { it.srcId to it.dstId to it.weight }).isEqualTo(expected)
            for (edge in edges) {
                assertThat(edge.weight).isEqualTo(neighbors.first { it.id == edge.dstId }.similarity)
                assertThat(edge.kind).isEqualTo(EdgeKind.RELATED)
            }
        }
    }

    /** LAW 3 — no self-loops, in any builder, ever. */
    @Test
    fun `law 3 - no builder ever produces a self-loop`() = runBlocking<Unit> {
        checkAll(ids(), similarity(), Arb.list(Arb.long(1, 20), 0..10)) { idList, sim, dupIds ->
            val source = idList.firstOrNull() ?: 7L
            // The source itself as a neighbor, even with an in-band cosine, is dropped.
            assertThat(MemoryEdges.related(source, listOf(Neighbor(source, 0.8)))).isEmpty()
            val edges = MemoryEdges.related(source, idList.map { Neighbor(it, sim) })
            assertThat(edges.map { it.srcId to it.dstId }).allSatisfy { (s, d) -> assertThat(s).isNotEqualTo(d) }
            // Cooccur collapses duplicates before pairing: every pair is two distinct memories.
            val clique = MemoryEdges.cooccur(dupIds + dupIds)
            assertThat(clique.map { it.srcId to it.dstId }).allSatisfy { (s, d) -> assertThat(s).isNotEqualTo(d) }
            // The chain's distinct rows keep self-loops out too.
            val now = Instant.now()
            val chain = MemoryEdges.temporalChain(dupIds.map { DatedMemory(it, now) })
            assertThat(chain.map { it.srcId to it.dstId }).allSatisfy { (s, d) -> assertThat(s).isNotEqualTo(d) }
        }
    }

    /** LAW 4 — cooccur is the unordered pairwise clique: once per pair, plain weight. */
    @Test
    fun `law 4 - cooccur links each pair once with the plain weight`() = runBlocking<Unit> {
        checkAll(ids()) { idList ->
            val distinct = idList.distinct()
            val clique = MemoryEdges.cooccur(idList)
            assertThat(clique).hasSize(distinct.size * (distinct.size - 1) / 2)
            assertThat(clique.map { it.srcId to it.dstId }.map { (a, b) -> if (a < b) a to b else b to a })
                .doesNotHaveDuplicates()
            assertThat(clique.map { it.weight }).allSatisfy { assertThat(it).isEqualTo(MemoryEdges.PLAIN_WEIGHT) }
            assertThat(clique.map { it.kind }).allSatisfy { assertThat(it).isEqualTo(EdgeKind.COOCUR) }
        }
    }

    /** LAW 5 — temporal is the CHAIN of consecutive within-window rows, never the clique. */
    @Test
    fun `law 5 - temporal chains consecutive rows inside the window only`() = runBlocking<Unit> {
        checkAll(Arb.int(1, 600), Arb.int(601, 3000)) { insideSeconds, outsideSeconds ->
            val base = Instant.now()
            fun at(offset: Long) = base.plusSeconds(offset)
            // Three rows, all consecutive within the window: exactly TWO edges (the chain, not the clique).
            val allClose = listOf(
                DatedMemory(1, at(0)),
                DatedMemory(2, at(insideSeconds.toLong())),
                DatedMemory(3, at(insideSeconds.toLong() * 2)),
            )
            assertThat(MemoryEdges.temporalChain(allClose)).hasSize(2)
            // One gap outside the window splits the chain exactly there.
            val split = listOf(DatedMemory(1, at(0)), DatedMemory(2, at(outsideSeconds.toLong())), DatedMemory(3, at(outsideSeconds.toLong() + insideSeconds.toLong())))
            val edges = MemoryEdges.temporalChain(split)
            assertThat(edges.map { it.srcId to it.dstId }).containsExactly(2L to 3L)
            assertThat(edges.map { it.weight }).allSatisfy { assertThat(it).isEqualTo(MemoryEdges.PLAIN_WEIGHT) }
            // Order comes from (created_at, id), not from the list's order: shuffled input, same chain.
            val shuffled = split.shuffled()
            assertThat(MemoryEdges.temporalChain(shuffled).map { it.srcId to it.dstId })
                .containsExactlyElementsOf(edges.map { it.srcId to it.dstId })
        }
    }

    /** LAW 6 — the traversal is symmetric: one edge makes each endpoint reachable from the other. */
    @Test
    fun `law 6 - an edge row serves both endpoints`() = runBlocking<Unit> {
        checkAll(Arb.long(1, 100), Arb.long(101, 200), Arb.numericDouble(0.0, 1.0)) { a, b, weight ->
            assertThat(a).isNotEqualTo(b)
            val memoryB = Memory(b, "fact", "b", null, null, 1.0, "2026-01-01T00:00:00Z")
            val memoryA = Memory(a, "fact", "a", null, null, 1.0, "2026-01-01T00:00:00Z")
            // The SAME edge row, described from the direction each search fetches it.
            val fromA = traverse(listOf(a), listOf(NeighborCandidate(a, EdgeKind.RELATED, weight, memoryB, null, 1.0)))
            val fromB = traverse(listOf(b), listOf(NeighborCandidate(b, EdgeKind.RELATED, weight, memoryA, null, 1.0)))
            assertThat(fromA.map { it.id to it.viaId }).containsExactly(b to a)
            assertThat(fromB.map { it.id to it.viaId }).containsExactly(a to b)
        }
    }

    /** LAW 7 — the traversal is deterministic: (edge weight DESC, relevance DESC, weight DESC, id ASC). */
    @Test
    fun `law 7 - the traversal ranking is the traversal key's total order`() = runBlocking<Unit> {
        checkAll(Arb.list(similarity(), 0..30), Arb.list(Arb.long(1, 10_000), 0..30)) { weights, idList ->
            // One candidate per memory id (the SQL fetch reads one row per id), each hanging
            // off one of the first three ids as its hit.
            val distinct = idList.distinct()
            val hits = distinct.take(3).ifEmpty { listOf(1L) }
            val candidates = distinct.zip(weights) { id, w ->
                NeighborCandidate(
                    viaId = hits[id.hashCode() % hits.size],
                    edgeKind = EdgeKind.TEMPORAL,
                    edgeWeight = w,
                    memory = Memory(id, "fact", "c$id", null, null, 1.0, "2026-01-01T00:00:00Z"),
                    relevance = null,
                    salienceWeight = 1.0,
                )
            }.filter { it.memory.id !in hits }
            val related = traverse(hits, candidates)
            // Deterministic: same inputs, same output.
            assertThat(traverse(hits, candidates)).isEqualTo(related)
            // Ranked by the key, deduped by memory id, capped per hit and globally.
            val keys = related.map { r ->
                val candidate = candidates.first { it.memory.id == r.id }
                TraversalKey(candidate.edgeWeight, candidate.relevance, candidate.salienceWeight, r.id)
            }
            assertThat(keys).isSortedAccordingTo { x, y -> x.compareTo(y) }
            assertThat(related.map { it.id }).doesNotHaveDuplicates()
            assertThat(related.size).isLessThanOrEqualTo(MemoryEdges.TRAVERSAL_MAX)
            val perVia = related.groupBy { it.viaId }.mapValues { it.value.size }
            assertThat(perVia.values).allSatisfy { assertThat(it).isLessThanOrEqualTo(MemoryEdges.TRAVERSAL_PER_HIT) }
        }
    }

    /** LAW 8 — a hit is never its own neighbor, and no forgotten-style exclusion leaks in here. */
    @Test
    fun `law 8 - hits never appear among the related`() = runBlocking<Unit> {
        checkAll(ids()) { idList ->
            val hits = idList.distinct().ifEmpty { listOf(5L) }
            val candidates = hits.map { hit ->
                NeighborCandidate(
                    viaId = hit,
                    edgeKind = EdgeKind.COOCUR,
                    edgeWeight = 1.0,
                    memory = Memory(hit, "fact", "self", null, null, 1.0, "2026-01-01T00:00:00Z"),
                    relevance = null,
                    salienceWeight = 1.0,
                )
            }
            assertThat(traverse(hits, candidates)).isEmpty()
        }
    }

    /** Boundary — a gap exactly at the window still chains (the edge belongs to the chain). */
    @Test
    fun `boundary - a gap exactly at the window chains`() {
        val base = Instant.now()
        val pair = listOf(DatedMemory(1, base), DatedMemory(2, base.plusSeconds(MemoryEdges.TEMPORAL_WINDOW_SECONDS.toLong())))
        assertThat(MemoryEdges.temporalChain(pair)).hasSize(1)
        val pastIt = listOf(
            DatedMemory(1, base),
            DatedMemory(2, base.plusMillis(MemoryEdges.TEMPORAL_WINDOW_SECONDS.toLong() * 1000 + Duration.ofMillis(1).toMillis())),
        )
        assertThat(MemoryEdges.temporalChain(pastIt)).isEmpty()
    }
}
