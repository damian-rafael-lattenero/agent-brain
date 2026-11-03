package dev.lattenero.brain

import dev.lattenero.brain.http.responseJson
import dev.lattenero.brain.rag.Repos
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `index-repos` CLI surface. The dry run is the one path that must work
 * without Postgres: it only scans files and prints the report, so these tests
 * need no database and run in tier 1.
 */
class IndexReposCliTest {
    @TempDir
    lateinit var tmp: Path

    private fun write(rel: String, text: String = "content of $rel") {
        val path = tmp.resolve(rel)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun captureStdout(block: () -> Int): Pair<Int, String> {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        return try {
            System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
            block() to buffer.toString(Charsets.UTF_8)
        } finally {
            System.setOut(original)
        }
    }

    private fun report(text: String): JsonObject = responseJson.parseToJsonElement(text).jsonObject

    @Test
    fun `the dry run scans and reports per repository without a database`() {
        write("kap/a.md", "12345")
        write("kap/src/App.kt", "fun main() {}")
        write("bot/b.md", "abc")
        val (exit, out) = captureStdout { indexRepos(listOf("--dry-run"), Repos(tmp)) }
        assertThat(exit).isEqualTo(EXIT_OK)
        val json = report(out)
        assertThat(json["dryRun"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(json["documents"]!!.jsonPrimitive.int).isEqualTo(3)
        assertThat(json["bytes"]!!.jsonPrimitive.long).isEqualTo(5L + "fun main() {}".length + 3L)
        assertThat(json["projectsDir"]!!.jsonPrimitive.content).isEqualTo(tmp.toString())
        val repos = json["repos"]!!.jsonArray.map { it.jsonObject }
        assertThat(repos.map { it["name"]!!.jsonPrimitive.content }).containsExactly("kap", "bot", "zcode-bot-kotlin", "ZCode", "agent-brain")
        assertThat(repos.first { it["name"]!!.jsonPrimitive.content == "kap" }["documents"]!!.jsonPrimitive.int).isEqualTo(2)
        assertThat(repos.first { it["name"]!!.jsonPrimitive.content == "bot" }["documents"]!!.jsonPrimitive.int).isEqualTo(1)
    }

    @Test
    fun `--repo keeps only the selected repositories`() {
        write("kap/a.md")
        write("bot/b.md")
        val (exit, out) = captureStdout { indexRepos(listOf("--dry-run", "--repo", "bot"), Repos(tmp)) }
        assertThat(exit).isEqualTo(EXIT_OK)
        val repos = report(out)["repos"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertThat(repos).containsExactly("bot")
    }

    @Test
    fun `an unknown repository or argument fails loudly`() {
        assertThat(indexRepos(listOf("--repo", "nope"), Repos(tmp))).isEqualTo(EXIT_FAIL)
        assertThat(indexRepos(listOf("--favicon"), Repos(tmp))).isEqualTo(EXIT_FAIL)
        assertThat(indexRepos(listOf("--repo"), Repos(tmp))).isEqualTo(EXIT_FAIL)
    }
}
