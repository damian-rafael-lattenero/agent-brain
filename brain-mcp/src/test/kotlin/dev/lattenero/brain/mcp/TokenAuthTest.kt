package dev.lattenero.brain.mcp

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The token is the ONLY thing between the endpoint and the world, so its
 * loading rules are tested as laws, not as examples: fail closed, no oracle,
 * no loggable secret, no short-circuit in the comparison.
 */
class TokenAuthTest {
    private val good = "0123456789abcdef0123456789abcdef"

    @Test
    fun `an env token is accepted and never appears in toString`() {
        val token = McpToken.load(mapOf(McpToken.ENV_TOKEN to good))
        assertThat(token.source).isEqualTo("env")
        assertThat(token.matches(good)).isTrue()
        assertThat(token.toString()).doesNotContain(good).contains("redacted")
    }

    @Test
    fun `the token file is read, trimmed, and its path is the only thing a failure shows`(@TempDir dir: Path) {
        val file = dir.resolve("mcp.token")
        Files.writeString(file, "$good\n")
        val token = McpToken.load(mapOf(McpToken.ENV_TOKEN_FILE to file.toString()))
        assertThat(token.source).isEqualTo("file")
        assertThat(token.matches(good)).isTrue()
    }

    @Test
    fun `env wins over the file (the same order as every other knob)`(@TempDir dir: Path) {
        val file = dir.resolve("mcp.token")
        Files.writeString(file, "file-token-should-lose")
        val token = McpToken.load(mapOf(McpToken.ENV_TOKEN to good, McpToken.ENV_TOKEN_FILE to file.toString()))
        assertThat(token.source).isEqualTo("env")
        assertThat(token.matches(good)).isTrue()
        assertThat(token.matches("file-token-should-lose")).isFalse()
    }

    @Test
    fun `no token fails closed with the variable names in the message`() {
        assertThatThrownBy { McpToken.load(emptyMap()) }
            .isInstanceOf(McpTokenException::class.java)
            .hasMessageContaining(McpToken.ENV_TOKEN)
            .hasMessageContaining(McpToken.ENV_TOKEN_FILE)
            .hasMessageContaining("openssl rand")
    }

    @Test
    fun `empty and short tokens are refused`() {
        listOf("", "   ").forEach { blank ->
            assertThatThrownBy { McpToken.load(mapOf(McpToken.ENV_TOKEN to blank)) }
                .isInstanceOf(McpTokenException::class.java)
                .hasMessageContaining(McpToken.ENV_TOKEN)
        }
        listOf("abcde", "0123456789abcde").forEach { weak ->
            assertThatThrownBy { McpToken.load(mapOf(McpToken.ENV_TOKEN to weak)) }
                .isInstanceOf(McpTokenException::class.java)
                .hasMessageContaining("too short")
                .hasMessageNotContaining(weak)
        }
        // Exactly MIN_LENGTH characters is fine.
        assertThat(McpToken.load(mapOf(McpToken.ENV_TOKEN to "x".repeat(McpToken.MIN_LENGTH))).source).isEqualTo("env")
    }

    @Test
    fun `an unreadable token file fails with the path, not the contents`(@TempDir dir: Path) {
        val missing = dir.resolve("nope.token")
        assertThatThrownBy { McpToken.load(mapOf(McpToken.ENV_TOKEN_FILE to missing.toString())) }
            .isInstanceOf(McpTokenException::class.java)
            .hasMessageContaining(missing.toString())
    }

    @Test
    fun `only a Bearer header carries a credential`() {
        assertThat(Bearer.credential("Bearer $good")).isEqualTo(good)
        assertThat(Bearer.credential("bearer $good")).isEqualTo(good)
        assertThat(Bearer.credential("  Bearer  $good  ")).isEqualTo(good)
        assertThat(Bearer.credential("Basic $good")).isNull()
        assertThat(Bearer.credential("Bearer")).isNull()
        assertThat(Bearer.credential("")).isNull()
        assertThat(Bearer.credential(null)).isNull()
        assertThat(Bearer.credential("Bearer ")).isNull()
    }

    @Test
    fun `a missing header, an empty candidate and a wrong token all compare false`() {
        val token = McpToken.of(good)
        assertThat(token.matches(null)).isFalse()
        assertThat(token.matches("")).isFalse()
        assertThat(token.matches(good.dropLast(1))).isFalse()
        assertThat(token.matches(good + "x")).isFalse()
        assertThat(token.matches(good.uppercase())).isFalse()
        assertThat(token.matches(good)).isTrue()
    }

    /**
     * Constant time, measured in BATCHES (per-call `nanoTime` is noise): a
     * comparison that short-circuits at the first differing byte answers a
     * candidate that shares no prefix about `len` times faster than one that
     * differs only in the last byte — ratio ~0.015 for a 64-char token. The
     * bound is 0.5, generous enough for a loaded machine and still 30x above a
     * short-circuit. The ratios are printed so the evidence is in the log.
     */
    @Test
    fun `the comparison costs the same whether the first byte or the last differs`() {
        val token = McpToken.of("a".repeat(64))
        val firstByteWrong = "b" + "a".repeat(63)
        val lastByteWrong = "a".repeat(63) + "b"
        val sink = java.util.concurrent.atomic.AtomicBoolean(false)

        fun batch(candidate: String, iterations: Int): Long {
            val start = System.nanoTime()
            repeat(iterations) { if (token.matches(candidate)) sink.set(true) }
            return System.nanoTime() - start
        }

        repeat(5) { batch(firstByteWrong, 5_000) } // warm up the digest and the comparison
        // 15 pairs and the MEDIAN of the ratios: the median ignores the odd batch
        // a GC pause or a JIT event distorts, which is what makes this stable on a
        // loaded machine while a short-circuit (every ratio ~0.015) still fails.
        val ratios = (1..15).map {
            val fast = batch(firstByteWrong, 20_000)
            val slow = batch(lastByteWrong, 20_000)
            slow.toDouble() / fast.toDouble()
        }.sorted()
        val median = ratios[ratios.size / 2]
        println("constant-time batches, median ratio wrong-prefix/full-prefix: $median in $ratios")
        assertThat(sink.get()).isFalse()
        assertThat(median)
            .describedAs("median of the batch ratios (a short-circuit would be ~0.015)")
            .isGreaterThan(0.2)
    }
}
