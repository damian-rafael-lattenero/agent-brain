package dev.lattenero.brain.rag

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ReposTest {
    @TempDir
    lateinit var tmp: Path

    private fun write(rel: String, text: String = "content of $rel"): Path {
        val path = tmp.resolve(rel)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return path
    }

    @Test
    fun `the default repositories resolve to one absolute glob each, rooted at the repository`() {
        val repos = Repos(tmp).resolve()
        assertThat(repos.map { it.name })
            .containsExactly("kap", "bot", "zcode-bot-kotlin", "ZCode", "agent-brain")
        assertThat(repos.map { it.root })
            .containsExactlyElementsOf(Repos.DEFAULT_NAMES.map { tmp.resolve(it) })
        val glob = repos.first { it.name == "kap" }.glob
        assertThat(glob).startsWith("${tmp.resolve("kap")}/**/*.{").endsWith("}")
        assertThat(glob).contains("md", "kt")
        // Corpus cuts the walk root at the first wildcard: the glob must land exactly on the repo root.
        assertThat(Corpus().source(glob).root).isEqualTo(tmp.resolve("kap"))
    }

    @Test
    fun `an unknown repository name is refused with the known ones`() {
        assertThatThrownBy { Repos(tmp).resolve(listOf("kap", "nope")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unknown repositories: nope")
            .hasMessageContaining("agent-brain")
    }

    @Test
    fun `docs and code at any depth are scanned, noise directories and other extensions are not`() {
        write("kap/README.md")
        write("kap/docs/guide.md")
        write("kap/src/main/kotlin/App.kt")
        write("kap/build/Generated.kt")
        write("kap/.git/HEAD.md")
        write("kap/node_modules/dep/index.js")
        write("kap/assets/logo.png")
        write("bot/docs/bot.md")

        val plan = Repos(tmp).plan(listOf("kap", "bot"))
        assertThat(plan.scan.documents.map { it.title })
            .containsExactly("README.md", "docs/guide.md", "src/main/kotlin/App.kt", "docs/bot.md")
        assertThat(plan.report.repos.single { it.name == "kap" }.documents).isEqualTo(3)
        assertThat(plan.report.repos.single { it.name == "bot" }.documents).isEqualTo(1)
        assertThat(plan.report.skipped).isEmpty()
    }

    @Test
    fun `the repository filter keeps only the selected repositories`() {
        write("kap/a.md")
        write("bot/b.md")
        val plan = Repos(tmp).plan(listOf("bot"))
        assertThat(plan.report.repos.map { it.name }).containsExactly("bot")
        assertThat(plan.scan.documents.map { it.uri })
            .containsExactly(tmp.resolve("bot/b.md").toAbsolutePath().toString())
        assertThat(plan.pruneKept.keys).containsExactly(plan.repositories.single().glob)
    }

    @Test
    fun `the same tree always scans to the same documents in the same order`() {
        write("kap/zeta.md")
        write("kap/docs/mid.md")
        write("kap/alpha.md")
        write("bot/z.md")

        val first = Repos(tmp).plan(listOf("kap", "bot"))
        val second = Repos(tmp).plan(listOf("kap", "bot"))
        val uris = first.scan.documents.map { it.uri }
        assertThat(uris).isEqualTo(second.scan.documents.map { it.uri })
        assertThat(first.scan.documents.map { it.sha }).isEqualTo(second.scan.documents.map { it.sha })
        // Repository order is the fixed default list, paths are sorted inside each walk.
        assertThat(uris.take(3)).isSorted
        assertThat(uris.first()).endsWith("kap/alpha.md")
        assertThat(uris.last()).endsWith("bot/z.md")
    }

    @Test
    fun `a missing repository root is reported and never prunes its old documents`() {
        write("kap/a.md")
        val plan = Repos(tmp).plan(listOf("kap", "bot"))
        assertThat(plan.report.repos.single { it.name == "bot" }.present).isFalse()
        assertThat(plan.report.skipped.joinToString()).contains("bot").contains("not a directory")
        assertThat(plan.pruneKept.keys).containsExactly(plan.repositories.first { it.name == "kap" }.glob)
    }

    @Test
    fun `per repository counters add up and bytes are the utf-8 length of the file`() {
        write("kap/a.md", "héllo")
        write("bot/b.kt", "abc")
        val report = Repos(tmp).plan(listOf("kap", "bot")).report
        assertThat(report.documents).isEqualTo(2)
        assertThat(report.bytes).isEqualTo(6L + 3L)
        assertThat(report.repos.map { it.bytes }).containsExactly(6L, 3L)
        assertThat(report.dryRun).isTrue()
        assertThat(report.projectsDir).isEqualTo(tmp.toString())
    }
}
