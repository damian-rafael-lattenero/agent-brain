package dev.lattenero.brain.memory

import dev.lattenero.brain.eval.Tokens
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path

/**
 * The LoRA training dataset builder (the closing project's first brick): the
 * bus + the memory store ARE the labeled corpus. Every processed turn with
 * stored memories yields a POSITIVE pair (transcript in, canonical extraction
 * out — built from the memories the pipeline actually kept), and every
 * quarantined event with a raw answer yields a NEGATIVE (the degenerate output
 * graded as an empty extraction). Output: chat-format JSONL ready for an SFT
 * pass over Qwen2.5-3B.
 */
class LoraExport(private val store: MemoryStore) {

    /** One chat-format training example, serialized as one JSONL line. */
    data class Pair(val system: String, val user: String, val assistant: String)

    /** What one export pass produced: counts and the token estimate. */
    data class Dataset(
        val file: Path,
        val positives: Int,
        val negatives: Int,
        val trivials: Int,
        val estimatedTokens: Long,
    )

    private fun extractionJson(
        facts: List<String>,
        decisions: List<String>,
        preferences: List<String>,
        summary: String,
    ): String = buildJsonObject {
        putJsonArray("facts") { facts.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("decisions") { decisions.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("preferences") { preferences.forEach { add(JsonPrimitive(it)) } }
        put("summary", summary)
    }.toString()

    private fun byKindOrEmpty(byKind: Map<String, List<String>>, kind: String): List<String> = byKind[kind].orEmpty()

    private val emptyExtraction = extractionJson(emptyList(), emptyList(), emptyList(), "")

    private fun line(pair: Pair): String = buildJsonObject {
        put("system", pair.system)
        put("user", pair.user)
        put("assistant", pair.assistant)
    }.toString()

    /**
     * Positive pairs from processed turns: the transcript is assembled exactly
     * as the extractor saw it ([MemoryStore.transcript]); the assistant target
     * is the canonical extraction rebuilt from the STORED memories of that
     * turn (kind-grouped, insertion order, invalidated rows excluded — a claim
     * a verdict killed is not a target). Turns without memories are skipped.
     */
    suspend fun positivePairs(limit: Int): List<Pair> =
        store.processedTurns(limit).mapNotNull { turn ->
            val memories = store.memoriesOfTurn(turn.turnId)
            if (memories.isEmpty()) return@mapNotNull null
            val transcript = store.transcript(turn)
            if (transcript.isEmpty) return@mapNotNull null
            val byKind = memories.groupBy({ it.kind }, { it.content })
            Pair(
                system = MemoryExtractor.SYSTEM_PROMPT,
                user = transcript.render(MemoryExtractor.TRANSCRIPT_CAP),
                assistant = extractionJson(
                    byKindOrEmpty(byKind, MemoryKind.FACT.wire),
                    byKindOrEmpty(byKind, MemoryKind.DECISION.wire),
                    byKindOrEmpty(byKind, MemoryKind.PREFERENCE.wire),
                    byKind[MemoryKind.SUMMARY.wire]?.firstOrNull().orEmpty(),
                ),
            )
        }

    /**
     * Negative pairs from quarantine: the raw answer the extractor produced
     * (kept verbatim in the quarantine row) paired with an EMPTY extraction —
     * the model learns that degenerate output means nothing worth keeping.
     * Rows whose raw is blank (gave-up rows) carry no signal and are skipped.
     */
    suspend fun negativePairs(limit: Int): List<Pair> =
        store.quarantined(limit)
            .filter { it.raw.isNotBlank() }
            .map { row ->
                Pair(
                    system = MemoryExtractor.SYSTEM_PROMPT,
                    // The quarantine row keeps the reason, not the prompt; the
                    // raw IS the signal (the answer to reject).
                    user = "RAW ANSWER:\n${row.raw.take(MemoryExtractor.TRANSCRIPT_CAP)}",
                    assistant = emptyExtraction,
                )
            }

    /**
     * Trivial pairs (the data pass, 19/11 — run 400's verdict: the gate
     * failures were DATA, not epochs): PROCESSED turns whose REPLY is SHORT —
     * greetings, acks, "dale". The canonical extraction for those IS the
     * empty one, regardless of what tiny memories the extractor squeezed out:
     * the owner PURGED exactly this class by hand (the 75 tiny status rows of
     * the 16/11 prune — "Merged to master", "## Ahora", "no es eso" — that
     * hand-prune IS the human label). A LONG reply stays out: its label is
     * unknown, never synthesized.
     */
    suspend fun trivialPairs(limit: Int, maxReplyChars: Int = LORA_TRIVIAL_REPLY_MAX): List<Pair> =
        store.processedTurns(limit).mapNotNull { turn ->
            val transcript = store.transcript(turn)
            if (transcript.isEmpty) return@mapNotNull null
            if (transcript.reply.length > maxReplyChars) return@mapNotNull null
            Pair(
                system = MemoryExtractor.SYSTEM_PROMPT,
                user = transcript.render(MemoryExtractor.TRANSCRIPT_CAP),
                assistant = emptyExtraction,
            )
        }

    /** Writes the chat-format JSONL and reports the measured shape. */
    suspend fun export(outDir: Path, limit: Int, trivialMax: Int = LORA_DEFAULT_TRIVIAL_MAX): Dataset {
        Files.createDirectories(outDir)
        val file = outDir.resolve("lora-pairs-${System.currentTimeMillis()}.jsonl")
        var positives = 0
        var negatives = 0
        var trivials = 0
        var tokens = 0L
        Files.newBufferedWriter(file).use { writer ->
            fun write(pair: Pair) {
                val text = line(pair)
                tokens += Tokens.estimate(text)
                writer.write(text)
                writer.write("\n")
            }
            positivePairs(limit).forEach { positives++; write(it) }
            negativePairs(limit).forEach { negatives++; write(it) }
            // The trivial pool scans a 4× WIDER window than the positives':
            // zero-memory turns sit OLDER than the newest kept ones, and
            // [trivialMax] caps how many teach the empty discipline (~30%).
            trivialPairs(limit * LORA_TRIVIAL_POOL_FACTOR)
                .take(trivialMax)
                .forEach { trivials++; write(it) }
        }
        return Dataset(file, positives, negatives, trivials, tokens)
    }
}

/** The trivial-reply ceiling: longer zero-memory replies are extraction misses, not trivials. */
const val LORA_TRIVIAL_REPLY_MAX = 200

/** The trivial cap the export uses by default (~30% of the current set). */
const val LORA_DEFAULT_TRIVIAL_MAX = 250

/** The trivial pool scans this many × the positives' window (see export). */
const val LORA_TRIVIAL_POOL_FACTOR = 4

/** The one-line face of [LoraExport.Dataset] — what the CLI prints. */
fun datasetReport(dataset: LoraExport.Dataset): String =
    "pairs: ${dataset.positives} positive + ${dataset.negatives} negative + " +
        "${dataset.trivials} trivial, ~${dataset.estimatedTokens} tokens -> ${dataset.file}"
