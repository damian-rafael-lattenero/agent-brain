package dev.lattenero.brain.rag

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.embeddings.EmbeddingProvider
import dev.lattenero.brain.embeddings.FakeProvider
import kap.CircuitBreaker
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Repositories as a corpus, on real Postgres with fake embeddings: the same
 * Corpus/Chunker/RagStore/Indexer path `index-repos` uses, no model needed.
 * Every test owns its database and its temp tree, so order never matters.
 */
@Tag("integration")
class ReposIT {
    private val dim = 768
    private val repos = listOf("kap", "bot")

    @TempDir
    lateinit var projects: Path

    private fun write(rel: String, text: String = "content of $rel") {
        val path = projects.resolve(rel)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun indexer(store: RagStore, provider: EmbeddingProvider = FakeProvider(dim, FakeProvider.Mode.BOW)): Indexer {
        val tracer = CountingTracer()
        val pipeline = EmbedPipeline(
            provider,
            batchSize = 4,
            concurrency = 2,
            timeout = 30.seconds,
            tracer = tracer,
            breaker = CircuitBreaker(100, 1.minutes),
            attempts = 1,
        )
        return Indexer(store, pipeline, tracer, docConcurrency = 2).also { it.embedBatchSize = 4 }
    }

    private suspend fun idsByUri(db: Database): Map<String, Long> = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT uri, id FROM documents").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) to rs.getLong(2) else null }.toMap()
            }
        }
    }

    @Test
    fun `a second run over an unchanged tree reports every document unchanged and keeps the ids`() {
        TestEnv.needPostgres()
        write("kap/docs/kafka.md", "# Kafka\nConsumer groups rebalance partitions.")
        write("kap/src/main/kotlin/App.kt", "fun main() = println(\"hello\")")
        write("bot/README.md", "# Bot\nTelegram bot wiring.")
        write("bot/docs/ops.md", "# Ops\nRestart the bot.")
        Database(TestDatabase.config("repos")).use { db ->
            runBlocking {
                Migrator(db, dim).apply()
                val store = RagStore(db)
                val indexer = indexer(store)
                val firstPlan = Repos(projects).plan(repos)
                val first = indexer.index(firstPlan.scan.documents, firstPlan.scan.skipped, firstPlan.pruneKept)
                assertThat(first.documents).isEqualTo(4)
                assertThat(first.indexed).isEqualTo(4)
                assertThat(first.unchanged).isZero
                assertThat(first.failed).isEmpty()
                assertThat(first.pruned).isZero
                assertThat(first.skipped).isEmpty()
                val idsAfterFirst = idsByUri(db)
                assertThat(idsAfterFirst).hasSize(4)

                val secondPlan = Repos(projects).plan(repos)
                val second = indexer.index(secondPlan.scan.documents, secondPlan.scan.skipped, secondPlan.pruneKept)
                assertThat(second.documents).isEqualTo(4)
                assertThat(second.indexed).isZero
                assertThat(second.unchanged).isEqualTo(4)
                assertThat(second.embedCalls).isZero
                assertThat(second.pruned).isZero
                // Identity is the uri, never the run order: the same path keeps its document id.
                assertThat(idsByUri(db)).isEqualTo(idsAfterFirst)
            }
        }
    }

    @Test
    fun `a deleted file is pruned from its repository and the other repository keeps its documents`() {
        TestEnv.needPostgres()
        write("kap/a.md", "# A\nalpha")
        write("kap/b.md", "# B\nbravo")
        write("bot/c.md", "# C\ncharlie")
        Database(TestDatabase.config("repos")).use { db ->
            runBlocking {
                Migrator(db, dim).apply()
                val store = RagStore(db)
                val indexer = indexer(store)
                val before = Repos(projects).plan(repos)
                indexer.index(before.scan.documents, before.scan.skipped, before.pruneKept)

                Files.delete(projects.resolve("kap/b.md"))
                val after = Repos(projects).plan(repos)
                val report = indexer.index(after.scan.documents, after.scan.skipped, after.pruneKept)
                assertThat(report.documents).isEqualTo(2)
                assertThat(report.unchanged).isEqualTo(2)
                assertThat(report.pruned).isEqualTo(1)
                val ids = idsByUri(db)
                assertThat(ids.keys).noneMatch { it.endsWith("kap/b.md") }
                assertThat(ids.keys).anyMatch { it.endsWith("kap/a.md") }
                    .anyMatch { it.endsWith("bot/c.md") }
                val stats = store.stats()
                assertThat(stats.documents).isEqualTo(2)
                assertThat(stats.bySource.values).containsExactly(1L, 1L)
            }
        }
    }

    @Test
    fun `a vanished repository root is reported but its indexed documents are not pruned`() {
        TestEnv.needPostgres()
        write("kap/a.md", "# A\nalpha")
        write("bot/c.md", "# C\ncharlie")
        Database(TestDatabase.config("repos")).use { db ->
            runBlocking {
                Migrator(db, dim).apply()
                val store = RagStore(db)
                val indexer = indexer(store)
                val before = Repos(projects).plan(repos)
                indexer.index(before.scan.documents, before.scan.skipped, before.pruneKept)

                Files.walk(projects.resolve("kap")).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
                val after = Repos(projects).plan(repos)
                assertThat(after.report.repos.single { it.name == "kap" }.present).isFalse()
                assertThat(after.report.skipped.joinToString()).contains("kap").contains("not a directory")
                assertThat(after.pruneKept.keys).doesNotContain(after.repositories.single { it.name == "kap" }.glob)
                val report = indexer.index(after.scan.documents, after.scan.skipped, after.pruneKept)
                assertThat(report.pruned).isZero
                assertThat(report.documents).isEqualTo(1)
                // The old document survives until the root comes back: a moved checkout must not wipe its index.
                assertThat(store.stats().documents).isEqualTo(2)
            }
        }
    }
}
