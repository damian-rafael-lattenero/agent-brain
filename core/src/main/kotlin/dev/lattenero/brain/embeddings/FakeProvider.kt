package dev.lattenero.brain.embeddings

import java.security.MessageDigest
import java.util.Random
import kotlin.math.sqrt

/**
 * Deterministic embeddings for unit tests: no network, no model.
 *
 * [Mode.HASH]: a unit vector seeded from sha256(text); identical texts
 * collide, everything else is near-orthogonal (tests of plumbing, dedup and
 * dimensions). [Mode.BOW]: a bag-of-words projection where texts sharing
 * tokens are close (tests of ranking and recall that need a meaningful order
 * without a real model).
 */
class FakeProvider(override val dim: Int, private val mode: Mode = Mode.HASH) : BaseProvider() {
    enum class Mode { HASH, BOW }

    override val name = "fake"
    override val model = "fake-${mode.name.lowercase()}"

    @Volatile
    var calls: Int = 0
        private set

    override suspend fun embedAll(texts: List<String>): List<FloatArray> {
        calls += 1
        return texts.map { if (mode == Mode.HASH) hashVector(it) else bowVector(it) }
    }

    private fun hashVector(text: String): FloatArray {
        val digest = sha256(text)
        var seed = 0L
        for (i in 0 until 8) seed = (seed shl 8) or (digest[i].toLong() and 0xff)
        val rng = Random(seed)
        return normalize(FloatArray(dim) { rng.nextGaussian().toFloat() })
    }

    private fun bowVector(text: String): FloatArray {
        val raw = FloatArray(dim)
        for (token in TOKEN_RE.findAll(text.lowercase()).map { it.value }) {
            val digest = sha256(token)
            val slot = ((digest[0].toInt() and 0xff shl 24) or (digest[1].toInt() and 0xff shl 16) or
                (digest[2].toInt() and 0xff shl 8) or (digest[3].toInt() and 0xff)).toLong().and(0xffffffffL) % dim
            val sign = if (digest[4].toInt() and 1 == 0) 1f else -1f
            raw[slot.toInt()] += sign
        }
        if (raw.all { it == 0f }) raw[0] = 1f
        return normalize(raw)
    }

    companion object {
        private val TOKEN_RE = Regex("[a-z0-9]+")

        private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

        fun normalize(raw: FloatArray): FloatArray {
            var sum = 0.0
            for (x in raw) sum += x.toDouble() * x
            val norm = sqrt(sum).takeIf { it > 0.0 } ?: 1.0
            return FloatArray(raw.size) { (raw[it] / norm).toFloat() }
        }
    }
}
