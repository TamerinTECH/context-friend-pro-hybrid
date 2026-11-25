package tech.tamerin.contextfriendpro

/**
 * Debug builds only (Settings → Advanced): a synthetic example meeting, so the summary, transcript and review
 * screens can be checked on an emulator without a microphone. Fictional people, clearly titled "Example",
 * no audio, and no notes provenance (nothing claims an engine wrote it). Never shown in release builds.
 */
object ExampleMeeting {
    fun create(app: ProApp): String {
        val lines = listOf(
            "S1" to "Thanks for coming in. I wanted to talk about how Noah is doing with reading this term.",
            "S2" to "Sure. At home he reads a little every evening, but he gets tired after ten minutes.",
            "S1" to "In class he is doing well with short texts. Longer chapters are still hard for him.",
            "S2" to "Should we try audiobooks alongside the printed book?",
            "S1" to "Yes, let's try that. I'll also give him two short reading sessions a week with Ms Rivera.",
            "S2" to "Good. I can check in with you again before the winter break.",
            "S1" to "Great. Let's meet again in three weeks and see how it goes.",
        )
        var start = 0L
        val transcript = lines.mapIndexed { i, (speaker, text) ->
            val length = 4000L + text.length * 55L
            Segment(id = newId(), startMs = start, endMs = start + length, original = text, speaker = speaker).also { start += length + 600 }
        }
        val id = transcript.map { it.id }
        val summary = Summary(
            title = "Example: reading support check-in",
            paragraphs = listOf(
                Statement("Noah reads a little every evening at home but tires after about ten minutes.", listOf(id[1])),
                Statement("In class he manages short texts well; longer chapters are still difficult.", listOf(id[2])),
            ),
            decisions = listOf(Statement("Try audiobooks alongside the printed book.", listOf(id[3], id[4]))),
            actions = listOf(
                ActionItem("Two short reading sessions a week", owner = "Ms Rivera", sources = listOf(id[4])),
                ActionItem("Meet again to review progress", due = "in three weeks", sources = listOf(id[6])),
            ),
            uncertainties = listOf(Statement("When the parent will check in before the winter break was not fixed.", listOf(id[5]))),
        )
        val session = Session(title = summary.title, customTitle = true, language = "en", state = "needs_review",
            durationMs = start, transcript = transcript, summary = summary, speakers = mapOf("S1" to "Teacher", "S2" to "Parent"),
            stages = mapOf("asr" to Stage("done"), "speakers" to Stage("done"), "summary" to Stage("done")),
            audioRetained = false, template = "parent-teacher")
        app.vault.create(session)
        return session.id
    }
}
