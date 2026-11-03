package dev.lattenero.brain.rag

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** One repository to index: the name, the walk root and the absolute glob handed to [Corpus]. */
data class Repo(val name: String, val root: Path, val glob: String) {
    val present: Boolean get() = Files.isDirectory(root)
}

@Serializable
data class RepoScan(
    val name: String,
    val root: String,
    val glob: String,
    val present: Boolean,
    val documents: Int,
    val bytes: Long,
)

@Serializable
data class RepoScanReport(
    val dryRun: Boolean,
    val projectsDir: String,
    val repos: List<RepoScan>,
    val documents: Int,
    val bytes: Long,
    val skipped: List<String>,
)

/**
 * The owner's repositories as RAG sources. `index` reads one corpus file;
 * this reads whole repositories, so the brain can answer questions about the
 * code, not only about the docs someone remembered to list.
 *
 * One `**` glob per repository, built from [Corpus.TEXT_EXTENSIONS], so the
 * walk keeps exactly the corpus rules: text-like files only, noise
 * directories (.git, build, node_modules, ...) pruned, oversized and non
 * UTF-8 files skipped with a reason. The default repository list is fixed,
 * so the same tree always scans to the same documents in the same order.
 *
 * [plan] performs the scan once and hands the indexer what the dry run
 * reported: the documents, the skipped reasons and, per glob, the uris that
 * still exist (the prune input). A dry run therefore never opens a database.
 * Repositories whose root does not exist are reported and never pruned:
 * a moved or unmounted checkout must not delete its already indexed
 * documents (deleting a file inside a present root still prunes it).
 */
class Repos(
    private val projectsDir: Path = defaultProjectsDir(),
    private val extensions: Set<String> = Corpus.TEXT_EXTENSIONS,
) {
    data class Plan(val repositories: List<Repo>, val scan: Corpus.Scan, val report: RepoScanReport) {
        val globs: List<String> get() = repositories.map { it.glob }

        /** The prune input: for every present repository, the uris still on disk under its glob. */
        val pruneKept: Map<String, List<String>> = repositories
            .filter { it.present }
            .associate { repo -> repo.glob to scan.documents.filter { it.source == repo.glob }.map { it.uri } }
    }

    fun resolve(names: List<String> = DEFAULT_NAMES): List<Repo> {
        val unknown = names.toSet() - DEFAULT_NAMES.toSet()
        require(unknown.isEmpty()) {
            "unknown repositories: ${unknown.sorted().joinToString()} (known: ${DEFAULT_NAMES.joinToString()})"
        }
        return names.distinct().map { name ->
            val root = projectsDir.resolve(name)
            Repo(name, root, globFor(root))
        }
    }

    /** The absolute alternation glob [Corpus.source] resolves back to [root]. */
    fun globFor(root: Path): String = "$root/**/*.{${extensions.sorted().joinToString(",")}}"

    /**
     * Scans every requested repository in one [Corpus] pass, so a file reached
     * by two globs is counted (and indexed) once, then attributes each
     * document to its source glob for the per-repository counters. Bytes are
     * the UTF-8 length of the content, which is the file size [Corpus] read.
     */
    fun plan(names: List<String> = DEFAULT_NAMES, corpus: Corpus = Corpus()): Plan {
        val repositories = resolve(names)
        val scan = corpus.scan(repositories.map { it.glob })
        val bySource = scan.documents.groupBy { it.source }
        val perRepo = repositories.map { repo ->
            val documents = bySource[repo.glob].orEmpty()
            RepoScan(
                name = repo.name,
                root = repo.root.toString(),
                glob = repo.glob,
                present = repo.present,
                documents = documents.size,
                bytes = documents.sumOf { it.content.toByteArray(Charsets.UTF_8).size.toLong() },
            )
        }
        return Plan(
            repositories = repositories,
            scan = scan,
            report = RepoScanReport(
                dryRun = true,
                projectsDir = projectsDir.toString(),
                repos = perRepo,
                documents = scan.documents.size,
                bytes = perRepo.sumOf { it.bytes },
                skipped = scan.skipped,
            ),
        )
    }

    companion object {
        val DEFAULT_NAMES = listOf("kap", "bot", "zcode-bot-kotlin", "ZCode", "agent-brain")

        fun defaultProjectsDir(home: Path = Paths.get(System.getProperty("user.home"))): Path = home.resolve("Projects")
    }
}
