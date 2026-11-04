package dev.lattenero.brain.eval

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class EvalSuitesTest {
    @Test
    fun `the shipped suites parse, validate and map to their axes`() {
        val suites = EvalSuites.shipped()
        assertThat(suites.map { it.name }).containsExactly("recall-core", "extraction-core", "grounded-citation")
        assertThat(suites.map { it.kind }).containsExactly(EvalKind.RECALL, EvalKind.EXTRACTION, EvalKind.GROUNDED)
        assertThat(suites.map { it.axis }).containsExactly(EvalAxis.EMBED, EvalAxis.CHAT, EvalAxis.CHAT)
        assertThat(suites.map { it.gate }).allSatisfy { assertThat(it).isBetween(0.0, 1.0) }
        suites.forEach { suite -> assertThat(suite.cases).isNotEmpty }
        assertThat(suites.first { it.kind == EvalKind.RECALL }.cases).hasSize(20)
    }

    @Test
    fun `a suite file on disk wins over the classpath`(@TempDir dir: Path) {
        val file = dir.resolve("mini.json")
        Files.writeString(
            file,
            """
            {"name":"mini","kind":"recall","gate":0.5,"cases":[
              {"type":"recall","id":"one","question":"where is the lock?","uri":"/migrator-lock.md"}
            ]}
            """.trimIndent(),
        )
        val suite = EvalSuites.load(file.toString())
        assertThat(suite.name).isEqualTo("mini")
        assertThat(suite.cases).hasSize(1)
        assertThat(suite.axis).isEqualTo(EvalAxis.EMBED)
    }

    @Test
    fun `an unknown suite names the shipped ones`() {
        assertThatThrownBy { EvalSuites.load("nope") }
            .isInstanceOf(EvalSuiteException::class.java)
            .hasMessageContaining("recall-core")
    }

    @Test
    fun `duplicate case ids and an out-of-range gate are rejected`() {
        val duplicates = """
            {"name":"dup","kind":"recall","gate":0.5,"cases":[
              {"type":"recall","id":"same","question":"a","uri":"/a.md"},
              {"type":"recall","id":"same","question":"b","uri":"/b.md"}
            ]}
        """.trimIndent()
        assertThatThrownBy { EvalSuites.parse(duplicates, "dup").validate() }
            .isInstanceOf(EvalSuiteException::class.java)
            .hasMessageContaining("duplicate case ids")

        val badGate = """
            {"name":"bad","kind":"recall","gate":1.5,"cases":[
              {"type":"recall","id":"one","question":"a","uri":"/a.md"}
            ]}
        """.trimIndent()
        assertThatThrownBy { EvalSuites.parse(badGate, "bad").validate() }
            .isInstanceOf(EvalSuiteException::class.java)
            .hasMessageContaining("gate must be within")
    }

    @Test
    fun `a case of another kind and a grounded case without needles are rejected`() {
        val mixed = """
            {"name":"mixed","kind":"extraction","gate":0.5,"cases":[
              {"type":"recall","id":"one","question":"a","uri":"/a.md"}
            ]}
        """.trimIndent()
        assertThatThrownBy { EvalSuites.parse(mixed, "mixed").validate() }
            .isInstanceOf(EvalSuiteException::class.java)
            .hasMessageContaining("does not match the suite kind 'extraction'")

        val unverifiable = """
            {"name":"weak","kind":"grounded","gate":0.5,"cases":[
              {"type":"grounded","id":"one","question":"a","uri":"/a.md","needles":[]}
            ]}
        """.trimIndent()
        assertThatThrownBy { EvalSuites.parse(unverifiable, "weak").validate() }
            .isInstanceOf(EvalSuiteException::class.java)
            .hasMessageContaining("needs at least one needle")
    }

    @Test
    fun `a malformed suite reports the source and never parses silently`() {
        assertThatThrownBy { EvalSuites.parse("""{"name":"x","kind":"recall"}""", "broken") }
            .isInstanceOf(EvalSuiteException::class.java)
            .hasMessageContaining("broken")
    }
}
