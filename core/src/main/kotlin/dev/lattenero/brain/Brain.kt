package dev.lattenero.brain

import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.embeddings.EmbeddingProvider
import dev.lattenero.brain.embeddings.EmbeddingProviders
import dev.lattenero.brain.http.HttpClients
import dev.lattenero.brain.http.Readiness
import io.ktor.client.HttpClient

const val VERSION = "0.1.0"

/**
 * The composition root: every long-lived object is built here, once, from
 * the injected [Config], and closed in reverse order. Tests may hand in
 * their own provider (fake, or one that fails on purpose).
 */
class Brain(val config: Config, providerOverride: EmbeddingProvider? = null) : AutoCloseable {
    val db: Database = Database(config)
    val httpClient: HttpClient = HttpClients.create(config.httpTimeoutS)
    val migrator: Migrator = Migrator(db, config.embedDim)
    val provider: EmbeddingProvider = providerOverride ?: EmbeddingProviders.create(config, httpClient)
    val readiness: Readiness = Readiness(config, db, migrator, provider)

    override fun close() {
        provider.close()
        httpClient.close()
        db.close()
    }
}
