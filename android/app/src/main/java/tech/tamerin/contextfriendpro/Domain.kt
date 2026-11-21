package tech.tamerin.contextfriendpro

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.math.sqrt

val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false; isLenient = false }
fun newId(): String = UUID.randomUUID().toString()
fun validId(s: String): Boolean = runCatching { UUID.fromString(s).toString() == s }.getOrDefault(false)

@Serializable data class Segment(val id: String = newId(), val startMs: Long, val endMs: Long, val original: String,
    val correction: String? = null, val speaker: String? = null, val uncertain: Boolean = false, val language: String = "auto") {
    val text: String get() = correction ?: original
}
@Serializable data class Statement(val text: String, val sources: List<String>)
@Serializable data class ActionItem(val text: String, val owner: String? = null, val due: String? = null, val sources: List<String>)
@Serializable data class Summary(val title: String, val paragraphs: List<Statement>, val decisions: List<Statement> = emptyList(),
    val actions: List<ActionItem> = emptyList(), val uncertainties: List<Statement> = emptyList()) {
    fun validate(ids: Set<String>) {
        require(title.length <= 500 && paragraphs.size <= 100 && decisions.size <= 100 && actions.size <= 100 && uncertainties.size <= 100)
        (paragraphs + decisions + uncertainties).forEach { require(it.text.length <= 10000 && it.sources.isNotEmpty() && it.sources.all(ids::contains)) }
        actions.forEach { require(it.text.length <= 10000 && it.sources.isNotEmpty() && it.sources.all(ids::contains)) }
    }
    fun plain(): String = (listOf(title) + paragraphs.map { it.text } + decisions.map { it.text } +
        actions.map { listOfNotNull(it.text, it.owner, it.due).joinToString(" · ") } + uncertainties.map { it.text }).joinToString("\n\n")
}
@Serializable data class Gap(val startMs: Long, val endMs: Long?, val reason: String)
@Serializable data class Stage(val state: String = "pending", val completedWindows: Int = 0, val modelHash: String? = null, val error: String? = null)
@Serializable data class Revision(val id: String, val transcript: List<Segment>, val summary: Summary?, val editedSummary: String?, val approved: Boolean)
@Serializable data class Session(val id: String = newId(), val title: String, val createdAt: Long = System.currentTimeMillis(),
    val timezone: String = java.util.TimeZone.getDefault().id, val language: String = "en", val state: String = "recorded",
    val durationMs: Long = 0, val gaps: List<Gap> = emptyList(), val transcript: List<Segment> = emptyList(), val summary: Summary? = null,
    val editedSummary: String? = null, val revision: String = newId(), val approvedRevision: String? = null,
    val history: List<Revision> = emptyList(), val stages: Map<String,Stage> = emptyMap(), val speakers: Map<String,String> = emptyMap(),
    val suggestedProfiles: Map<String,String> = emptyMap(), val speakerVectors: Map<String,List<Float>> = emptyMap(), val audioRetained: Boolean = true, val received: Boolean = false,
    val senderStatus: String? = null, val sourceBundle: String? = null) {
    val approved: Boolean get() = approvedRevision == revision
    fun edited(): Session = copy(revision = newId(), approvedRevision = null, state = "needs_review",
        history = (history + Revision(revision,transcript,summary,editedSummary,approved)).takeLast(20))
    fun summaryText(): String = editedSummary ?: summary?.plain().orEmpty()
}
@Serializable data class AudioChunk(val index: Int, val startMs: Long, val sampleRate: Int = 16000, val pcm: String, val samples: Int)
@Serializable data class VoiceProfile(val id: String = newId(), val name: String, val embedding: List<Float>, val modelHash: String,
    val preprocessing: String = "mono16k-f32-sherpa-v1", val consentVersion: Int = 1, val createdAt: Long = System.currentTimeMillis(),
    val imported: Boolean = false, val enabled: Boolean = true) {
    fun validate() { require(validId(id) && name.isNotBlank() && name.length <= 100 && embedding.size == 192 &&
        embedding.all { it.isFinite() } && embedding.any { it != 0f } && modelHash.matches(Regex("[0-9a-f]{64}")) &&
        preprocessing == "mono16k-f32-sherpa-v1" && consentVersion == 1) }
}
@Serializable data class Preferences(val language: String = "en", val matching: Boolean = false, val appLock: Boolean = false,
    val deleteAudioAfterApproval: Boolean = false, val asrModel: String = "whisper-tiny", val summaryModel: String = "gemma4-e2b")

fun cosine(a: List<Float>, b: List<Float>): Float {
    if(a.size != b.size || a.isEmpty()) return -1f
    val dot=a.indices.sumOf { (a[it]*b[it]).toDouble() }; val aa=a.sumOf { (it*it).toDouble() }; val bb=b.sumOf { (it*it).toDouble() }
    return if(aa>0 && bb>0) (dot/sqrt(aa*bb)).toFloat() else -1f
}
