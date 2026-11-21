package tech.tamerin.contextfriendpro

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Local privacy audit log: where each AI request ran, never what it said.
 * No transcript, summary, name or placeholder mapping is ever written here. The meeting is
 * referenced by a short hash of its random id. Stored encrypted in the vault, exportable as JSON.
 */
@Serializable
data class AuditEntry(
    val at: Long = System.currentTimeMillis(),
    val action: String,
    val engine: String? = null,
    val model: String? = null,
    /** ON_DEVICE / IN_CLOUD as reported by Firebase AI Logic, or LOCAL_ENGINE for Gemma via llama.cpp. */
    val source: String? = null,
    val calls: Int = 0,
    val redacted: Map<String, Int> = emptyMap(),
    val charsSent: Int = 0,
    val consent: Boolean? = null,
    val reason: String? = null,
    val meeting: String? = null,
)

/** Where a session's current notes came from. Shown as the badge on the summary. */
@Serializable
data class NotesProvenance(
    val engine: String,
    val model: String,
    val onDeviceCalls: Int = 0,
    val cloudCalls: Int = 0,
    val redactedEntities: Int = 0,
    val at: Long = System.currentTimeMillis(),
)

object Audit {
    private const val NAME = "audit-log"
    private const val KEEP = 500

    fun meetingRef(sessionId: String) = digest(sessionId.toByteArray()).take(8)

    @Synchronized fun add(vault: Vault, entry: AuditEntry) {
        vault.putPrivate(NAME, json.encodeToString((list(vault) + entry).takeLast(KEEP)))
    }

    fun list(vault: Vault): List<AuditEntry> = vault.private(NAME)?.let { json.decodeFromString<List<AuditEntry>>(it) } ?: emptyList()

    @Synchronized fun clear(vault: Vault) = vault.putPrivate(NAME, "[]")

    fun export(vault: Vault): String = json.encodeToString(list(vault))

    fun sourceOf(engine: NotesEngine) = when (engine) {
        NotesEngine.GEMINI_NANO -> "ON_DEVICE"
        NotesEngine.GEMMA_LOCAL -> "LOCAL_ENGINE"
        NotesEngine.CLOUD_REDACTED -> "IN_CLOUD"
    }
}
