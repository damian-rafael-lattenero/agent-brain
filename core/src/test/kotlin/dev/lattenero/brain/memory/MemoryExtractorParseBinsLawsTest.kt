package dev.lattenero.brain.memory

import dev.lattenero.brain.chat.ChatResult
import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Property laws over the extraction caps (plan E5): the pure
 * `(raw) -> Parsed | Invalid(reason)` bins are total, and for generated
 * answers the Parsed bin respects every cap exactly — short items dropped,
 * long ones clipped, duplicates collapsed, ten per kind, summary capped —
 * and [MemoryExtractor.parse] is its throwing twin: it either yields the
 * very same extraction or throws the very same bin as [ExtractionInvalid].
 */
class MemoryExtractorParseBinsLawsTest {

    /** Items straddling every cap: below MIN_ITEM_CHARS (8), above MAX_ITEM_CHARS (300), and in between. */
    private fun item(): Arb<String> = Arb.choice(
        Arb.int(1, 7).map { "x".repeat(it) }, // always dropped
        Arb.int(8, 20).map { "word-$it" },
        Arb.int(290, 320).map { "y".repeat(it) }, // straddles the 300 clip
    )

    /** Summaries straddling the 500 cap (summaries have no minimum: anything trimmed is kept). */
    private fun summary(): Arb<String> = Arb.choice(
        Arb.int(0, 20).map { "a summary of ${it} chars".take(it) },
        Arb.int(490, 520).map { "s".repeat(it) },
        Arb.int(1, 7).map { "z".repeat(it) },
    )

    /** A generated raw answer: the three kinds (one generated array each) plus a generated summary. */
    private fun rawAnswer(): Arb<String> = item().let { items ->
        Arb.list(items, 0..15).flatMap { facts ->
            Arb.list(items, 0..15).flatMap { decisions ->
                Arb.list(items, 0..15).flatMap { preferences ->
                    summary().map { text ->
                        buildJsonObject {
                            putJsonArray("facts") { facts.forEach { add(it) } }
                            putJsonArray("decisions") { decisions.forEach { add(it) } }
                            putJsonArray("preferences") { preferences.forEach { add(it) } }
                            put("summary", text)
                        }.toString()
                    }
                }
            }
        }
    }

    /** Generated answers the bins must reject, each with its reason. */
    private fun malformedAnswer(): Arb<String> = Arb.choice(
        Arb.string(1..30), // arbitrary text: not JSON (and if it ever parses, the bins still answer)
        Arb.int(1, 100).map { "[$it, 2]" }, // not a JSON object
        Arb.int(1, 100).map { """{"facts": $it, "decisions": [], "preferences": [], "summary": ""}""" }, // not an array
        Arb.int(1, 100).map { """{"facts": [$it], "decisions": [], "preferences": [], "summary": ""}""" }, // non-string item
        Arb.int(1, 100).map { """{"facts": [], "decisions": [], "preferences": [], "summary": {"x": $it}}""" }, // summary not a string
    )

    /** Both bins' worlds: well-formed answers and rejected ones. */
    private fun anyAnswer(): Arb<String> = Arb.choice(rawAnswer(), malformedAnswer())

    /** The spec, restated independently of the implementation. */
    private fun expectedKind(items: List<String>): List<String> = items.asSequence()
        .map { it.trim() }
        .filter { it.length >= MemoryExtractor.MIN_ITEM_CHARS }
        .map { it.take(MemoryExtractor.MAX_ITEM_CHARS) }
        .distinct()
        .take(MemoryExtractor.MAX_ITEMS_PER_KIND)
        .toList()

    private fun rawItems(parsed: JsonObject, key: String): List<String> =
        (parsed[key] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

    @Test
    fun `totality - any raw lands in exactly one bin, never an exception`() {
        runBlocking {
            checkAll(anyAnswer()) { raw ->
                when (val outcome = MemoryExtractor.parseBins(raw)) {
                    is ParseOutcome.Parsed -> assertThat(outcome.extraction.raw).isEqualTo(raw)
                    is ParseOutcome.Invalid -> {
                        assertThat(outcome.reason).isNotBlank
                        assertThat(outcome.raw).isEqualTo(raw)
                    }
                }
            }
        }
    }

    @Test
    fun `the caps hold - every kept item is in min-max, at most ten per kind, never duplicated`() {
        runBlocking {
            checkAll(rawAnswer()) { raw ->
                val outcome = MemoryExtractor.parseBins(raw)
                val extraction = (outcome as? ParseOutcome.Parsed)?.extraction
                    ?: return@checkAll
                for (kind in listOf(extraction.facts, extraction.decisions, extraction.preferences)) {
                    assertThat(kind.size).isLessThanOrEqualTo(MemoryExtractor.MAX_ITEMS_PER_KIND)
                    assertThat(kind).doesNotHaveDuplicates()
                    for (kept in kind) {
                        assertThat(kept.length).isGreaterThanOrEqualTo(MemoryExtractor.MIN_ITEM_CHARS)
                        assertThat(kept.length).isLessThanOrEqualTo(MemoryExtractor.MAX_ITEM_CHARS)
                        assertThat(raw).contains(kept) // every kept item came from the answer
                    }
                }
                assertThat(extraction.summary.length).isLessThanOrEqualTo(MemoryExtractor.MAX_SUMMARY_CHARS)
            }
        }
    }

    @Test
    fun `the bins equal the spec - trim, drop short, clip long, dedupe, cap per kind, in order`() {
        runBlocking {
            checkAll(rawAnswer()) { raw ->
                val outcome = MemoryExtractor.parseBins(raw)
                val extraction = (outcome as? ParseOutcome.Parsed)?.extraction
                    ?: return@checkAll
                val parsed = Json.parseToJsonElement(raw) as JsonObject
                assertThat(extraction.facts).containsExactlyElementsOf(expectedKind(rawItems(parsed, "facts")))
                assertThat(extraction.decisions).containsExactlyElementsOf(expectedKind(rawItems(parsed, "decisions")))
                assertThat(extraction.preferences).containsExactlyElementsOf(expectedKind(rawItems(parsed, "preferences")))
            }
        }
    }

    @Test
    fun `an item over the cap survives as its exact prefix and a short one never lands`() {
        runBlocking {
            checkAll(Arb.int(301, 500)) { length ->
                val raw = """{"facts": ["${"q".repeat(length)}"], "decisions": [], "preferences": [], "summary": ""}"""
                val extraction = (MemoryExtractor.parseBins(raw) as ParseOutcome.Parsed).extraction
                assertThat(extraction.facts).containsExactly("q".repeat(MemoryExtractor.MAX_ITEM_CHARS))

                val rawShort = """{"facts": ["${"x".repeat(MemoryExtractor.MIN_ITEM_CHARS - 1)}"], "decisions": [], "preferences": [], "summary": ""}"""
                val dropped = (MemoryExtractor.parseBins(rawShort) as ParseOutcome.Parsed).extraction
                assertThat(dropped.facts).isEmpty()
            }
        }
    }

    @Test
    fun `the exact boundaries - min and max are inside, one under and one over are outside`() {
        // An item at exactly MIN is kept; at MIN-1 it is dropped (the property ranges only straddle it).
        val atMin = "m".repeat(MemoryExtractor.MIN_ITEM_CHARS)
        val underMin = "m".repeat(MemoryExtractor.MIN_ITEM_CHARS - 1)
        // An item at exactly MAX is kept unchanged; at MAX+1 it is clipped to exactly MAX (and still kept).
        val atMax = "M".repeat(MemoryExtractor.MAX_ITEM_CHARS)
        val overMax = "O".repeat(MemoryExtractor.MAX_ITEM_CHARS + 1)
        val facts = rawWith(facts = listOf(atMin, underMin, atMax, overMax))
        val extraction = (MemoryExtractor.parseBins(facts) as ParseOutcome.Parsed).extraction
        assertThat(extraction.facts).containsExactly(atMin, atMax, overMax.take(MemoryExtractor.MAX_ITEM_CHARS))
        assertThat(extraction.facts.last().length).isEqualTo(MemoryExtractor.MAX_ITEM_CHARS)

        // A summary at exactly MAX is kept unchanged; at MAX+1 it is clipped to exactly MAX; blank stays empty.
        val summaryAtMax = "S".repeat(MemoryExtractor.MAX_SUMMARY_CHARS)
        val summaryOver = "T".repeat(MemoryExtractor.MAX_SUMMARY_CHARS + 1)
        assertThat((MemoryExtractor.parseBins(rawWith(summary = summaryAtMax)) as ParseOutcome.Parsed).extraction.summary)
            .isEqualTo(summaryAtMax)
        assertThat((MemoryExtractor.parseBins(rawWith(summary = summaryOver)) as ParseOutcome.Parsed).extraction.summary)
            .isEqualTo(summaryOver.take(MemoryExtractor.MAX_SUMMARY_CHARS))
        assertThat((MemoryExtractor.parseBins(rawWith(summary = "   ")) as ParseOutcome.Parsed).extraction.summary)
            .isEmpty()
    }

    private fun rawWith(facts: List<String> = emptyList(), summary: String = ""): String =
        buildJsonObject {
            putJsonArray("facts") { facts.forEach { add(it) } }
            putJsonArray("decisions") { }
            putJsonArray("preferences") { }
            put("summary", summary)
        }.toString()

    @Test
    fun `parse is the throwing twin of parseBins - same extraction or the same bin as the exception`() {
        runBlocking {
            checkAll(anyAnswer()) { raw ->
                when (val outcome = MemoryExtractor.parseBins(raw)) {
                    is ParseOutcome.Parsed -> {
                        val extraction = MemoryExtractor.parse(ChatResult(raw))
                        assertThat(extraction.facts).isEqualTo(outcome.extraction.facts)
                        assertThat(extraction.decisions).isEqualTo(outcome.extraction.decisions)
                        assertThat(extraction.preferences).isEqualTo(outcome.extraction.preferences)
                        assertThat(extraction.summary).isEqualTo(outcome.extraction.summary)
                    }
                    is ParseOutcome.Invalid -> {
                        assertThatThrownBy { MemoryExtractor.parse(ChatResult(raw)) }
                            .isInstanceOf(ExtractionInvalid::class.java)
                            .hasMessage(outcome.reason)
                            .extracting("raw").isEqualTo(outcome.raw)
                    }
                }
            }
        }
    }
}
