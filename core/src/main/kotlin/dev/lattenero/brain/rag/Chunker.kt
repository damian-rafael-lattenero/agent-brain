package dev.lattenero.brain.rag

/**
 * Heading-aware chunker for markdown-ish text. Pure: same input, same
 * chunks; no IO, no clocks, no state between calls.
 *
 * 1. The text is cut into sections at ATX headings (`#`..`######`), with
 *    fenced code blocks respected (a `#` inside ``` is code, not a heading).
 *    Every section remembers its heading chain ("Title > Guide > Quickstart").
 * 2. A section longer than [targetChars] is split by paragraphs, and a
 *    paragraph longer than that by sliding windows of [targetChars] with
 *    [overlapChars] of shared text.
 * 3. Consecutive small pieces are packed into chunks of at most
 *    [targetChars]; a new chunk starts with the last [overlapChars] of the
 *    previous one (cut at whitespace when possible) so a fact straddling a
 *    boundary is retrievable from either side.
 *
 * Every chunk's content starts with its heading chain: the embedding and
 * the full-text index both see where the text lives.
 */
class Chunker(
    private val targetChars: Int = DEFAULT_TARGET_CHARS,
    private val overlapChars: Int = DEFAULT_OVERLAP_CHARS,
) {
    init {
        require(targetChars >= MIN_TARGET_CHARS) { "targetChars must be >= $MIN_TARGET_CHARS" }
        require(overlapChars in 0 until targetChars / 2) { "overlapChars must be in [0, targetChars/2)" }
    }

    data class Chunk(val ordinal: Int, val heading: String, val content: String) {
        val chars: Int get() = content.length
    }

    internal data class Piece(val heading: String, val text: String)

    fun chunk(title: String, text: String): List<Chunk> {
        val pieces = sections(title, text).flatMap { split(it) }
        if (pieces.isEmpty()) return emptyList()
        val chunks = ArrayList<Chunk>()
        var heading = pieces.first().heading
        val current = StringBuilder()
        for (piece in pieces) {
            if (current.isEmpty()) {
                current.append(piece.text)
                heading = piece.heading
            } else if (current.length + SEPARATOR.length + piece.text.length <= targetChars) {
                current.append(SEPARATOR).append(piece.text)
            } else {
                chunks += Chunk(chunks.size, heading, heading + "\n" + current)
                val tail = overlapTail(current.toString())
                current.setLength(0)
                if (tail.isNotEmpty()) current.append(tail).append(SEPARATOR)
                current.append(piece.text)
                heading = piece.heading
            }
        }
        if (current.isNotEmpty()) chunks += Chunk(chunks.size, heading, heading + "\n" + current)
        return chunks
    }

    internal fun sections(title: String, text: String): List<Piece> {
        val out = ArrayList<Piece>()
        val chain = ArrayDeque<String>()
        var heading = title.trim()
        val body = StringBuilder()
        var inFence = false
        fun flush() {
            val trimmed = body.toString().trim()
            if (trimmed.isNotEmpty()) out += Piece(heading, trimmed)
            body.setLength(0)
        }
        for (line in text.lineSequence()) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence
                body.append(line).append('\n')
                continue
            }
            val level = if (inFence) null else headingLevel(trimmed)
            if (level == null) {
                body.append(line).append('\n')
                continue
            }
            flush()
            val label = trimmed.drop(level).trim().trimEnd('#').trim()
            while (chain.size >= level) chain.removeLast()
            chain.addLast(label)
            heading = (listOf(title.trim()) + chain).filter { it.isNotEmpty() }.joinToString(" > ")
        }
        flush()
        return out
    }

    private fun headingLevel(trimmed: String): Int? {
        val hashes = trimmed.takeWhile { it == '#' }.length
        return if (hashes in 1..6 && trimmed.getOrNull(hashes) == ' ') hashes else null
    }

    private fun split(piece: Piece): List<Piece> {
        if (piece.text.length <= targetChars) return listOf(piece)
        val out = ArrayList<Piece>()
        val packed = StringBuilder()
        for (paragraph in piece.text.split(PARAGRAPH_BREAK)) {
            val para = paragraph.trim()
            if (para.isEmpty()) continue
            if (para.length > targetChars) {
                if (packed.isNotEmpty()) {
                    out += Piece(piece.heading, packed.toString())
                    packed.setLength(0)
                }
                out += windows(piece.heading, para)
            } else if (packed.isEmpty()) {
                packed.append(para)
            } else if (packed.length + SEPARATOR.length + para.length <= targetChars) {
                packed.append(SEPARATOR).append(para)
            } else {
                out += Piece(piece.heading, packed.toString())
                packed.setLength(0)
                packed.append(para)
            }
        }
        if (packed.isNotEmpty()) out += Piece(piece.heading, packed.toString())
        return out
    }

    private fun windows(heading: String, para: String): List<Piece> {
        val step = targetChars - overlapChars
        val out = ArrayList<Piece>()
        var start = 0
        while (start < para.length) {
            val end = minOf(start + targetChars, para.length)
            out += Piece(heading, para.substring(start, end))
            if (end == para.length) break
            start += step
        }
        return out
    }

    private fun overlapTail(content: String): String {
        if (overlapChars <= 0 || content.length <= overlapChars) return if (overlapChars <= 0) "" else content
        val raw = content.takeLast(overlapChars)
        val cut = raw.indexOfFirst { it.isWhitespace() }
        return if (cut in 0 until raw.length - 1) raw.substring(cut + 1) else raw
    }

    companion object {
        const val DEFAULT_TARGET_CHARS = 3200
        const val DEFAULT_OVERLAP_CHARS = 300
        const val MIN_TARGET_CHARS = 200
        const val SEPARATOR = "\n\n"
        private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")
    }
}
