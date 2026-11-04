package dev.lattenero.brain.eval

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EvalMetricsTest {
    @Test
    fun `percentiles are nearest-rank and total on empty input`() {
        assertThat(Percentiles.of(emptyList(), 50.0)).isZero()
        assertThat(Percentiles.of(listOf(7L), 95.0)).isEqualTo(7L)
        val samples = (1L..20L).toList()
        assertThat(Percentiles.of(samples, 0.0)).isEqualTo(1L)
        assertThat(Percentiles.of(samples, 50.0)).isEqualTo(10L)
        assertThat(Percentiles.of(samples, 95.0)).isEqualTo(19L)
        assertThat(Percentiles.of(samples, 100.0)).isEqualTo(20L)
    }

    @Test
    fun `percentiles do not depend on the order of the samples`() {
        val sorted = listOf(3L, 9L, 12L, 40L, 41L)
        val shuffled = listOf(41L, 3L, 40L, 12L, 9L)
        assertThat(Percentiles.of(shuffled, 50.0)).isEqualTo(Percentiles.of(sorted, 50.0))
        assertThat(Percentiles.of(shuffled, 95.0)).isEqualTo(Percentiles.of(sorted, 95.0))
        assertThat(Percentiles.of(shuffled, 50.0)).isLessThanOrEqualTo(Percentiles.of(shuffled, 95.0))
    }

    @Test
    fun `the declared price table makes local free and unknown unknown`() {
        val table = PriceTable.parse(
            """
            {"currency":"USD","entries":[
              {"provider":"ollama","model":"*","promptPer1m":0.0,"completionPer1m":0.0},
              {"provider":"glm","model":"*","promptPer1m":null,"completionPer1m":null}
            ]}
            """.trimIndent(),
        )
        assertThat(table.cost("ollama", "qwen2.5:3b", 1_000, 500)).isEqualTo(Cost(0.0, known = true))
        assertThat(table.cost("glm", "glm-4.5-flash", 1_000, 500)).isEqualTo(Cost(0.0, known = false))
        assertThat(table.cost("nobody", "nothing", 1, 1).known).isFalse()
    }

    @Test
    fun `an exact price wins over the provider wildcard`() {
        val table = PriceTable.parse(
            """
            {"entries":[
              {"provider":"glm","model":"*","promptPer1m":1.0,"completionPer1m":2.0},
              {"provider":"glm","model":"cheap","promptPer1m":0.5,"completionPer1m":0.5}
            ]}
            """.trimIndent(),
        )
        val cost = table.cost("glm", "cheap", 1_000_000, 1_000_000)
        assertThat(cost.known).isTrue()
        assertThat(cost.usd).isEqualTo(1.0)
        assertThat(table.cost("glm", "other", 1_000_000, 0).usd).isEqualTo(1.0)
    }

    @Test
    fun `the shipped table parses and declares local inference free`() {
        val table = PriceTable.load()
        assertThat(table.entries).isNotEmpty
        assertThat(table.cost("ollama", "qwen2.5:1.5b", 10_000, 10_000)).isEqualTo(Cost(0.0, known = true))
        assertThat(table.cost("ollama", "anything", 1, 1).known).isTrue()
    }

    @Test
    fun `token estimation is four characters per token, ceiling`() {
        assertThat(Tokens.estimate("")).isZero()
        assertThat(Tokens.estimate("abc")).isEqualTo(1L)
        assertThat(Tokens.estimate("abcd")).isEqualTo(1L)
        assertThat(Tokens.estimate("abcde")).isEqualTo(2L)
        assertThat(Tokens.estimate(listOf("abcd", "abc"))).isEqualTo(2L)
    }

    @Test
    fun `suite metrics aggregate latency, tokens and declared cost from the cases`() {
        val table = PriceTable.parse("""{"entries":[{"provider":"ollama","model":"*","promptPer1m":0.0,"completionPer1m":0.0}]}""")
        val cases = listOf(
            CaseResult(id = "a", ok = true, durationMs = 10, promptTokens = 100, completionTokens = 10),
            CaseResult(id = "b", ok = false, durationMs = 30, promptTokens = 50, completionTokens = 5),
            CaseResult(id = "c", ok = true, durationMs = 20),
        )
        val identity = ModelIdentity("qwen2.5:3b", "ollama", "nomic-embed-text")
        val metrics = SuiteMetricsCalculator.of(cases, table, identity, durationMs = 123)
        assertThat(metrics.p50Ms).isEqualTo(20)
        assertThat(metrics.p95Ms).isEqualTo(30)
        assertThat(metrics.promptTokens).isEqualTo(150)
        assertThat(metrics.completionTokens).isEqualTo(15)
        assertThat(metrics.costUsd).isZero()
        assertThat(metrics.costKnown).isTrue()
        assertThat(metrics.durationMs).isEqualTo(123)
        assertThat(metrics.supported).isNull()
        assertThat(metrics.supportChecked).isNull()
    }

    @Test
    fun `suite metrics count support only when the runner checked it`() {
        val cases = listOf(
            CaseResult(id = "a", ok = true, durationMs = 10, supported = true),
            CaseResult(id = "b", ok = true, durationMs = 20, supported = false),
            CaseResult(id = "c", ok = true, durationMs = 30, supported = null),
        )
        val metrics = SuiteMetricsCalculator.of(cases, PriceTable(), ModelIdentity("m", "ollama", "n"), 0)
        assertThat(metrics.supportChecked).isEqualTo(2)
        assertThat(metrics.supported).isEqualTo(1)
    }
}
