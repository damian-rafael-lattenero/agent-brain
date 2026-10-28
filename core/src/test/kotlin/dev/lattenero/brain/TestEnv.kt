package dev.lattenero.brain

import dev.lattenero.brain.config.Config
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import java.util.UUID

/**
 * Service gates for the suite. Locally an unreachable service skips the
 * test with a visible reason; with AB_REQUIRE_SERVICES=1 (CI, `make bb`)
 * the same condition FAILS, so a green build never hides a skipped tier.
 */
object TestEnv {
    val required: Boolean = System.getenv("AB_REQUIRE_SERVICES") == "1"

    /** The configured knobs from the environment, with nothing else assumed. */
    val base: Config by lazy { Config.load() }

    fun config(pgUrl: String = base.pgUrl, provider: String = "fake", extra: Map<String, String> = emptyMap()): Config {
        val env = System.getenv().toMutableMap()
        env["AB_PG_URL"] = pgUrl
        env["AB_EMBED_PROVIDER"] = provider
        env["AB_BASE"] = Files.createTempDirectory("agent-brain-test").toString()
        env.putAll(extra)
        return Config.load(env)
    }

    fun gate(available: Boolean, what: String) {
        if (available) return
        if (required) fail<Unit>("$what is required (AB_REQUIRE_SERVICES=1) but unreachable")
        assumeTrue(false, "$what unreachable, skipping (set AB_REQUIRE_SERVICES=1 to fail instead)")
    }

    fun needPostgres() = gate(postgresAvailable(), "Postgres at ${base.pgUrl}")

    fun needOllama() = gate(ollamaAvailable(base.ollamaBaseUrl), "Ollama at ${base.ollamaBaseUrl}")

    fun needKafka() = gate(dev.lattenero.brain.bus.KafkaTopics.reachable(base.kafkaBootstrap), "Kafka/Redpanda at ${base.kafkaBootstrap}")

    /** GLM needs a paid key; its absence is the one documented skip even in CI. */
    fun needGlmKey() = assumeTrue(base.glmApiKey.isNotBlank(), "GLM_API_KEY not set, skipping the GLM integration test")

    fun postgresAvailable(): Boolean = try {
        adminConnection().use { true }
    } catch (e: SQLException) {
        false
    }

    fun ollamaAvailable(baseUrl: String): Boolean = try {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/tags")).timeout(Duration.ofSeconds(3)).GET().build()
        client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200
    } catch (e: Exception) {
        false
    }

    fun adminConnection(): Connection =
        DriverManager.getConnection(withParams(base.pgUrl, "connectTimeout=3&loginTimeout=3"), base.pgUser, base.pgPassword)

    fun withDatabase(jdbcUrl: String, dbName: String): String {
        val (head, query) = jdbcUrl.split("?", limit = 2).let { it[0] to it.getOrNull(1) }
        val replaced = head.substringBeforeLast('/') + "/" + dbName
        return if (query != null) "$replaced?$query" else replaced
    }

    private fun withParams(jdbcUrl: String, params: String): String =
        if ("?" in jdbcUrl) "$jdbcUrl&$params" else "$jdbcUrl?$params"
}

/**
 * Throwaway databases: one per test class (or per test when order would
 * otherwise matter), created on demand and dropped at JVM exit, so the suite
 * never touches the real agent_brain database and no test depends on what
 * another one left behind.
 */
object TestDatabase {
    private val created = mutableListOf<String>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread { dropAll() })
    }

    /** A brand-new empty database; returns its JDBC url. */
    @Synchronized
    fun fresh(tag: String = "t"): String {
        val name = "agent_brain_test_${tag}_" + UUID.randomUUID().toString().replace("-", "").take(8)
        TestEnv.adminConnection().use { conn ->
            conn.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        created += name
        return TestEnv.withDatabase(TestEnv.base.pgUrl, name)
    }

    /** A config pointing at a brand-new database. */
    fun config(tag: String = "t", provider: String = "fake", extra: Map<String, String> = emptyMap()): Config =
        TestEnv.config(pgUrl = fresh(tag), provider = provider, extra = extra)

    @Synchronized
    private fun dropAll() {
        if (created.isEmpty()) return
        try {
            TestEnv.adminConnection().use { conn ->
                created.forEach { name ->
                    conn.createStatement().use { it.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)") }
                }
            }
        } catch (e: SQLException) {
            System.err.println("could not drop test databases $created: ${e.message}")
        }
    }
}
