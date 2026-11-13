package dev.lattenero.brain.bus

import dev.lattenero.brain.config.Config
import dev.lattenero.brain.config.ConfigException

/**
 * The bus transport as a sum (was: a string knob compared at every branch,
 * with a nullable consumer the code had to `!!` away — an invalid state the
 * type now refuses to represent). [Config] still validates the knob (it
 * fails loudly on an unknown wire, its `bus_transport` validation is the
 * parse edge); [parse] is the typed conversion at the composition root, so
 * no branch downstream re-reads the string.
 */
sealed interface BusTransport {
    /** The knob's wire value, for status reports and metrics. */
    val wire: String

    data object File : BusTransport {
        override val wire: String get() = Config.BUS_TRANSPORT_FILE
    }

    class Kafka(val consumer: KafkaBusConsumer) : BusTransport {
        override val wire: String get() = Config.BUS_TRANSPORT_KAFKA
    }

    companion object {
        /**
         * The typed conversion of the validated knob. [consumer] is only
         * built when the transport is kafka — the file tailer never
         * constructs a Kafka client (and building it may suspend: the
         * consumer holds kap `Ref` state).
         */
        suspend fun parse(
            wire: String,
            consumer: suspend () -> KafkaBusConsumer,
        ): BusTransport = when (wire) {
            Config.BUS_TRANSPORT_FILE -> File
            Config.BUS_TRANSPORT_KAFKA -> Kafka(consumer())
            else -> throw ConfigException(
                "bus_transport must be ${Config.BUS_TRANSPORTS.joinToString(" | ")}, got '$wire'",
            )
        }
    }
}
