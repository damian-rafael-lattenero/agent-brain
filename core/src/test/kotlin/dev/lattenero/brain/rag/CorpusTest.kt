package dev.lattenero.brain.rag

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CorpusTest {
    @TempDir
    lateinit var tmp: Path

    private fun write(rel: String, text: String = "content of $rel"): Path {
        val path = tmp.resolve(rel)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return path
    }

    @Test
    fun `globs are parsed from a file with comments and blanks`() {
        val globs = Corpus().parseGlobs("# corpus\n\n/a/**/*.md\n  /b/*.txt  \n")
        assertThat(globs).containsExactly("/a/**/*.md", "/b/*.txt")
    }

    @Test
    fun `the walk root is the longest glob-free prefix`() {
        val corpus = Corpus()
        assertThat(corpus.source("/Users/x/Projects/cv/**/*.md").root.toString()).isEqualTo("/Users/x/Projects/cv")
        assertThat(corpus.source("/Users/x/notes.md").root.toString()).isEqualTo("/Users/x")
        assertThatThrownBy { corpus.source("relative/*.md") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `scan matches the glob, prunes noise directories and keeps text extensions only`() {
        write("docs/a.md")
        write("docs/deep/b.md")
        write("docs/c.txt")
        write("docs/image.png")
        write("docs/node_modules/x.md")
        write("docs/.git/HEAD.md")
        write("other/d.md")
        val scan = Corpus().scan(listOf("$tmp/docs/**/*.md", "$tmp/docs/*.txt"))
        assertThat(scan.documents.map { it.title }).containsExactly("a.md", "deep/b.md", "c.txt")
        assertThat(scan.documents.map { it.source }).containsExactly("$tmp/docs/**/*.md", "$tmp/docs/**/*.md", "$tmp/docs/*.txt")
        assertThat(scan.skipped).isEmpty()
        val a = scan.documents.first()
        assertThat(a.uri).isEqualTo(tmp.resolve("docs/a.md").toAbsolutePath().toString())
        assertThat(a.sha).isEqualTo(Corpus.sha256("content of docs/a.md"))
    }

    @Test
    fun `oversized and non UTF-8 files are skipped with a reason, a missing root too`() {
        write("docs/big.md", "x".repeat(2_000))
        Files.write(tmp.resolve("docs/bin.md"), byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0xC0.toByte()))
        write("docs/ok.md")
        val scan = Corpus(maxBytes = 1_000).scan(listOf("$tmp/docs/*.md", "$tmp/nowhere/*.md"))
        assertThat(scan.documents.map { it.title }).containsExactly("ok.md")
        assertThat(scan.skipped).hasSize(3)
        assertThat(scan.skipped.joinToString()).contains("bytes > 1000").contains("not UTF-8").contains("not a directory")
    }

    @Test
    fun `the same file reached by two globs is indexed once`() {
        write("docs/a.md")
        val scan = Corpus().scan(listOf("$tmp/docs/*.md", "$tmp/**/*.md"))
        assertThat(scan.documents).hasSize(1)
    }
}
