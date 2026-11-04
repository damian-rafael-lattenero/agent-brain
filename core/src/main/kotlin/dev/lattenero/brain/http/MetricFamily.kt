package dev.lattenero.brain.http

import java.math.BigDecimal

/**
 * The exposition GRAMMAR (Prometheus text format v0.0.4), as values: one
 * [MetricFamily] is one `# HELP` + `# TYPE` + its samples, and [renderExposition]
 * folds a list of them into the scraped body. No state, no I/O — the
 * inventory in MetricsInventory.kt is the only thing that knows what the
 * brain can measure.
 */
internal data class MetricFamily(
    val name: String,
    val help: String,
    val type: String,
    val samples: List<String>,
)

/** One family with one sample per call site — the usual case. */
internal fun family(name: String, help: String, type: String, vararg samples: String): MetricFamily =
    MetricFamily(name, help, type, samples.toList())

/** A family's samples as one block, each line ending in the format's newline. */
internal fun renderExposition(families: List<MetricFamily>): String = buildString {
    families.forEach { family ->
        append(HELP_PREFIX).append(family.name).append(' ').append(family.help).append('\n')
        append(TYPE_PREFIX).append(family.name).append(' ').append(family.type).append('\n')
        family.samples.forEach { sample -> append(sample).append('\n') }
    }
}

/** One sample line: `name` or `name{labels}`, a space, the value. */
internal fun sample(name: String, labels: Map<String, String>, value: String): String =
    if (labels.isEmpty()) {
        "$name $value"
    } else {
        val rendered = labels.entries.joinToString(SEPARATOR) { "${it.key}=\"${escapeLabel(it.value)}\"" }
        "$name{$rendered} $value"
    }

internal fun sample(name: String, labels: Map<String, String>, value: Long): String =
    sample(name, labels, value.toString())

internal fun sample(name: String, labels: Map<String, String>, value: Int): String =
    sample(name, labels, value.toString())

/** Label values escape backslash, quote and newline — the exposition format's own law. */
internal fun escapeLabel(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

/** A boolean is the state itself: 1/0, never a guess. */
internal fun flag(value: Boolean): String = if (value) "1" else "0"

/** Milliseconds as seconds with three decimals, plain-notation — no locale, no exponent. */
internal fun seconds(millis: Long): String = BigDecimal.valueOf(millis, SECONDS_SCALE).toPlainString()

internal const val TYPE_COUNTER = "counter"
internal const val TYPE_GAUGE = "gauge"

private const val SECONDS_SCALE = 3
private const val SEPARATOR = ","
private const val HELP_PREFIX = "# HELP "
private const val TYPE_PREFIX = "# TYPE "
