package dev.lattenero.brain.bus

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/**
 * ADR-003's contract test: the same fixtures through the file transport and
 * through Kafka (bridge → topic → consumer) leave an identical database.
 * Real Redpanda, real Postgres.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaTransportIT {
    private val json = kotlinx.serialization.json.Json { encodeDefaults = true }

    @BeforeAll
    fun up() {
        TestEnv.needPostgres()
        TestEnv.needKafka()
    }

    private fun fixtureBytes(): ByteArray = javaClass.classLoader.getResourceAsStream("bus/sample-bot-publisher.jsonl")!!.readAllBytes()

    private fun kafkaConfig(tag: String): Config = TestDatabase.config(
        tag, extra = mapOf(
            "AB_BUS_TRANSPORT" to "kafka",
            "AB_KAFKA_TOPIC" to "agent-brain-test-$tag-" + UUID.randomUUID().toString().take(8),
            "AB_KAFKA_GROUP" to "agent-brain-test-$tag-" + UUID.randomUUID().toString().take(8),
        ),
    )

    private suspend fun snapshot(db: Database): String = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT id, ts, kind, sid, turn, source, payload::text FROM bus_events ORDER BY id").use { rs ->
                val digest = MessageDigest.getInstance("SHA-256")
                var n = 0
                while (rs.next()) {
                    n += 1
                    for (i in 1..7) digest.update((rs.getString(i) ?: "").toByteArray())
                }
                "$n:" + digest.digest().joinToString("") { "%02x".format(it) }
            }
        }
    }

    @Test
    fun `contract - the same bus through file and through kafka gives an identical database`() = runBlocking<Unit> {
        // Leg 1: file transport.
        val fileConfig = TestDatabase.config("viafile")
        val fileDb = Database(fileConfig)
        Migrator(fileDb, fileConfig.embedDim).apply()
        Files.createDirectories(fileConfig.busFile.parent)
        Files.write(fileConfig.busFile, fixtureBytes())
        assertThat(BusPipeline(fileConfig, fileDb).drain()).isEqualTo(6)
        val viaFile = snapshot(fileDb)

        // Leg 2: bridge the same file into a topic, consume the topic into another database.
        val kafkaConfig = kafkaConfig("viakafka")
        val kafkaDb = Database(kafkaConfig)
        Migrator(kafkaDb, kafkaConfig.embedDim).apply()
        Files.createDirectories(kafkaConfig.busFile.parent)
        Files.write(kafkaConfig.busFile, fixtureBytes())
        KafkaTopics.ensure(kafkaConfig)
        KafkaBridge(kafkaConfig).use { bridge -> assertThat(bridge.drain()).isEqualTo(6) }
        val pipeline = BusPipeline(kafkaConfig, kafkaDb)
        try {
            assertThat(pipeline.drain()).isEqualTo(6)
            val status = pipeline.status()
            assertThat(status.transport).isEqualTo("kafka")
            assertThat(status.inserted).isEqualTo(6)
            assertThat(status.kafkaCommits).isGreaterThanOrEqualTo(1)
            assertThat(status.kafkaOffsets).isNotEmpty
        } finally {
            pipeline.stop()
        }
        assertThat(snapshot(kafkaDb)).isEqualTo(viaFile)
        fileDb.close()
        kafkaDb.close()
    }

    @Test
    fun `redelivery - the bridge replaying from zero and a malformed record change nothing but the counters`() = runBlocking<Unit> {
        val config = kafkaConfig("redeliver")
        Database(config).use { db ->
            Migrator(db, config.embedDim).apply()
            Files.createDirectories(config.busFile.parent)
            Files.write(config.busFile, fixtureBytes())
            KafkaTopics.ensure(config)
            KafkaBridge(config).use { it.drain() }
            // A lost bridge offset: everything is produced again (at-least-once).
            Files.deleteIfExists(config.stateDir.resolve("bus-offset-bridge.json"))
            KafkaBridge(config).use { assertThat(it.drain()).isEqualTo(6) }
            // And a poison record straight into the topic.
            KafkaProducer<String, String>(
                Properties().apply {
                    put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrap)
                    put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                    put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                },
            ).use { it.send(ProducerRecord(config.kafkaTopic, "poison", "{not json")).get() }

            val pipeline = BusPipeline(config, db)
            try {
                assertThat(pipeline.drain()).isEqualTo(13)
                val status = pipeline.status()
                assertThat(status.dbEvents).isEqualTo(6)
                assertThat(status.inserted).isEqualTo(6)
                assertThat(status.duplicates).isEqualTo(6)
                assertThat(status.malformed).isEqualTo(1)
            } finally {
                pipeline.stop()
            }
        }
    }

    @Test
    fun `a bridged event round-trips the contract byte for byte in meaning`() {
        val raw = String(fixtureBytes()).lineSequence().first { it.isNotBlank() }
        val event = (BusEvents.parse(raw) as BusEvents.Parsed.Ok).event
        val reencoded = json.encodeToString(BusEvent.serializer(), event)
        assertThat((BusEvents.parse(reencoded) as BusEvents.Parsed.Ok).event).isEqualTo(event)
    }
}
