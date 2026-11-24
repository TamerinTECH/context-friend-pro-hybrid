package tech.tamerin.contextfriendpro

import android.app.Application
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
    override fun onCreate() {
        super.onCreate();vault=Vault(this);models=Models(this)
        runCatching {Hybrid.init(this)};hybridConfig.value=Hybrid.config
        scope.launch(Dispatchers.IO) {runCatching {vault.recover()};changed()}
        refreshHybrid()
    }
    fun refreshHybrid() {scope.launch(Dispatchers.IO) {
        runCatching {hybridConfig.value=Hybrid.refreshConfig()}
        runCatching {signedIn.value=Hybrid.signIn()!=null}
        runCatching {nano.value=Hybrid.nanoState()}
    }}
    fun changed() {generation.value++}
}
data class RecordingStatus(val sessionId: String?=null,val state: String="idle",val elapsedMs: Long=0,val level: Float=0f,val error: String?=null)
