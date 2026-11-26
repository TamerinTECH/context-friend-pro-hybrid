package tech.tamerin.contextfriendpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingStateTest {
    private fun line(text: String) = Segment(startMs = 0, endMs = 1000, original = text)
    private val notes = Summary("Reading support", listOf(Statement("Agreed on two reading sessions a week.", listOf("x"))))
    private val local = NotesProvenance(NotesEngine.GEMINI_NANO.id, "gemini-nano")
    private val cloud = NotesProvenance(NotesEngine.CLOUD_REDACTED.id, "gemini · eu")

    @Test fun silentRecordingIsNoSpeechNotAReviewableSummary() {
        val s = Session(title = "t", transcript = listOf(line("[BLANK_AUDIO]")), stages = mapOf("asr" to Stage("done")))
        assertTrue(s.noSpeech())
        assertEquals(Phase.NO_SPEECH, s.phase(null, null))
    }

    @Test fun olderBlankSummariesCountAsNoSpeech() {
        val blank = Summary("Meeting Notes", listOf(Statement("[BLANK_AUDIO]", listOf("x"))))
        val s = Session(title = "Meeting Notes", transcript = listOf(line("[BLANK_AUDIO]")), summary = blank)
        assertEquals(Phase.NO_SPEECH, s.phase(null, null))
        assertNull(s.topicTitle())
    }

    @Test fun failedForNoSpeechIsNoSpeech() {
        val s = Session(title = "t", state = "failed", stages = mapOf("summary" to Stage("failed", error = "no_recognized_speech")))
        assertEquals(Phase.NO_SPEECH, s.phase(null, null))
    }

    @Test fun draftThenReviewedThenDraftAfterEdit() {
        val draft = Session(title = notes.title, transcript = listOf(line("We agreed on reading.")), summary = notes, state = "needs_review")
        assertEquals(Phase.DRAFT, draft.phase(null, null))
        val reviewed = draft.copy(approvedRevision = draft.revision)
        assertEquals(Phase.REVIEWED, reviewed.phase(null, null))
        assertEquals(Phase.DRAFT, reviewed.edited().copy(editedSummary = "changed").phase(null, null))
    }

    @Test fun liveStatesWinAndStaleRecordingIsInterrupted() {
        val s = Session(title = "t", state = "recording")
        assertEquals(Phase.RECORDING, s.phase(null, s.id))
        assertEquals(Phase.INTERRUPTED, s.phase(null, null))
        assertEquals(Phase.PROCESSING, s.copy(state = "transcribing").phase(s.id, null))
        assertEquals(Phase.NOT_PROCESSED, s.copy(state = "recorded").phase(null, null))
        assertEquals(Phase.FAILED, s.copy(state = "failed").phase(null, null))
    }

    @Test fun receivedNotesAreReceivedAndNeverNoSpeech() {
        val s = Session(title = "Shared notes", received = true, editedSummary = null, summary = notes)
        assertFalse(s.noSpeech())
        assertEquals(Phase.RECEIVED, s.phase(null, null))
        assertEquals(Origin.RECEIVED, s.origin())
        assertEquals("Shared notes", s.topicTitle())
    }

    @Test fun originFollowsTheRecordedEngine() {
        assertEquals(Origin.NONE, Session(title = "t").origin())
        assertEquals(Origin.ON_DEVICE, Session(title = "t", notes = local).origin())
        assertEquals(Origin.CLOUD, Session(title = "t", notes = cloud).origin())
    }

    @Test fun noTopicTitleUntilThereIsOne() {
        val recorded = Session(title = "Nov 3, 2026 11:40 PM")
        assertNull(recorded.topicTitle())
        assertEquals("Lesson 4b", recorded.copy(title = "Lesson 4b", customTitle = true).topicTitle())
        assertEquals("Reading support", recorded.copy(title = "Reading support", summary = notes, transcript = listOf(line("Reading."))).topicTitle())
    }

    @Test fun cloudNotesKeepTheLocalVersionAndCanSwitchBack() {
        val onDevice = Session(title = notes.title, transcript = listOf(line("Reading.")), summary = notes, notes = local)
        val cloudNotes = notes.copy(title = "Reading support plan")
        val afterCloud = onDevice.copy(history = onDevice.keepPrevious(), summary = cloudNotes, notes = cloud, title = cloudNotes.title, revision = newId())
        assertEquals(local, afterCloud.previousNotes()?.notes)
        val restored = afterCloud.restorePrevious()
        assertEquals(notes, restored.summary)
        assertEquals(local, restored.notes)
        assertEquals("Reading support", restored.title)
        assertFalse(restored.approved)
        // and back again
        assertEquals(cloud, restored.previousNotes()?.notes)
    }

    @Test fun restoreKeepsTheUsersTitle() {
        val s = Session(title = "My title", customTitle = true, summary = notes, notes = cloud,
            history = listOf(Revision("r", emptyList(), notes.copy(title = "Other"), null, false, local)))
        assertEquals("My title", s.restorePrevious().title)
    }

    @Test fun stepsMarkTheFirstUnfinishedStageAsCurrent() {
        val s = Session(title = "t", durationMs = 300_000, stages = mapOf("asr" to Stage("running", completedWindows = 3)))
        assertEquals(listOf(StepState.DONE, StepState.CURRENT, StepState.PENDING, StepState.PENDING), s.steps().map { it.second })
        assertEquals(90_000L, s.transcribedMs())
    }

    @Test fun readinessUsesThePolicy() {
        assertTrue(localNotesReady("en", "AUTO", NanoState.UNAVAILABLE, gemmaInstalled = true))
        assertFalse(localNotesReady("en", "AUTO", NanoState.UNAVAILABLE, gemmaInstalled = false))
    }

    @Test fun unfinishedMeetingsAreResumedFinishedOnesAreNot() {
        val audio = Session(title = "t", durationMs = 60_000)
        assertTrue(audio.copy(state = "recorded").needsProcessing())
        assertTrue(audio.copy(state = "interrupted").needsProcessing())
        assertTrue(audio.copy(state = "transcribing").needsProcessing())      // the app was killed while processing
        assertTrue(audio.copy(state = "notes_waiting").needsProcessing())     // Gemini Nano waited for the screen
        assertTrue(audio.copy(state = "recording").needsProcessing())         // the app died mid-recording
        assertFalse(audio.copy(state = "failed").needsProcessing())           // waits for the person's Retry
        assertFalse(audio.copy(state = "cancelled").needsProcessing())
        assertFalse(audio.copy(state = "recorded", durationMs = 0).needsProcessing())
        assertFalse(audio.copy(state = "recorded", audioRetained = false).needsProcessing())
        assertFalse(audio.copy(state = "recorded", received = true).needsProcessing())
        assertFalse(audio.copy(state = "needs_review", summary = notes, transcript = listOf(line("Reading."))).needsProcessing())
        assertFalse(audio.copy(state = "recorded", transcript = listOf(line("[BLANK_AUDIO]")), stages = mapOf("asr" to Stage("done"))).needsProcessing())
    }

    @Test fun pausedMeetingsWaitAndKeepTheirPercent() {
        val s = Session(title = "t", durationMs = 600_000, state = "queued", transcript = listOf(line("We agreed on reading.")),
            stages = mapOf("asr" to Stage("done"), "speakers" to Stage("running", completedWindows = 5)))
        assertEquals(Phase.WAITING, s.phase(null, null))
        assertEquals(Phase.PROCESSING, s.phase(s.id, null))
        assertEquals(Phase.WAITING, s.copy(state = "notes_waiting").phase(null, null))
        assertTrue(s.needsProcessing())
        assertEquals(77, s.donePercent())   // 65 % speech + half of the 25 % for speakers
        assertEquals(0, Session(title = "t").donePercent())
        assertEquals(Phase.DRAFT, s.copy(summary = notes, transcript = listOf(line("Reading."))).phase(null, null))
    }

    @Test fun speakerProgressCountsMinuteWindows() {
        assertEquals(120_000L, Session(title = "t", durationMs = 300_000, stages = mapOf("speakers" to Stage("running", completedWindows = 2))).speakersMs())
    }
}
