@file:OptIn(PublicPreviewAPI::class)

package tech.tamerin.contextfriendpro

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.ai.DownloadStatus
import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.OnDeviceModelOption
import com.google.firebase.ai.OnDeviceModelStatus
import com.google.firebase.ai.type.FirebaseAIException
import com.google.firebase.ai.type.FirebaseAIOnDeviceInvalidRequestException
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Firebase AI Logic and the Firebase services around it.
 *
 * The app runs fully without Firebase (no options in local.properties): recording, transcription,
 * speakers and Gemma notes stay available; Gemini Nano and the cloud show "not configured".
 * Cloud requests go to the Vertex AI (Agent Platform) backend in an EU region: the Gemini API
 * terms allow only paid services for apps offered to users in the EEA.
 */
object Hybrid {
    val configured: Boolean
        get() = listOf(BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_PROJECT_ID).all { it.isNotBlank() }

    @Volatile private var firebase: FirebaseApp? = null
    @Volatile var config = HybridConfig()
        private set
    @Volatile private var defaultsReady: Task<Void>? = null

    val ready: Boolean get() = firebase != null
    fun firebaseApp(): FirebaseApp? = firebase
    val location: String get() = BuildConfig.VERTEX_LOCATION

    fun init(context: Context) {
        if (!configured || firebase != null) return
        val options = FirebaseOptions.Builder()
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .apply { if (BuildConfig.FIREBASE_SENDER_ID.isNotBlank()) setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID) }
            .build()
        val app = FirebaseApp.initializeApp(context, options)
        installAppCheck(app) // debug provider in debug builds, Play Integrity in release (src/debug, src/release)
        firebase = app
        val remote = FirebaseRemoteConfig.getInstance(app)
        remote.setConfigSettingsAsync(FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(if (BuildConfig.DEBUG) 0 else 3600).build())
        // Until Remote Config has loaded, the kill switch stays closed. Reading before the defaults are applied
        // would return static zeros (cloud off, chunk size 0), so the first read waits for them. Last activated
        // values survive restarts and work offline.
        config = HybridConfig(cloudEnabled = false)
        defaultsReady = remote.setDefaultsAsync(REMOTE_DEFAULTS).addOnCompleteListener { config = readConfig(remote) }
    }

    /** Anonymous sign-in: Firestore rules and per-user AI Logic limits need a user. Offline it fails quietly. */
    suspend fun signIn(): String? {
        val app = firebase ?: return null
        val auth = FirebaseAuth.getInstance(app)
        auth.currentUser?.let { return it.uid }
        return runCatching { auth.signInAnonymously().await().user?.uid }.getOrNull()
    }

    val signedIn: Boolean get() = firebase?.let { FirebaseAuth.getInstance(it).currentUser != null } == true

    suspend fun refreshConfig(): HybridConfig {
        val app = firebase ?: return config
        val remote = FirebaseRemoteConfig.getInstance(app)
        runCatching { defaultsReady?.await() }
        runCatching { remote.fetchAndActivate().await() }
        return readConfig(remote).also { config = it }
    }

    private fun readConfig(remote: FirebaseRemoteConfig) = HybridConfig(
        cloudEnabled = remote.getBoolean("cloud_enabled"),
        cloudModel = remote.getString("cloud_model").ifBlank { HybridConfig().cloudModel },
        nanoModelOption = remote.getString("nano_model_option").ifBlank { HybridConfig().nanoModelOption },
        nanoChunkChars = remote.getLong("nano_chunk_chars").toInt(),
        extraProtectedTerms = runCatching { Json.decodeFromString<List<String>>(remote.getString("extra_protected_terms")) }.getOrDefault(emptyList()),
    )

    private val REMOTE_DEFAULTS: Map<String, Any> = HybridConfig().let {
        mapOf("cloud_enabled" to it.cloudEnabled, "cloud_model" to it.cloudModel, "nano_model_option" to it.nanoModelOption,
            "nano_chunk_chars" to it.nanoChunkChars.toLong(), "extra_protected_terms" to "[]")
    }

    private fun ai(): FirebaseAI = FirebaseAI.getInstance(firebase ?: error("firebase_not_configured"), GenerativeBackend.agentPlatform(location))

    private fun modelOption() = when (config.nanoModelOption.uppercase()) {
        "STABLE" -> OnDeviceModelOption.STABLE
        "PREVIEW_FAST" -> OnDeviceModelOption.PREVIEW_FAST
        else -> OnDeviceModelOption.PREVIEW // Gemini Nano 4 Full on Pixel 10/11 with the AICore Developer Preview
    }

    private val jsonOutput = generationConfig { responseMimeType = "application/json" }

    /** The only on-device model the app builds: ONLY_ON_DEVICE, never a PREFER_* mode. */
    fun onDeviceModel(json: Boolean): GenerativeModel = ai().generativeModel(
        modelName = config.cloudModel, // required by the SDK even for on-device requests
        generationConfig = if (json) jsonOutput else null,
        onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_ON_DEVICE, temperature = 0.2f, modelOption = modelOption(), enableThinking = false),
    )

    fun cloudModel(): GenerativeModel = ai().generativeModel(
        modelName = config.cloudModel,
        generationConfig = generationConfig { responseMimeType = "application/json"; temperature = 0.2f },
        onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_IN_CLOUD),
    )

    suspend fun nanoState(): NanoState {
        if (!ready) return NanoState.NOT_CONFIGURED
        return runCatching {
            when (onDeviceModel(false).onDeviceExtension?.checkStatus()) {
                OnDeviceModelStatus.AVAILABLE -> NanoState.AVAILABLE
                OnDeviceModelStatus.DOWNLOADABLE -> NanoState.DOWNLOADABLE
                OnDeviceModelStatus.DOWNLOADING -> NanoState.DOWNLOADING
                OnDeviceModelStatus.UNAVAILABLE -> NanoState.UNAVAILABLE
                else -> NanoState.UNKNOWN
            }
        }.getOrDefault(NanoState.UNAVAILABLE)
    }

    suspend fun nanoModelName(): String? = runCatching { onDeviceModel(false).onDeviceExtension?.getModelName() }.getOrNull()

    fun downloadNano(): Flow<DownloadStatus> = if (!ready) emptyFlow() else onDeviceModel(false).onDeviceExtension?.download() ?: emptyFlow()
}

class NanoNotes(override val modelName: String) : NotesModel {
    override val engine = NotesEngine.GEMINI_NANO
    override var calls = 0
        private set
    private var json = true
    private val withJson by lazy { Hybrid.onDeviceModel(json = true) }
    private val plain by lazy { Hybrid.onDeviceModel(json = false) }

    override suspend fun complete(prompt: String): String {
        var attempt = 0
        while (true) {
            try {
                val response = (if (json) withJson else plain).generateContent(prompt)
                Policy.verifySource(engine, response.inferenceSource == InferenceSource.ON_DEVICE)
                calls++
                return response.text ?: throw IllegalStateException("empty_answer")
            } catch (e: FirebaseAIOnDeviceInvalidRequestException) {
                // If JSON output is not accepted on this device, ask for JSON in the prompt only; otherwise the input is too long.
                if (json) { json = false; continue }
                throw InputTooLong(e)
            } catch (e: FirebaseAIException) {
                // AICore answers BUSY on bursts; back off exponentially (ML Kit Prompt API guidance).
                if (attempt < 4 && e.message.orEmpty().contains("BUSY", ignoreCase = true)) { delay(1500L shl attempt); attempt++; continue }
                throw e
            }
        }
    }
}

class CloudNotes(override val modelName: String) : NotesModel {
    override val engine = NotesEngine.CLOUD_REDACTED
    override var calls = 0
        private set
    private val model by lazy { Hybrid.cloudModel() }

    override suspend fun complete(prompt: String): String {
        val response = model.generateContent(prompt)
        Policy.verifySource(engine, response.inferenceSource == InferenceSource.ON_DEVICE)
        calls++
        return response.text ?: throw IllegalStateException("empty_answer")
    }
}

/** The prototype's own engine: Gemma 4 E2B through llama.cpp, fully on the device. */
class GemmaNotes(private val path: String, override val modelName: String) : NotesModel {
    override val engine = NotesEngine.GEMMA_LOCAL
    override var calls = 0
        private set

    override suspend fun complete(prompt: String): String {
        val raw = NativeEngine.summarize(path, prompt.toByteArray()).decodeToString()
        val error = runCatching { json.parseToJsonElement(raw.trim()).let { (it as? kotlinx.serialization.json.JsonObject)?.get("error") } }.getOrNull()
        if (error != null && error.toString().trim('"') in setOf("input_too_long", "output_exhausted_context")) throw InputTooLong()
        calls++
        return raw
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) continuation.resume(task.result)
        else continuation.resumeWithException(task.exception ?: IllegalStateException("task_failed"))
    }
}
