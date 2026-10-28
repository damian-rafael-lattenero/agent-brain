package dev.lattenero.brain.bus

import dev.lattenero.brain.config.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kap.Kap
import kap.Schedule
import kap.evalGraph
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
import java.util.concurrent.atomic.AtomicLong
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
class KafkaBridge(private val config: Config, private val topic: String = config.kafkaTopic) : AutoCloseable {
    val tailer: FileTailer = FileTailer(config.busFile, OffsetStore(config.stateDir.resolve("bus-offset-bridge.json")))
    val produced = AtomicLong()
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
            produced.addAndGet(events.size.toLong())
        }
    }

    suspend fun drain(): Int {
        var total = 0
        Kap { poll().also { total += it } }
            .repeatUntil { it == 0 }
            .evalGraph()
        return total
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
    }
}

class KafkaStats {
    val polls = AtomicLong()
    val records = AtomicLong()
    val malformed = AtomicLong()
    val commits = AtomicLong()

    @Volatile
    var lastOffsets: Map<Int, Long> = emptyMap()
}

/**
 * The Kafka transport feeds the SAME handler the file tailer feeds
 * (`PgBusIngest.handle`). Offsets are committed only after the batch is in
 * the database, so a rebalance or a crash redelivers and the primary key
 * absorbs it: at-least-once in, exactly-once in the table.
 */
class KafkaBusConsumer(private val config: Config, private val ingest: PgBusIngest, private val topic: String = config.kafkaTopic) : AutoCloseable {
    val stats = KafkaStats()
    private val consumer: KafkaConsumer<String, String> by lazy {
        KafkaConsumer<String, String>(
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
    }

    /** One poll: parse, hand the batch to the ingest handler, commit. Returns records seen. */
    suspend fun poll(timeout: Duration = Duration.ofMillis(POLL_MS)): Int {
        val records = withContext(Dispatchers.IO) { consumer.poll(timeout) }
        stats.polls.incrementAndGet()
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
        stats.records.addAndGet(records.count().toLong())
        stats.malformed.addAndGet(bad.toLong())
        stats.commits.incrementAndGet()
        stats.lastOffsets = records.partitions().associate { tp -> tp.partition() to records.records(tp).last().offset() }
        return records.count()
    }

    /** Polls until [quietPolls] consecutive polls return nothing (tests, `ingest --once`); KAP policy loop. */
    suspend fun drain(quietPolls: Int = DRAIN_QUIET_POLLS): Int {
        var total = 0
        var quiet = 0
        Kap {
            poll(Duration.ofMillis(DRAIN_POLL_MS)).also { n ->
                if (n == 0) quiet += 1 else { quiet = 0; total += n }
            }
        }.repeatUntil { quiet >= quietPolls }.evalGraph()
        return total
    }

    suspend fun run() {
        try {
            Kap { poll() }
                .recover { 0 }
                .repeat(Schedule.spaced<Int>(KDuration.parse("${RETRY_DELAY_MS}ms")))
                .evalGraph()
        } finally {
            close()
        }
    }

    override fun close() {
        try {
            consumer.close(org.apache.kafka.clients.consumer.CloseOptions.timeout(Duration.ofSeconds(CLOSE_TIMEOUT_S)))
        } catch (e: Exception) {
            log.debug("consumer close: {}", e.message)
        }
    }

    companion object {
        const val POLL_MS = 500L
        const val DRAIN_POLL_MS = 1_000L
        const val DRAIN_QUIET_POLLS = 3
        const val MAX_POLL_RECORDS = 500
        const val RETRY_DELAY_MS = 1_000L
        const val CLOSE_TIMEOUT_S = 10L
    }
}
