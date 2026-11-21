package tech.tamerin.contextfriendpro

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.serialization.json.*
import com.k2fsa.sherpa.onnx.*

@RunWith(AndroidJUnit4::class)
class CoreInstrumentationTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ProApp
    private lateinit var a: Vault
    private lateinit var b: Vault
    @Before fun prepare() {a=Vault(app,"test-sender");b=Vault(app,"test-recipient");clean()}
    private fun model(name:String):String {val path=File(app.filesDir,"test-models/$name");path.parentFile!!.mkdirs();if(!path.exists())instrument.context.assets.open(name).use {input->path.outputStream().use {input.copyTo(it)}};return path.path}
    private fun clean() {for(v in listOf(a,b)){v.list().forEach {v.delete(it.id)};v.root.listFiles()?.filter {it.isFile}?.forEach {it.delete()}}}
    @After fun cleanup() {clean()}
    private fun fails(body: ()->Unit) {try {body();fail("Expected rejection")}catch(e:IllegalArgumentException){}catch(e:IllegalStateException){}}
    @Test fun encryptedRecoveryAndApproval() {
        val marker="PRIVATE_FIXTURE_עברית_Über_984204";val s=Session(title=marker,state="recording")
        val key=a.create(s);val chunk=AudioChunk(0,0,pcm=Base64.getEncoder().encodeToString(ByteArray(32000)),samples=16000)
        a.writeChunk(s.id,chunk,key);key.fill(0);a.recover();val recovered=a.load(s.id)
        assertEquals("interrupted",recovered.state);assertEquals(1000L,recovered.durationMs);assertEquals(1,recovered.gaps.size)
        a.update(s.id){it.copy(editedSummary=marker,approvedRevision=it.revision)};assertTrue(a.load(s.id).approved)
        a.update(s.id){it.edited().copy(editedSummary="edited")};assertFalse(a.load(s.id).approved);assertTrue(a.load(s.id).history.last().approved)
        a.root.walkTopDown().filter{it.isFile}.forEach {assertFalse(it.readBytes().decodeToString().contains(marker))}
        val encrypted=File(a.root,"${s.id}/session");val original=encrypted.readBytes();encrypted.writeBytes(original.copyOf().also {it[20]=(it[20].toInt() xor 1).toByte()})
        assertTrue(runCatching {a.load(s.id)}.isFailure);encrypted.writeBytes(original)
        a.deleteAudio(s.id);assertFalse(a.load(s.id).audioRetained);assertTrue(a.chunks(s.id).isEmpty())
        a.delete(s.id);assertTrue(a.list().isEmpty())
    }
    @Test fun recipientBoundPurposeLimitedTransferAndReceipt() {
        val sender=Transfers(app,a);val recipient=Transfers(app,b);val invitation=recipient.invite()
        val segment=Segment(startMs=0,endMs=1000,original="SECRET_TRANSCRIPT")
        val s=Session(title="Test",transcript=listOf(segment),editedSummary="שלום — Prüfung",speakerVectors=mapOf("speaker" to List(64){1f}))
        a.create(s).fill(0);val approved=a.update(s.id){it.copy(approvedRevision=it.revision)}
        val payload=sender.meeting(approved,false,false,false)
        assertNull(payload.meeting!!.transcript);assertNull(payload.meeting.audio);assertNull(payload.profiles)
        val bytes=sender.seal(payload,invitation);assertFalse(bytes.decodeToString().contains("שלום"))
        val opened=recipient.open(bytes);assertFalse(opened.trusted);assertEquals("שלום — Prüfung",opened.payload.meeting!!.summary)
        val receipt=recipient.accept(opened,false);assertTrue(sender.verifyReceipt(receipt,invitation,bytes));assertFalse(sender.verifyReceipt(receipt+"x",invitation,bytes))
        assertEquals(receipt,recipient.accept(recipient.open(bytes),false));assertEquals(1,b.list().size)
        fails {recipient.open(bytes.copyOf().also {it[100]=(it[100].toInt() xor 1).toByte()})}
        fails {recipient.open(bytes.copyOf().also {it[260]=(it[260].toInt() xor 1).toByte()})}
        fails {recipient.open(bytes.copyOf(bytes.size-1))}
        fails {sender.open(bytes)}
        fails {recipient.open(sender.seal(payload.copy(bundleId=newId()),invitation))}
        recipient.revoke(invitation.id);fails {recipient.open(bytes)}
        val importing=Session(title="partial",state="importing");b.create(importing).fill(0);b.recover();assertTrue(b.list().none {it.id==importing.id})
    }
    @Test fun profileConsentAndModelCompatibility() {
        val sender=Transfers(app,a);val recipient=Transfers(app,b);val invite=recipient.invite()
        val p=VoiceProfile(name="Fixture",embedding=List(192){(it+1)/192f},modelHash=app.models.entry("titanet-small").sha256)
        val opened=recipient.open(sender.seal(sender.profiles(listOf(p)),invite))
        fails {recipient.accept(opened,false)};recipient.accept(opened,true)
        assertTrue(b.profiles().single().imported);assertFalse(b.profiles().single().enabled)
        val incompatible=recipient.open(sender.seal(sender.profiles(listOf(p.copy(id=newId(),modelHash="a".repeat(64)))),recipient.invite()))
        fails {recipient.accept(incompatible,true)}
    }
    @Test fun qrAndBleReassemblyRejectHostileFrames() {
        val data=ByteArray(2107){(it%251).toByte()};val frames=QrFrames.encode(data);val c=QrFrames.Collector()
        assertNull(c.add(frames[2],100));assertNull(c.add(frames[2],100));assertNull(c.add(frames[0],100));assertNull(c.add(frames[3],100));assertArrayEquals(data,c.add(frames[1],100))
        fails {QrFrames.Collector().add(frames[0].replace(":0:4:",":0:999999:"),100)}
        val expired=QrFrames.Collector();expired.add(frames[0],100);fails {expired.add(frames[1],600101)}
        fails {requireJsonDepth("[".repeat(17)+"]".repeat(17))}
        val f=FragmentReceiver();val hash=digest(data).chunked(2).map {it.toInt(16).toByte()}.toByteArray()
        f.begin(ByteBuffer.allocate(40).putInt(0x43464231).put(hash).putInt(data.size).array())
        fails {f.append(ByteBuffer.allocate(8).putInt(10).putInt(1).array())}
        assertNull(f.append(ByteBuffer.allocate(1004).putInt(0).put(data,0,1000).array()))
        f.begin(ByteBuffer.allocate(40).putInt(0x43464231).put(hash).putInt(data.size).array());assertEquals(1000,f.offset)
        assertArrayEquals(data,f.append(ByteBuffer.allocate(data.size-1000+4).putInt(1000).put(data,1000,data.size-1000).array()))
    }
    @Test fun independentPythonWireVector() {
        val v=json.parseToJsonElement(app.assets.open("protocol-vectors.json").bufferedReader().readText()).jsonObject
        b.putPrivate("identity",v.getValue("recipient").toString())
        val inv=json.decodeFromJsonElement<Invitation>(v.getValue("invitation"))
        val boot=android.provider.Settings.Global.getInt(app.contentResolver,android.provider.Settings.Global.BOOT_COUNT)
        b.putPrivate("invitations",json.encodeToString(kotlinx.serialization.builtins.ListSerializer(PendingInvitation.serializer()),listOf(PendingInvitation(inv,android.os.SystemClock.elapsedRealtime()+600000,boot))))
        val receiver=Transfers(app,b);val bytes=un64(v.getValue("envelope").jsonPrimitive.content);val opened=receiver.open(bytes)
        assertEquals("שלום · Prüfung · offline",opened.payload.meeting!!.summary)
        assertEquals(v.getValue("receipt").jsonPrimitive.content,receiver.accept(opened,false))
        assertTrue(receiver.verifyReceipt(v.getValue("receipt").jsonPrimitive.content,inv,bytes))
    }
    @Test fun actualWhisperRecognizesNaturalSpeechFixture() {
        val path=File(model("ggml-tiny.bin"));assertTrue("Provision test model first",path.exists())
        val raw=instrument.context.assets.open("jfk.pcm").use {it.readBytes()};val input=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);val pcm=FloatArray(raw.size/2){input.short/32768f}
        NativeEngine.reset();val result=NativeEngine.transcribe(path.path,pcm,"en").decodeToString()
        val parsed=json.parseToJsonElement(result).jsonObject;assertFalse(result,parsed.containsKey("error"))
        val text=parsed.getValue("segments").jsonArray.joinToString(" "){it.jsonObject.getValue("text").jsonPrimitive.content}.lowercase()
        assertTrue(text,text.contains("country"));assertTrue(text,text.contains("ask"));assertTrue(text,text.contains("you"))
        val silence=json.parseToJsonElement(NativeEngine.transcribe(path.path,FloatArray(16000),"en").decodeToString()).jsonObject
        assertTrue(silence.getValue("segments").jsonArray.isEmpty())
    }
    @Test fun whisperLabelsAreNotSpeech() {
        listOf("[BLANK_AUDIO]","(wind blowing)","[MUSIC] [MUSIC]"," [Music] ","♪♪").forEach {assertTrue(it,notSpeech(it))}
        listOf("Ask not what your country can do","We said (again) no","[Laughs] okay then","שלום","Über [unklar] nichts").forEach {assertFalse(it,notSpeech(it))}
    }
    @Test fun realSpeakerModelsReturnTurnsAndEmbedding() {
        val segmentation=model("segmentation.int8.onnx");val embedding=model("nemo_en_titanet_small.onnx")
        val raw=instrument.context.assets.open("jfk.pcm").use{it.readBytes()};val buffer=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);val pcm=FloatArray(raw.size/2){buffer.short/32768f}
        val extractor=SpeakerEmbeddingExtractor(config=SpeakerEmbeddingExtractorConfig(model=embedding,numThreads=2,debug=false,provider="cpu"))
        val stream=extractor.createStream()
        try {stream.acceptWaveform(pcm,16000);stream.inputFinished();assertTrue(extractor.isReady(stream));val vector=extractor.compute(stream);assertEquals(192,vector.size);assertTrue(vector.all{it.isFinite()});assertTrue(vector.any{it!=0f})} finally {stream.release();extractor.release()}
        val config=OfflineSpeakerDiarizationConfig(segmentation=OfflineSpeakerSegmentationModelConfig(pyannote=OfflineSpeakerSegmentationPyannoteModelConfig(model=segmentation),numThreads=2,debug=false,provider="cpu"),embedding=SpeakerEmbeddingExtractorConfig(model=embedding,numThreads=2,debug=false,provider="cpu"),clustering=FastClusteringConfig(numClusters=-1,threshold=0.5f))
        val diarizer=OfflineSpeakerDiarization(config=config)
        try {
            val turns=diarizer.process(pcm);assertTrue(turns.isNotEmpty());assertTrue(turns.all {it.start>=0 && it.end>it.start && it.end<=pcm.size/16000f+1})
            // The form Processing uses: without the annotation sherpa's JNI lookup fails and the process aborts.
            var calls=0;val reported=diarizer.processWithCallback(pcm,@JvmSerializableLambda {_,_,_->calls++;0})
            assertTrue(calls>0);assertEquals(turns.size,reported.size)
        } finally {diarizer.release()}
    }
}
