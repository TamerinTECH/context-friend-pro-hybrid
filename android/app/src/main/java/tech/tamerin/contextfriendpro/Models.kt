package tech.tamerin.contextfriendpro

import android.content.Context
import android.net.Uri
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

@Serializable data class ModelEntry(val id: String,val task: String,val file: String,val revision: String,val url: String,
    val bytes: Long,val sha256: String,val license: String,val runtime: String,val quantization: String,val languages: List<String>,
    val qualification: String,val repo: String?=null,val archive: Boolean=false)
@Serializable data class ModelManifest(val version: Int,val models: List<ModelEntry>)
class Models(private val context: Context) {
    val root=File(context.noBackupFilesDir,"models").apply {mkdirs()}
    val manifest: ModelManifest by lazy {json.decodeFromString(context.assets.open("MODEL_MANIFEST.json").bufferedReader().readText())}
    fun entry(id: String) = manifest.models.single {it.id==id}
    fun file(id: String): File = File(root,entry(id).file)
    fun installed(id: String): Boolean = file(id).let {it.isFile && it.length()==entry(id).bytes && File(root,"$id.verified").readTextOrNull()==entry(id).sha256}
    fun verifiedFile(id: String): File {val f=file(id);require(installed(id) && digest(f)==entry(id).sha256) {"model_integrity_failed"};return f}
    fun remove(id: String) {file(id).delete();File(root,"$id.verified").delete();File(root,entry(id).file+".partial").delete()}
    fun install(id: String,uri: Uri,cancel: AtomicBoolean,progress: (Long,Long)->Unit) {
        val spec=entry(id);val partial=File(root,spec.file+".partial")
        context.contentResolver.openInputStream(uri)!!.use {source -> partial.outputStream().use {out ->
            val buffer=ByteArray(1024*1024);var total=0L
            while(true) {if(cancel.get())error("cancelled");val n=source.read(buffer);if(n<0)break;total+=n;require(total<=spec.bytes);out.write(buffer,0,n);progress(total,spec.bytes)}
        }}
        finish(spec,partial)
    }
    fun download(id: String,cancel: AtomicBoolean,progress: (Long,Long)->Unit) {
        val spec=entry(id);require(!spec.archive) {"import_extracted_model"}
        val partial=File(root,spec.file+".partial");var offset=if(partial.exists())partial.length()else 0L
        require(offset<=spec.bytes)
        if(offset==spec.bytes) {finish(spec,partial);return}
        val connection=URL(spec.url).openConnection() as HttpURLConnection
        connection.connectTimeout=15000;connection.readTimeout=15000
        if(offset>0)connection.setRequestProperty("Range","bytes=$offset-")
        try {
            val code=connection.responseCode;require(code==200 || code==206) {"model_download_failed"}
            if(code==200)offset=0
            connection.inputStream.use {source -> java.io.FileOutputStream(partial,offset>0).use {out ->
                val buffer=ByteArray(1024*1024);var total=offset
                while(true) {if(cancel.get())error("cancelled");val n=source.read(buffer);if(n<0)break;total+=n;require(total<=spec.bytes);out.write(buffer,0,n);progress(total,spec.bytes)}
                out.fd.sync()
            }}
            finish(spec,partial)
        } finally {connection.disconnect()}
    }
    private fun finish(spec: ModelEntry,partial: File) {
        require(partial.length()==spec.bytes && digest(partial)==spec.sha256) {partial.delete();"model_integrity_failed"}
        java.nio.file.Files.move(partial.toPath(),File(root,spec.file).toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        File(root,"${spec.id}.verified").writeText(spec.sha256)
    }
}
private fun File.readTextOrNull(): String? = if(exists())readText() else null
fun digest(file: File): String {val hash=MessageDigest.getInstance("SHA-256");file.inputStream().use {val b=ByteArray(1024*1024);while(true){val n=it.read(b);if(n<0)break;hash.update(b,0,n)}};return hash.digest().joinToString("") {"%02x".format(it)}}
fun digest(bytes: ByteArray): String=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
