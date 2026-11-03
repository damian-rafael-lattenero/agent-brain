package dev.lattenero.brain.memory

import dev.lattenero.brain.embeddings.FakeProvider
import java.util.Random
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/** Controlled embeddings for tests: exact-cosine unit vectors and random unit gaussians. */
object TestVectors {
    private val DEFAULT_DIM = 768

    /**
     * A unit [dim]-vector whose cosine with [axis] is exactly [c]: the vector
     * lives in the 2D plane spanned by [axis] and its neighbour, `[cos, sin,
     * 0, ...]`.
     */
    fun at(c: Double, axis: Int, dim: Int = DEFAULT_DIM): FloatArray {
        val angle = acos(c)
        val v = FloatArray(dim)
        v[axis] = cos(angle).toFloat()
        v[(axis + 1) % dim] = sin(angle).toFloat()
        return v
    }

    /** A deterministic random unit vector (near-orthogonal to other seeds). */
    fun randomUnit(dim: Int = DEFAULT_DIM, seed: Long): FloatArray {
        val rng = Random(seed)
        return FakeProvider.normalize(FloatArray(dim) { rng.nextGaussian().toFloat() })
    }
}
