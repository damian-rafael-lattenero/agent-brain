package dev.lattenero.brain.rag

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * The corpus is a list of absolute globs (the cv repo's markdown tree, the
 * course folder, a transcripts directory), one per line in the corpus file
 * (`#` comments allowed) or given on the command line. Only text-like
 * extensions are indexed, noise directories are pruned, and files above
 * [maxBytes] are skipped and reported.
 */
class Corpus(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val extensions: Set<String> = TEXT_EXTENSIONS,
    private val prunedDirs: Set<String> = PRUNED_DIRS,
) {
    data class Source(val glob: String, val root: Path, val matchers: List<PathMatcher>) {
        fun matches(path: Path): Boolean = matchers.any { it.matches(path) }
    }

    data class Document(val source: String, val uri: String, val title: String, val content: String, val sha: String)

    data class Scan(val documents: List<Document>, val skipped: List<String>)

    fun parseGlobs(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()

    /** The longest glob-free prefix is the walk root; the rest is the matcher. */
    fun source(glob: String): Source {
        require(glob.startsWith("/")) { "corpus globs must be absolute: $glob" }
        val parts = glob.split("/")
        val firstWild = parts.indexOfFirst { it.any { ch -> ch in GLOB_CHARS } }
        val root = if (firstWild < 0) Paths.get(glob).parent else Paths.get(parts.take(firstWild).joinToString("/").ifEmpty { "/" })
        // Java's "**/" needs a directory in between; people mean "any depth, including none".
        val patterns = listOf(glob, glob.replace("/**/", "/")).distinct()
        val matchers = patterns.map { FileSystems.getDefault().getPathMatcher("glob:$it") }
        return Source(glob, root, matchers)
    }

    fun scan(globs: List<String>): Scan {
        val documents = LinkedHashMap<String, Document>()
        val skipped = ArrayList<String>()
        for (glob in globs) {
            val source = source(glob)
            if (!Files.isDirectory(source.root)) {
                skipped += "${source.glob}: root ${source.root} is not a directory"
                continue
            }
            Files.walk(source.root).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .filter { path -> path.none { it.toString() in prunedDirs } }
                    .filter { source.matches(it) }
                    .filter { it.fileName.toString().substringAfterLast('.', "").lowercase() in extensions }
                    .sorted()
                    .forEach { path ->
                        val uri = path.toAbsolutePath().toString()
                        if (uri in documents) return@forEach
                        val size = Files.size(path)
                        if (size > maxBytes) {
                            skipped += "$uri: $size bytes > $maxBytes"
                            return@forEach
                        }
                        val content = try {
                            Files.readString(path)
                        } catch (e: java.nio.charset.MalformedInputException) {
                            skipped += "$uri: not UTF-8"
                            return@forEach
                        }
                        documents[uri] = Document(
                            source = source.glob,
                            uri = uri,
                            title = title(source.root, path),
                            content = content,
                            sha = sha256(content),
                        )
                    }
            }
        }
        return Scan(documents.values.toList(), skipped)
    }

    private fun title(root: Path, path: Path): String = root.relativize(path).toString()

    companion object {
        const val DEFAULT_MAX_BYTES = 1_000_000L
        private const val GLOB_CHARS = "*?[{"
        val TEXT_EXTENSIONS = setOf(
            "md", "txt", "rst", "adoc", "kt", "kts", "java", "py", "sql", "yml", "yaml", "toml", "json",
            "sh", "mjs", "js", "ts", "tex", "cfg", "ini", "properties", "gradle",
        )
        val PRUNED_DIRS = setOf(".git", "node_modules", "build", ".gradle", ".venv", "venv", "__pycache__", ".idea", "dist", "target", ".kotlin")

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
