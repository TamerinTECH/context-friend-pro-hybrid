package tech.tamerin.contextfriendpro

import kotlinx.serialization.Serializable

/**
 * Routing policy for meeting notes. Plain Kotlin, no Android or Firebase types, so every rule
 * has a unit test (see PolicyTest). The rules, in short:
 *
 * 1. Meeting content is sensitive by definition. Notes are written on the device.
 * 2. Gemini Nano is used only with ONLY_ON_DEVICE (a PREFER_* mode falls back to the cloud silently).
 * 3. Automatic routing never chooses the cloud. The cloud is a separate, explicit user action on
 *    redacted text, after the user saw the exact outgoing text and consented.
 * 4. Every answer's inference source is checked; a mismatch is a policy violation and is discarded.
 */
enum class NotesEngine(val id: String, val onDevice: Boolean) {
    /** Gemini Nano through Firebase AI Logic, InferenceMode.ONLY_ON_DEVICE. */
    GEMINI_NANO("gemini-nano", true),
    /** ContextFriend engine: Gemma 4 E2B through llama.cpp, on the device. */
    GEMMA_LOCAL("gemma-local", true),
    /** Gemini through Firebase AI Logic, InferenceMode.ONLY_IN_CLOUD, redacted text only. */
    CLOUD_REDACTED("cloud-gemini", false);

    companion object { fun of(id: String) = entries.single { it.id == id } }
}

enum class EnginePreference { AUTO, GEMINI_NANO, GEMMA_LOCAL }

enum class NanoState { AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE, NOT_CONFIGURED, UNKNOWN }

data class OnDeviceOptions(val nano: NanoState, val gemmaInstalled: Boolean, val gemmaFitsMemory: Boolean)

sealed interface LocalChoice {
    data class Use(val engine: NotesEngine, val reason: String, val warning: String? = null) : LocalChoice
    data class Blocked(val reason: String) : LocalChoice
}

/** Values that Remote Config may change. Safe defaults ship in the app. */
@Serializable
data class HybridConfig(
    val cloudEnabled: Boolean = true,
    val cloudModel: String = "gemini-4-hydrogen",
    val nanoModelOption: String = "PREVIEW",
    val nanoChunkChars: Int = 3500,
    val extraProtectedTerms: List<String> = emptyList(),
)

sealed interface CloudDecision {
    data object Allow : CloudDecision
    data class Block(val reason: String) : CloudDecision
}

class PolicyViolation(message: String) : IllegalStateException(message)

object Policy {
    /** Firebase AI Logic hybrid docs (Android): only English and Korean are validated on-device. */
    val NANO_VALIDATED_LANGUAGES = setOf("en", "ko")

    fun nanoValidated(language: String) = language.substringBefore('-').lowercase() in NANO_VALIDATED_LANGUAGES

    /** Picks the on-device engine. Never returns the cloud. */
    fun chooseOnDevice(language: String, preference: EnginePreference, options: OnDeviceOptions): LocalChoice {
        val nanoReady = options.nano == NanoState.AVAILABLE
        val gemmaReady = options.gemmaInstalled && options.gemmaFitsMemory
        val nanoWarning = if (nanoValidated(language)) null else "nano_language_unvalidated"
        return when (preference) {
            EnginePreference.GEMINI_NANO ->
                if (nanoReady) LocalChoice.Use(NotesEngine.GEMINI_NANO, "user_choice", nanoWarning)
                else LocalChoice.Blocked(nanoBlockReason(options.nano))
            EnginePreference.GEMMA_LOCAL -> when {
                gemmaReady -> LocalChoice.Use(NotesEngine.GEMMA_LOCAL, "user_choice")
                !options.gemmaInstalled -> LocalChoice.Blocked("gemma_missing")
                else -> LocalChoice.Blocked("gemma_needs_more_memory")
            }
            EnginePreference.AUTO -> when {
                nanoReady && nanoValidated(language) -> LocalChoice.Use(NotesEngine.GEMINI_NANO, "auto_nano")
                gemmaReady -> LocalChoice.Use(NotesEngine.GEMMA_LOCAL,
                    if (nanoReady) "auto_language_not_validated_for_nano" else "auto_nano_not_ready")
                nanoReady -> LocalChoice.Use(NotesEngine.GEMINI_NANO, "auto_only_nano_available", nanoWarning)
                else -> LocalChoice.Blocked("no_on_device_engine")
            }
        }
    }

    private fun nanoBlockReason(state: NanoState) = when (state) {
        NanoState.DOWNLOADABLE -> "nano_not_downloaded"
        NanoState.DOWNLOADING -> "nano_downloading"
        NanoState.NOT_CONFIGURED -> "firebase_not_configured"
        else -> "nano_unavailable"
    }

    /** The cloud gate. Consent is always required; Remote Config can only switch the cloud off. */
    fun cloudDecision(config: HybridConfig, firebaseReady: Boolean, consent: Boolean, risk: ResidualRisk): CloudDecision = when {
        !config.cloudEnabled -> CloudDecision.Block("cloud_disabled")
        !firebaseReady -> CloudDecision.Block("firebase_not_configured")
        risk.blocking -> CloudDecision.Block("redaction_incomplete")
        !consent -> CloudDecision.Block("consent_required")
        else -> CloudDecision.Allow
    }

    /** Throws when an answer came from somewhere other than the engine that was asked. */
    fun verifySource(engine: NotesEngine, answeredOnDevice: Boolean?) {
        when (engine) {
            NotesEngine.GEMINI_NANO -> if (answeredOnDevice != true) throw PolicyViolation("on_device_request_answered_in_cloud")
            NotesEngine.CLOUD_REDACTED -> if (answeredOnDevice != false) throw PolicyViolation("cloud_request_answered_on_device")
            NotesEngine.GEMMA_LOCAL -> Unit
        }
    }

    /** How much transcript text one request may carry. Nano's whole request is limited to 4,000 tokens. */
    fun chunkChars(engine: NotesEngine, config: HybridConfig): Int = when (engine) {
        NotesEngine.GEMINI_NANO -> config.nanoChunkChars.coerceIn(1000, 8000)
        NotesEngine.GEMMA_LOCAL -> 4000
        NotesEngine.CLOUD_REDACTED -> 400_000
    }
}
