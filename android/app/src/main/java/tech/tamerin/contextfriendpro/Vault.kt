package tech.tamerin.contextfriendpro

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.*
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class Vault(private val context: Context,namespace: String="vault") {
    init {require(namespace.matches(Regex("[a-z-]+")))}
    val root=File(context.noBackupFilesDir,namespace).apply { mkdirs() }
    private val rng=SecureRandom()
    private val alias="contextfriend-pro-vault-v1-$namespace"
    private val keyStore=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    init {
        if(!keyStore.containsAlias(alias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setUnlockedDeviceRequired(true).build()); generateKey()
            }
        }
    }
    private fun master() = keyStore.getKey(alias,null) as SecretKey
    private fun seal(bytes: ByteArray,key: SecretKey,aad: String): ByteArray {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key)
        cipher.updateAAD(aad.toByteArray());return byteArrayOf(1)+cipher.iv+cipher.doFinal(bytes)
    }
    private fun open(bytes: ByteArray,key: SecretKey,aad: String): ByteArray {
        require(bytes.size>=29 && bytes[0]==1.toByte())
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes.copyOfRange(1,13)))
        cipher.updateAAD(aad.toByteArray());return cipher.doFinal(bytes,13,bytes.size-13)
    }
    private fun atomic(file: File,bytes: ByteArray) {
        file.parentFile!!.mkdirs();val tmp=File(file.parentFile,file.name+".tmp")
        FileOutputStream(tmp).use { it.write(bytes);it.fd.sync() }
        java.nio.file.Files.move(tmp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        // Directory fsync commits the rename on Android/Linux.
        runCatching { val fd=android.system.Os.open(file.parent,android.system.OsConstants.O_RDONLY,0);try {android.system.Os.fsync(fd)}finally {android.system.Os.close(fd)} }.getOrThrow()
    }
    private fun dir(id: String): File { require(validId(id));return File(root,id) }
    @Synchronized fun create(session: Session): ByteArray {
        val key=ByteArray(32).also(rng::nextBytes);val d=dir(session.id);require(!d.exists());d.mkdirs()
        atomic(File(d,"key"),seal(key,master(),session.id));save(session,key);return key
    }
    @Synchronized fun sessionKey(id: String): ByteArray = open(File(dir(id),"key").readBytes(),master(),id)
    @Synchronized fun save(session: Session,key: ByteArray? = null) {
        val raw=key?:sessionKey(session.id)
        try { atomic(File(dir(session.id),"session"),seal(json.encodeToString(session).toByteArray(),SecretKeySpec(raw,"AES"),session.id+"/session")) }
        finally { if(key==null) raw.fill(0) }
    }
    @Synchronized fun load(id: String): Session {
        val key=sessionKey(id)
        try { return json.decodeFromString(open(File(dir(id),"session").readBytes(),SecretKeySpec(key,"AES"),id+"/session").decodeToString()) }
        finally { key.fill(0) }
    }
    @Synchronized fun list(): List<Session> = root.listFiles().orEmpty().filter { it.isDirectory && validId(it.name) }
        .map { load(it.name) }.sortedByDescending { it.createdAt }
    @Synchronized fun update(id: String,change: (Session)->Session): Session = change(load(id)).also { save(it) }
    @Synchronized fun writeChunk(id: String,chunk: AudioChunk,key: ByteArray) {
        require(chunk.index>=0 && chunk.samples in 1..80000)
        atomic(File(dir(id),"chunks/${chunk.index}.audio"),seal(json.encodeToString(chunk).toByteArray(),SecretKeySpec(key,"AES"),"$id/audio/${chunk.index}"))
    }
    fun chunks(id: String): List<Int> = File(dir(id),"chunks").listFiles().orEmpty().filter { it.extension=="audio" }
        .mapNotNull { it.nameWithoutExtension.toIntOrNull() }.sorted()
    fun readChunk(id: String,index: Int,key: ByteArray): AudioChunk = json.decodeFromString(open(File(dir(id),"chunks/$index.audio").readBytes(),SecretKeySpec(key,"AES"),"$id/audio/$index").decodeToString())
    @Synchronized fun deleteAudio(id: String) { File(dir(id),"chunks").deleteRecursively();update(id) { it.copy(audioRetained=false) } }
    @Synchronized fun delete(id: String) { File(dir(id),"key").delete();require(dir(id).deleteRecursively()) }
    @Synchronized fun putPrivate(name: String,data: String) { require(name.matches(Regex("[a-z-]+")));atomic(File(root,"$name.enc"),seal(data.toByteArray(),master(),name)) }
    @Synchronized fun private(name: String): String? {require(name.matches(Regex("[a-z-]+")));val f=File(root,"$name.enc");return if(f.exists()) open(f.readBytes(),master(),name).decodeToString() else null}
    fun preferences(): Preferences = private("preferences")?.let {json.decodeFromString(it)} ?: Preferences()
    fun profiles(): List<VoiceProfile> = private("profiles")?.let { json.decodeFromString(it) } ?: emptyList()
    fun putProfiles(profiles: List<VoiceProfile>) {profiles.forEach {it.validate()};putPrivate("profiles",json.encodeToString(profiles))}
    @Synchronized fun recover() {
        list().forEach { s ->
            if(s.state=="importing") {delete(s.id);return@forEach}
            if(s.state in listOf("recording","paused","transcribing","diarizing","summarizing","queued")) {
                var duration=s.durationMs
                val key=sessionKey(s.id)
                try { chunks(s.id).forEach { n -> val c=readChunk(s.id,n,key);duration=maxOf(duration,c.startMs+c.samples*1000L/c.sampleRate) } }
                finally {key.fill(0)}
                // A recording cut off by a killed app is interrupted; a meeting whose notes were cut off only waits:
                // its audio is complete and the finished windows are kept, so it continues by itself (ProApp.resumePending).
                save(if(s.state in listOf("recording","paused")) s.copy(state="interrupted",durationMs=duration,
                    gaps=s.gaps+Gap(duration,null,"process_interrupted_uncommitted_tail_unknown")) else s.copy(state="queued",durationMs=duration))
            }
        }
    }
}
