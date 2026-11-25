package tech.tamerin.contextfriendpro

import tech.tamerin.contextfriendpro.Notes.mapText
import tech.tamerin.contextfriendpro.Notes.withSources

sealed interface CloudOutcome {
    data class Blocked(val reason: String) : CloudOutcome
    data class Done(val summary: Summary) : CloudOutcome
}

/**
 * An opt-in cloud request, prepared entirely on the device. The privacy panel shows [prompt],
 * which is exactly the text that would leave the phone in one request. The pseudonymizer and its
 * mapping exist only inside this object; drop the draft after use. Plain Kotlin, see CloudDraftTest.
 */
class CloudDraft private constructor(
    val sessionId: String,
    val revision: String,
    val prompt: String,
    /** The meeting lines inside [prompt], already redacted: what the review screen shows first. */
    val rows: List<SourceRow>,
    val counts: Map<Entity, Int>,
    val risk: ResidualRisk,
    private val ids: Map<String, String>,
    private val pseudonymizer: Pseudonymizer,
) {
    val redactedTotal: Int get() = counts.values.sum()

    /**
     * The only way a draft reaches a model: the policy gate runs right before the request, and the
     * model receives [prompt] (what the user reviewed) and nothing else.
     */
    suspend fun send(model: NotesModel, config: HybridConfig, firebaseReady: Boolean, consent: Boolean, transcriptIds: Set<String>): CloudOutcome {
        require(model.engine == NotesEngine.CLOUD_REDACTED) { "cloud_draft_needs_cloud_model" }
        return when (val decision = Policy.cloudDecision(config, firebaseReady, consent, risk)) {
            is CloudDecision.Block -> CloudOutcome.Blocked(decision.reason)
            CloudDecision.Allow -> CloudOutcome.Done(finish(model.complete(prompt), transcriptIds))
        }
    }

    /** Turns the cloud answer into notes: parse, keep sourced statements, map ids back, re-identify locally. */
    fun finish(raw: String, transcriptIds: Set<String>): Summary {
        val result = Notes.sanitize(Notes.parse(raw), ids.keys)
        result.validate(ids.keys)
        return result.withSources { listOf(ids.getValue(it)) }.mapText(pseudonymizer::reidentify).also { it.validate(transcriptIds) }
    }

    companion object {
        /** Names the redaction must know: named speakers, voice profiles, the user's roster, remote extra terms, words the user tapped. */
        fun knownNames(session: Session, profileNames: List<String>, roster: List<String>): List<KnownName> =
            (session.speakers.values.filterNot { GENERIC_SPEAKER.matches(it) } + profileNames + roster)
                .map(String::trim).filter { it.length >= 2 }.distinct().map(::KnownName)

        fun build(session: Session, names: List<KnownName>, extraTerms: List<String>, maxChars: Int): CloudDraft {
            require(session.transcript.isNotEmpty()) { "no_recognized_speech" }
            require(session.transcript.sumOf { it.text.length } <= maxChars) { "meeting_too_long_for_one_cloud_request" }
            val pseudonymizer = Pseudonymizer(names, extraTerms)
            val ids = session.transcript.mapIndexed { i, line -> "s${i + 1}" to line.id }.toMap()
            val rows = session.transcript.mapIndexed { i, line ->
                SourceRow("s${i + 1}", line.speaker?.let { session.speakers[it] ?: it }, line.text, line.uncertain)
            }
            val redacted = Prompts.redactedRows(rows, pseudonymizer)
            val prompt = Prompts.notes(session.language, MeetingTemplate.of(session.template), redacted, redacted = true)
            val risk = pseudonymizer.residualRisk(redacted.joinToString("\n") { listOfNotNull(it.speaker, it.text).joinToString(": ") }, session.language)
            return CloudDraft(session.id, session.revision, prompt, redacted, pseudonymizer.counts(), risk, ids, pseudonymizer)
        }

        private val GENERIC_SPEAKER = Regex("Speaker \\d+|Sprecher(in)? \\d+|דובר \\d+")
    }
}
