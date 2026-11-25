package tech.tamerin.contextfriendpro

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs

class RecordingService: Service() {
    private val app get()=application as ProApp
    private var audio: AudioRecord?=null
    private var session: Session?=null
    private var key: ByteArray?=null
    private val running=AtomicBoolean(false)
    private val producerDone=AtomicBoolean(true)
    private val paused=AtomicBoolean(false)
    private val queue=ArrayBlockingQueue<AudioChunk>(2)
    private var origin=0L
    private var pauseStart=0L
    private var worker: Thread?=null
    private var capture: Thread?=null
    private var wake: PowerManager.WakeLock?=null
    private var failure: String?=null
    private var routedDeviceId: Int?=null
    private val guard=Any()
    private val recordingCallback=object: AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            if(configs.any {it.clientAudioSessionId==audio?.audioSessionId && it.isClientSilenced}) pause("microphone_interrupted")
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        when(intent?.action) { "start" -> if(!running.get()) startCapture(); "pause" -> pause("user_pause"); "resume" -> resume(); "stop" -> stopCapture() }
        return START_NOT_STICKY
    }
    private fun notifyRecording() {
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("recording",getString(R.string.recording),NotificationManager.IMPORTANCE_LOW))
        val stop=PendingIntent.getService(this,1,Intent(this,RecordingService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val n=Notification.Builder(this,"recording").setSmallIcon(R.drawable.ic_stat_contextfriend).setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if(paused.get()) R.string.paused else R.string.recording)).setContentIntent(open)
            .setOngoing(true).setVisibility(Notification.VISIBILITY_SECRET).addAction(Notification.Action.Builder(null,getString(R.string.stop),stop).build()).build()
        startForeground(7,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }
    @Suppress("MissingPermission") private fun startCapture() {
        val prepared=app.preparedRecording ?: return stopSelf()
        app.preparedRecording=null; session=prepared.first;key=prepared.second
        try {
            notifyRecording()
            val size=maxOf(AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT),32000)
            audio=AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(size).build()
            require(audio!!.state==AudioRecord.STATE_INITIALIZED && audio!!.sampleRate==16000)
            origin=SystemClock.elapsedRealtime();producerDone.set(false);running.set(true)
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ContextFriendPro:recording").apply {acquire(4*60*60*1000L)}
            getSystemService(AudioManager::class.java).registerAudioRecordingCallback(recordingCallback,Handler(mainLooper))
            audio!!.addOnRoutingChangedListener({route -> val id=route.routedDevice?.id;val previous=routedDeviceId;routedDeviceId=id;if(previous!=null && previous!=id)pause("audio_route_changed")},Handler(mainLooper))
            synchronized(guard) {session=session!!.copy(state="recording");app.vault.save(session!!,key!!)}
            worker=thread(name="encrypted-audio-writer") {
                try {
                    while(!producerDone.get() || queue.isNotEmpty()) {
                        val chunk=queue.poll(200,java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                        if(app.vault.root.usableSpace < 128L*1024*1024) error("storage_low")
                        app.vault.writeChunk(session!!.id,chunk,key!!)
                        synchronized(guard) {session=session!!.copy(durationMs=maxOf(session!!.durationMs,chunk.startMs+chunk.samples*1000L/16000));app.vault.save(session!!,key!!)}
                    }
                } catch(_: Exception) {failure="encrypted_storage_failed";running.set(false)}
            }
            audio!!.startRecording()
            capture=thread(name="native-microphone") {
                var index=0; var buffer=ShortArray(16000);var used=0;var chunkStart=0L
                fun flush() {
                    if(used==0) return
                    val data=ByteBuffer.allocate(used*2).order(ByteOrder.LITTLE_ENDIAN);for(i in 0 until used)data.putShort(buffer[i])
                    val chunk=AudioChunk(index++,chunkStart,pcm=Base64.getEncoder().encodeToString(data.array()),samples=used)
                    if(!queue.offer(chunk)) {failure="storage_backpressure_gap";running.set(false)}
                    buffer.fill(0);used=0
                }
                try {
                    val part=ShortArray(320)
                    while(running.get()) {
                        if(paused.get()) {flush();Thread.sleep(40);continue}
                        val n=audio!!.read(part,0,part.size,AudioRecord.READ_BLOCKING)
                        if(n<=0) {if(running.get() && !paused.get()) {failure="microphone_read_failed";running.set(false)};continue}
                        if(used==0) chunkStart=maxOf(0,SystemClock.elapsedRealtime()-origin-n*1000L/16000)
                        part.copyInto(buffer,used,0,n);used+=n
                        app.recording.value=RecordingStatus(session!!.id,"recording",SystemClock.elapsedRealtime()-origin,part.take(n).maxOf {abs(it.toInt())}/32768f)
                        if(used==16000) flush()
                        part.fill(0)
                    }
                    flush()
                } catch(_: Exception) {if(running.get())failure="capture_interrupted"}
                finally {running.set(false);producerDone.set(true);finishCapture()}
            }
        } catch(_: Exception) {failure="microphone_unavailable";running.set(false);producerDone.set(true);finishCapture()}
    }
    private fun pause(reason: String) {
        if(!running.get() || paused.getAndSet(true))return
        pauseStart=SystemClock.elapsedRealtime()-origin
        runCatching {audio?.stop()}
        synchronized(guard) {session=session?.let {it.copy(state="paused",gaps=it.gaps+Gap(pauseStart,null,reason))};session?.let {app.vault.save(it,key)}}
        app.recording.value=RecordingStatus(session?.id,"paused",pauseStart);notifyRecording()
    }
    private fun resume() {
        if(!running.get() || !paused.get())return
        try {
            synchronized(guard) {session=session!!.copy(state="recording",gaps=session!!.gaps.map {if(it.endMs==null)it.copy(endMs=SystemClock.elapsedRealtime()-origin)else it})}
            audio!!.startRecording();paused.set(false);notifyRecording()
        } catch(_: Exception) {failure="microphone_unavailable";stopCapture()}
    }
    private fun stopCapture() {running.set(false);runCatching {audio?.stop()}}
    private fun finishCapture() {
        worker?.let {if(it!=Thread.currentThread())it.join()}
        runCatching {audio?.stop()};audio?.release();audio=null
        getSystemService(AudioManager::class.java).unregisterAudioRecordingCallback(recordingCallback)
        synchronized(guard) {
            session?.let {s -> runCatching {app.vault.save(s.copy(state=if(failure==null)"recorded" else "interrupted",
                gaps=if(failure!=null)s.gaps+Gap(s.durationMs,null,failure!!)else s.gaps.map {if(it.endMs==null)it.copy(endMs=SystemClock.elapsedRealtime()-origin)else it}),key)} }
        }
        key?.fill(0);key=null;wake?.let {if(it.isHeld)it.release()};wake=null
        app.recording.value=RecordingStatus(error=failure);app.changed();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    override fun onDestroy() {stopCapture();super.onDestroy()}
}
