package tech.tamerin.contextfriendpro

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A model that records every prompt it receives and answers with sourced JSON for the ids it sees. */
class FakeModel(override val engine: NotesEngine, private val maxRows: Int = Int.MAX_VALUE,
                private val answer: ((String) -> String)? = null) : NotesModel {
    override val modelName = "fake"
    val prompts = mutableListOf<String>()
    override val calls get() = prompts.size

    override suspend fun complete(prompt: String): String {
        val ids = Regex("\"id\":\"(s\\d+)\"").findAll(prompt).map { it.groupValues[1] }.toList()
        if (ids.size > maxRows) throw InputTooLong()
        prompts += prompt
        answer?.let { return it(prompt) }
        val first = ids.first()
        val placeholder = Regex("\\[PERSON_\\d+]").find(prompt)?.value ?: "the teacher"
        return """```json
            {"title":"Reading progress","paragraphs":[{"text":"$placeholder reported progress.","sources":["$first"]}],
             "decisions":[],"actions":[{"text":"Practise reading","owner":null,"due":null,"sources":["${ids.last()}"]}],
             "uncertainties":[],"extra":"ignored"}
            ```"""
    }
}

object Meetings {
    fun session(lines: Int = 5, template: String = "parent-teacher"): Session {
        val transcript = List(lines) { i ->
            val row = Synthetic.meeting[i % Synthetic.meeting.size]
            Segment(id = newId(), startMs = i * 10_000L, endMs = i * 10_000L + 9_000, original = row.text,
                speaker = if (i % 2 == 0) "Speaker 1" else "Speaker 2")
        }
        return Session(title = "Demo", language = "en", transcript = transcript, template = template,
            speakers = mapOf("Speaker 1" to "Jonas Testmann", "Speaker 2" to "Petra Sonnenberg"))
    }
}

class NotesTest {
    @Test fun notesAreSourcedToRealSegmentIds() = runBlocking {
        val s = Meetings.session()
        val summary = Notes.write(FakeModel(NotesEngine.GEMINI_NANO), s, 100_000)
        val ids = s.transcript.map { it.id }.toSet()
        assertTrue(summary.paragraphs.single().sources.all(ids::contains))
        assertEquals(s.transcript.last().id, summary.actions.single().sources.single())
    }

    @Test fun onDevicePromptUsesSpeakerNamesAndTemplate() = runBlocking {
        val model = FakeModel(NotesEngine.GEMINI_NANO)
        Notes.write(model, Meetings.session(), 100_000)
        assertTrue(model.prompts.single().contains("\"speaker\":\"Jonas Testmann\""))
        assertTrue(model.prompts.single().contains("parent-teacher conference"))
    }

    @Test fun longMeetingIsMapReducedToOneSummary() = runBlocking {
        val model = FakeModel(NotesEngine.GEMINI_NANO)
        val s = Meetings.session(lines = 40)
        val summary = Notes.write(model, s, chunkChars = 400)
        assertTrue(model.calls > 3)
        assertTrue(summary.paragraphs.isNotEmpty())
        summary.validate(s.transcript.map { it.id }.toSet())
    }

    @Test fun tooLongForTheEngineSplitsTheBatch() = runBlocking {
        val model = FakeModel(NotesEngine.GEMINI_NANO, maxRows = 4)
        val summary = Notes.write(model, Meetings.session(lines = 10), chunkChars = 100_000)
        assertTrue(model.prompts.all { Regex("\"id\":\"s\\d+\"").findAll(it).count() <= 4 })
        assertTrue(summary.actions.isNotEmpty())
    }

    @Test fun mergeThatCannotShrinkFailsOpenlyInsteadOfLooping() = runBlocking {
        val failure = runCatching { Notes.write(FakeModel(NotesEngine.GEMINI_NANO, maxRows = 3), Meetings.session(lines = 10), 100_000) }.exceptionOrNull()
        assertEquals("summary_too_long_to_merge", failure?.message)
    }

    @Test fun statementsWithUnknownIdsAreDropped() {
        val summary = Notes.sanitize(Notes.parse("""{"title":"T","paragraphs":[{"text":"ok","sources":["s1"]},{"text":"bad","sources":["s99"]}]}"""), setOf("s1"))
        assertEquals(listOf("ok"), summary.paragraphs.map { it.text })
    }

    @Test(expected = IllegalArgumentException::class) fun summaryWithoutAnyValidStatementFails() {
        Notes.sanitize(Notes.parse("""{"title":"T","paragraphs":[{"text":"bad","sources":["s99"]}]}"""), setOf("s1"))
    }

    @Test fun policyViolationIsNeverRetriedOrSwallowed() = runBlocking {
        val model = object : NotesModel {
            override val engine = NotesEngine.GEMINI_NANO
            override val modelName = "fake"
            override var calls = 0
            override suspend fun complete(prompt: String): String { calls++; throw PolicyViolation("on_device_request_answered_in_cloud") }
        }
        val failure = runCatching { Notes.write(model, Meetings.session(), 100_000) }.exceptionOrNull()
        assertTrue(failure is PolicyViolation)
        assertEquals(1, model.calls)
    }

    @Test fun garbageAnswerFailsAfterOneRetry() = runBlocking {
        val model = FakeModel(NotesEngine.GEMINI_NANO) { "I cannot help with that." }
        assertTrue(runCatching { Notes.write(model, Meetings.session(), 100_000) }.isFailure)
        assertEquals(2, model.calls)
    }
}

class CloudDraftTest {
    private val roster = Synthetic.roster.map { it.fullName }

    private fun draft(session: Session = Meetings.session()) =
        CloudDraft.build(session, CloudDraft.knownNames(session, emptyList(), roster), emptyList(), 400_000)

    @Test fun draftPromptContainsOnlyPlaceholders() {
        val d = draft()
        Synthetic.sensitive.forEach { assertFalse("leaked: $it", d.prompt.contains(it, ignoreCase = true)) }
        assertTrue(d.redactedTotal > 0)
        assertEquals(0, d.risk.detectorHits)
    }

    @Test fun withoutConsentTheModelIsNeverCalled() = runBlocking {
        val model = FakeModel(NotesEngine.CLOUD_REDACTED)
        val outcome = draft().send(model, HybridConfig(), firebaseReady = true, consent = false, transcriptIds = emptySet())
        assertEquals(CloudOutcome.Blocked("consent_required"), outcome)
        assertEquals(0, model.calls)
    }

    @Test fun killSwitchStopsTheCloudEvenWithConsent() = runBlocking {
        val model = FakeModel(NotesEngine.CLOUD_REDACTED)
        val outcome = draft().send(model, HybridConfig(cloudEnabled = false), firebaseReady = true, consent = true, transcriptIds = emptySet())
        assertEquals(CloudOutcome.Blocked("cloud_disabled"), outcome)
        assertEquals(0, model.calls)
    }

    @Test fun withConsentTheCloudSeesPlaceholdersAndTheAnswerIsReidentifiedLocally() = runBlocking {
        val s = Meetings.session()
        val model = FakeModel(NotesEngine.CLOUD_REDACTED)
        val outcome = draft(s).send(model, HybridConfig(), firebaseReady = true, consent = true, transcriptIds = s.transcript.map { it.id }.toSet())
        val sent = model.prompts.single()
        Synthetic.sensitive.forEach { assertFalse("leaked: $it", sent.contains(it, ignoreCase = true)) }
        val summary = (outcome as CloudOutcome.Done).summary
        assertFalse(summary.paragraphs.single().text.contains("[PERSON_"))
        assertTrue(roster.any { summary.paragraphs.single().text.contains(it) })
    }

    @Test fun genericSpeakerLabelsAreNotTreatedAsNames() {
        val s = Meetings.session().copy(speakers = mapOf("Speaker 1" to "Speaker 1"))
        assertTrue(CloudDraft.knownNames(s, emptyList(), emptyList()).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class) fun meetingTooLongForOneRequestIsRefused() {
        CloudDraft.build(Meetings.session(lines = 20), emptyList(), emptyList(), maxChars = 100)
    }

    @Test(expected = IllegalArgumentException::class) fun draftNeedsACloudModel(): Unit = runBlocking {
        draft().send(FakeModel(NotesEngine.GEMINI_NANO), HybridConfig(), true, true, emptySet())
    }
}
