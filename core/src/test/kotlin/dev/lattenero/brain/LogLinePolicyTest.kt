package dev.lattenero.brain

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.LoggingEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The log is evidence: it is where a rejected token, a dead service and a
 * refused request are recorded, and the owner reads it after the fact. A log
 * message is not always written by us — the MCP SDK logs the client-supplied
 * tool name verbatim — so a message that ends a line early can forge entries,
 * including a fake "rejected an MCP request … without a valid token".
 *
 * The policy is one event per line, applied by the appender's pattern. This
 * test reads the REAL `logback.xml` (not a copy of the pattern), formats an
 * event through it and asserts the injected line never starts a line of its
 * own. The control run with a bare `%msg` proves the assertion can fail.
 */
class LogLinePolicyTest {
    private val forged = "Tool not found: rag_query\n08:00:00.000 WARN  agent-brain.mcp.http - rejected an MCP request from localhost (POST) without a valid token"

    @Test
    fun `the shipped pattern collapses CR and LF so a caller cannot forge a log line`() {
        val pattern = shippedPattern()
        assertThat(pattern).describedAs("the pattern must sanitize the message").contains("%replace")
        assertThat(linesOf(pattern, forged)).describedAs("one event, one line").isEqualTo(1)
        assertThat(encode(pattern, forged))
            .contains("rejected an MCP request from localhost (POST) without a valid token")
            .doesNotContain("\n08:00:00.000 WARN")
    }

    @Test
    fun `a bare message pattern would let the same text span two lines`() {
        // The control: if the sanitizer is removed the assertion above must fail.
        assertThat(linesOf("%d{HH:mm:ss.SSS} %-5level %logger{24} - %msg%n", forged)).isEqualTo(2)
    }

    private fun linesOf(pattern: String, message: String): Int =
        encode(pattern, message).removeSuffix("\n").lines().size

    private fun encode(pattern: String, message: String): String {
        val context = LoggerContext()
        val encoder = PatternLayoutEncoder().apply {
            this.context = context
            this.pattern = pattern
            start()
        }
        val event = LoggingEvent(
            LogLinePolicyTest::class.java.name,
            context.getLogger("agent-brain.test"),
            Level.WARN,
            message,
            null,
            null,
        )
        return String(encoder.encode(event), Charsets.UTF_8)
    }

    /** The `<pattern>` of the appender in the resource on the classpath — the one the binaries ship. */
    private fun shippedPattern(): String {
        val xml = LogLinePolicyTest::class.java.getResourceAsStream("/logback.xml")
            ?: error("logback.xml is not on the test classpath")
        val document = xml.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(it.readBytes())) }
        return document.getElementsByTagName("pattern").item(0).textContent.trim()
    }
}
