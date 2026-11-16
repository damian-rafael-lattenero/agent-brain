package dev.lattenero.brain.db

/**
 * pgvector's text input format: `[1,2,3]`. The generated statements bind
 * embeddings through `?::vector` casts, so the array travels as this literal —
 * one mechanism on every generated path (ADR-007), no PGvector registration.
 */
fun FloatArray.toVectorText(): String = joinToString(",", prefix = "[", postfix = "]")
