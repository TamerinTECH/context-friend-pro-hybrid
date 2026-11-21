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
    override fun onCreate() { super.onCreate();vault=Vault(this);models=Models(this);scope.launch(Dispatchers.IO) {runCatching {vault.recover()};changed()} }
    fun changed() {generation.value++}
}
data class RecordingStatus(val sessionId: String?=null,val state: String="idle",val elapsedMs: Long=0,val level: Float=0f,val error: String?=null)
