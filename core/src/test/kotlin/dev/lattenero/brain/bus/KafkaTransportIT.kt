package dev.lattenero.brain.bus

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import kap.evalGraph
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
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

    /** Throwaway topics per test run; deleted in [downTopics] so repeated runs never exhaust Redpanda's FD budget. */
    private val createdTopics = mutableListOf<String>()

    @BeforeAll
    fun up() {
        TestEnv.needPostgres()
        TestEnv.needKafka()
    }

    @AfterAll
    fun downTopics() {
        if (createdTopics.isEmpty()) return
        try {
            val props = Properties().apply {
                put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, TestEnv.base.kafkaBootstrap)
                put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, KafkaTopics.PROBE_TIMEOUT_MS)
                put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, KafkaTopics.PROBE_TIMEOUT_MS)
            }
            AdminClient.create(props).use { it.deleteTopics(createdTopics).all().get() }
        } catch (e: Exception) {
            // A leftover topic is an environment cost, never a masked test result.
            System.err.println("kafka IT: could not delete throwaway topics $createdTopics: ${e.message}")
        }
    }

    private fun fixtureBytes(): ByteArray = javaClass.classLoader.getResourceAsStream("bus/sample-bot-publisher.jsonl")!!.readAllBytes()

    private fun kafkaConfig(tag: String): Config {
        val topic = "agent-brain-test-$tag-" + UUID.randomUUID().toString().take(8)
        createdTopics += topic
        return TestDatabase.config(
            tag, extra = mapOf(
                "AB_BUS_TRANSPORT" to "kafka",
                "AB_KAFKA_TOPIC" to topic,
                "AB_KAFKA_GROUP" to "agent-brain-test-$tag-" + UUID.randomUUID().toString().take(8),
            ),
        )
    }

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
        assertThat(BusPipeline.of(fileConfig, fileDb).evalGraph().drain()).isEqualTo(6)
        val viaFile = snapshot(fileDb)

        // Leg 2: bridge the same file into a topic, consume the topic into another database.
        val kafkaConfig = kafkaConfig("viakafka")
        val kafkaDb = Database(kafkaConfig)
        Migrator(kafkaDb, kafkaConfig.embedDim).apply()
        Files.createDirectories(kafkaConfig.busFile.parent)
        Files.write(kafkaConfig.busFile, fixtureBytes())
        KafkaTopics.ensure(kafkaConfig)
        KafkaBridge.of(kafkaConfig).evalGraph().use { bridge -> assertThat(bridge.drain()).isEqualTo(6) }
        val pipeline = BusPipeline.of(kafkaConfig, kafkaDb).evalGraph()
        assertThat(pipeline.drain()).isEqualTo(6)
        val status = pipeline.status()
        assertThat(status.transport).isEqualTo("kafka")
        assertThat(status.inserted).isEqualTo(6)
        assertThat(status.kafkaCommits).isGreaterThanOrEqualTo(1)
        assertThat(status.kafkaOffsets).isNotEmpty
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
            KafkaBridge.of(config).evalGraph().use { it.drain() }
            // A lost bridge offset: everything is produced again (at-least-once).
            Files.deleteIfExists(config.stateDir.resolve("bus-offset-bridge.json"))
            KafkaBridge.of(config).evalGraph().use { assertThat(it.drain()).isEqualTo(6) }
            // And a poison record straight into the topic.
            KafkaProducer<String, String>(
                Properties().apply {
                    put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrap)
                    put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                    put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                },
            ).use { it.send(ProducerRecord(config.kafkaTopic, "poison", "{not json")).get() }

            val pipeline = BusPipeline.of(config, db).evalGraph()
            assertThat(pipeline.drain()).isEqualTo(13)
            val status = pipeline.status()
            assertThat(status.dbEvents).isEqualTo(6)
            assertThat(status.inserted).isEqualTo(6)
            assertThat(status.duplicates).isEqualTo(6)
            assertThat(status.malformed).isEqualTo(1)
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
