package tech.tamerin.contextfriendpro

import android.app.ActivityManager
import android.os.PowerManager
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object NativeEngine {
    init {System.loadLibrary("cf_native")}
    external fun transcribe(path: String,samples: FloatArray,language: String): ByteArray
    external fun summarize(path: String,prompt: ByteArray): ByteArray
    external fun cancel()
    external fun reset()
}
private val nonSpeechLabels=Regex("""(\s*([\[(][^\[\]()]*[\])]|♪+))+\s*""")
/** Whisper's labels for what nobody said: [BLANK_AUDIO], (wind blowing). A real microphone never delivers pure zeros, so every quiet stretch yields them. */
internal fun notSpeech(text: String)=nonSpeechLabels.matches(text)
data class ChunkIndex(val index: Int,val startMs: Long,val samples: Int)
class AudioReader(private val vault: Vault,private val id: String): AutoCloseable {
    private val key=vault.sessionKey(id)
    val index=vault.chunks(id).map {n -> vault.readChunk(id,n,key).let {ChunkIndex(n,it.startMs,it.samples)} }
    fun window(startMs: Long,durationMs: Long): FloatArray {
        require(durationMs in 1..65000)
        val result=FloatArray((durationMs*16).toInt())
        index.filter {it.startMs<startMs+durationMs && it.startMs+it.samples/16L>startMs}.forEach { info ->
            val chunk=vault.readChunk(id,info.index,key);val bytes=Base64.getDecoder().decode(chunk.pcm)
            val pcm=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            repeat(chunk.samples) {i -> val sample=pcm.short;val dst=((chunk.startMs-startMs)*16+i).toInt();if(dst in result.indices)result[dst]=sample/32768f }
            bytes.fill(0)
        }
        return result
    }
    override fun close() {key.fill(0)}
}
class Processing(private val app: ProApp) {
    private val cancel=AtomicBoolean(false)
    fun cancel() {cancel.set(true);NativeEngine.cancel()}
    private fun check() {
        if(cancel.get())error("cancelled")
        val power=app.getSystemService(PowerManager::class.java)
        if(power.currentThermalStatus>=PowerManager.THERMAL_STATUS_SEVERE)error("thermal_pause")
        val info=ActivityManager.MemoryInfo();app.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        if(info.lowMemory)error("memory_pause")
    }
    suspend fun run(id: String) = withContext(Dispatchers.Default) {
        if(app.recording.value.sessionId!=null || app.preparedRecording!=null || !app.processing.compareAndSet(null,id))return@withContext
        cancel.set(false);NativeEngine.reset()
        var stage="asr";var reason: String?=null
        try {
            var s=app.vault.load(id);require(!s.received && s.audioRetained)
            val pref=app.vault.preferences()
            AudioReader(app.vault,id).use {audio ->
                if(s.stages["asr"]?.state!="done") {
                    val spec=app.models.entry(pref.asrModel);val model=app.models.verifiedFile(spec.id)
                    var start=(s.stages["asr"]?.completedWindows ?: 0)*30000L
                    while(start<s.durationMs) {
                        check();app.vault.update(id) {it.copy(state="transcribing")};app.changed()
                        val from=maxOf(0,start-2000);val to=minOf(s.durationMs,start+32000)
                        val samples=audio.window(from,to-from)
                        val result=try {json.parseToJsonElement(NativeEngine.transcribe(model.path,samples,s.language).decodeToString()).jsonObject}finally {samples.fill(0f)}
                        require(result["error"]==null) {result["error"].toString()}
                        val language=result["language"]?.jsonPrimitive?.content ?: s.language
                        val segments=result.getValue("segments").jsonArray.mapIndexedNotNull {i,e ->
                            val o=e.jsonObject;val a=from+o.getValue("startMs").jsonPrimitive.long;val b=from+o.getValue("endMs").jsonPrimitive.long
                            val mid=(a+b)/2;val text=o.getValue("text").jsonPrimitive.content.trim()
                            if(mid<start || mid>=minOf(s.durationMs,start+30000) || text.isBlank() || notSpeech(text))null else Segment(
                                UUID.nameUUIDFromBytes("$id/$start/$i".toByteArray()).toString(),a,b,text,language=language,
                                uncertain=s.gaps.any {it.startMs<b && (it.endMs ?: Long.MAX_VALUE)>a})
                        }
                        val count=(start/30000+1).toInt()
                        s=app.vault.update(id) {old -> old.copy(transcript=(old.transcript.filterNot {p->segments.any {it.id==p.id}}+segments).sortedBy {it.startMs},
                            stages=old.stages+("asr" to Stage("running",count,spec.sha256)))}
                        start+=30000;app.changed()
                    }
                    s=app.vault.update(id) {it.copy(stages=it.stages+("asr" to it.stages.getValue("asr").copy(state="done")))}
                }
                stage="speakers"
                if(s.stages[stage]?.state!="done") {
                    val seg=app.models.verifiedFile("pyannote-segmentation3");val emb=app.models.verifiedFile("titanet-small")
                    val diarizer=OfflineSpeakerDiarization(config=OfflineSpeakerDiarizationConfig(
                        segmentation=OfflineSpeakerSegmentationModelConfig(pyannote=OfflineSpeakerSegmentationPyannoteModelConfig(seg.path),numThreads=2),
                        embedding=SpeakerEmbeddingExtractorConfig(emb.path,numThreads=2)))
                    val extractor=SpeakerEmbeddingExtractor(config=SpeakerEmbeddingExtractorConfig(emb.path,numThreads=2))
                    try {
                        var start=(s.stages[stage]?.completedWindows ?: 0)*60000L
                        while(start<s.durationMs) {
                            check();app.vault.update(id) {it.copy(state="diarizing")};app.changed()
                            val samples=audio.window(start,minOf(60000,s.durationMs-start))
                            try {
                                // sherpa finds this through JNI as invoke(IIJ)Ljava/lang/Integer;. Only a real lambda
                                // class has that method: a plain Kotlin 2 lambda (invokedynamic) aborts the process.
                                val turns=diarizer.processWithCallback(samples,@JvmSerializableLambda {_,_,_->if(cancel.get())1 else 0})
                                check()
                                val vectors=s.speakerVectors.toMutableMap();val labels=mutableMapOf<Int,String>()
                                turns.groupBy {it.speaker}.forEach {(speaker,parts) ->
                                    val voiced=parts.filter {it.end-it.start>=0.8f}.flatMap {part ->
                                        samples.slice((part.start*16000).toInt().coerceIn(0,samples.size)..<minOf(samples.size,(part.end*16000).toInt()))
                                    }.take(30*16000).toFloatArray()
                                    if(voiced.size>=16000) {
                                        val stream=extractor.createStream()
                                        try {
                                            stream.acceptWaveform(voiced,16000);stream.inputFinished()
                                            if(extractor.isReady(stream)) {
                                                val vector=extractor.compute(stream).toList();val match=vectors.maxByOrNull {cosine(it.value,vector)}
                                                val label=if(match!=null && cosine(match.value,vector)>=0.65f)match.key else "Speaker ${vectors.size+1}"
                                                labels[speaker]=label;vectors[label]=vector
                                            }
                                        } finally {stream.release();voiced.fill(0f)}
                                    }
                                }
                                val windowStart=start
                                s=app.vault.update(id) {old -> old.copy(speakerVectors=vectors,transcript=old.transcript.map {line ->
                                    if(line.startMs>=windowStart+60000 || line.endMs<=windowStart)line else {
                                        val intersect=turns.filter {windowStart+(it.start*1000).toLong()<line.endMs && windowStart+(it.end*1000).toLong()>line.startMs}
                                        val possible=intersect.mapNotNull {labels[it.speaker]}.distinct()
                                        line.copy(speaker=possible.singleOrNull(),uncertain=line.uncertain || possible.size!=1)
                                    }},stages=old.stages+(stage to Stage("running",(windowStart/60000+1).toInt(),app.models.entry("titanet-small").sha256)))}
                            } finally {samples.fill(0f)}
                            start+=60000;app.changed()
                        }
                    } finally {diarizer.release();extractor.release()}
                    val profiles=if(pref.matching)app.vault.profiles().filter {it.enabled && it.modelHash==app.models.entry("titanet-small").sha256}else emptyList()
                    val suggestions=s.speakerVectors.mapNotNull {(speaker,v) -> profiles.maxByOrNull {cosine(it.embedding,v)}?.takeIf {cosine(it.embedding,v)>=0.75f}?.let {speaker to it.id}}.toMap()
                    s=app.vault.update(id) {it.copy(suggestedProfiles=suggestions,stages=it.stages+(stage to Stage("done",modelHash=app.models.entry("titanet-small").sha256)))}
                }
            }
            stage="summary";check()
            require(s.transcript.isNotEmpty()) {"no_recognized_speech"}
            // Meeting content is sensitive: notes are written on this device. The policy never picks the cloud.
            val memory=ActivityManager.MemoryInfo();app.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            val gemmaFits=memory.totalMem>=6L*1024*1024*1024 && memory.availMem>=4L*1024*1024*1024
            val preference=runCatching {EnginePreference.valueOf(pref.engine)}.getOrDefault(EnginePreference.AUTO)
            val choice=Policy.chooseOnDevice(s.language,preference,OnDeviceOptions(Hybrid.nanoState(),app.models.installed(pref.summaryModel),gemmaFits))
            if(choice is LocalChoice.Blocked) {reason=choice.reason;error(choice.reason)}
            val use=choice as LocalChoice.Use
            val model: NotesModel=when(use.engine) {
                NotesEngine.GEMINI_NANO -> NanoNotes(Hybrid.nanoModelName() ?: "gemini-nano")
                NotesEngine.GEMMA_LOCAL -> GemmaNotes(app.models.verifiedFile(pref.summaryModel).path,pref.summaryModel)
                NotesEngine.CLOUD_REDACTED -> error("cloud_is_never_automatic")
            }
            app.vault.update(id) {it.copy(state="summarizing")};app.changed()
            val summary=try {Notes.write(model,s,Policy.chunkChars(model.engine,Hybrid.config),::check)} catch(e: PolicyViolation) {
                reason="policy_violation"
                Audit.add(app.vault,AuditEntry(action="policy_violation",engine=model.engine.id,model=model.modelName,reason=e.message,meeting=Audit.meetingRef(id)))
                throw e
            }
            app.vault.update(id) {it.copy(title=summary.title,summary=summary,editedSummary=null,state="needs_review",revision=newId(),approvedRevision=null,
                notes=NotesProvenance(model.engine.id,model.modelName,onDeviceCalls=model.calls),
                stages=it.stages+("summary" to Stage("done",modelHash=if(model.engine==NotesEngine.GEMMA_LOCAL)app.models.entry(pref.summaryModel).sha256 else null)))}
            Audit.add(app.vault,AuditEntry(action="notes",engine=model.engine.id,model=model.modelName,source=Audit.sourceOf(model.engine),
                calls=model.calls,reason=use.reason+(use.warning?.let {",$it"} ?: ""),meeting=Audit.meetingRef(id)))
        } catch(_: Exception) {
            app.vault.update(id) {it.copy(state=if(cancel.get())"cancelled"else "failed",stages=it.stages+(stage to (it.stages[stage]?:Stage()).copy(state="failed",error=if(cancel.get())"cancelled"else reason ?: "stage_failed_retry_or_check_models")))}
        } finally {app.processing.value=null;app.changed()}
    }

    /**
     * Sends a prepared, consented cloud draft. The policy gate runs again here, right before the request.
     * Returns the reason when blocked; saves the re-identified notes as a new revision when sent.
     */
    suspend fun cloud(draft: CloudDraft,consent: Boolean): String? = withContext(Dispatchers.IO) {
        val ref=Audit.meetingRef(draft.sessionId);val counts=draft.counts.mapKeys {it.key.name}
        val model=CloudNotes(Hybrid.config.cloudModel);val label="${model.modelName} · ${Hybrid.location}"
        try {
            val session=app.vault.load(draft.sessionId);require(session.revision==draft.revision) {"meeting_changed_since_preview"}
            val firebaseReady=Hybrid.ready && Hybrid.signIn()!=null
            when(val outcome=draft.send(model,Hybrid.config,firebaseReady,consent,session.transcript.map {it.id}.toSet())) {
                is CloudOutcome.Blocked -> {
                    Audit.add(app.vault,AuditEntry(action="cloud_blocked",engine=model.engine.id,reason=outcome.reason,redacted=counts,consent=consent,meeting=ref))
                    outcome.reason
                }
                is CloudOutcome.Done -> {
                    val summary=outcome.summary
                    app.vault.update(draft.sessionId) {it.copy(title=summary.title,summary=summary,editedSummary=null,state="needs_review",revision=newId(),approvedRevision=null,
                        notes=NotesProvenance(model.engine.id,label,cloudCalls=model.calls,redactedEntities=draft.redactedTotal))}
                    Audit.add(app.vault,AuditEntry(action="cloud_notes",engine=model.engine.id,model=label,source="IN_CLOUD",calls=model.calls,
                        redacted=counts,charsSent=draft.prompt.length,consent=true,meeting=ref))
                    app.changed();null
                }
            }
        } catch(e: PolicyViolation) {
            Audit.add(app.vault,AuditEntry(action="policy_violation",engine=model.engine.id,reason=e.message,meeting=ref));"policy_violation"
        } catch(e: Exception) {
            Audit.add(app.vault,AuditEntry(action="cloud_failed",engine=model.engine.id,model=label,reason=e.message?.take(80) ?: e.javaClass.simpleName,consent=consent,meeting=ref))
            "cloud_failed"
        }
    }
}
