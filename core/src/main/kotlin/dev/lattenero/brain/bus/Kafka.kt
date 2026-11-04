package dev.lattenero.brain.bus

import dev.lattenero.brain.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kap.Kap
import kap.KapStream
import kap.Process
import kap.Progress
import kap.Ref
import kap.RestartPolicy
import kap.Schedule
import kap.evalGraph
import kap.process
import kap.recover
import kap.repeat
import kap.repeatUntil
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.Properties
import java.util.concurrent.ExecutionException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration as KDuration

private val log = LoggerFactory.getLogger("agent-brain.kafka")

/** Topic creation is idempotent; Redpanda would auto-create, but partitions are a decision, not a default. */
object KafkaTopics {
    fun ensure(config: Config, topic: String = config.kafkaTopic) {
        val props = Properties().apply {
            put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrap)
            put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, ADMIN_TIMEOUT_MS)
            put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, ADMIN_TIMEOUT_MS)
        }
        AdminClient.create(props).use { admin ->
            try {
                admin.createTopics(listOf(NewTopic(topic, config.kafkaPartitions, 1.toShort()))).all().get()
            } catch (e: ExecutionException) {
                if (e.cause !is TopicExistsException) throw e
            }
        }
    }

    fun reachable(bootstrap: String): Boolean = try {
        val props = Properties().apply {
            put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
            put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, PROBE_TIMEOUT_MS)
            put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, PROBE_TIMEOUT_MS)
        }
        AdminClient.create(props).use { it.listTopics().names().get() }
        true
    } catch (e: Exception) {
        false
    }

    const val ADMIN_TIMEOUT_MS = 10_000
    const val PROBE_TIMEOUT_MS = 3_000
}

/**
 * The bridge: tails the SAME bus file with its own offset and produces every
 * event to the topic, keyed by session id so one session stays ordered on one
 * partition. The producer is idempotent and waits for all replicas; a crash
 * before the offset is persisted re-produces a few events, which the
 * consumer's database dedup absorbs (ADR-003).
 */
class KafkaBridge private constructor(
    private val config: Config,
    private val topic: String,
    val tailer: FileTailer,
    val produced: Ref<Long>,
) : AutoCloseable {
    private val producer: KafkaProducer<String, String> by lazy {
        KafkaProducer<String, String>(
            Properties().apply {
                put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrap)
                put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                put(ProducerConfig.ACKS_CONFIG, "all")
                put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
                put(ProducerConfig.LINGER_MS_CONFIG, LINGER_MS)
                put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, DELIVERY_TIMEOUT_MS)
            },
        )
    }

    /** One poll of the file; returns the events produced. The offset moves only after Kafka acknowledged. */
    suspend fun poll(): Int = tailer.poll { events ->
        withContext(Dispatchers.IO) {
            val futures = events.map { event ->
                producer.send(ProducerRecord(topic, event.sid.ifEmpty { null }, json.encodeToString(BusEvent.serializer(), event)))
            }
            producer.flush()
            futures.forEach { it.get() }
            produced.update { it + events.size }.evalGraph()
        }
    }

    /**
     * Drains the file into the topic; returns the events produced. The
     * repeated body is stateless — one poll — and the loop terminates on the
     * returned poll count. The drain's total is not a closure counter: [poll]
     * already folds every produced batch into the [produced] `Ref`, so the
     * caller folds the total from the counter's before/after values (Phase D:
     * state as values, never side effects on captured vars). Single-shot: no
     * other poll may run on this bridge while the drain's two reads bracket
     * the loop.
     */
    suspend fun drain(): Int {
        val before = produced.get().evalGraph()
        Kap { poll() }
            .repeatUntil { it == 0 }
            .evalGraph()
        return (produced.get().evalGraph() - before).toInt()
    }

    suspend fun run(interval: KDuration) {
        Kap { poll() }
            .recover { 0 }
            .repeat(Schedule.spaced<Int>(interval))
            .evalGraph()
    }

    override fun close() = producer.close(Duration.ofSeconds(CLOSE_TIMEOUT_S))

    companion object {
        private val json = Json { encodeDefaults = true }
        const val LINGER_MS = 20
        const val DELIVERY_TIMEOUT_MS = 60_000 // must cover linger + request.timeout (30 s default)
        const val CLOSE_TIMEOUT_S = 10L

        /** Construction is a [Kap]: the tailer's stats and the produced counter are kap `Ref` state. */
        fun of(config: Config, topic: String = config.kafkaTopic): Kap<KafkaBridge> = Kap {
            val bridgeTailer = FileTailer.of(
                config.busFile,
                OffsetStore(config.stateDir.resolve("bus-offset-bridge.json")),
            ).evalGraph()
            KafkaBridge(
                config,
                topic,
                tailer = bridgeTailer,
                produced = Ref.of(0L).evalGraph(),
            )
        }
    }
}

/** The consumer's committed counters as one immutable value (the [KafkaStats] read surface). */
data class KafkaCounters(
    val records: Long = 0,
    val malformed: Long = 0,
    val commits: Long = 0,
    val lastOffsets: Map<Int, Long> = emptyMap(),
)

/**
 * Business counters (what the consumer committed), not liveness: liveness is
 * the process handle's restarts and heartbeat age (see [BusStatus]). The state
 * is one kap [Ref] over [KafkaCounters] (Phase G: "true business counters stay
 * plain until kap Ref") — one atomic update per committed batch.
 */
class KafkaStats private constructor(private val state: Ref<KafkaCounters>) {
    suspend fun accept(recordsCount: Int, malformed: Int, offsets: Map<Int, Long>) {
        state.update {
            it.copy(
                records = it.records + recordsCount,
                malformed = it.malformed + malformed,
                commits = it.commits + 1,
                lastOffsets = offsets,
            )
        }.evalGraph()
    }

    /** The counters as a value; the /bus/status surface reads this. */
    suspend fun snapshot(): KafkaCounters = state.get().evalGraph()

    companion object {
        fun of(): Kap<KafkaStats> = Kap { KafkaStats(Ref.of(KafkaCounters()).evalGraph()) }
    }
}

/**
 * The quiet-window drain over the polls: poll until [quietPolls] consecutive
 * polls come back empty, return the total the polls handed over.
 *
 * Since kap 5.1 this is the unlock the retired interim asked for — the
 * hand-rolled `DrainProgress` fold plus `repeatUntil` is gone, and the loop is
 * a [KapStream.pollUntilQuiet] whose stop condition is
 * [Schedule.debounce] over the report's own `quiet` flag. The laws come from
 * the kap combinators, not from this adapter:
 *
 * - **Cadence** is the schedule's, and the policy asks for none: the current
 *   drain re-polls immediately (each poll blocks up to `DRAIN_POLL_MS` inside
 *   the consumer), so the schedule is the bare window, whose `Continue` delay
 *   is zero.
 * - **The window** counts consecutive quiet reports, wherever the run starts:
 *   `Schedule.debounce(quietPolls) { it.quiet }` decides `Done` exactly on the
 *   [quietPolls]-th consecutive empty poll and a report with work resets it.
 * - **The total** is the sum of the emitted reports, and only reports with
 *   work are emitted ([Progress.total] is that poll's count, never a running
 *   figure) — so the quiet polls contribute nothing by construction.
 *
 * [Schedule.debounce] is stateful (it remembers the run), so the schedule is
 * built fresh per call: two concurrent drains must never share one.
 */
internal suspend fun drainUntilQuiet(quietPolls: Int, poll: suspend () -> Int): Int =
    KapStream.pollUntilQuiet(
        progress = { poll().let { count -> Progress(total = count, quiet = count == 0) } },
        schedule = Schedule.debounce<Progress>(quietPolls) { it.quiet },
    ).collectAsKap()
        .evalGraph()
        .sumOf { it.total }

/**
 * The Kafka transport feeds the SAME handler the file tailer feeds
 * (`PgBusIngest.handle`). Offsets are committed only after the batch is in
 * the database, so a rebalance or a crash redelivers and the primary key
 * absorbs it: at-least-once in, exactly-once in the table.
 *
 * A consumer is not thread-safe and a crash must not leave a half-closed one
 * behind, so every use opens its own and closes it in a NonCancellable
 * finally: the supervised loop gets a fresh consumer per body execution,
 * `drain` gets one for the whole drain.
 */
class KafkaBusConsumer private constructor(
    private val config: Config,
    private val ingest: PgBusIngest,
    private val topic: String,
    val stats: KafkaStats,
) {

    /** One poll on a fresh consumer (tests, `ingest --once`). */
    suspend fun poll(timeout: Duration = Duration.ofMillis(POLL_MS)): Int =
        withConsumer { consumer -> pollOnce(consumer, timeout) }

    /**
     * Polls until [quietPolls] consecutive polls return nothing (tests,
     * `ingest --once`): the quiet-window drain over one fresh consumer (see
     * [drainUntilQuiet]) — a kap [KapStream.pollUntilQuiet] stopped by
     * [Schedule.debounce], whose emitted reports carry the work.
     */
    suspend fun drain(quietPolls: Int = DRAIN_QUIET_POLLS): Int = withConsumer { consumer ->
        drainUntilQuiet(quietPolls) { pollOnce(consumer, Duration.ofMillis(DRAIN_POLL_MS)) }
    }

    /**
     * The supervised consume loop: a fresh consumer per body execution, closed
     * on failure and on cancellation; a failure restarts the body per policy.
     */
    fun process(restart: RestartPolicy = defaultRestart()): Process<Int> =
        Kap { consume() }
            .process(
                name = PROCESS_NAME,
                restart = restart,
                heartbeatEvery = HEARTBEAT_EVERY,
                onRestart = { attempt, error, nextDelay ->
                    log.warn(
                        "kafka consumer failed (restart {} in {}): {}: {}",
                        attempt, nextDelay, error::class.simpleName, error.message,
                    )
                },
            )

    /** Runs the process for the caller's lifetime; cancellation stops it. */
    suspend fun run() {
        coroutineScope { process().startIn(this).await() }
    }

    private suspend fun consume(): Int = withConsumer { consumer ->
        Kap { pollOnce(consumer, Duration.ofMillis(POLL_MS)) }
            .repeat(Schedule.spaced(RETRY_DELAY))
            .evalGraph()
    }

    /** One poll: parse, hand the batch to the ingest handler, commit. Returns records seen. */
    private suspend fun pollOnce(consumer: KafkaConsumer<String, String>, timeout: Duration): Int {
        val records = withContext(Dispatchers.IO) { consumer.poll(timeout) }
        if (records.isEmpty) return 0
        val events = ArrayList<BusEvent>()
        var bad = 0
        for (record in records) {
            when (val parsed = BusEvents.parse(record.value() ?: "")) {
                is BusEvents.Parsed.Ok -> events += parsed.event
                is BusEvents.Parsed.Bad -> {
                    bad += 1
                    log.warn("malformed record skipped at {}-{}@{}: {}", record.topic(), record.partition(), record.offset(), parsed.reason)
                }
            }
        }
        ingest.handle(events)
        withContext(Dispatchers.IO) { consumer.commitSync() }
        stats.accept(
            records.count(),
            bad,
            records.partitions().associate { tp -> tp.partition() to records.records(tp).last().offset() },
        )
        return records.count()
    }

    /** Opens a consumer for exactly this block and closes it even on failure or cancellation. */
    private suspend fun <T> withConsumer(block: suspend (KafkaConsumer<String, String>) -> T): T {
        val consumer = KafkaConsumer<String, String>(
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrap)
                put(ConsumerConfig.GROUP_ID_CONFIG, config.kafkaGroup)
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, MAX_POLL_RECORDS)
            },
        ).also { it.subscribe(listOf(topic)) }
        return try {
            block(consumer)
        } finally {
            withContext(Dispatchers.IO + NonCancellable) { closeQuietly(consumer) }
        }
    }

    private fun closeQuietly(consumer: KafkaConsumer<String, String>) {
        try {
            consumer.close(org.apache.kafka.clients.consumer.CloseOptions.timeout(Duration.ofSeconds(CLOSE_TIMEOUT_S)))
        } catch (e: Exception) {
            log.debug("consumer close: {}", e.message)
        }
    }

    companion object {
        const val PROCESS_NAME = "bus-kafka-consumer"
        const val POLL_MS = 500L
        const val DRAIN_POLL_MS = 1_000L
        const val DRAIN_QUIET_POLLS = 3
        const val MAX_POLL_RECORDS = 500
        const val CLOSE_TIMEOUT_S = 10L

        /** Construction is a [Kap]: the stats are kap `Ref` state (see [KafkaStats]). */
        fun of(config: Config, ingest: PgBusIngest, topic: String = config.kafkaTopic): Kap<KafkaBusConsumer> =
            Kap { KafkaBusConsumer(config, ingest, topic, KafkaStats.of().evalGraph()) }

        /** The heartbeat answers "is the process there", never "did the cycle finish". */
        val HEARTBEAT_EVERY = 5.seconds

        /** Positive base, exponential backoff, finite cap: the convergence law. */
        fun defaultRestart(): RestartPolicy =
            RestartPolicy.exponential(base = RETRY_DELAY, max = RESTART_MAX_DELAY, maxRestarts = RESTART_MAX)

        val RETRY_DELAY = 1.seconds
        val RESTART_MAX_DELAY = 30.seconds
        const val RESTART_MAX = 8
    }
}
