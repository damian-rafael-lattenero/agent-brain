package dev.lattenero.brain

import dev.lattenero.brain.config.Config
import dev.lattenero.brain.config.ConfigException
import dev.lattenero.brain.db.MigrationException
import dev.lattenero.brain.embeddings.ProviderException
import dev.lattenero.brain.http.BrainServer
import dev.lattenero.brain.http.HttpClients
import dev.lattenero.brain.http.ReadyResponse
import dev.lattenero.brain.http.responseJson
import org.slf4j.LoggerFactory
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import dev.lattenero.brain.bus.BusStatus
import dev.lattenero.brain.http.readCorpusFile
import dev.lattenero.brain.memory.Memory
import dev.lattenero.brain.memory.MemoryRequestException
import dev.lattenero.brain.memory.MemorySaveRequest
import dev.lattenero.brain.memory.MemorySearchRequest
import dev.lattenero.brain.memory.MemorySearchResponse
import dev.lattenero.brain.memory.MemoryStatus
import dev.lattenero.brain.memory.QuarantineRow
import dev.lattenero.brain.memory.WorkerReport
import dev.lattenero.brain.rag.IndexReport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.launch
import dev.lattenero.brain.rag.QueryRequest
import dev.lattenero.brain.rag.QueryResponse
import dev.lattenero.brain.rag.RagQueryException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.sql.SQLException
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("agent-brain")

const val EXIT_OK = 0
const val EXIT_FAIL = 1
const val EXIT_USAGE = 2
const val VECTOR_PREVIEW = 5

const val USAGE = """usage: agent-brain <command> [args]

  serve              run the HTTP service + the bus tailer (applies pending migrations first)
  migrate [--status] apply pending migrations, or only report them
  ingest [--once]    tail the bus into Postgres without HTTP; --once drains and exits
  bus status         the tailer/ingest counters and the database count
  bridge [--once]    tail the bus file into the Kafka topic (bus_transport=kafka deployments)
  index [GLOB...]    index the corpus (globs from args, else ~/.zcode/agent-brain/corpus.txt)
  query TEXT [--k N] hybrid retrieval with citations (no generation)
  memory search TEXT [--k N] | recent [--k N] [--kind K] | save KIND TEXT | quarantine | status | run [--once]
  health [--url U]   GET /ready of a running service; exit 1 when degraded
  embed TEXT...      embed text(s) with the configured provider
  config             print the effective configuration (secrets redacted)
"""

fun main(args: Array<String>) {
    exitProcess(run(args))
}

fun run(args: Array<String>): Int {
    val command = args.firstOrNull() ?: return usage()
    val rest = args.drop(1)
    return try {
        when (command) {
            "serve" -> serve()
            "migrate" -> migrate(status = "--status" in rest)
            "ingest" -> ingest(once = "--once" in rest)
            "bus" -> if (rest.firstOrNull() == "status") busStatus() else usage()
            "bridge" -> bridge(once = "--once" in rest)
            "query" -> query(rest)
            "index" -> index(rest)
            "memory" -> memory(rest)
            "health" -> health(url = rest.optionValue("--url"))
            "embed" -> embed(rest)
            "config" -> printJson(Config.load().snapshot()).let { EXIT_OK }
            else -> usage()
        }
    } catch (e: ConfigException) {
        fail("config: ${e.message}")
    } catch (e: MigrationException) {
        fail("migrate: ${e.message}")
    } catch (e: ProviderException) {
        fail("embed: ${e.message}")
    } catch (e: RagQueryException) {
        fail("query: ${e.message}")
    } catch (e: MemoryRequestException) {
        fail("memory: ${e.message}")
    } catch (e: SQLException) {
        fail("database: ${e.message}")
    }
}

private fun serve(): Int {
    val config = Config.load()
    Brain(config).use { brain ->
        val applied = runBlocking { brain.migrator.apply() }
        if (applied.isNotEmpty()) log.info("migrations applied: ${applied.joinToString()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        brain.bus.start(scope)
        if (brain.bus.isKafka) {
            log.info("bus consumer on ${config.kafkaBootstrap} topic ${config.kafkaTopic} group ${config.kafkaGroup}")
        } else {
            log.info("bus tailer on ${config.busFile} every ${config.busPollMs} ms")
        }
        if (config.memoryWorker) {
            scope.launch { brain.memoryWorker.run(kotlin.time.Duration.parse("${config.memoryPollMs}ms")) }
            log.info("memory worker every ${config.memoryPollMs} ms with ${brain.chat.name}/${brain.chat.model}")
        }
        try {
            BrainServer.start(config, brain.readiness, brain.bus, brain.rag, brain.indexer, brain.memory, wait = true)
        } finally {
            scope.cancel()
        }
    }
    return EXIT_OK
}

private fun ingest(once: Boolean): Int {
    val config = Config.load()
    Brain(config).use { brain ->
        runBlocking { brain.migrator.apply() }
        if (once) {
            val handed = runBlocking { brain.bus.drain() }
            val status = runBlocking { brain.bus.status() }
            printJson(responseJson.encodeToJsonElement(BusStatus.serializer(), status))
            log.info("drained $handed event(s)")
            return EXIT_OK
        }
        runBlocking { brain.bus.tailer.run(kotlin.time.Duration.parse("${config.busPollMs}ms")) { brain.bus.ingest.handle(it) } }
    }
    return EXIT_OK
}

private fun index(args: List<String>): Int {
    val config = Config.load()
    val globs = args.ifEmpty { readCorpusFile(config) }
    if (globs.isEmpty()) return fail("index: no globs given and ${config.corpusFile} is empty or missing")
    Brain(config).use { brain ->
        runBlocking { brain.migrator.apply() }
        val report = try {
            runBlocking { brain.indexer.indexGlobs(globs) }
        } catch (e: IllegalArgumentException) {
            return fail("index: ${e.message}")
        }
        printJson(responseJson.encodeToJsonElement(IndexReport.serializer(), report))
        return if (report.failed.isEmpty()) EXIT_OK else EXIT_FAIL
    }
}

private fun memory(args: List<String>): Int {
    val sub = args.firstOrNull() ?: return usage()
    val rest = args.drop(1)
    val config = Config.load()
    Brain(config).use { brain ->
        when (sub) {
            "search" -> {
                val k = rest.optionValue("--k")?.toIntOrNull() ?: MemorySearchRequest(query = "").k
                val text = rest.withoutOption("--k").joinToString(" ")
                if (text.isBlank()) return usage()
                val response = runBlocking { brain.memory.search(MemorySearchRequest(text, k)) }
                printJson(responseJson.encodeToJsonElement(MemorySearchResponse.serializer(), response))
            }
            "recent" -> {
                val k = rest.optionValue("--k")?.toIntOrNull() ?: MemorySearchRequest(query = "").k
                val list = runBlocking { brain.memory.recent(k, rest.optionValue("--kind")) }
                printJson(responseJson.encodeToJsonElement(ListSerializer(Memory.serializer()), list))
            }
            "save" -> {
                val kind = rest.firstOrNull() ?: return usage()
                val text = rest.drop(1).joinToString(" ")
                val saved = runBlocking { brain.memory.save(MemorySaveRequest(kind, text)) }
                printJson(responseJson.encodeToJsonElement(Memory.serializer(), saved))
            }
            "quarantine" -> printJson(responseJson.encodeToJsonElement(ListSerializer(QuarantineRow.serializer()), runBlocking { brain.memory.quarantined(MemorySearchRequest(query = "").k) }))
            "status" -> printJson(responseJson.encodeToJsonElement(MemoryStatus.serializer(), runBlocking { brain.memory.status() }))
            "run" -> {
                runBlocking { brain.migrator.apply() }
                if ("--once" in rest) {
                    val report = runBlocking { brain.memoryWorker.processOnce() }
                    printJson(responseJson.encodeToJsonElement(WorkerReport.serializer(), report))
                } else {
                    runBlocking { brain.memoryWorker.run(kotlin.time.Duration.parse("${config.memoryPollMs}ms")) }
                }
            }
            else -> return usage()
        }
    }
    return EXIT_OK
}

private fun List<String>.withoutOption(flag: String): List<String> =
    filterIndexed { i, a -> a != flag && (i == 0 || this[i - 1] != flag) }

private fun query(args: List<String>): Int {
    val k = args.optionValue("--k")?.toIntOrNull() ?: QueryRequest(query = "").k
    val text = args.filterIndexed { i, a -> a != "--k" && (i == 0 || args[i - 1] != "--k") }.joinToString(" ")
    if (text.isBlank()) return usage()
    val config = Config.load()
    Brain(config).use { brain ->
        val response = runBlocking { brain.rag.query(QueryRequest(text, k)) }
        printJson(responseJson.encodeToJsonElement(QueryResponse.serializer(), response))
    }
    return EXIT_OK
}

private fun bridge(once: Boolean): Int {
    val config = Config.load()
    dev.lattenero.brain.bus.KafkaTopics.ensure(config)
    dev.lattenero.brain.bus.KafkaBridge(config).use { bridge ->
        if (once) {
            val produced = runBlocking { bridge.drain() }
            printJson(buildJsonObject { put("produced", produced); put("offset", bridge.tailer.offset); put("topic", config.kafkaTopic) })
            return EXIT_OK
        }
        runBlocking { bridge.run(kotlin.time.Duration.parse("${config.busPollMs}ms")) }
    }
    return EXIT_OK
}

private fun busStatus(): Int {
    val config = Config.load()
    Brain(config).use { brain ->
        val status = runBlocking { brain.bus.status() }
        printJson(responseJson.encodeToJsonElement(BusStatus.serializer(), status))
    }
    return EXIT_OK
}

private fun migrate(status: Boolean): Int {
    val config = Config.load()
    Brain(config).use { brain ->
        val appliedNow = if (status) emptyList() else runBlocking { brain.migrator.apply() }
        val state = runBlocking { brain.migrator.status() }
        printJson(
            buildJsonObject {
                putJsonArray("applied_now") { appliedNow.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("applied") { state.applied.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("pending") { state.pending.forEach { add(JsonPrimitive(it)) } }
                put("embed_dim", state.embedDim)
            },
        )
        return if (state.pending.isEmpty()) EXIT_OK else EXIT_FAIL
    }
}

private fun health(url: String?): Int {
    val config = Config.load()
    val target = url ?: "http://${config.httpHost}:${config.httpPort}/ready"
    Brain(config).use { brain ->
        return runBlocking {
            try {
                val response = brain.httpClient.get(target)
                val body = response.bodyAsText()
                println(body)
                val parsed = runCatching { HttpClients.json.decodeFromString<ReadyResponse>(body) }
                if (response.status.value == 200 && parsed.getOrNull()?.ok == true) EXIT_OK else EXIT_FAIL
            } catch (e: IOException) {
                fail("health: $target unreachable (${e.message})")
            }
        }
    }
}

private fun embed(texts: List<String>): Int {
    if (texts.isEmpty()) return usage()
    val config = Config.load()
    Brain(config).use { brain ->
        val vectors = runBlocking { brain.provider.embed(texts) }
        printJson(
            buildJsonObject {
                put("provider", brain.provider.name)
                put("model", brain.provider.model)
                put("vectors", previews(texts, vectors))
            },
        )
    }
    return EXIT_OK
}

private fun previews(texts: List<String>, vectors: List<FloatArray>): JsonArray = buildJsonArray {
    texts.zip(vectors).forEach { (text, vector) ->
        add(
            buildJsonObject {
                put("text", text)
                put("dim", vector.size)
                putJsonArray("head") { vector.take(VECTOR_PREVIEW).forEach { add(JsonPrimitive(it)) } }
            },
        )
    }
}

private fun List<String>.optionValue(flag: String): String? {
    val index = indexOf(flag)
    return if (index >= 0 && index + 1 < size) this[index + 1] else null
}

private fun printJson(value: kotlinx.serialization.json.JsonElement) {
    println(responseJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), value))
}

private fun usage(): Int {
    System.err.print(USAGE)
    return EXIT_USAGE
}

private fun fail(message: String): Int {
    System.err.println("agent-brain: $message")
    return EXIT_FAIL
}
