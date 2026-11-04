package dev.lattenero.brain.eval

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/** A suite is malformed or unknown: a versioned file problem, never a model problem. */
class EvalSuiteException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** What a suite measures, which also decides the matrix axis. */
@Serializable
enum class EvalKind {
    /** rank@5 of the expected document under hybrid retrieval (the shipped gate). */
    @SerialName("recall")
    RECALL,

    /** Does the chat model return a schema-valid extraction with the expected content? */
    @SerialName("extraction")
    EXTRACTION,

    /** Does the composed answer cite the expected document and state the expected fact? */
    @SerialName("grounded")
    GROUNDED,
    ;

    /** Recall ranks embeddings; extraction and grounded measure the chat model. */
    val axis: EvalAxis
        get() = if (this == RECALL) EvalAxis.EMBED else EvalAxis.CHAT

    /** The lowercase name used in reports, artifacts and the CLI. */
    val label: String
        get() = name.lowercase()

    /** Only retrieval suites need the fixture corpus indexed in a fresh database. */
    val needsCorpus: Boolean
        get() = this != EXTRACTION

    /** The turn this suite prices: what the bot spends a model call on. */
    val turn: String
        get() = when (this) {
            RECALL -> "retrieval (embedding a query)"
            EXTRACTION -> "memory extraction (one chat call per turn)"
            GROUNDED -> "answer composition (one chat call per answered turn)"
        }
}

/** The config knob a matrix replaces: the embedding model or the chat model. */
@Serializable
enum class EvalAxis {
    @SerialName("embed")
    EMBED,

    @SerialName("chat")
    CHAT,
    ;

    /** How an axis is spelled on the command line, in reports and in error messages. */
    val label: String
        get() = if (this == EMBED) "embed" else "chat"
}

/** One case, discriminated by `"type"` in the suite file. */
@Serializable
sealed interface EvalCase {
    val id: String
}

@Serializable
@SerialName("recall")
data class RecallCase(
    override val id: String,
    val question: String,
    val uri: String,
    /** Optional support facts: if the top hit contains any needle, it really answers the question. */
    val needles: List<String> = emptyList(),
) : EvalCase

@Serializable
@SerialName("grounded")
data class GroundedCase(
    override val id: String,
    val question: String,
    val uri: String,
    val needles: List<String>,
    val k: Int = DEFAULT_GROUNDED_K,
) : EvalCase {
    companion object {
        const val DEFAULT_GROUNDED_K = 5
    }
}

@Serializable
@SerialName("extraction")
data class ExtractionCase(
    override val id: String,
    val prompt: String,
    val reply: String,
    val expect: ExtractionExpect,
    val title: String = "",
    val tools: List<String> = emptyList(),
) : EvalCase

/**
 * What a good extraction of the transcript looks like. Groups pass when ANY
 * item contains ANY needle (case-insensitive, whitespace-normalized), so a
 * paraphrase is not punished and a hallucination is: the needle is a fact of
 * the turn, not a phrasing.
 */
@Serializable
data class ExtractionExpect(
    val factsAny: List<String> = emptyList(),
    val decisionsAny: List<String> = emptyList(),
    val preferencesAny: List<String> = emptyList(),
    val summaryRequired: Boolean = true,
    /** A turn with nothing durable: every group must come back empty. */
    val empty: Boolean = false,
)

/**
 * A versioned eval suite: cases plus the gate they are graded against. Lives
 * in `evals/suites` (JSON files, copied to the classpath at build time), so a
 * new question, a new expected fact or a different gate is a reviewable diff,
 * not a code change.
 */
@Serializable
data class EvalSuite(
    val name: String,
    val kind: EvalKind,
    val gate: Double,
    val cases: List<EvalCase>,
    val description: String = "",
) {
    val axis: EvalAxis get() = kind.axis

    /** Fails loudly on a suite file that cannot be graded; returns this suite for chaining. */
    fun validate(): EvalSuite {
        requireHeader()
        requireCases()
        return this
    }

    private fun requireHeader() {
        if (name.isBlank()) throw EvalSuiteException("a suite needs a name")
        if (gate !in 0.0..1.0) throw EvalSuiteException("$name: gate must be within 0.0..1.0, got $gate")
    }

    private fun requireCases() {
        if (cases.isEmpty()) throw EvalSuiteException("$name: no cases")
        val duplicates = cases.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) throw EvalSuiteException("$name: duplicate case ids ${duplicates.sorted()}")
        cases.forEach { requireCase(it) }
        cases.filterIsInstance<GroundedCase>().forEach { requireGrounded(it) }
    }

    private fun requireCase(case: EvalCase) {
        if (case.id.isBlank()) throw EvalSuiteException("$name: a case has a blank id")
        val matches = when (kind) {
            EvalKind.RECALL -> case is RecallCase
            EvalKind.GROUNDED -> case is GroundedCase
            EvalKind.EXTRACTION -> case is ExtractionCase
        }
        if (!matches) {
            throw EvalSuiteException("$name: case ${case.id} does not match the suite kind '${kind.label}'")
        }
    }

    private fun requireGrounded(case: GroundedCase) {
        if (case.needles.isEmpty()) {
            throw EvalSuiteException("$name: grounded case ${case.id} needs at least one needle to verify its answer")
        }
        if (case.k < 1) throw EvalSuiteException("$name: grounded case ${case.id} has k < 1")
    }

    companion object {
        const val DEFAULT = "recall-core"

        /** The suites shipped inside the jar; a test keeps this list and the directory in sync. */
        val SHIPPED = listOf("recall-core", "extraction-core", "grounded-citation")
    }
}

/** Loading and parsing: a name resolves on the classpath, a path on disk wins. */
object EvalSuites {
    const val DIR = "eval/suites"

    private val json = Json { ignoreUnknownKeys = false }

    fun load(spec: String): EvalSuite {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) throw EvalSuiteException("empty suite name")
        val file = Path.of(trimmed)
        val text = if (Files.isRegularFile(file)) {
            Files.readString(file)
        } else {
            val resource = if (trimmed.endsWith(".json")) "$DIR/$trimmed" else "$DIR/$trimmed.json"
            val loader = EvalSuites::class.java.classLoader
            loader.getResourceAsStream(resource)?.bufferedReader()?.use { it.readText() }
                ?: throw EvalSuiteException(
                    "unknown suite '$spec': no $resource on the classpath " +
                        "(shipped: ${EvalSuite.SHIPPED.joinToString()}) and no such file on disk",
                )
        }
        return parse(text, trimmed).validate()
    }

    fun parse(text: String, source: String): EvalSuite = runCatching {
        json.decodeFromString(EvalSuite.serializer(), text)
    }.getOrElse { e ->
        throw EvalSuiteException("suite $source is not valid: ${e.message?.lineSequence()?.firstOrNull()}", e)
    }

    /** Every shipped suite, for the CLI's usage and the docs. */
    fun shipped(): List<EvalSuite> = EvalSuite.SHIPPED.map { load(it) }
}
