package dev.lattenero.brain.rag

import dev.lattenero.brain.Brain
import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path

/**
 * The retrieval quality gate: ten planted facts among distractors, indexed
 * with the REAL default model, and each question must surface its document
 * in the top five. The threshold is a floor, not a target; recall@1 is
 * reported alongside so regressions show before they cross the floor.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecallGateIT {
    @Serializable
    data class Fact(val question: String, val uri: String)

    private lateinit var brain: Brain
    private lateinit var root: Path
    private lateinit var facts: List<Fact>

    @BeforeAll
    fun up() {
        TestEnv.needPostgres()
        TestEnv.needOllama()
        root = Files.createTempDirectory("agent-brain-recall")
        val loader = javaClass.classLoader
        val names = loader.getResourceAsStream("corpus")!!.bufferedReader().readLines()
        names.filter { it.endsWith(".md") }.forEach { name ->
            Files.write(root.resolve(name), loader.getResourceAsStream("corpus/$name")!!.readAllBytes())
        }
        facts = Json.decodeFromString(loader.getResourceAsStream("corpus/facts.json")!!.bufferedReader().readText())
        brain = Brain(TestDatabase.config("recall", provider = "ollama"))
        runBlocking {
            brain.migrator.apply()
            val report = brain.indexer.indexGlobs(listOf("$root/*.md"))
            assertThat(report.failed).isEmpty()
            assertThat(report.indexed).isEqualTo(names.count { it.endsWith(".md") })
        }
    }

    @AfterAll
    fun down() {
        if (::brain.isInitialized) brain.close()
    }

    @Test
    fun `every planted fact is retrievable in the top five with the real model`() = runBlocking<Unit> {
        var atOne = 0
        var atFive = 0
        val misses = ArrayList<String>()
        for (fact in facts) {
            val hits = brain.rag.query(QueryRequest(fact.question, k = 5)).hits
            val files = hits.map { it.uri.substringAfterLast('/') }.distinct()
            if (files.firstOrNull() == fact.uri) atOne += 1
            if (fact.uri in files) atFive += 1 else misses += "${fact.question} -> expected ${fact.uri}, got $files"
        }
        val recallAtFive = atFive.toDouble() / facts.size
        val recallAtOne = atOne.toDouble() / facts.size
        println("recall@1 = $recallAtOne, recall@5 = $recallAtFive (${facts.size} facts, model ${brain.provider.model})")
        assertThat(recallAtFive).withFailMessage("recall@5 %.2f below the %.2f floor; misses:\n  %s", recallAtFive, RECALL_AT_5_FLOOR, misses.joinToString("\n  ")).isGreaterThanOrEqualTo(RECALL_AT_5_FLOOR)
        assertThat(recallAtOne).withFailMessage("recall@1 %.2f below the %.2f floor", recallAtOne, RECALL_AT_1_FLOOR).isGreaterThanOrEqualTo(RECALL_AT_1_FLOOR)
    }

    companion object {
        const val RECALL_AT_5_FLOOR = 1.0
        const val RECALL_AT_1_FLOOR = 0.8
    }
}
