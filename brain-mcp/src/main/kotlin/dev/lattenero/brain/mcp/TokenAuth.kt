package dev.lattenero.brain.mcp

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** The HTTP transport cannot start: no token, an empty one, a guessable one, or an unreadable file. */
class McpTokenException(message: String) : RuntimeException(message)

/**
 * The bearer token that gates the HTTP MCP endpoint (the stdio plugin needs
 * none: the harness owns the process and its pipes).
 *
 * Two properties shape this class:
 *
 * 1. **Fail closed.** A missing, empty or too-short token is not a degraded
 *    mode; [load] throws and the server never binds. A placeholder token on a
 *    port that may be tunneled is worse than no endpoint at all.
 * 2. **Constant time.** The candidate is hashed whole and compared with
 *    [MessageDigest.isEqual] over two fixed-width digests, so neither the
 *    CONTENT nor the LENGTH of the candidate leaks through timing — a plain
 *    `==` stops at the first differing byte and would reveal a correct prefix
 *    character by character. `isEqual` itself returns early on different
 *    lengths, which is why both sides are SHA-256 (always 32 bytes).
 *
 * The token never reaches a log, an exception message or a JSON body: [toString]
 * is redacted and the source (env|file) is the only loggable attribute.
 */
class McpToken private constructor(private val digest: ByteArray, val source: String) {

    /** Always hashes and compares the whole candidate; returns false for null (a missing header). */
    fun matches(candidate: String?): Boolean = MessageDigest.isEqual(digest, digestOf(candidate.orEmpty()))

    override fun toString(): String = "McpToken(source=$source, redacted)"

    companion object {
        /** The token itself. Environment-only, like GLM_API_KEY (ADR-002): it never lands in config.json. */
        const val ENV_TOKEN = "AB_MCP_TOKEN"

        /** A file holding the token (one line, chmod 600): keeps it out of the plist and of `ps -E`. */
        const val ENV_TOKEN_FILE = "AB_MCP_TOKEN_FILE"

        /** A guessable token is the realistic failure mode, so the length is enforced, not documented. */
        const val MIN_LENGTH = 16

        /** [raw] is already validated (used by [load] and by the tests). */
        fun of(raw: String, source: String = "test"): McpToken = McpToken(digestOf(raw), source)

        /**
         * `AB_MCP_TOKEN` wins over `AB_MCP_TOKEN_FILE` (the same env-over-file
         * order as every other knob). [readFile] is a seam for the tests.
         */
        fun load(
            env: Map<String, String> = System.getenv(),
            readFile: (Path) -> String = { Files.readString(it) },
        ): McpToken {
            val fromEnv = env[ENV_TOKEN]?.trim()
            val filePath = env[ENV_TOKEN_FILE]?.trim()
            val (raw, source) = when {
                !fromEnv.isNullOrEmpty() -> fromEnv to "env"
                !filePath.isNullOrEmpty() -> readTokenFile(filePath, readFile) to "file"
                else -> throw McpTokenException(
                    "no token: set $ENV_TOKEN or $ENV_TOKEN_FILE (generate one with `openssl rand -hex 32`)",
                )
            }
            val token = raw.trim()
            if (token.length < MIN_LENGTH) throw McpTokenException(
                "the token is too short (${token.length} chars, minimum $MIN_LENGTH): " +
                    "a guessable token defeats the point of the gate; generate one with `openssl rand -hex 32`",
            )
            return of(token, source)
        }

        private fun readTokenFile(path: String, readFile: (Path) -> String): String {
            val contents = runCatching { readFile(Path.of(path)) }.getOrElse { e ->
                // The path is configuration, not a secret; the contents never enter the message.
                throw McpTokenException("$ENV_TOKEN_FILE is unreadable: $path (${e::class.simpleName}: ${e.message})")
            }
            return contents
        }
    }
}

private fun digestOf(text: String): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

/**
 * The `Authorization: Bearer <token>` header, parsed before the comparison so
 * the comparison itself has no branching on shape. Every rejection (missing
 * header, another scheme, no value) resolves to `null` and takes the same
 * [McpToken.matches] path as a wrong token.
 */
object Bearer {
    private const val SCHEME = "Bearer"

    fun credential(header: String?): String? {
        val raw = header?.trim().orEmpty()
        val space = raw.indexOf(' ')
        val scheme = if (space > 0) raw.substring(0, space) else ""
        return raw.substring(space + 1).trim().takeIf { space > 0 && scheme.equals(SCHEME, ignoreCase = true) }
    }
}
