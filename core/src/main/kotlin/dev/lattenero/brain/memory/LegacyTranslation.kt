package dev.lattenero.brain.memory

import dev.lattenero.brain.chat.ChatException
import dev.lattenero.brain.chat.ChatProvider
import dev.lattenero.brain.chat.ChatResult
import dev.lattenero.brain.db.Database
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate

/**
 * The English-corpus migration (the owner's call 19/11 — "no me molestaría
 * una pasada larga nocturna aunque cueste tokens"): the legacy SPANISH
 * memories translate to ENGLISH in place, batch per judge call, and re-embed
 * with nomic so retrieval never sees two languages. The row keeps its
 * identity, salience, edges and turn linkage — the graph does not know the
 * language changed; only content, embedding and embed_model swap.
 *
 * Safety rails (the house migration laws):
 *  - a backup table snapshots the original content before the first batch;
 *  - `translated_at` is the pass's cursor — an at-least-once replay is a
 *    no-op (`AND translated_at IS NULL`);
 *  - a batch whose verdict does not parse stays untranslated and is reported
 *    loudly (the next run retries; a poisoned batch never lies as done);
 *  - dry-run plans and prints, writes nothing.
 */
class LegacyTranslation(
    private val db: Database,
    private val judge: ChatProvider,
    private val embed: suspend (String) -> FloatArray,
    private val embedModel: String,
) {
    /** What one pass did: the numbers the CLI and the announce speak. */
    data class Report(
        val batches: Int,
        val translated: Int,
        val failedBatches: Int,
        val failedItems: Int,
        val backupTable: String,
        val batchesRun: List<BatchResult>,
    )

    data class BatchResult(val size: Int, var translated: Int, var parseFailed: Boolean)

    /** The backup table the pass created (created once, rows = originals). */
    fun ensureBackup(): String {
        val table = "memories_pre_translation_backup_" + LocalDate.now().toString().replace("-", "")
        db.pool.connection.use { conn ->
            conn.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS $table AS " +
                "SELECT id, content FROM memories WHERE translated_at IS NULL") }
        }
        return table
    }

    /**
     * Translates up to [maxItems] untranslated valid memories. Returns the
     * report; dry-run returns the counts without writing anything.
     */
    suspend fun run(
        maxItems: Int = DEFAULT_MAX_ITEMS,
        batchSize: Int = DEFAULT_BATCH,
        dryRun: Boolean = false,
    ): Report {
        require(maxItems > 0) { "maxItems must be > 0" }
        require(batchSize in 1..MAX_BATCH) { "batchSize must be within 1..$MAX_BATCH" }
        val backupTable = ensureBackup()
        val batchesRun = mutableListOf<BatchResult>()
        var translated = 0
        var failedBatches = 0
        var failedItems = 0
        var batches = 0
        var remaining = maxItems
        while (remaining > 0) {
            val size = minOf(batchSize, remaining)
            val rows = db.withDriver { brainDb ->
                brainDb.memoriesQueries.untranslatedBatch(size.toLong()).executeAsList()
            }
            if (rows.isEmpty()) break
            batches++
            val result = translateBatch(rows.map { it.id to it.content }, dryRun)
            batchesRun.add(result)
            translated += result.translated
            if (result.parseFailed) failedBatches++
            failedItems += result.size - result.translated
            remaining -= size
        }
        return Report(batches, translated, failedBatches, failedItems, backupTable, batchesRun)
    }

    /**
     * One batch: one judge call for the whole group, then re-embed + stamp
     * each. The judge's items are POSITIONAL (n = the 1-based row of the
     * batch — caught live: keying by memory id never matches when ids are
     * thousands). The two early returns keep the whole batch UNTRANSLATED
     * (never lies as done); per-item misses are the failedItems count.
     */
    private suspend fun translateBatch(rows: List<Pair<Long, String>>, dryRun: Boolean): BatchResult {
        val result = BatchResult(rows.size, 0, parseFailed = false)
        if (dryRun) return result.also { result.translated = rows.size }
        val verdict: ChatResult? = try {
            judge.completeJson(SYSTEM, renderBatch(rows.map { it.second }), SCHEMA)
        } catch (e: ChatException) {
            System.err.println("translate: the judge call failed (${e.message}) — the batch stays untranslated")
            null
        }
        val parsed: Map<Int, String>? = verdict?.let {
            try {
                TranslationBatch.parse(it.text, rows.size)
            } catch (e: IllegalArgumentException) {
                System.err.println("translate: unparsable verdict (${e.message}) — the batch stays untranslated")
                null
            }
        }
        // Positional join: the judge's n is the 1-based index of the batch.
        val englishByPosition: Map<Int, String> = parsed.orEmpty()
        rows.forEachIndexed { index, row ->
            val english = englishByPosition[index + 1]
            if (english.isNullOrBlank()) return@forEachIndexed
            val vector = embed(english)
            val vectorText = "[${vector.joinToString(",")}]"
            db.withDriver { brainDb ->
                brainDb.memoriesQueries.translateOne(
                    id = row.first,
                    content = english,
                    embedding = vectorText,
                    embedModel = embedModel,
                )
            }
            result.translated++
        }
        result.parseFailed = parsed == null
        return result
    }

    private fun renderBatch(items: List<String>): String = buildString {
        append("TRANSLATE TO ENGLISH (keep code identifiers, file names and numbers verbatim; ")
        append("short standalone sentence):\n")
        items.forEachIndexed { index, content -> append(index + 1).append(". ").append(content.trim()).append('\n') }
    }

    /** The judge's translation batch: POSITION n → English text. */
    object TranslationBatch {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String, expectedMax: Int): Map<Int, String> {
            val root = json.parseToJsonElement(text).jsonObject
            val items = root["items"]?.jsonArray ?: throw IllegalArgumentException("missing 'items'")
            // mapNotNull: the per-item skips are lambda returns, not loop jumps.
            return items.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val n = o["n"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val en = o["en"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (n !in 1..expectedMax || en.isBlank()) return@mapNotNull null
                n to en.trim()
            }.toMap()
        }
    }

    companion object {
        const val DEFAULT_MAX_ITEMS = 10_000
        const val DEFAULT_BATCH = 12
        const val MAX_BATCH = 24

        private const val SYSTEM =
            "You translate memory rows to English for a developer's memory corpus. " +
                "Return JSON only: {\"items\":[{\"n\": int, \"en\": string}]} — n is the input " +
                "number, en is the English translation as a short standalone sentence. Keep code " +
                "identifiers, file names and numbers verbatim. Already-English rows translate to " +
                "themselves (same words)."

        private val SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("items") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("n") { put("type", "integer") }
                            putJsonObject("en") { put("type", "string") }
                        }
                    }
                }
            }
            put("required", buildJsonArray { add(JsonPrimitive("items")) })
        }
    }
}
