package tech.tamerin.contextfriendpro

import android.os.SystemClock
import com.goterl.lazysodium.SodiumAndroid
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

fun b64(b: ByteArray): String=Base64.getUrlEncoder().withoutPadding().encodeToString(b)
fun un64(s: String): ByteArray {require(s.length<=90_000_000);return Base64.getUrlDecoder().decode(s)}
fun uuidBytes(s: String): ByteArray {val u=UUID.fromString(s);return ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()}
fun uuidString(b: ByteArray): String {val r=ByteBuffer.wrap(b);return UUID(r.long,r.long).toString()}
@Serializable data class Identity(val boxPublic: String,val boxSecret: String,val signPublic: String,val signSecret: String)
@Serializable data class Invitation(val version: Int=1,val id: String=newId(),val boxKey: String,val signKey: String,val ttl: Int=600,val host: String?=null,val port: Int?=null) {
    fun encode()="CFI1:"+b64(json.encodeToString(this).toByteArray())
    fun validate() {require(version==1 && validId(id) && un64(boxKey).size==32 && un64(signKey).size==32 && ttl in 1..600);if(host!=null)require(host.length<=100);if(port!=null)require(port in 1024..65535)}
    fun fingerprint()=digest(un64(boxKey)+un64(signKey)).take(24).chunked(4).joinToString(" ")
    companion object {fun decode(s: String): Invitation {require(s.startsWith("CFI1:") && s.length<=2048);return json.decodeFromString<Invitation>(un64(s.substring(5)).decodeToString()).also {it.validate()}}}
}
@Serializable data class PendingInvitation(val invitation: Invitation,val deadlineElapsed: Long,val bootCount: Int,val bundleHash: String?=null,val completed: Boolean=false,val receipt: String?=null)
@Serializable data class TrustedPeer(val signKey: String,val boxKey: String,val label: String)
@Serializable data class MeetingPayload(val sessionId: String,val revision: String,val createdAt: Long,val language: String,val title: String,
    val approved: Boolean,val summary: String,val transcript: List<Segment>?=null,val speakers: Map<String,String>?=null,
    val audio: List<AudioChunk>?=null,val stages: Map<String,Stage> = emptyMap(),val structuredSummary: Summary?=null)
@Serializable data class BundlePayload(val version: Int=1,val bundleId: String=newId(),val purpose: String,
    val meeting: MeetingPayload?=null,val profiles: List<VoiceProfile>?=null) {
    fun validate() {
        require(version==1 && validId(bundleId))
        if(purpose=="meeting") {
            require(profiles==null && meeting!=null);val m=meeting
            require(validId(m.sessionId) && validId(m.revision) && m.title.length<=500 && m.summary.length<=300000 && m.language in listOf("en","de","he","auto"))
            require((m.transcript?.size ?: 0)<=20000 && (m.speakers?.size ?: 0)<=1000 && (m.audio?.size ?: 0)<=7200)
            m.transcript?.forEach {require(validId(it.id) && it.startMs>=0 && it.endMs>=it.startMs && it.original.length<=20000 && (it.correction?.length ?: 0)<=20000)}
            require(m.transcript.orEmpty().map {it.id}.distinct().size==m.transcript.orEmpty().size && m.transcript.orEmpty().all {it.endMs<=86400000})
            require(m.audio.orEmpty().map {it.index}.distinct().size==m.audio.orEmpty().size)
            m.structuredSummary?.validate(m.transcript.orEmpty().map {it.id}.toSet())
            m.audio?.forEach {require(it.index>=0 && it.startMs in 0..86400000 && it.sampleRate==16000 && it.samples in 1..80000 && Base64.getDecoder().decode(it.pcm).size==it.samples*2)}
        } else {require(purpose=="profiles" && meeting==null && !profiles.isNullOrEmpty() && profiles.size<=100);profiles.forEach {it.validate()}}
    }
}
data class OpenBundle(val payload: BundlePayload,val invitation: PendingInvitation,val hash: String,val sender: String,val trusted: Boolean)
class Transfers(private val app: ProApp,private val vault: Vault=app.vault) {
    private val sodium=SodiumAndroid()
    private val random=SecureRandom()
    private val identity: Identity by lazy {
        vault.private("identity")?.let {json.decodeFromString<Identity>(it)} ?: run {
            val bp=ByteArray(32);val bs=ByteArray(32);val sp=ByteArray(32);val ss=ByteArray(64)
            check(sodium.crypto_box_keypair(bp,bs)==0 && sodium.crypto_sign_keypair(sp,ss)==0)
            Identity(b64(bp),b64(bs),b64(sp),b64(ss)).also {vault.putPrivate("identity",json.encodeToString(it));bs.fill(0);ss.fill(0)}
        }
    }
    private fun pending(): List<PendingInvitation> = vault.private("invitations")?.let {json.decodeFromString(it)} ?: emptyList()
    private fun savePending(v: List<PendingInvitation>)=vault.putPrivate("invitations",json.encodeToString(v.takeLast(20)))
    fun peers(): List<TrustedPeer> = vault.private("trusted-peers")?.let {json.decodeFromString(it)} ?: emptyList()
    fun trust(inv: Invitation,label: String) {inv.validate();require(label.length in 1..100);vault.putPrivate("trusted-peers",json.encodeToString(peers().filterNot {it.signKey==inv.signKey}+TrustedPeer(inv.signKey,inv.boxKey,label)))}
    fun untrust(key: String)=vault.putPrivate("trusted-peers",json.encodeToString(peers().filterNot {it.signKey==key}))
    private fun boot()=android.provider.Settings.Global.getInt(app.contentResolver,android.provider.Settings.Global.BOOT_COUNT)
    @Synchronized fun invite(host: String?=null,port: Int?=null): Invitation {
        val inv=Invitation(boxKey=identity.boxPublic,signKey=identity.signPublic,host=host,port=port)
        savePending(pending().filter {it.bootCount==boot() && it.deadlineElapsed>SystemClock.elapsedRealtime()}+PendingInvitation(inv,SystemClock.elapsedRealtime()+600000,boot()));return inv
    }
    @Synchronized fun revoke(invitationId: String) {savePending(pending().filterNot {it.invitation.id==invitationId})}
    fun meeting(session: Session,includeTranscript: Boolean,includeAudio: Boolean,allowDraft: Boolean): BundlePayload {
        require(session.approved || allowDraft);require(session.summaryText().isNotBlank())
        val audio=if(includeAudio) {require(session.audioRetained && session.durationMs<=15*60*1000) {"audio_bundle_limit_15_minutes"};val k=vault.sessionKey(session.id);try {vault.chunks(session.id).map {vault.readChunk(session.id,it,k)}}finally {k.fill(0)}}else null
        return BundlePayload(purpose="meeting",meeting=MeetingPayload(session.id,session.revision,session.createdAt,session.language,session.title,
            session.approved,session.summaryText(),if(includeTranscript)session.transcript else null,if(includeTranscript)session.speakers else null,audio,
            session.stages.mapValues {(_,v)->v.copy(error=null)},if(includeTranscript && session.editedSummary==null)session.summary else null)).also {it.validate()}
    }
    fun profiles(selected: List<VoiceProfile>): BundlePayload=BundlePayload(purpose="profiles",profiles=selected).also {it.validate()}
    fun seal(payload: BundlePayload,recipient: Invitation): ByteArray {
        payload.validate();recipient.validate();val plain=json.encodeToString(payload).toByteArray();require(plain.size<=MAX_BUNDLE-512)
        val contentKey=ByteArray(32).also(random::nextBytes);val wrapped=ByteArray(80);val nonce=ByteArray(24).also(random::nextBytes)
        try {
            check(sodium.crypto_box_seal(wrapped,contentKey,32,un64(recipient.boxKey))==0)
            val header=ByteBuffer.allocate(241).put("CFB1".toByteArray()).put(if(payload.purpose=="meeting")1.toByte()else 2.toByte())
                .put(uuidBytes(recipient.id)).put(uuidBytes(payload.bundleId)).put(un64(recipient.boxKey)).put(un64(recipient.signKey))
                .put(un64(identity.signPublic)).put(nonce).put(wrapped).putInt(plain.size+16).array()
            val cipher=ByteArray(plain.size+16)
            check(sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(cipher,LongArray(1),plain,plain.size.toLong(),header,header.size.toLong(),null,nonce,contentKey)==0)
            val bytes=header+cipher;val signature=ByteArray(64);val signed="CFB1-SIGN:".toByteArray()+bytes
            check(sodium.crypto_sign_detached(signature,null,signed,signed.size.toLong(),un64(identity.signSecret))==0)
            return bytes+signature
        } finally {plain.fill(0);contentKey.fill(0)}
    }
    @Synchronized fun open(bytes: ByteArray): OpenBundle {
        require(bytes.size in 321..MAX_BUNDLE)
        val r=ByteBuffer.wrap(bytes);val magic=ByteArray(4).also(r::get);require(magic.decodeToString()=="CFB1")
        val purpose=r.get().toInt();require(purpose in 1..2)
        val invId=uuidString(ByteArray(16).also(r::get));val bundleId=uuidString(ByteArray(16).also(r::get))
        val box=ByteArray(32).also(r::get);val sign=ByteArray(32).also(r::get);val sender=ByteArray(32).also(r::get)
        val nonce=ByteArray(24).also(r::get);val wrapped=ByteArray(80).also(r::get);val length=r.int
        require(length>=16 && length==bytes.size-241-64 && b64(box)==identity.boxPublic && b64(sign)==identity.signPublic)
        val hash=digest(bytes);val pending=pending().singleOrNull {it.invitation.id==invId} ?: error("invitation_unknown")
        require(pending.bootCount==boot() && pending.deadlineElapsed>SystemClock.elapsedRealtime()) {"invitation_expired"}
        require(pending.bundleHash==null || pending.bundleHash==hash) {"invitation_already_bound"}
        val signed="CFB1-SIGN:".toByteArray()+bytes.copyOfRange(0,bytes.size-64)
        require(sodium.crypto_sign_verify_detached(bytes.takeLast(64).toByteArray(),signed,signed.size.toLong(),sender)==0) {"invalid_signature"}
        val key=ByteArray(32);check(sodium.crypto_box_seal_open(key,wrapped,80,box,un64(identity.boxSecret))==0)
        val plain=ByteArray(length-16)
        try {
            require(sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(plain,LongArray(1),null,bytes.copyOfRange(241,241+length),length.toLong(),bytes.copyOfRange(0,241),241,nonce,key)==0)
            val text=plain.decodeToString();requireJsonDepth(text)
            val payload=json.decodeFromString<BundlePayload>(text);payload.validate()
            require(payload.bundleId==bundleId && payload.purpose==if(purpose==1)"meeting"else "profiles")
            val bound=pending.copy(bundleHash=hash);savePending(this.pending().map {if(it.invitation.id==invId)bound else it})
            return OpenBundle(payload,bound,hash,b64(sender),peers().any {it.signKey==b64(sender)})
        } finally {key.fill(0);plain.fill(0)}
    }
    @Synchronized fun accept(open: OpenBundle,profileConsent: Boolean): String {
        val current=pending().single {it.invitation.id==open.invitation.invitation.id}
        require(current.bundleHash==open.hash && current.bootCount==boot() && current.deadlineElapsed>SystemClock.elapsedRealtime())
        if(current.completed)return current.receipt!!
        val payload=open.payload
        if(payload.purpose=="profiles") {
            require(profileConsent);val compatible=app.models.entry("titanet-small").sha256
            val imported=payload.profiles!!;require(imported.all {it.modelHash==compatible}) {"profile_model_incompatible"}
            val existing=vault.profiles();vault.putProfiles(existing+imported.filter {p->existing.none {it.id==p.id}}.map {it.copy(imported=true,enabled=false)})
        } else {
            val m=payload.meeting!!
            vault.list().filter {it.sourceBundle==payload.bundleId && it.state=="importing"}.forEach {vault.delete(it.id)}
            if(vault.list().none {it.sourceBundle==payload.bundleId}) {
                val s=Session(title=m.title,createdAt=m.createdAt,language=m.language,state="importing",transcript=m.transcript?:emptyList(),
                    summary=m.structuredSummary,editedSummary=m.summary,revision=m.revision,approvedRevision=if(m.approved)m.revision else null,speakers=m.speakers?:emptyMap(),
                    audioRetained=!m.audio.isNullOrEmpty(),received=true,senderStatus=if(open.trusted)"trusted"else "unverified",sourceBundle=payload.bundleId,stages=m.stages)
                val key=vault.create(s)
                try {m.audio?.forEach {vault.writeChunk(s.id,it,key)};vault.save(s.copy(state="needs_review",durationMs=m.audio?.maxOfOrNull {it.startMs+it.samples/16}?:m.transcript?.maxOfOrNull {it.endMs}?:0),key)}catch(e: Exception){vault.delete(s.id);throw e}finally {key.fill(0)}
            }
        }
        val data="CFR1:${current.invitation.id}:${payload.bundleId}:${open.hash}:imported"
        val sig=ByteArray(64);check(sodium.crypto_sign_detached(sig,null,data.toByteArray(),data.toByteArray().size.toLong(),un64(identity.signSecret))==0)
        val receipt=data+":"+b64(sig)
        savePending(pending().map {if(it.invitation.id==current.invitation.id)it.copy(completed=true,receipt=receipt)else it});app.changed();return receipt
    }
    fun verifyReceipt(receipt: String,invitation: Invitation,bytes: ByteArray): Boolean {
        if(runCatching {invitation.validate()}.isFailure || bytes.size<37)return false
        if(receipt.length>1024)return false
        val parts=receipt.split(':');if(parts.size!=6)return false
        val bundleId=uuidString(bytes.copyOfRange(21,37));val expected="CFR1:${invitation.id}:$bundleId:${digest(bytes)}:imported"
        return parts.take(5).joinToString(":")==expected && runCatching {val signature=un64(parts[5]);require(signature.size==64);sodium.crypto_sign_verify_detached(signature,expected.toByteArray(),expected.length.toLong(),un64(invitation.signKey))==0}.getOrDefault(false)
    }
    companion object {const val MAX_BUNDLE=64*1024*1024}
}
fun requireJsonDepth(s: String) {
    var depth=0;var quoted=false;var escape=false
    s.forEach {c -> if(quoted) {if(escape)escape=false else if(c=='\\')escape=true else if(c=='\"')quoted=false}
        else when(c) {'\"'->quoted=true;'[','{'->{depth++;require(depth<=16)};']','}'->{depth--;require(depth>=0)}} }
    require(!quoted && depth==0)
}

object QrFrames {
    const val SIZE=600
    fun encode(bytes: ByteArray): List<String> {
        require(bytes.size<=SIZE*64);val hash=digest(bytes);val parts=bytes.toList().chunked(SIZE)
        return parts.mapIndexed {i,p -> "CFQ1:$hash:$i:${parts.size}:${bytes.size}:${b64(p.toByteArray())}"}
    }
    class Collector {
        private var id: String?=null;private var count=0;private var total=0;private var deadline=0L
        private val chunks=mutableMapOf<Int,ByteArray>()
        fun progress()=chunks.size to count
        fun add(text: String,now: Long=SystemClock.elapsedRealtime()): ByteArray? {
            require(text.length<=1100);val p=text.split(':');require(p.size==6 && p[0]=="CFQ1" && p[1].matches(Regex("[0-9a-f]{64}")))
            val index=p[2].toInt();val n=p[3].toInt();val size=p[4].toInt();val data=un64(p[5])
            require(n in 1..64 && size in 1..SIZE*64 && n==(size+SIZE-1)/SIZE && index in 0 until n && data.size==if(index==n-1)size-SIZE*(n-1)else SIZE)
            if(id==null) {id=p[1];count=n;total=size;deadline=now+600000}
            require(now<deadline && id==p[1] && n==count && size==total)
            chunks[index]?.let {require(it.contentEquals(data))};chunks[index]=data
            if(chunks.size<count)return null
            val bytes=(0 until count).flatMap {chunks.getValue(it).asIterable()}.toByteArray();require(digest(bytes)==id);return bytes
        }
    }
}
