package tech.tamerin.contextfriendpro

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class ProApp: Application() {
    lateinit var vault: Vault
    lateinit var models: Models
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    val generation=MutableStateFlow(0)
    val recording=MutableStateFlow(RecordingStatus())
    val processing=MutableStateFlow<String?>(null)
    var preparedRecording: Pair<Session,ByteArray>?=null
    /** Hybrid status for the UI: Gemini Nano state, Remote Config values, anonymous sign-in. */
    val nano=MutableStateFlow(NanoState.UNKNOWN)
    val hybridConfig=MutableStateFlow(HybridConfig())
    val signedIn=MutableStateFlow(false)
    /** One processing engine for the screens and the background service. */
    val engine by lazy { Processing(this) }
    /** The meeting being processed and how much of it is done (0..1), from real work: never an estimate of time. */
    val progress=MutableStateFlow<Pair<String,Float>?>(null)
    /** True while an activity of the app is started: Gemini Nano answers only then. */
    val foreground=MutableStateFlow(false)
    /** Counts taps on the recording notification: the shell then shows the recording screen. */
    val openRecording=MutableStateFlow(0)
    private var started=0

    override fun onCreate() {
        super.onCreate();vault=Vault(this);models=Models(this)
        runCatching {Hybrid.init(this)};hybridConfig.value=Hybrid.config
        scope.launch(Dispatchers.IO) {runCatching {vault.recover()};changed()}
        refreshHybrid()
        registerActivityLifecycleCallbacks(object: ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {started++;foreground.value=true}
            override fun onActivityStopped(activity: Activity) {started=(started-1).coerceAtLeast(0);foreground.value=started>0}
            override fun onActivityCreated(activity: Activity,state: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity,state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
    fun refreshHybrid() {scope.launch(Dispatchers.IO) {
        runCatching {hybridConfig.value=Hybrid.refreshConfig()}
        runCatching {signedIn.value=Hybrid.signIn()!=null}
        runCatching {nano.value=Hybrid.nanoState()}
    }}
    fun changed() {generation.value++}

    /**
     * Processes meetings in the background service, so notes keep coming when the screen is off or the app is closed.
     * Returns false when Android refused to start it from the background; the meetings then wait for [resumePending].
     */
    fun startProcessing(id: String? = null): Boolean = try {
        startForegroundService(Intent(this,ProcessingService::class.java).apply {if(id!=null)putExtra("id",id)});true
    } catch(_: Exception) {false}

    /** Meetings whose notes were started but not finished (interrupted, killed, waiting for the app), oldest first. */
    fun pendingMeetings(): List<String> {
        if(recording.value.sessionId!=null || preparedRecording!=null) return emptyList()
        val pref=vault.preferences()
        if(!speechReady(this,pref)) return emptyList()
        return vault.list().filter {it.needsProcessing()}.sortedBy {it.createdAt}.map {it.id}
    }

    /** Called when the app comes to the screen: picks up every meeting that still needs its notes. */
    fun resumePending() {scope.launch(Dispatchers.IO) {
        if(processing.value==null && runCatching {pendingMeetings()}.getOrDefault(emptyList()).isNotEmpty()) startProcessing()
    }}
}
/** [level] 0..1 on a decibel scale (see SoundMeter), [sound] whether the last 100 ms counted as sound, [heardMs] sound so far. */
data class RecordingStatus(val sessionId: String?=null,val state: String="idle",val elapsedMs: Long=0,val level: Float=0f,val error: String?=null,
                           val heardMs: Long=0,val sound: Boolean=false)
