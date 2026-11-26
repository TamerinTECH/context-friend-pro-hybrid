package tech.tamerin.contextfriendpro

import kotlinx.serialization.json.Json

/** One engine that turns a prompt into text. Implementations check where the answer came from. */
interface NotesModel {
    val engine: NotesEngine
    val modelName: String
    val calls: Int
    suspend fun complete(prompt: String): String
}

/** The request is too long for this engine: the caller splits the batch and tries again. */
class InputTooLong(cause: Throwable? = null) : Exception("input_too_long", cause)
/** Gemini Nano is reported available but refuses every request on this phone yet. */
class NanoNotReady(cause: Throwable? = null) : Exception("nano_not_ready", cause)

/**
 * Sourced meeting notes, map-reduced over long meetings, for any [NotesModel].
 * Plain Kotlin: NotesTest runs the whole pipeline with a fake model.
 */
object Notes {
    private val modelJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    /** [progress] gets the share of model calls done (0..1): map calls plus the merge rounds they need. */
    suspend fun write(model: NotesModel, session: Session, chunkChars: Int, check: () -> Unit = {}, progress: (Float) -> Unit = {}): Summary {
        require(session.transcript.isNotEmpty()) { "no_recognized_speech" }
        val template = MeetingTemplate.of(session.template)

        suspend fun generate(lines: List<Segment>, speakers: Boolean): List<Summary> {
            check()
            // Short ids: a UUID costs the model more tokens than the words of a short segment.
            val ids = lines.mapIndexed { i, line -> "s${i + 1}" to line.id }.toMap()
            val rows = lines.mapIndexed { i, line ->
                SourceRow("s${i + 1}", if (speakers) line.speaker?.let { session.speakers[it] ?: it } else null, line.text, line.uncertain)
            }
            val prompt = Prompts.notes(session.language, template, rows, redacted = false)
            var failure: Exception? = null
            repeat(2) {
                val raw = try {
                    model.complete(prompt)
                } catch (e: InputTooLong) {
                    // How many tokens a text needs depends on its language: split and try again.
                    if (lines.size > 1) return lines.chunked((lines.size + 1) / 2).flatMap { generate(it, speakers) }
                    throw e
                }
                try {
                    val result = sanitize(parse(raw), ids.keys)
                    result.validate(ids.keys)
                    return listOf(result.withSources { listOf(ids.getValue(it)) })
                } catch (e: Exception) {
                    failure = e
                    check()
                }
            }
            throw failure ?: IllegalStateException("summary_invalid")
        }

        val groups = mutableListOf<List<Segment>>()
        var batch = mutableListOf<Segment>()
        var chars = 0
        session.transcript.forEach { line ->
            if (chars + line.text.length > chunkChars && batch.isNotEmpty()) { groups += batch.toList(); batch.clear(); chars = 0 }
            batch += line; chars += line.text.length
        }
        if (batch.isNotEmpty()) groups += batch.toList()

        fun merges(n: Int): Int = if (n <= 1) 0 else (n + 2) / 3 + merges((n + 2) / 3)
        val planned = (groups.size + merges(groups.size)).coerceAtLeast(1)
        var done = 0
        fun step() { done++; progress((done.toFloat() / planned).coerceAtMost(1f)) }
        // Each map result keeps its source ids. Hierarchical synthesis avoids truncating later sections.
        var parts = groups.flatMap { generate(it, speakers = true).also { step() } }
        while (parts.size > 1) {
            val before = parts.size
            parts = parts.chunked(3).flatMap { group ->
                if (group.size == 1) group else {
                    val mapping = mutableMapOf<String, List<String>>()
                    fun row(text: String, sources: List<String>): Segment {
                        val id = newId(); mapping[id] = sources
                        return Segment(id = id, startMs = 0, endMs = 0, original = text)
                    }
                    val lines = group.flatMap { p ->
                        (p.paragraphs + p.decisions + p.uncertainties).map { row(it.text, it.sources) } +
                            p.actions.map { row(listOfNotNull(it.text, it.owner, it.due).joinToString(" · "), it.sources) }
                    }
                    generate(lines, speakers = false).map { reduced -> reduced.withSources { mapping.getValue(it) } }.also { step() }
                }
            }
            // A round that merges nothing would never end: fail openly, never keep a partial summary.
            require(parts.size < before) { "summary_too_long_to_merge" }
        }
        check()
        return parts.single().also { it.validate(session.transcript.map { line -> line.id }.toSet()) }
    }

    /** Model output as JSON: tolerant of code fences and unknown keys, strict about the schema it keeps. */
    fun parse(raw: String): Summary {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        require(start >= 0 && end > start) { "summary_not_json" }
        return modelJson.decodeFromString(Summary.serializer(), text.substring(start, end + 1))
    }

    /** Drops statements without a valid source id instead of failing a whole meeting on one slip. */
    fun sanitize(summary: Summary, ids: Set<String>): Summary {
        fun Statement.clean() = copy(sources = sources.filter(ids::contains).distinct()).takeIf { it.text.isNotBlank() && it.sources.isNotEmpty() }
        val result = summary.copy(
            title = summary.title.trim().ifBlank { "Meeting" }.take(500),
            paragraphs = summary.paragraphs.mapNotNull { it.clean() },
            decisions = summary.decisions.mapNotNull { it.clean() },
            uncertainties = summary.uncertainties.mapNotNull { it.clean() },
            actions = summary.actions.mapNotNull { a ->
                a.copy(sources = a.sources.filter(ids::contains).distinct()).takeIf { it.text.isNotBlank() && it.sources.isNotEmpty() }
            },
        )
        require(result.paragraphs.isNotEmpty() || result.decisions.isNotEmpty() || result.actions.isNotEmpty()) { "summary_empty" }
        return result
    }

    fun Summary.withSources(sources: (String) -> List<String>): Summary {
        fun Statement.mapped() = copy(sources = this.sources.flatMap(sources).distinct())
        return copy(paragraphs = paragraphs.map { it.mapped() }, decisions = decisions.map { it.mapped() },
            uncertainties = uncertainties.map { it.mapped() }, actions = actions.map { it.copy(sources = it.sources.flatMap(sources).distinct()) })
    }

    /** Applies [f] to every human-readable text of a summary (used to re-identify cloud answers locally). */
    fun Summary.mapText(f: (String) -> String): Summary = copy(
        title = f(title),
        paragraphs = paragraphs.map { it.copy(text = f(it.text)) },
        decisions = decisions.map { it.copy(text = f(it.text)) },
        uncertainties = uncertainties.map { it.copy(text = f(it.text)) },
        actions = actions.map { it.copy(text = f(it.text), owner = it.owner?.let(f), due = it.due?.let(f)) },
    )
}
