package dev.lattenero.brain.rag

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The decomposer's parse laws: the loop can only ADD recall, so a parse that
 * adds nothing (blank, unparsable, restating the question) is null and the
 * original leg stands alone. Items beyond the fan-out cap are dropped.
 */
class MultiHopParseTest {
    @Test
    fun `a valid decomposition returns its sub-queries`() {
        val parsed = MultiHop.parse(
            """{"queries":["breaker open attempt budget","giant transcript extraction splitting"]}""",
            "Does an open breaker consume a turn's budget and how are giants split?",
        )
        assertThat(parsed).containsExactly(
            "breaker open attempt budget",
            "giant transcript extraction splitting",
        )
    }

    @Test
    fun `blanks and duplicates collapse, items beyond the cap drop`() {
        val parsed = MultiHop.parse(
            """{"queries":["  ","timeout ownership","timeout ownership","a","b","c"]}""",
            "the question",
        )
        assertThat(parsed).containsExactly("timeout ownership", "a", "b", "c")
    }

    @Test
    fun `a decomposition that restates the question adds nothing and is null`() {
        val original = "Which model extracts memories and who owns the chat timeout?"
        assertThat(MultiHop.parse("""{"queries":["$original"]}""", original)).isNull()
        assertThat(MultiHop.parse("""{"queries":["  $original  "]}""", original)).isNull()
        assertThat(MultiHop.parse("""{"queries":[]}""", original)).isNull()
    }

    @Test
    fun `an unparsable or schema-missing answer is null, never a throw`() {
        assertThat(MultiHop.parse("the decomposer rambled", "q")).isNull()
        assertThat(MultiHop.parse("""{"other":[]}""", "q")).isNull()
    }
}
