package dev.lattenero.brain.eval

import dev.lattenero.brain.Brain
import dev.lattenero.brain.memory.MemoryReconcile
import dev.lattenero.brain.memory.MemorySearchRequest
import dev.lattenero.brain.memory.MemoryStore
import dev.lattenero.brain.memory.ReconcileAction
import dev.lattenero.brain.memory.Verdict
import dev.lattenero.brain.memory.VerdictAttempt
import dev.lattenero.brain.memory.VerdictParser
import kotlin.time.TimeSource

/**
 * The E2 memory legs, seeded and graded OUT of [EvalRunner] (plan E5's shape):
 *
 * - MEMORY stores one real memory per case (the other cases' contents ride as
 *   distractors), builds the graph edges over the seeded session and grades
 *   hybrid ranking (the spine's retrieval columns) PLUS the graph's `related`
 *   field.
 * - CONTRADICTION seeds claim pairs RAW (no dedup probe — a near-duplicate
 *   pair is a verdict's INPUT, merging it at insert time would leave nothing
 *   to judge), calls the verdict judge once per pair, applies the reconcile
 *   action to the store and grades the verdict word AND the state effect.
 */
class MemoryEvalLegs(private val timeSource: TimeSource = TimeSource.Monotonic) {

    /** The seeded rows, in suite order (contradiction: old/new interleaved). */
    suspend fun seed(brain: Brain, suite: EvalSuite): List<Long> {
        val contents = when (suite.kind) {
            EvalKind.MEMORY -> suite.cases.filterIsInstance<MemoryCase>().map { it.content }
            EvalKind.CONTRADICTION -> suite.cases.filterIsInstance<ContradictionCase>()
                .flatMap { listOf(it.oldFact, it.newFact) }
            else -> emptyList()
        }
        check(contents.isNotEmpty()) { "eval: a ${suite.kind.label} suite seeded nothing" }
        check(contents.size == contents.distinct().size) { "eval: a memory leg seeded duplicate contents" }
        val vectors = brain.provider.embed(contents)
        val ids = contents.mapIndexed { index, content ->
            val row = MemoryStore.MemoryRow("fact", content, vectors[index])
            brain.memoryStore.saveRaw(row, brain.provider.model, sessionId = "eval-${suite.name}")
        }
        if (suite.kind == EvalKind.MEMORY) {
            // The graph leg needs edges: `related` lands wherever a seeded pair
            // measures inside the band (cooccur/temporal need real turn rows —
            // raw inserts have none — and the recall grade does not need them).
            brain.edgeBuilder.forTurn("eval-${suite.name}", "eval-turn", ids)
        }
        return ids
    }

    /** Memory recall: the expected memory ranks in the top k; `related` carries the needle when asked. */
    suspend fun memoryCases(brain: Brain, suite: EvalSuite): List<CaseResult> =
        suite.cases.filterIsInstance<MemoryCase>().map { case ->
            val mark = timeSource.markNow()
            val response = brain.memory.search(MemorySearchRequest(query = case.question, k = case.k))
            val durationMs = mark.elapsedNow().inWholeMilliseconds
            val rank = response.memories
                .indexOfFirst { EvalAssertions.supports(it.content, listOf(case.expect)) }
                .let { if (it >= 0) it + 1 else null }
            val relatedOk = case.relatedNeedle?.let { needle ->
                response.related.any { EvalAssertions.supports(it.content, listOf(needle)) }
            }
            CaseResult(
                id = case.id,
                ok = rank != null && relatedOk != false,
                durationMs = durationMs,
                promptTokens = Tokens.estimate(case.question),
                tokensEstimated = true,
                question = case.question,
                rank = rank,
                relatedHits = response.related.size,
                supported = relatedOk,
                failure = when {
                    rank == null -> "expected a hit containing '${case.expect}' in the top ${case.k}"
                    relatedOk == false -> "the related field carried no memory matching '${case.relatedNeedle}'"
                    else -> null
                },
            )
        }

    /** The verdict word AND the state effect, one case per seeded pair. */
    suspend fun contradictionCases(brain: Brain, suite: EvalSuite, ids: List<Long>): List<CaseResult> =
        suite.cases.filterIsInstance<ContradictionCase>().mapIndexed { index, case ->
            val oldId = ids[index * 2]
            val newId = ids[index * 2 + 1]
            val mark = timeSource.markNow()
            val attempt = brain.verdictJudge.judge(case.oldFact, case.newFact)
            val durationMs = mark.elapsedNow().inWholeMilliseconds
            val decided = attempt as? VerdictAttempt.Decided
            if (decided == null) {
                val reason = (attempt as? VerdictAttempt.Skipped)?.reason ?: "no answer"
                return@mapIndexed CaseResult(
                    id = case.id,
                    ok = false,
                    durationMs = durationMs,
                    tokensEstimated = true,
                    verdict = "skipped",
                    expectedVerdict = case.expectedVerdict,
                    failure = "the judge skipped the pair: $reason",
                )
            }
            val expected = expectedVerdictOf(case.expectedVerdict)
            val action = MemoryReconcile.decide(decided.verdict)
            applyAction(brain.memoryStore, action, oldId, newId)
            val effectOk = effectLanded(brain, expected, oldId, newId, case.newFact)
            val verdictOk = decided.verdict == expected
            CaseResult(
                id = case.id,
                ok = verdictOk && effectOk,
                durationMs = durationMs,
                promptTokens = Tokens.estimate(case.oldFact) + Tokens.estimate(case.newFact),
                tokensEstimated = true,
                verdict = wireWord(decided.verdict),
                expectedVerdict = case.expectedVerdict,
                supported = effectOk,
                failure = when {
                    !verdictOk -> {
                        val said = wireWord(decided.verdict)
                        "the judge said $said, the case expects ${case.expectedVerdict}"
                    }
                    !effectOk -> "the verdict was right but the state effect did not land (${wireWord(expected)})"
                    else -> null
                },
            )
        }

    /** The chat-answer face differs from the bare-word face: direct mapping here. */
    private fun expectedVerdictOf(word: String): Verdict = when (word) {
        VerdictParser.SAME -> Verdict.Same
        VerdictParser.SUPERSEDE -> Verdict.Supersede
        else -> Verdict.Distinct
    }

    private fun wireWord(verdict: Verdict): String = when (verdict) {
        Verdict.Same -> "same"
        Verdict.Supersede -> "supersede"
        Verdict.Distinct -> "distinct"
    }

    /** The action applied exactly as the reconciler would apply it. */
    private suspend fun applyAction(store: MemoryStore, action: ReconcileAction, oldId: Long, newId: Long) {
        when (action) {
            ReconcileAction.InvalidateOld -> store.invalidate(oldId)
            ReconcileAction.MergeIntoOld -> store.mergeInto(oldId, newId)
            ReconcileAction.Nothing -> {}
        }
    }

    /** The expected action's visible state: invalidated-and-excluded, absorbed, or both alive. */
    private suspend fun effectLanded(
        brain: Brain,
        expected: Verdict,
        oldId: Long,
        newId: Long,
        newFact: String,
    ): Boolean = when (expected) {
        Verdict.Supersede -> brain.memoryStore.isInvalidated(oldId) && excludesFromSearch(brain, newFact, oldId)
        Verdict.Same -> brain.memoryStore.rowAsNeighbor(newId) == null
        Verdict.Distinct -> !brain.memoryStore.isInvalidated(oldId) && brain.memoryStore.rowAsNeighbor(newId) != null
    }

    /** The Supersede effect's visible half: the stale claim must leave search's top k. */
    private suspend fun excludesFromSearch(brain: Brain, question: String, oldId: Long): Boolean {
        val response = brain.memory.search(MemorySearchRequest(query = question, k = EvalRunner.RECALL_K))
        val stale = brain.memoryStore.rowAsNeighbor(oldId)?.content
            ?: return response.memories.isNotEmpty() // a deleted claim trivially excludes nothing
        return response.memories.none { EvalAssertions.supports(it.content, listOf(stale)) }
    }

    companion object {
        /** The two memory legs' per-case tables (the runner delegates these kinds here). */
        fun markdown(report: EvalReport): String = when (report.kind) {
            EvalKind.CONTRADICTION -> EvalRunner.table(
                listOf("case", "verdict", "expected", "effect", "failure"),
                report.cases.map { row ->
                    listOf(
                        row.id,
                        row.verdict,
                        row.expectedVerdict,
                        row.supported?.let { if (it) "landed" else "missed" },
                        row.failure,
                    )
                },
            )
            else -> EvalRunner.table(
                listOf("case", "question", "rank@k", "related", "support", "failure"),
                report.cases.map { row ->
                    listOf(
                        row.id,
                        row.question,
                        row.rank?.toString(),
                        row.relatedHits?.toString(),
                        row.supported?.let { if (it) "yes" else "no" },
                        row.failure,
                    )
                },
            )
        }
    }
}
