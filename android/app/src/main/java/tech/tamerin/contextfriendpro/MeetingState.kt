package tech.tamerin.contextfriendpro

/**
 * What the screens show for a meeting, derived from saved state only. Plain Kotlin, see MeetingStateTest.
 * The UI renders actions from these values instead of guessing from a screen name.
 */
enum class Phase { RECORDING, PROCESSING, WAITING, NOT_PROCESSED, NO_SPEECH, FAILED, INTERRUPTED, DRAFT, REVIEWED, RECEIVED }

/** Shares of the overall progress: speech to text is the longest step on a phone, the notes the shortest. */
const val SPEECH_SHARE = 0.65f
const val SPEAKER_SHARE = 0.25f
const val NOTES_SHARE = 0.10f

/** Where the current notes were made. Shown per meeting; there is no global "processed on this device" claim. */
enum class Origin { ON_DEVICE, CLOUD, RECEIVED, NONE }

/** True when at least one transcript line is real speech, not a Whisper label such as [BLANK_AUDIO]. */
fun Session.hasSpeech(): Boolean = transcript.any { it.text.isNotBlank() && !notSpeech(it.text) }

/**
 * Nothing was said: speech recognition ran (or notes were attempted) and found no speech.
 * Older builds wrote a summary of [BLANK_AUDIO] labels; those meetings count as no speech too.
 */
fun Session.noSpeech(): Boolean {
    if (received || !editedSummary.isNullOrBlank() || hasSpeech()) return false
    return stages["asr"]?.state == "done" || stages["summary"]?.error == "no_recognized_speech" || summary != null
}

fun Session.phase(processingId: String?, recordingId: String?): Phase = when {
    id == recordingId -> Phase.RECORDING
    id == processingId -> Phase.PROCESSING
    received -> Phase.RECEIVED
    state == "recording" || state == "paused" || state == "interrupted" -> Phase.INTERRUPTED
    noSpeech() -> Phase.NO_SPEECH
    summaryText().isNotBlank() -> if (approved) Phase.REVIEWED else Phase.DRAFT
    // Paused (a new recording, heat, memory) or waiting for the screen (Gemini Nano): continues by itself.
    state == "queued" || state == "notes_waiting" -> Phase.WAITING
    state == "failed" || state == "cancelled" -> Phase.FAILED
    else -> Phase.NOT_PROCESSED
}

fun Session.origin(): Origin = when {
    received -> Origin.RECEIVED
    notes == null -> Origin.NONE
    notes.engine == NotesEngine.CLOUD_REDACTED.id -> Origin.CLOUD
    else -> Origin.ON_DEVICE
}

/** The error code of the stage that failed, if any. */
fun Session.failure(): String? = stages.values.firstOrNull { it.state == "failed" && it.error != null }?.error

/** A title only when there is a real one: the user's name, generated notes or a received title. Otherwise null. */
fun Session.topicTitle(): String? =
    title.trim().takeIf { it.isNotEmpty() && (customTitle || received || (summary != null && !noSpeech())) }

/** The most recent earlier version that had notes, for "switch to the previous notes". */
fun Session.previousNotes(): Revision? = history.lastOrNull { it.summary != null || !it.editedSummary.isNullOrBlank() }

/**
 * Swaps the current notes with the previous version (for example back to the on-device notes after a
 * cloud run). The current version goes into the history, so the swap can be undone. Review starts again.
 */
fun Session.restorePrevious(): Session {
    val previous = previousNotes() ?: return this
    return copy(summary = previous.summary, editedSummary = previous.editedSummary, notes = previous.notes,
        title = if (customTitle) title else previous.summary?.title ?: title,
        revision = newId(), approvedRevision = null, state = "needs_review",
        history = (history - previous + snapshot()).takeLast(20))
}

/** Processing steps for the "Creating your notes" view, in order. */
enum class Step(val stage: String?) { RECORDED(null), SPEECH("asr"), SPEAKERS("speakers"), NOTES("summary") }
enum class StepState { DONE, CURRENT, PENDING }

fun Session.steps(): List<Pair<Step, StepState>> {
    var currentGiven = false
    return Step.entries.map { step ->
        val done = step.stage == null || stages[step.stage]?.state == "done"
        val state = when {
            done -> StepState.DONE
            !currentGiven -> { currentGiven = true; StepState.CURRENT }
            else -> StepState.PENDING
        }
        step to state
    }
}

/** Milliseconds of audio already transcribed: speech recognition works in 30-second windows. */
fun Session.transcribedMs(): Long = minOf(durationMs, (stages["asr"]?.completedWindows ?: 0) * 30_000L)

/** Whether this phone can create notes for [language] without the internet, using the same policy as processing. */
fun localNotesReady(language: String, engine: String, nano: NanoState, gemmaInstalled: Boolean): Boolean {
    val preference = runCatching { EnginePreference.valueOf(engine) }.getOrDefault(EnginePreference.AUTO)
    return Policy.chooseOnDevice(language, preference, OnDeviceOptions(nano, gemmaInstalled, gemmaFitsMemory = true)) is LocalChoice.Use
}

/** Failure codes that mean "set up on-device AI first" rather than "try again". */
val SETUP_FAILURES = setOf("nano_not_downloaded", "nano_downloading", "nano_unavailable", "nano_not_ready", "gemma_missing", "no_on_device_engine",
    "stage_failed_retry_or_check_models", "model_integrity_failed")

/** History before new notes replace the current ones: the current notes stay restorable. */
fun Session.keepPrevious(): List<Revision> =
    if (summary == null && editedSummary.isNullOrBlank()) history else (history + snapshot()).takeLast(20)

/** States in which processing was started but stopped before the notes: a killed app, an interruption, waiting for the screen. */
// "recording"/"paused" here means the app died mid-recording: callers check that no recording runs.
private val UNFINISHED = setOf("recording", "paused", "recorded", "interrupted", "queued", "transcribing", "diarizing", "summarizing", "notes_waiting")

/**
 * The meeting still needs its notes and can get them on this phone: audio kept, never received, not failed or
 * cancelled by the person (those wait for "Retry"), no notes yet, and not already known to hold no speech.
 */
fun Session.needsProcessing(): Boolean =
    !received && audioRetained && durationMs > 0 && state in UNFINISHED && summaryText().isBlank() && !noSpeech()

/** Milliseconds of audio whose speakers are separated: speaker separation works in 60-second windows. */
fun Session.speakersMs(): Long = minOf(durationMs, (stages["speakers"]?.completedWindows ?: 0) * 60_000L)

/** How much of the notes work is saved, 0..100, from finished windows: the same weights as the live progress. */
fun Session.donePercent(): Int {
    if (durationMs <= 0) return 0
    fun part(stage: String, windowMs: Long): Float = when (stages[stage]?.state) {
        "done" -> 1f
        else -> ((stages[stage]?.completedWindows ?: 0) * windowMs).toFloat().div(durationMs).coerceIn(0f, 1f)
    }
    val notes = if (stages["summary"]?.state == "done") 1f else 0f
    return ((SPEECH_SHARE * part("asr", 30_000) + SPEAKER_SHARE * part("speakers", 60_000) + NOTES_SHARE * notes) * 100).toInt()
}
