package dev.lattenero.brain.eval

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The corpus doctor's law battery: the diagnosis parser is TOTAL (parse, don't
 * validate — any judge string decides or degrades, never throws), the schema's
 * vocabulary decides (case and whitespace do not matter), and the report
 * renders the truth it was built from (the counts a human reads match the
 * cases the machine kept). Block-bodied tests throughout — an expression-bodied
 * test returns the assert and JUnit skips it silently
 * (the house gate [TestMethodsReturnUnitGate] says so loudly).
 */
class CorpusDoctorLawsTest {

    /** LAW 1 — totality: ANY string parses to a verdict with a cause; nothing throws. */
    @Test
    fun `law 1 - any string parses to a verdict`() = runBlocking<Unit> {
        checkAll(Arb.string(0..160)) { raw ->
            val verdict = CorpusDoctor.DoctorVerdict.parse(raw)
            assertThat(verdict.cause).isIn(DoctorCause.entries)
        }
    }

    /** LAW 2 — the vocabulary decides: every wire parses back, case/whitespace irrelevant. */
    @Test
    fun `law 2 - the vocabulary decides`() {
        DoctorCause.entries.filter { it != DoctorCause.UNCLASSIFIED }.forEach { cause ->
            listOf(cause.wire, cause.wire.uppercase(), "  ${cause.wire}\n").forEach { wire ->
                val verdict = CorpusDoctor.DoctorVerdict.parse(
                    """{"cause":"$wire","target_uri":"docs/a.md","proposal":"add the section"}""",
                )
                assertThat(verdict.cause).isEqualTo(cause)
                assertThat(verdict.targetUri).isEqualTo("docs/a.md")
                assertThat(verdict.proposal).isEqualTo("add the section")
            }
        }
    }

    /** LAW 3 — unreadable answers degrade honestly to UNCLASSIFIED with empty fields. */
    @Test
    fun `law 3 - unreadable answers degrade to unclassified`() {
        val degradations = listOf(
            "",
            "the judge rambled instead of JSON",
            "[1, 2, 3]",
            "{}",
            """{"other":"field"}""",
            """{"cause":42}""",
        )
        for (raw in degradations) {
            val verdict = CorpusDoctor.DoctorVerdict.parse(raw)
            assertThat(verdict.cause).isEqualTo(DoctorCause.UNCLASSIFIED)
            assertThat(verdict.targetUri).isEmpty()
            assertThat(verdict.proposal).isEmpty()
        }
        // An unknown wire ALSO degrades: the vocabulary decides, not the spelling.
        assertThat(CorpusDoctor.DoctorVerdict.parse("""{"cause":"meh"}""").cause)
            .isEqualTo(DoctorCause.UNCLASSIFIED)
        // A non-string FIELD is noise, dropped — the cause that DID parse keeps its signal.
        val noisy = CorpusDoctor.DoctorVerdict.parse("""{"cause":"corpus_gap","target_uri":7}""")
        assertThat(noisy.cause).isEqualTo(DoctorCause.CORPUS_GAP)
        assertThat(noisy.targetUri).isEmpty()
    }

    /** LAW 4 — the report renders the truth: the markdown and the cause map match the cases. */
    @Test
    fun `law 4 - the report renders the cases it holds`() {
        val report = fixtureReport(
            windowDays = 7, gradedTotal = 40, failedTotal = 3,
            cases = listOf(
                fixtureCase(cause = DoctorCause.CORPUS_GAP, proposal = "create a note about the tailer"),
                fixtureCase(cause = DoctorCause.STALE_CHUNK, targetUri = "docs/old.md", proposal = "refresh the section"),
                fixtureCase(cause = DoctorCause.UNCLASSIFIED, evidence = "judge call failed: boom"),
            ),
        )
        val markdown = report.markdown()
        assertThat(markdown).contains("7d window")
        assertThat(markdown).contains("Graded 40 production queries · 3 failures")
        assertThat(markdown).contains("corpus_gap")
        assertThat(markdown).contains("stale_chunk")
        assertThat(markdown).contains("unclassified")
        assertThat(markdown).contains("judge call failed: boom")
        assertThat(markdown).contains("create a note about the tailer")
        // The cause map rides the same truth, keyed by the wire the script announces.
        assertThat(report.causes["corpus_gap"]).isEqualTo(1)
        assertThat(report.causes["stale_chunk"]).isEqualTo(1)
        assertThat(report.causes["unclassified"]).isEqualTo(1)
        assertThat(report.causes["phrasing_mismatch"]).isEqualTo(0)
        assertThat(report.causes.values.sum()).isEqualTo(3)
        // The failure rate is failures over graded, not over diagnosed.
        assertThat(report.failureRate).isEqualTo(0.075)
    }

    /** LAW 5 — LAW_GAP parses from its wire, proposes a RULE sentence, and the degraded bucket stays last. */
    @Test
    fun `law 5 - law_gap parses from its wire with the rule proposal`() {
        val verdict = CorpusDoctor.DoctorVerdict.parse(
            """{"cause":"law_gap","target_uri":"","proposal":"Always write code comments in English."}""",
        )
        assertThat(verdict.cause).isEqualTo(DoctorCause.LAW_GAP)
        assertThat(verdict.proposal).isEqualTo("Always write code comments in English.")
        // The vocabulary decides: case and whitespace do not matter (LAW 2's rule, pinned for LAW_GAP).
        assertThat(CorpusDoctor.DoctorVerdict.parse("""{"cause":" LAW_GAP\n"}""").cause)
            .isEqualTo(DoctorCause.LAW_GAP)
        // A typo is NOT law_gap: the unknown wire degrades to the honest bucket...
        assertThat(CorpusDoctor.DoctorVerdict.parse("""{"cause":"lawgap"}""").cause)
            .isEqualTo(DoctorCause.UNCLASSIFIED)
        // ...which stays LAST in the taxonomy, after every real cause (plan U8).
        assertThat(DoctorCause.entries.last()).isEqualTo(DoctorCause.UNCLASSIFIED)
        assertThat(DoctorCause.entries[DoctorCause.entries.size - 2]).isEqualTo(DoctorCause.LAW_GAP)
    }

    /** LAW 6 — an empty window renders an honest clean bill, never an empty table. */
    @Test
    fun `law 6 - an empty window renders the clean bill`() {
        val report = fixtureReport(windowDays = 7, gradedTotal = 12, failedTotal = 0, cases = emptyList())
        assertThat(report.markdown()).contains("No failures in the window")
        assertThat(report.causes.values.sum()).isEqualTo(0)
        assertThat(report.failureRate).isEqualTo(0.0)
    }
}

/** Fixtures kept tiny on purpose: the laws above build reports by hand. */
private fun fixtureReport(
    windowDays: Int,
    gradedTotal: Int,
    failedTotal: Int,
    cases: List<CorpusDoctor.DoctorCase>,
): CorpusDoctor.DoctorReport = CorpusDoctor.DoctorReport(
    windowDays = windowDays,
    gradedTotal = gradedTotal,
    failedTotal = failedTotal,
    truncated = false,
    causes = DoctorCause.entries.associate { cause -> cause.wire to cases.count { it.cause == cause } },
    cases = cases,
    model = "fake-chat",
    provider = "fake",
    startedAt = "2026-11-22T04:30:00Z",
    durationMs = 1200,
)

private fun fixtureCase(
    cause: DoctorCause,
    targetUri: String? = null,
    proposal: String? = null,
    evidence: String? = null,
): CorpusDoctor.DoctorCase = CorpusDoctor.DoctorCase(
    query = "how does the tailer handle truncation?",
    at = "2026-11-21T10:00:00Z",
    servedUris = listOf("docs/tailer.md"),
    cause = cause,
    targetUri = targetUri,
    proposal = proposal,
    evidence = evidence,
)
