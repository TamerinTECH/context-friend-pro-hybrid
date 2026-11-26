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

/** Notification tap: open the app on the running recording. */
const val OPEN_RECORDING="tech.tamerin.contextfriendpro.OPEN_RECORDING"

class RecordingService: Service() {
    private val app get()=application as ProApp
    private var audio: AudioRecord?=null
    private var session: Session?=null
    private var key: ByteArray?=null
    private val running=AtomicBoolean(false)
    private val producerDone=AtomicBoolean(true)
    private val paused=AtomicBoolean(false)
    // Two minutes of audio in memory: a storage stall of a few seconds must never end a recording.
    private val queue=ArrayBlockingQueue<AudioChunk>(120)
    private var restarts=0
    private var origin=0L
    private var pauseStart=0L
    /** Audio that counted as sound so far, for "Sound heard" on the recording screen. */
    @Volatile private var heardMs=0L
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
    /**
     * The recording, visible while the person uses other apps: status bar icon, running time, Pause/Resume and Finish,
     * and a tap goes straight back to the recording screen. Shown on the lock screen too, without the meeting's title:
     * that a recording runs is not a secret, what it is about is. No sound, no vibration.
     */
    private fun notifyRecording() {
        val manager=getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel("recording")   // the earlier silent channel hid the icon and sorted the recording under "Silent"
        manager.createNotificationChannel(NotificationChannel("recording_live",getString(R.string.recording),NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null,null);enableVibration(false);lockscreenVisibility=Notification.VISIBILITY_PUBLIC})
        fun action(name: String,code: Int)=PendingIntent.getService(this,code,Intent(this,RecordingService::class.java).setAction(name),PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).setAction(OPEN_RECORDING)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val isPaused=paused.get()
        val elapsed=if(isPaused) pauseStart else SystemClock.elapsedRealtime()-origin
        val builder=Notification.Builder(this,"recording_live").setSmallIcon(R.drawable.ic_stat_contextfriend)
            .setContentTitle(getString(if(isPaused) R.string.paused else R.string.recording))
            .setContentText(if(isPaused) getString(R.string.recording_paused_at,clock(elapsed)) else getString(R.string.recording_return))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setShowWhen(!isPaused).setUsesChronometer(!isPaused).setWhen(System.currentTimeMillis()-elapsed)
            .addAction(Notification.Action.Builder(null,getString(if(isPaused) R.string.resume else R.string.pause),action(if(isPaused)"resume" else "pause",2)).build())
            .addAction(Notification.Action.Builder(null,getString(R.string.finish),action("stop",1)).build())
        startForeground(7,builder.build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }
    @Suppress("MissingPermission") private fun openMicrophone(): AudioRecord {
        val size=maxOf(AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT),32000)
        val record=AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(size).build()
        require(record.state==AudioRecord.STATE_INITIALIZED && record.sampleRate==16000)
        record.addOnRoutingChangedListener({route -> val id=route.routedDevice?.id;val previous=routedDeviceId;routedDeviceId=id;if(previous!=null && previous!=id)pause("audio_route_changed")},Handler(mainLooper))
        return record
    }
    /** A read error (the audio server restarted, a device changed) reopens the microphone up to three times, with a gap. */
    private fun restartMicrophone(): Boolean {
        if(restarts>=3) return false
        restarts++
        val at=SystemClock.elapsedRealtime()-origin
        runCatching {audio?.stop()};runCatching {audio?.release()}
        return try {
            audio=openMicrophone();audio!!.startRecording()
            synchronized(guard) {session=session?.let {it.copy(gaps=it.gaps+Gap(at,SystemClock.elapsedRealtime()-origin,"microphone_restarted"))}}
            true
        } catch(_: Exception) {false}
    }
    @Suppress("MissingPermission") private fun startCapture() {
        val prepared=app.preparedRecording ?: return stopSelf()
        app.preparedRecording=null; session=prepared.first;key=prepared.second
        try {
            notifyRecording()
            audio=openMicrophone()
            origin=SystemClock.elapsedRealtime();producerDone.set(false);running.set(true)
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ContextFriendPro:recording").apply {acquire(4*60*60*1000L)}
            getSystemService(AudioManager::class.java).registerAudioRecordingCallback(recordingCallback,Handler(mainLooper))
            synchronized(guard) {session=session!!.copy(state="recording");app.vault.save(session!!,key!!)}
            worker=thread(name="encrypted-audio-writer") {
                try {
                    while(!producerDone.get() || queue.isNotEmpty()) {
                        val chunk=queue.poll(200,java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                        if(app.vault.root.usableSpace < 128L*1024*1024) error("storage_low")
                        app.vault.writeChunk(session!!.id,chunk,key!!)
                        // The audio chunk is on disk at once; the meeting's metadata only every ten seconds and at the end.
                        synchronized(guard) {session=session!!.copy(durationMs=maxOf(session!!.durationMs,chunk.startMs+chunk.samples*1000L/16000));if(chunk.index%10==0)app.vault.save(session!!,key!!)}
                    }
                } catch(_: Exception) {failure="encrypted_storage_failed";running.set(false)}
            }
            audio!!.startRecording()
            capture=thread(name="native-microphone") {
                var index=0; var buffer=ShortArray(16000);var used=0;var chunkStart=0L
                val meter=SoundMeter();heardMs=0L
                fun flush() {
                    if(used==0) return
                    val data=ByteBuffer.allocate(used*2).order(ByteOrder.LITTLE_ENDIAN);for(i in 0 until used)data.putShort(buffer[i])
                    val chunk=AudioChunk(index++,chunkStart,pcm=Base64.getEncoder().encodeToString(data.array()),samples=used)
                    // Two minutes behind: this second is lost (recorded as a gap) but the recording goes on.
                    if(!queue.offer(chunk)) synchronized(guard) {session=session?.let {it.copy(gaps=it.gaps+Gap(chunk.startMs,chunk.startMs+chunk.samples*1000L/16000,"storage_slow"))}}
                    buffer.fill(0);used=0
                }
                try {
                    val part=ShortArray(320)
                    while(running.get()) {
                        if(paused.get()) {flush();Thread.sleep(40);continue}
                        val n=audio!!.read(part,0,part.size,AudioRecord.READ_BLOCKING)
                        if(n==0) continue
                        if(n<0) {if(running.get() && !paused.get() && !restartMicrophone()) {failure="microphone_read_failed";running.set(false)};continue}
                        if(used==0) chunkStart=maxOf(0,SystemClock.elapsedRealtime()-origin-n*1000L/16000)
                        part.copyInto(buffer,used,0,n);used+=n
                        // Ten level updates a second, in decibels: quiet voices still show, and the screen counts heard sound.
                        meter.add(part,n)?.let {block -> heardMs=meter.heardMs
                            app.recording.value=RecordingStatus(session!!.id,"recording",SystemClock.elapsedRealtime()-origin,block.level,heardMs=heardMs,sound=block.sound)}
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
        app.recording.value=RecordingStatus(session?.id,"paused",pauseStart,heardMs=heardMs);notifyRecording()
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
        val finished=session
        app.recording.value=RecordingStatus(error=failure);app.changed()
        // Notes start at once, in the background service, whether the recording ended normally or was interrupted.
        // If Android refuses the start from the background, the meeting waits until the app is opened (resumePending).
        if(finished!=null && finished.durationMs>0) app.startProcessing(finished.id)
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    override fun onDestroy() {stopCapture();super.onDestroy()}
}
