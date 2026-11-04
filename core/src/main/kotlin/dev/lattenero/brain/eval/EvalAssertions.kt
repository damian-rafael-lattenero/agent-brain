package dev.lattenero.brain.eval

import dev.lattenero.brain.memory.Extraction
import dev.lattenero.brain.memory.ParseOutcome
import dev.lattenero.brain.rag.Hit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the chat model answered for a grounded case; `citations` are 1-based context numbers. */
@Serializable
data class GroundedAnswer(val answer: String = "", val citations: List<Int> = emptyList())

/** The verdict on one grounded case: every clause is recorded, not just the final boolean. */
data class GroundedCheck(
    val ok: Boolean,
    val citedExpected: Boolean,
    val answerSupports: Boolean,
    val citedUris: List<String>,
    val failure: String?,
)

/** The verdict on one extraction case, with the reason a human can act on. */
data class ExtractionCheck(
    val ok: Boolean,
    val facts: Int,
    val decisions: Int,
    val preferences: Int,
    val summary: String,
    val failure: String?,
)

/**
 * Every assertion is a pure function of what the model returned: no clock, no
 * IO, no hidden state. The runner measures and calls these; the tests pin
 * them. A case is a fact about a result, never a fact about the run.
 */
object EvalAssertions {
    private val WHITESPACE = Regex("\\s+")
    private val json = Json { ignoreUnknownKeys = true }

    /** Lowercase, collapse whitespace: the comparison form for needles and answers. */
    fun normalize(text: String): String = text.lowercase().replace(WHITESPACE, " ").trim()

    /** True when any needle appears in [content]; a case without needles is not support-checked. */
    fun supports(content: String, needles: List<String>): Boolean {
        if (needles.isEmpty()) return false
        val normalized = normalize(content)
        return needles.any { normalized.contains(normalize(it)) }
    }

    /** The recalled gate: the expected document must be somewhere in the top k. */
    fun recall(rank: Int?): Boolean = rank != null

    /** The strict shape of a composed answer: a JSON object with a string answer and integer citations. */
    fun parseGrounded(raw: String): GroundedAnswer? = runCatching {
        json.decodeFromString(GroundedAnswer.serializer(), raw.trim())
    }.getOrNull()

    /**
     * Answer quality with a correct citation: the JSON must parse, at least one
     * cited chunk must belong to the expected document, and the answer itself
     * must state the expected fact. Citations outside the context are dropped
     * with the rest of the verdict intact (a model citing chunk 9 of 5 did not
     * cite the document, it guessed).
     */
    fun grounded(case: GroundedCase, hits: List<Hit>, answer: GroundedAnswer?): GroundedCheck {
        if (answer == null) {
            return GroundedCheck(false, false, false, emptyList(), "the answer is not JSON {answer, citations:[int]}")
        }
        val cited = answer.citations.distinct().sorted().mapNotNull { number -> hits.getOrNull(number - 1) }
        val citedUris = cited.map { it.uri }
        val citedExpected = cited.any { it.uri.endsWith(case.uri) }
        val answerSupports = supports(answer.answer, case.needles)
        val failure = when {
            !citedExpected -> "cited ${citedUris.ifEmpty { listOf("nothing") }} instead of ${case.uri}"
            !answerSupports -> "the answer does not state ${case.needles} (got \"${answer.answer.take(120)}\")"
            else -> null
        }
        return GroundedCheck(
            ok = citedExpected && answerSupports,
            citedExpected = citedExpected,
            answerSupports = answerSupports,
            citedUris = citedUris,
            failure = failure,
        )
    }

    /**
     * The extraction assertion: the schema-validated parse must carry the
     * expected content. [ParseOutcome.Invalid] is a failure with the parser's
     * reason (the same reason production quarantine records).
     */
    fun extraction(expect: ExtractionExpect, outcome: ParseOutcome): ExtractionCheck = when (outcome) {
        is ParseOutcome.Invalid -> ExtractionCheck(
            ok = false,
            facts = 0,
            decisions = 0,
            preferences = 0,
            summary = "",
            failure = "invalid extraction: ${outcome.reason}",
        )
        is ParseOutcome.Parsed -> {
            val extraction = outcome.extraction
            val failure = firstFailure(expect, extraction)
            ExtractionCheck(
                ok = failure == null,
                facts = extraction.facts.size,
                decisions = extraction.decisions.size,
                preferences = extraction.preferences.size,
                summary = extraction.summary,
                failure = failure,
            )
        }
    }

    private fun firstFailure(expect: ExtractionExpect, extraction: Extraction): String? =
        failures(expect, extraction).firstOrNull()

    /** Every way the extraction misses the expectation, in a stable order; empty means it passed. */
    private fun failures(expect: ExtractionExpect, extraction: Extraction): List<String> {
        val durable = extraction.facts + extraction.decisions + extraction.preferences
        if (expect.empty) {
            return if (durable.isEmpty()) {
                emptyList()
            } else {
                listOf("expected no durable items, got ${durable.size} (${durable.first()})")
            }
        }
        val groups = listOf(
            "facts" to (expect.factsAny to extraction.facts),
            "decisions" to (expect.decisionsAny to extraction.decisions),
            "preferences" to (expect.preferencesAny to extraction.preferences),
        )
        val groupFailures = groups.mapNotNull { (name, spec) ->
            val (needles, items) = spec
            val hit = items.any { item -> needles.any { needle -> normalize(item).contains(normalize(needle)) } }
            if (needles.isEmpty() || hit) null else "$name has none of $needles (got $items)"
        }
        val summaryFailure = if (expect.summaryRequired && extraction.summary.isBlank()) {
            listOf("summary is empty")
        } else {
            emptyList()
        }
        return groupFailures + summaryFailure
    }
}
