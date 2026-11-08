# Concurrency/FP audit — U0 mechanical inventory for agent-brain (generated 2026-11-08)

Scope: `core/src/main` + `brain-mcp/src/main` (tests excluded).
Every hit is a SUSPECT for the U1 semantic review, not a verdict.

- **forbidden**: 12
- **handrolled**: 7
- **impure**: 10
- **errors**: 128

## forbidden

| file | line | text |
|---|---|---|
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/Main.kt` | 34 | `import kotlinx.coroutines.runBlocking` |
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/Main.kt` | 193 | `runBlocking { Brain.resource(config).use { brain -> serveStdio(brain) } }` |
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/Main.kt` | 262 | `runBlocking { McpHttp.start(config, host, port, token, client) }` |
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/Main.kt` | 271 | `runBlocking { shutdownSignal(Thread.currentThread()).evalGraph() }` |
| `core/src/main/kotlin/dev/lattenero/brain/eval/EvalRunner.kt` | 15 | `import kotlinx.coroutines.runBlocking` |
| `core/src/main/kotlin/dev/lattenero/brain/eval/EvalRunner.kt` | 140 | `val report = runBlocking {` |
| `core/src/main/kotlin/dev/lattenero/brain/eval/EvalRunner.kt` | 151 | `runBlocking { EvalLedger(db, ledger.embedDim).record(report) }` |
| `core/src/main/kotlin/dev/lattenero/brain/Main.kt` | 56 | `import kotlinx.coroutines.runBlocking` |
| `core/src/main/kotlin/dev/lattenero/brain/Main.kt` | 147 | `runBlocking { Brain.resource(config).use(block) }` |
| `core/src/main/kotlin/dev/lattenero/brain/Main.kt` | 374 | `runBlocking {` |
| `core/src/main/kotlin/dev/lattenero/brain/Main.kt` | 492 | `return runBlocking {` |
| `core/src/main/kotlin/dev/lattenero/brain/bus/FileTailer.kt` | 278 | `* `runBlocking`).` |

## handrolled

| file | line | text |
|---|---|---|
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/Tools.kt` | 88 | `* kap `Ref` state (Phase G: the audit's last AtomicLong global — "Traces` |
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/Tools.kt` | 89 | `* AtomicLong global — espera kap Ref"), so the ids are created by executing` |
| `core/src/main/kotlin/dev/lattenero/brain/db/Database.kt` | 11 | `import java.util.concurrent.Executors` |
| `core/src/main/kotlin/dev/lattenero/brain/rag/EmbedPipeline.kt` | 18 | `import java.util.concurrent.ConcurrentHashMap` |
| `core/src/main/kotlin/dev/lattenero/brain/bus/FileTailer.kt` | 99 | `@Volatile` |
| `core/src/main/kotlin/dev/lattenero/brain/bus/Kafka.kt` | 36 | `import java.util.concurrent.ExecutionException` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/FakeProvider.kt` | 26 | `* [embedAll] from several batches in parallel, so a plain (or `@Volatile`)` |

## impure

| file | line | text |
|---|---|---|
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/Tools.kt` | 96 | `fun of(): Kap<Traces> = Kap { Traces(Instant.now().toEpochMilli(), Ref.of(0L).evalGraph()) }` |
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/Main.kt` | 167 | `private val REAL_OUT: java.io.PrintStream = System.out` |
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/Main.kt` | 348 | `} catch (e: java.io.IOException) {` |
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/Main.kt` | 388 | `} catch (e: java.io.IOException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/config/Config.kt` | 12 | `import java.io.IOException` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/FakeProvider.kt` | 43 | `val rng = Random(seed)` |
| `core/src/main/kotlin/dev/lattenero/brain/chat/Providers.kt` | 20 | `import java.io.IOException` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/GlmProvider.kt` | 14 | `import java.io.IOException` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/OllamaProvider.kt` | 13 | `import java.io.IOException` |
| `core/src/main/kotlin/dev/lattenero/brain/Main.kt` | 65 | `import java.io.IOException` |

## errors

| file | line | text |
|---|---|---|
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/TokenAuth.kt` | 62 | `else -> throw McpTokenException(` |
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/TokenAuth.kt` | 67 | `if (token.length < MIN_LENGTH) throw McpTokenException(` |
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/TokenAuth.kt` | 77 | `throw McpTokenException("$ENV_TOKEN_FILE is unreadable: $path (${e::class.simpleName}: ${e.message})")` |
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/Main.kt` | 172 | `} catch (e: IllegalArgumentException) {` |
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/Main.kt` | 180 | `} catch (e: Exception) {` |
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/Main.kt` | 194 | `} catch (e: Exception) {` |
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/Main.kt` | 250 | `} catch (e: McpTokenException) {` |
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/Main.kt` | 256 | `} catch (e: IllegalArgumentException) {` |
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/Main.kt` | 263 | `} catch (e: Exception) {` |
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/Main.kt` | 320 | `else -> throw IllegalStateException("unrouted tool ${spec.name}")` |
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/Main.kt` | 350 | `} catch (_: KapTimeoutException) {` |
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/Main.kt` | 384 | `} catch (e: IllegalArgumentException) {` |
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/Main.kt` | 390 | `} catch (e: KapTimeoutException) {` |
| `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/Main.kt` | 393 | `} catch (e: Exception) {` |
| `core/src/main/kotlin/dev/lattenero/brain/eval/EvalRunner.kt` | 237 | `} catch (e: ChatException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/eval/EvalRunner.kt` | 283 | `} catch (e: ChatException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/eval/Suite.kt` | 145 | `if (name.isBlank()) throw EvalSuiteException("a suite needs a name")` |
| `core/src/main/kotlin/dev/lattenero/brain/eval/Suite.kt` | 146 | `if (gate !in 0.0..1.0) throw EvalSuiteException("$name: gate must be within 0.0..1.0, got $gate")` |
| `core/src/main/kotlin/dev/lattenero/brain/eval/Suite.kt` | 150 | `if (cases.isEmpty()) throw EvalSuiteException("$name: no cases")` |
| `core/src/main/kotlin/dev/lattenero/brain/eval/Suite.kt` | 152 | `if (duplicates.isNotEmpty()) throw EvalSuiteException("$name: duplicate case ids ${duplicates.sorted()}")` |
| `core/src/main/kotlin/dev/lattenero/brain/eval/Suite.kt` | 158 | `if (case.id.isBlank()) throw EvalSuiteException("$name: a case has a blank id")` |
| `core/src/main/kotlin/dev/lattenero/brain/eval/Suite.kt` | 165 | `throw EvalSuiteException("$name: case ${case.id} does not match the suite kind '${kind.label}'")` |
| `core/src/main/kotlin/dev/lattenero/brain/eval/Suite.kt` | 171 | `throw EvalSuiteException("$name: grounded case ${case.id} needs at least one needle to verify its answer")` |
| `core/src/main/kotlin/dev/lattenero/brain/eval/Suite.kt` | 173 | `if (case.k < 1) throw EvalSuiteException("$name: grounded case ${case.id} has k < 1")` |
| `core/src/main/kotlin/dev/lattenero/brain/eval/Suite.kt` | 192 | `if (trimmed.isEmpty()) throw EvalSuiteException("empty suite name")` |
| `core/src/main/kotlin/dev/lattenero/brain/eval/Suite.kt` | 200 | `?: throw EvalSuiteException(` |
| `core/src/main/kotlin/dev/lattenero/brain/eval/Suite.kt` | 211 | `throw EvalSuiteException("suite $source is not valid: ${e.message?.lineSequence()?.firstOrNull()}", e)` |
| `core/src/main/kotlin/dev/lattenero/brain/db/Migrator.kt` | 30 | `?: throw MigrationException("missing $INDEX on the classpath")` |
| `core/src/main/kotlin/dev/lattenero/brain/db/Migrator.kt` | 33 | `?: throw MigrationException("migration listed in index.txt but missing on the classpath: $name")` |
| `core/src/main/kotlin/dev/lattenero/brain/db/Migrator.kt` | 51 | `?: throw MigrationException("migration file name must be NNNN_snake_case.sql: ${migration.name}")` |
| `core/src/main/kotlin/dev/lattenero/brain/db/Migrator.kt` | 54 | `throw MigrationException("migration numbers must strictly increase: ${migration.name} after $last")` |
| `core/src/main/kotlin/dev/lattenero/brain/db/Migrator.kt` | 65 | `if (embedDim <= 0) throw MigrationException("embed_dim must be positive, got $embedDim")` |
| `core/src/main/kotlin/dev/lattenero/brain/db/Migrator.kt` | 69 | `throw MigrationException("unknown placeholders in migration: ${leftovers.joinToString()}")` |
| `core/src/main/kotlin/dev/lattenero/brain/db/Migrator.kt` | 121 | `} catch (e: SQLException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/db/Migrator.kt` | 122 | `throw MigrationException("migration failed: ${e.message}", e)` |
| `core/src/main/kotlin/dev/lattenero/brain/db/Migrator.kt` | 126 | `throw MigrationException(` |
| `core/src/main/kotlin/dev/lattenero/brain/config/Config.kt` | 153 | `throw ConfigException(` |
| `core/src/main/kotlin/dev/lattenero/brain/config/Config.kt` | 208 | `?: throw ConfigException("$ENV_PREFIX${name.uppercase()} expects an integer, got '$raw'")` |
| `core/src/main/kotlin/dev/lattenero/brain/config/Config.kt` | 213 | `is Boolean -> value.booleanOrNull ?: throw ConfigException("$name expects true or false in config.json, got $value")` |
| `core/src/main/kotlin/dev/lattenero/brain/config/Config.kt` | 214 | `is Int -> value.intOrNull ?: throw ConfigException("$name expects an integer in config.json, got $value")` |
| `core/src/main/kotlin/dev/lattenero/brain/config/Config.kt` | 215 | `else -> if (value.isString) value.content else throw ConfigException("$name expects a string in config.json, got $value")` |
| `core/src/main/kotlin/dev/lattenero/brain/config/Config.kt` | 222 | `} catch (e: IOException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/config/Config.kt` | 223 | `throw ConfigException("config unreadable: $path (${e.message})")` |
| `core/src/main/kotlin/dev/lattenero/brain/config/Config.kt` | 227 | `} catch (e: SerializationException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/config/Config.kt` | 228 | `throw ConfigException("config is not valid JSON: $path (${e.message})")` |
| `core/src/main/kotlin/dev/lattenero/brain/config/Config.kt` | 230 | `val obj = element as? JsonObject ?: throw ConfigException("config must be a JSON object: $path")` |
| `core/src/main/kotlin/dev/lattenero/brain/config/Config.kt` | 232 | `value as? JsonPrimitive ?: throw ConfigException("$key must be a scalar value in $path")` |
| `core/src/main/kotlin/dev/lattenero/brain/http/Server.kt` | 109 | `} catch (e: CancellationException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/http/Server.kt` | 110 | `throw e` |
| `core/src/main/kotlin/dev/lattenero/brain/http/Server.kt` | 111 | `} catch (e: Exception) {` |
| `core/src/main/kotlin/dev/lattenero/brain/http/Server.kt` | 158 | `} catch (e: Exception) {` |
| `core/src/main/kotlin/dev/lattenero/brain/http/Server.kt` | 163 | `} catch (e: RagQueryException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/http/Server.kt` | 173 | `} catch (e: Exception) {` |
| `core/src/main/kotlin/dev/lattenero/brain/http/Server.kt` | 178 | `} catch (e: MemoryRequestException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/http/Server.kt` | 185 | `} catch (e: Exception) {` |
| `core/src/main/kotlin/dev/lattenero/brain/http/Server.kt` | 190 | `} catch (e: MemoryRequestException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/http/Server.kt` | 199 | `} catch (e: MemoryRequestException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/http/Server.kt` | 213 | `} catch (e: Exception) {` |
| `core/src/main/kotlin/dev/lattenero/brain/http/Server.kt` | 220 | `} catch (e: IllegalArgumentException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/rag/RagService.kt` | 24 | `if (text.isEmpty()) throw RagQueryException("query must not be empty")` |
| `core/src/main/kotlin/dev/lattenero/brain/rag/RagService.kt` | 25 | `if (request.k !in 1..MAX_K) throw RagQueryException("k must be between 1 and $MAX_K")` |
| `core/src/main/kotlin/dev/lattenero/brain/chat/ChatProvider.kt` | 34 | `else -> throw ConfigException("chat_provider must be ollama, glm or fake, got '${config.chatProvider}'")` |
| `core/src/main/kotlin/dev/lattenero/brain/chat/Providers.kt` | 50 | `} catch (e: HttpRequestTimeoutException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/chat/Providers.kt` | 51 | `throw ProviderUnavailable("ollama chat timed out at $baseUrl", e)` |
| `core/src/main/kotlin/dev/lattenero/brain/chat/Providers.kt` | 52 | `} catch (e: IOException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/chat/Providers.kt` | 53 | `throw ProviderUnavailable("ollama chat unreachable at $baseUrl (${e.message})", e)` |
| `core/src/main/kotlin/dev/lattenero/brain/chat/Providers.kt` | 57 | `HttpStatusCode.NotFound -> throw ChatException("ollama model '$model' is not pulled (ollama pull $model): ${response.bodyAsText().take(ERROR_TAIL)}")` |
| `core/src/main/kotlin/dev/lattenero/brain/chat/Providers.kt` | 58 | `else -> throw ChatException("ollama $url -> HTTP ${response.status.value}: ${response.bodyAsText().take(ERROR_TAIL)}")` |
| `core/src/main/kotlin/dev/lattenero/brain/chat/Providers.kt` | 61 | `val content = parsed.message?.content ?: throw ChatException("ollama chat answered without a message: ${parsed.error ?: "?"}")` |
| `core/src/main/kotlin/dev/lattenero/brain/chat/Providers.kt` | 76 | `if (apiKey.isBlank()) throw ChatException("GLM_API_KEY is not set; export it or switch chat_provider to ollama")` |
| `core/src/main/kotlin/dev/lattenero/brain/chat/Providers.kt` | 108 | `} catch (e: HttpRequestTimeoutException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/chat/Providers.kt` | 109 | `throw ProviderUnavailable("glm chat timed out at $baseUrl", e)` |
| `core/src/main/kotlin/dev/lattenero/brain/chat/Providers.kt` | 110 | `} catch (e: IOException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/chat/Providers.kt` | 111 | `throw ProviderUnavailable("glm chat unreachable at $baseUrl (${e.message})", e)` |
| `core/src/main/kotlin/dev/lattenero/brain/chat/Providers.kt` | 115 | `HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> throw ChatException("glm rejected the key (HTTP ${response.status.value}): ${response.bodyAsText().take(ERROR_TAIL` |
| `core/src/main/kotlin/dev/lattenero/brain/chat/Providers.kt` | 116 | `else -> throw ChatException("glm $url -> HTTP ${response.status.value}: ${response.bodyAsText().take(ERROR_TAIL)}")` |
| `core/src/main/kotlin/dev/lattenero/brain/chat/Providers.kt` | 119 | `val content = parsed.choices.firstOrNull()?.message?.content ?: throw ChatException("glm answered without choices")` |
| `core/src/main/kotlin/dev/lattenero/brain/Main.kt` | 127 | `} catch (e: ConfigException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/Main.kt` | 129 | `} catch (e: MigrationException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/Main.kt` | 131 | `} catch (e: ProviderException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/Main.kt` | 133 | `} catch (e: RagQueryException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/Main.kt` | 135 | `} catch (e: MemoryRequestException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/Main.kt` | 137 | `} catch (e: SQLException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/Main.kt` | 196 | `} catch (e: ProcessExhaustedException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/Main.kt` | 198 | `} catch (e: ProcessStoppedException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/Main.kt` | 229 | `} catch (e: IllegalArgumentException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/Main.kt` | 268 | `} catch (e: IllegalArgumentException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/Main.kt` | 358 | `} catch (e: IllegalArgumentException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/Main.kt` | 541 | `} catch (e: IOException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/memory/MemoryWorker.kt` | 293 | `if (text.isEmpty()) throw MemoryRequestException("query must not be empty")` |
| `core/src/main/kotlin/dev/lattenero/brain/memory/MemoryWorker.kt` | 294 | `if (request.k !in 1..MAX_K) throw MemoryRequestException("k must be between 1 and $MAX_K")` |
| `core/src/main/kotlin/dev/lattenero/brain/memory/MemoryWorker.kt` | 300 | `if (request.kind !in KINDS) throw MemoryRequestException("kind must be one of ${KINDS.joinToString()}")` |
| `core/src/main/kotlin/dev/lattenero/brain/memory/MemoryWorker.kt` | 302 | `if (content.length < MemoryExtractor.MIN_ITEM_CHARS) throw MemoryRequestException("content too short")` |
| `core/src/main/kotlin/dev/lattenero/brain/memory/MemoryWorker.kt` | 310 | `if (limit !in 1..MAX_K) throw MemoryRequestException("limit must be between 1 and $MAX_K")` |
| `core/src/main/kotlin/dev/lattenero/brain/memory/MemoryWorker.kt` | 311 | `if (kind != null && kind !in KINDS) throw MemoryRequestException("kind must be one of ${KINDS.joinToString()}")` |
| `core/src/main/kotlin/dev/lattenero/brain/bus/FileTailer.kt` | 39 | `} catch (e: Exception) {` |
| `core/src/main/kotlin/dev/lattenero/brain/bus/BusPipeline.kt` | 39 | `kafka!!.process()` |
| `core/src/main/kotlin/dev/lattenero/brain/bus/BusPipeline.kt` | 64 | `return kafka!!.drain()` |
| `core/src/main/kotlin/dev/lattenero/brain/bus/BusEvent.kt` | 43 | `} catch (e: SerializationException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/bus/BusEvent.kt` | 45 | `} catch (e: IllegalArgumentException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/bus/BusEvent.kt` | 53 | `} catch (e: DateTimeParseException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/memory/MemoryExtractor.kt` | 190 | `} catch (e: SerializationException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/memory/MemoryExtractor.kt` | 225 | `is ParseOutcome.Invalid -> throw ExtractionInvalid(outcome.reason, outcome.raw)` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/GlmProvider.kt` | 36 | `throw ProviderException("GLM_API_KEY is not set; export it or switch embed_provider to ollama")` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/GlmProvider.kt` | 60 | `} catch (e: HttpRequestTimeoutException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/GlmProvider.kt` | 61 | `throw ProviderUnavailable("glm timed out at $baseUrl", e)` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/GlmProvider.kt` | 62 | `} catch (e: IOException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/GlmProvider.kt` | 63 | `throw ProviderUnavailable("glm unreachable at $baseUrl (${e.message})", e)` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/GlmProvider.kt` | 67 | `HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> throw ProviderException(` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/GlmProvider.kt` | 70 | `else -> throw ProviderException(` |
| `core/src/main/kotlin/dev/lattenero/brain/bus/Kafka.kt` | 53 | `} catch (e: ExecutionException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/bus/Kafka.kt` | 54 | `if (e.cause !is TopicExistsException) throw e` |
| `core/src/main/kotlin/dev/lattenero/brain/bus/Kafka.kt` | 67 | `} catch (e: Exception) {` |
| `core/src/main/kotlin/dev/lattenero/brain/bus/Kafka.kt` | 338 | `} catch (e: Exception) {` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/EmbeddingProvider.kt` | 48 | `throw ProviderException("$name/$model returned ${vectors.size} vectors for ${texts.size} texts")` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/EmbeddingProvider.kt` | 51 | `if (vector.size != dim) throw DimensionMismatch(name, model, dim, vector.size)` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/EmbeddingProvider.kt` | 52 | `if (vector.any { !it.isFinite() }) throw ProviderException("$name/$model returned a non-finite value")` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/EmbeddingProvider.kt` | 62 | `} catch (e: CancellationException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/EmbeddingProvider.kt` | 63 | `throw e` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/EmbeddingProvider.kt` | 64 | `} catch (e: ProviderException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/EmbeddingProvider.kt` | 66 | `} catch (e: Exception) {` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/EmbeddingProvider.kt` | 86 | `else -> throw ConfigException("embed_provider must be ollama, glm or fake, got '${config.embedProvider}'")` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/OllamaProvider.kt` | 42 | `} catch (e: HttpRequestTimeoutException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/OllamaProvider.kt` | 43 | `throw ProviderUnavailable("ollama timed out at $baseUrl", e)` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/OllamaProvider.kt` | 44 | `} catch (e: IOException) {` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/OllamaProvider.kt` | 45 | `throw ProviderUnavailable("ollama unreachable at $baseUrl (${e.message})", e)` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/OllamaProvider.kt` | 49 | `HttpStatusCode.NotFound -> throw ProviderException(` |
| `core/src/main/kotlin/dev/lattenero/brain/embeddings/OllamaProvider.kt` | 52 | `else -> throw ProviderException(` |

