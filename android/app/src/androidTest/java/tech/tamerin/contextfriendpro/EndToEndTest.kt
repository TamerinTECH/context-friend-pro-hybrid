package tech.tamerin.contextfriendpro

import android.app.ActivityManager
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The whole local pipeline with real models, as a fresh install would run it. Not part of the
 * quick suite: it needs network for the download test and the Gemma file already in place
 * (see BUILD.md), and each summary takes minutes. All speech and text here is public or invented.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ProApp
    private fun importAsset(id: String,asset: String) {
        if(app.models.installed(id))return
        val copy=File(app.cacheDir,asset);instrument.context.assets.open(asset).use {input->copy.outputStream().use {input.copyTo(it)}}
        try {app.models.install(id,Uri.fromFile(copy),AtomicBoolean(false)){_,_->}} finally {copy.delete()}
    }
    private fun models() {
        assumeTrue("Put the Gemma file in place first",app.models.installed("gemma4-e2b"))
        importAsset("whisper-tiny","ggml-tiny.bin");importAsset("titanet-small","nemo_en_titanet_small.onnx")
        if(!app.models.installed("pyannote-segmentation3"))importAsset("pyannote-segmentation3","segmentation.int8.onnx")
        val memory=ActivityManager.MemoryInfo();app.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        Log.i("CFE2E","memory total=${memory.totalMem/1048576} MiB available=${memory.availMem/1048576} MiB")
    }
    private fun process(id: String): Session {
        val started=System.currentTimeMillis();runBlocking {Processing(app).run(id)}
        val done=app.vault.load(id)
        Log.i("CFE2E","$id took ${(System.currentTimeMillis()-started)/1000}s state=${done.state} stages=${done.stages}")
        Log.i("CFE2E","transcript: "+done.transcript.joinToString(" | ") {it.text}.take(600))
        Log.i("CFE2E","summary: "+done.summary?.plain())
        return done
    }
    private fun assertSummarized(s: Session) {
        assertEquals(s.stages.toString(),"needs_review",s.state)
        val summary=s.summary!!;summary.validate(s.transcript.map {it.id}.toSet());assertTrue(summary.paragraphs.isNotEmpty())
    }
    /** A fresh install has no way to obtain a model except this button. */
    @Test fun everyRequiredModelCanBeDownloadedInTheApp() {
        assertTrue(app.models.manifest.models.none {it.archive})
        app.models.remove("pyannote-segmentation3")
        app.models.download("pyannote-segmentation3",AtomicBoolean(false)){_,_->}
        assertTrue(app.models.installed("pyannote-segmentation3"))
        assertEquals(app.models.entry("pyannote-segmentation3").sha256,digest(app.models.verifiedFile("pyannote-segmentation3")))
    }
    /** Audio in the encrypted vault → Whisper → speakers → Gemma summary. */
    @Test fun recordedSpeechBecomesTranscriptAndSummary() {
        models()
        val raw=instrument.context.assets.open("jfk.pcm").use {it.readBytes()}
        val s=Session(title="E2E speech",state="recorded",language="en",durationMs=raw.size/2*1000L/16000)
        val key=app.vault.create(s)
        try {
            raw.toList().chunked(32000).forEachIndexed {i,part -> app.vault.writeChunk(s.id,AudioChunk(i,i*1000L,pcm=Base64.getEncoder().encodeToString(part.toByteArray()),samples=part.size/2),key)}
            key.fill(0)
            val done=process(s.id);assertSummarized(done)
            assertTrue(done.transcript.joinToString(" ") {it.text}.lowercase().contains("country"))
            // Hackathon edition: the notes record where they ran, and the audit log holds metadata only.
            val notes=done.notes!!;Log.i("CFE2E","notes: $notes")
            assertTrue(NotesEngine.of(notes.engine).onDevice);assertEquals(0,notes.cloudCalls)
            val audit=Audit.export(app.vault);Log.i("CFE2E","audit: ${Audit.list(app.vault).last()}")
            assertTrue(audit.contains(Audit.meetingRef(s.id)));assertFalse(audit.lowercase().contains("country"))
        } finally {app.vault.delete(s.id)}
    }
    /** Whisper labels quiet audio [BLANK_AUDIO]. Pure zeros never reach it (engine.cpp), but a real room is never pure zeros. */
    @Test fun quietRecordingIsNotPresentedAsASummary() {
        models()
        val noise=java.util.Random(7);val raw=ByteArray(15*32000)
        for(i in raw.indices step 2) {val v=(noise.nextGaussian()*12).toInt();raw[i]=v.toByte();raw[i+1]=(v shr 8).toByte()}
        val s=Session(title="E2E quiet",state="recorded",language="en",durationMs=15000)
        val key=app.vault.create(s)
        try {
            raw.toList().chunked(32000).forEachIndexed {i,part -> app.vault.writeChunk(s.id,AudioChunk(i,i*1000L,pcm=Base64.getEncoder().encodeToString(part.toByteArray()),samples=part.size/2),key)}
            key.fill(0)
            val done=process(s.id)
            assertEquals(done.transcript.toString(),emptyList<Segment>(),done.transcript);assertNull(done.summary);assertEquals("failed",done.state)
        } finally {app.vault.delete(s.id)}
    }
    /** Longer than one batch: the case that used to fail with input_too_long before any answer. */
    @Test fun meetingLongerThanOneBatchIsSummarized() {
        models()
        val lines=listOf("Okay, let's start with the budget for the next quarter.","I think we agreed last time to keep the travel costs flat.",
            "Maria said the supplier wants an answer by the end of the month.","We still don't know whether the second contract is going ahead.",
            "So the plan is to test the new release with two customers first.","Daniel will send the updated numbers on Thursday.",
            "The question is who owns the migration once the pilot ends.","I would rather not promise a date until we have seen the results.",
            "Can we move the training to the week after the holiday?","The feedback from the last workshop was mostly about the slides.",
            "We decided not to renew the old licence next year.","There was a problem with the invoice from the agency again.",
            "Let's put that on the list for the next meeting.","I'll check with legal whether we need a new agreement for that.",
            "The team in Munich needs two more weeks for the integration.","Right, and the customer asked for a written confirmation.",
            "If we delay this, the whole schedule for the spring moves too.","Does anyone object to using the same vendor as last year?",
            "The numbers look better than we expected, but only slightly.","We should write down what we told them so nobody forgets.")
        val transcript=List(90) {i -> Segment(startMs=i*4000L,endMs=i*4000L+3500,original=lines[i%lines.size],speaker="Speaker ${1+i%3}",language="en")}
        assertTrue(transcript.sumOf {it.text.length}>4000)
        val s=Session(title="E2E long",state="recorded",language="en",durationMs=360000,transcript=transcript,
            stages=mapOf("asr" to Stage("done"),"speakers" to Stage("done")))
        app.vault.create(s).fill(0)
        try {assertSummarized(process(s.id))} finally {app.vault.delete(s.id)}
    }
}
