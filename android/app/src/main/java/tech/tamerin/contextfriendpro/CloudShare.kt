package tech.tamerin.contextfriendpro

import com.goterl.lazysodium.SodiumAndroid
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.SecureRandom
import java.util.Base64
import java.util.Date

/** What a cloud link carries: the notes text only. Never transcript, audio, voice profiles or the speaker map. */
@Serializable
data class SharedNotes(val v: Int = 1, val title: String, val createdAt: Long, val language: String, val approved: Boolean, val summary: String)

/**
 * A cloud link: `CFS1:<document id>:<key>`. The key exists only in the link (shown as QR or shared text).
 * Firestore stores XChaCha20-Poly1305 ciphertext bound to the document id, for [CloudShare.TTL_DAYS] days.
 */
data class ShareLink(val id: String, val key: ByteArray) {
    fun encode() = "CFS1:$id:" + Base64.getUrlEncoder().withoutPadding().encodeToString(key)

    companion object {
        private val FORMAT = Regex("""CFS1:([A-Za-z0-9_-]{22}):([A-Za-z0-9_-]{43})""")
        fun parse(text: String): ShareLink {
            val m = FORMAT.find(text.trim()) ?: throw IllegalArgumentException("share_link_invalid")
            val key = Base64.getUrlDecoder().decode(m.groupValues[2])
            require(key.size == 32) { "share_link_invalid" }
            return ShareLink(m.groupValues[1], key)
        }
    }
}

/**
 * "Hybrid if desired": share notes through Firebase without giving the server the content.
 * Encryption and decryption happen on the phones; Firestore never sees plaintext or the key.
 */
object CloudShare {
    const val TTL_DAYS = 7
    private const val COLLECTION = "shares"
    private val sodium by lazy { SodiumAndroid() }
    private val random = SecureRandom()

    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun ad(id: String) = "CFS1:$id".toByteArray()

    suspend fun publish(app: ProApp, s: Session): ShareLink {
        val firebase = Hybrid.firebaseApp() ?: throw IllegalStateException("firebase_not_configured")
        val uid = Hybrid.signIn() ?: throw IllegalStateException("not_signed_in")
        require(s.summaryText().isNotBlank()) { "nothing_to_share" }
        val plain = json.encodeToString(SharedNotes(title = s.title, createdAt = s.createdAt, language = s.language,
            approved = s.approved, summary = s.summaryText())).toByteArray()
        require(plain.size <= 200_000) { "share_too_large" }
        val id = b64(bytes(16))
        val key = bytes(32)
        val nonce = bytes(24)
        val cipher = ByteArray(plain.size + 16)
        try {
            check(sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(cipher, LongArray(1), plain, plain.size.toLong(),
                ad(id), ad(id).size.toLong(), null, nonce, key) == 0)
        } finally { plain.fill(0) }
        FirebaseFirestore.getInstance(firebase).collection(COLLECTION).document(id).set(mapOf(
            "v" to 1L, "ct" to b64(cipher), "nonce" to b64(nonce), "owner" to uid,
            "createdAt" to FieldValue.serverTimestamp(),
            "expiresAt" to Timestamp(Date(System.currentTimeMillis() + TTL_DAYS * 86_400_000L)),
        )).await()
        Audit.add(app.vault, AuditEntry(action = "cloud_share", source = "ENCRYPTED_STORE", charsSent = cipher.size,
            consent = true, reason = "end_to_end_encrypted,ttl_${TTL_DAYS}d", meeting = Audit.meetingRef(s.id)))
        return ShareLink(id, key)
    }

    /** Fetches, decrypts and stores the notes as a received, read-only meeting. */
    suspend fun open(app: ProApp, link: ShareLink): String {
        val firebase = Hybrid.firebaseApp() ?: throw IllegalStateException("firebase_not_configured")
        Hybrid.signIn() ?: throw IllegalStateException("not_signed_in")
        val doc = FirebaseFirestore.getInstance(firebase).collection(COLLECTION).document(link.id).get().await()
        require(doc.exists()) { "share_not_found" }
        val cipher = Base64.getUrlDecoder().decode(doc.getString("ct") ?: "")
        val nonce = Base64.getUrlDecoder().decode(doc.getString("nonce") ?: "")
        require(cipher.size > 16 && nonce.size == 24) { "share_invalid" }
        val plain = ByteArray(cipher.size - 16)
        require(sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(plain, LongArray(1), null, cipher, cipher.size.toLong(),
            ad(link.id), ad(link.id).size.toLong(), nonce, link.key) == 0) { "share_wrong_key" }
        val notes = try { json.decodeFromString<SharedNotes>(plain.decodeToString()) } finally { plain.fill(0) }
        require(notes.v == 1 && notes.title.length <= 500 && notes.summary.length <= 300_000 && notes.language in listOf("en", "de", "he", "auto"))
        val source = "cloud:" + link.id
        app.vault.list().firstOrNull { it.sourceBundle == source }?.let { return it.id }
        val session = Session(title = notes.title, createdAt = notes.createdAt, language = notes.language, state = "needs_review",
            editedSummary = notes.summary, audioRetained = false, received = true, senderStatus = "unverified", sourceBundle = source)
        val revisioned = session.copy(approvedRevision = if (notes.approved) session.revision else null)
        app.vault.create(revisioned).fill(0)
        Audit.add(app.vault, AuditEntry(action = "cloud_share_opened", source = "ENCRYPTED_STORE", reason = "end_to_end_encrypted",
            meeting = Audit.meetingRef(revisioned.id)))
        return revisioned.id
    }

    /** The owner can delete a link before it expires. */
    suspend fun revoke(id: String) {
        val firebase = Hybrid.firebaseApp() ?: return
        FirebaseFirestore.getInstance(firebase).collection(COLLECTION).document(id).delete().await()
    }
}
