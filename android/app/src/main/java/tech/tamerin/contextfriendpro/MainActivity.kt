package tech.tamerin.contextfriendpro

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.serialization.*
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

typealias CopyText=(String)->String
class MainActivity: ComponentActivity() {
    private val app get()=application as ProApp
    private val locked=mutableStateOf(false)
    private var unlockInProgress=false
    private val unlock=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {result ->unlockInProgress=false;locked.value=result.resultCode!=RESULT_OK}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);applyScreenCapture(this,runCatching {app.vault.preferences().allowScreenCapture}.getOrDefault(false))
        locked.value=app.vault.preferences().appLock
        setContent {if(locked.value) Button(onClick={unlockApp()}) {Text("ContextFriend Pro · Unlock / Entsperren / פתיחה")} else ProInterface(app)}
    }
    override fun onStop() {if(runCatching {app.vault.preferences().appLock}.getOrDefault(true) && !unlockInProgress)locked.value=true;super.onStop()}
    private fun unlockApp() {val manager=getSystemService(KeyguardManager::class.java);val intent=manager.createConfirmDeviceCredentialIntent("ContextFriend Pro",null);if(intent!=null){unlockInProgress=true;unlock.launch(intent)}}
}
@Composable fun ProInterface(app: ProApp) {
    val generation by app.generation.collectAsState();val recording by app.recording.collectAsState();val processing by app.processing.collectAsState()
    var pref by remember {mutableStateOf(app.vault.preferences())};val locale=pref.language
    val dictionaries=remember {json.decodeFromString<Map<String,Map<String,String>>>(app.assets.open("translations.json").bufferedReader().readText())}
    val t: CopyText={key -> dictionaries[locale]?.get(key) ?: dictionaries.getValue("en")[key] ?: key}
    var sessions by remember {mutableStateOf<List<Session>>(emptyList())};var selected by remember {mutableStateOf<String?>(null)}
    var tab by remember {mutableStateOf("sessions")};var error by remember {mutableStateOf(false)};var startDialog by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope();val engine=remember {Processing(app)};val activity=androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val act: (suspend ()->Unit)->Unit={block -> scope.launch {try {withContext(Dispatchers.IO) {block()};app.changed()}catch(_:Exception){error=true}}}
    // A new change cancels the list still loading. That is not a failure, and runCatching would report it as one.
    LaunchedEffect(generation) {try {sessions=withContext(Dispatchers.IO){app.vault.list()}}catch(e: CancellationException){throw e}catch(_: Exception){error=true};pref=app.vault.preferences()}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {granted ->if(granted[Manifest.permission.RECORD_AUDIO]==true)startDialog=true else error=true}
    val colorScheme=lightColorScheme(primary=Color(0xff245ca8),secondary=Color(0xff9a7400),background=Color(0xfff4f8fc),surface=Color.White)
    CompositionLocalProvider(LocalLayoutDirection provides if(locale=="he")LayoutDirection.Rtl else LayoutDirection.Ltr) {
        MaterialTheme(colorScheme=colorScheme) {
            Surface(Modifier.fillMaxSize()) {Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal=16.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical=12.dp),horizontalArrangement=Arrangement.SpaceBetween) {Text(t("app_name"),style=MaterialTheme.typography.titleLarge);Text(t("local"),style=MaterialTheme.typography.labelSmall)}
                if(recording.sessionId!=null) {
                    Text(t(if(recording.state=="paused")"paused"else "recording")+" · "+time(recording.elapsedMs))
                    LinearProgressIndicator(progress={recording.level},modifier=Modifier.fillMaxWidth())
                    Row {Button(onClick={app.startService(Intent(app,RecordingService::class.java).setAction(if(recording.state=="paused")"resume"else "pause"))}){Text(t(if(recording.state=="paused")"resume"else "pause"))};Spacer(Modifier.width(12.dp));Button(onClick={app.startService(Intent(app,RecordingService::class.java).setAction("stop"))}){Text(t("stop"))}}
                }
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {listOf("sessions","ai","models","profiles","transfer","settings").forEach {name ->FilterChip(selected=tab==name,onClick={tab=name;selected=null},label={Text(t(name))})}}
                Box(Modifier.weight(1f)) {
                    when(tab) {
                        "sessions" -> {
                            val current=sessions.firstOrNull {it.id==selected}
                            if(current==null) Column {
                                Button(enabled=recording.sessionId==null && processing==null,onClick={permission.launch(arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS))}) {Text(t("start"))}
                                if(sessions.isEmpty())Text(t("no_sessions"),Modifier.padding(vertical=24.dp))
                                LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {items(sessions,key={it.id}) {s ->Card(onClick={selected=s.id},modifier=Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp)) {Text(s.title,style=MaterialTheme.typography.titleMedium);Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(s.createdAt)));Text(if(s.approved)t("approved")else t("state_${s.state}"))}}}}
                            } else SessionView(app,current,processing==current.id,recording.sessionId!=null,t,act,engine,onBack={selected=null},onProcess={scope.launch {engine.run(current.id)}},onCancel={engine.cancel()},onShare={tab="transfer"})
                        }
                        "ai" -> AiPrivacyView(app,t,act)
                        "models" -> ModelView(app,t,act)
                        "profiles" -> ProfilesView(app,t,act)
                        "transfer" -> TransferView(app,sessions.firstOrNull {it.id==selected},t,act)
                        "settings" -> Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                            Text(t("language"));Row {listOf("en" to "English","de" to "Deutsch","he" to "עברית").forEach {(l,label)->TextButton(onClick={pref=pref.copy(language=l);act {app.vault.putPrivate("preferences",json.encodeToString(pref))}}){Text(label)}}}
                            Toggle(t("matching"),pref.matching) {pref=pref.copy(matching=it);act {app.vault.putPrivate("preferences",json.encodeToString(pref))}}
                            Toggle(t("lock"),pref.appLock) {value ->if(!value || app.getSystemService(KeyguardManager::class.java).isDeviceSecure){pref=pref.copy(appLock=value);act {app.vault.putPrivate("preferences",json.encodeToString(pref))}}else error=true}
                            Toggle(t("retention"),pref.deleteAudioAfterApproval) {pref=pref.copy(deleteAudioAfterApproval=it);act {app.vault.putPrivate("preferences",json.encodeToString(pref))}}
                            Toggle(t("screen_capture"),pref.allowScreenCapture) {pref=pref.copy(allowScreenCapture=it);activity?.let {a->applyScreenCapture(a,pref.allowScreenCapture)};act {app.vault.putPrivate("preferences",json.encodeToString(pref))}}
                            Text(t("screen_capture_note"),style=MaterialTheme.typography.labelSmall)
                            Text(t("loss_notice"));Text(t("profile_notice"));Text(t("disclaimer"))
                        }
                    }
                }
            }}
            if(error)AlertDialog(onDismissRequest={error=false},text={Text(t("error"))},confirmButton={TextButton(onClick={error=false}){Text(t("close"))}})
            if(startDialog) {var consent by remember {mutableStateOf(false)}
                AlertDialog(onDismissRequest={startDialog=false},title={Text(t("start"))},text={Column {Text(t("record_notice"));Toggle(t("acknowledge"),consent){consent=it}}},
                    confirmButton={TextButton(enabled=consent,onClick={startDialog=false;act {val s=Session(title=DateFormat.getDateTimeInstance().format(Date()),language=pref.language,state="recording");val key=app.vault.create(s);app.preparedRecording=s to key;withContext(Dispatchers.Main){ContextCompat.startForegroundService(app,Intent(app,RecordingService::class.java).setAction("start"))}}}){Text(t("start"))}},dismissButton={TextButton(onClick={startDialog=false}){Text(t("cancel"))}})
            }
        }
    }
}
/** FLAG_SECURE unless the user allowed screen capture in Settings. */
fun applyScreenCapture(activity: android.app.Activity,allowed: Boolean) {if(allowed)activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)else activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)}
@Composable fun Toggle(label: String,value: Boolean,onChange: (Boolean)->Unit) {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label,Modifier.weight(1f).padding(top=12.dp));Switch(checked=value,onCheckedChange=onChange)}}
fun time(ms: Long)="%02d:%02d".format(ms/60000,(ms/1000)%60)

@Composable fun SessionView(app: ProApp,s: Session,busy: Boolean,recording: Boolean,t: CopyText,act: (suspend ()->Unit)->Unit,engine: Processing,onBack: ()->Unit,onProcess: ()->Unit,onCancel: ()->Unit,onShare: ()->Unit) {
    var section by remember {mutableStateOf("summary")};var edit by remember {mutableStateOf(false)};var draft by remember(s.revision){mutableStateOf(s.summaryText())}
    var deletion by remember {mutableStateOf<String?>(null)};var export by remember {mutableStateOf(false)};var enroll by remember {mutableStateOf<String?>(null)}
    var playing by remember {mutableStateOf(false)};val stopPlay=remember {AtomicBoolean(false)}
    var cloudPanel by remember {mutableStateOf(false)};var cloudResult by remember {mutableStateOf<String?>(null)}
    val listState=rememberLazyListState();var focus by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(section,focus) {if(section=="transcript" && focus!=null){val index=s.transcript.indexOfFirst {it.id==focus};if(index>=0)listState.scrollToItem(index)}}
    DisposableEffect(Unit){onDispose {stopPlay.set(true)}}
    Column {
        TextButton(onClick=onBack){Text(t("back"))};Text(s.title,style=MaterialTheme.typography.headlineSmall)
        Text(if(s.approved)t("approved")else t("state_${s.state}"));Text(t("disclaimer"),style=MaterialTheme.typography.labelSmall)
        KeepScreenOn(busy)
        s.notes?.let {NotesBadge(it,t)}
        if(!busy)s.stages.values.firstOrNull {it.state=="failed" && it.error!=null}?.error?.let {code->Text(t("reason_$code"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        if(!s.received && s.audioRetained)TemplatePicker(app,s,!busy && !recording,t,act)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            if(busy)TextButton(onClick=onCancel){Text(t("cancel_processing"))}else if(!s.received && s.audioRetained)TextButton(enabled=!recording,onClick=onProcess){Text(t("process"))}
            if(!s.received && s.transcript.isNotEmpty() && Hybrid.configured)TextButton(enabled=!busy && !recording,onClick={cloudPanel=true}){Text(t("cloud_improve"))}
            if(s.audioRetained)TextButton(onClick={if(playing){stopPlay.set(true)}else {playing=true;stopPlay.set(false);act {try{play(app,s,stopPlay)}finally {playing=false}}}}){Text(t(if(playing)"stop"else "play"))}
            TextButton(enabled=!busy && !recording,onClick={deletion="session"}){Text(t("delete"))}
            if(s.audioRetained)TextButton(enabled=!busy && !recording,onClick={deletion="audio"}){Text(t("delete_audio"))}
        }
        if(!s.audioRetained)Text(t("audio_deleted"))
        if(s.gaps.isNotEmpty())Text(t("gaps")+": "+s.gaps.joinToString {time(it.startMs)+"–"+(it.endMs?.let(::time)?:"?")})
        Row {listOf("summary","transcript","profiles").forEach {name->FilterChip(selected=section==name,onClick={section=name},label={Text(t(name))})}}
        LazyColumn(Modifier.weight(1f),state=listState,verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(section=="summary") {
                item {if(edit)OutlinedTextField(value=draft,onValueChange={draft=it},modifier=Modifier.fillMaxWidth().heightIn(min=250.dp),label={Text(t("summary"))})else Text(s.summaryText().ifBlank {t("empty_summary")})}
                item {Row(Modifier.horizontalScroll(rememberScrollState())) {
                    if(edit) {TextButton(onClick={act {app.vault.update(s.id){it.edited().copy(editedSummary=draft)}};edit=false}){Text(t("save"))};TextButton(onClick={edit=false;draft=s.summaryText()}){Text(t("cancel"))}}
                    else if(s.summaryText().isNotBlank()) {TextButton(enabled=!busy && !recording,onClick={edit=true}){Text(t("edit"))};TextButton(enabled=!busy && !recording && !s.approved,onClick={act {app.vault.update(s.id){require(it.revision==s.revision);it.copy(approvedRevision=s.revision)};if(app.vault.preferences().deleteAudioAfterApproval)app.vault.deleteAudio(s.id)}}){Text(t("approve"))};TextButton(onClick=onShare){Text(t("send"))};TextButton(onClick={export=true}){Text(t("external_export"))}}
                }}
                s.summary?.let {summary -> items(summary.paragraphs+summary.decisions+summary.uncertainties+summary.actions.map {Statement(listOfNotNull(it.text,it.owner,it.due).joinToString(" · "),it.sources)}){st ->Column {Text(st.text);st.sources.forEach {source->TextButton(onClick={focus=source;section="transcript"}){Text(t("source")+" · "+(s.transcript.find {it.id==source}?.startMs?.let(::time)?:source.take(8)))}}}}}
            } else if(section=="transcript") {
                items(s.transcript,key={it.id}) {line ->var value by remember(line.id,line.correction){mutableStateOf(line.text)};var editing by remember {mutableStateOf(false)};var assigned by remember(line.id,line.speaker){mutableStateOf(line.speaker)}
                    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)) {Text(time(line.startMs)+" – "+time(line.endMs)+" · "+(s.speakers[line.speaker]?:line.speaker?:t("unknown")));if(line.uncertain)Text(t("unknown"),style=MaterialTheme.typography.labelSmall)
                        if(editing) {OutlinedTextField(value=value,onValueChange={value=it},modifier=Modifier.fillMaxWidth());Row(Modifier.horizontalScroll(rememberScrollState())) {FilterChip(selected=assigned==null,onClick={assigned=null},label={Text(t("unknown"))});(s.speakerVectors.keys+s.speakers.keys).distinct().forEach {speaker->FilterChip(selected=assigned==speaker,onClick={assigned=speaker},label={Text(s.speakers[speaker]?:speaker)})}};Row {TextButton(onClick={act{app.vault.update(s.id){old->old.edited().copy(transcript=old.transcript.map {if(it.id==line.id)it.copy(correction=value,speaker=assigned)else it})}};editing=false}){Text(t("save"))};TextButton(onClick={editing=false}){Text(t("cancel"))}}}
                        else {Text(line.text);TextButton(enabled=!busy && !recording,onClick={editing=true}){Text(t("edit"))}}
                        if(s.audioRetained)TextButton(enabled=!playing && !recording,onClick={playing=true;stopPlay.set(false);act {try {play(app,s,stopPlay,line.startMs,line.endMs)}finally {playing=false}}}){Text(t("play"))}
                    }}
                }
            } else {
                item {Text(t("profile_notice"))}
                items(s.speakerVectors.keys.toList()) {speaker ->var label by remember {mutableStateOf(s.speakers[speaker]?:speaker)}
                    Column {OutlinedTextField(value=label,onValueChange={label=it},label={Text(t("rename"))});TextButton(onClick={act {app.vault.update(s.id){it.edited().copy(speakers=it.speakers+(speaker to label))}}}){Text(t("save"))}
                        s.suggestedProfiles[speaker]?.let {id ->val profile=app.vault.profiles().find {it.id==id};if(profile!=null)TextButton(onClick={act {app.vault.update(s.id){it.edited().copy(speakers=it.speakers+(speaker to profile.name),suggestedProfiles=it.suggestedProfiles-speaker)}}}){Text(t("confirm_match")+": "+profile.name)}}
                        TextButton(onClick={enroll=speaker}){Text(t("enroll"))}
                    }
                }
            }
        }
    }
    if(cloudPanel)CloudPanel(app,s,engine,t) {result->cloudPanel=false;cloudResult=result}
    cloudResult?.let {result->AlertDialog(onDismissRequest={cloudResult=null},text={Text(if(result=="sent")t("cloud_sent")else t("reason_$result"))},confirmButton={TextButton(onClick={cloudResult=null}){Text(t("close"))}})}
    if(deletion!=null)AlertDialog(onDismissRequest={deletion=null},text={Text(t("confirm_delete"))},confirmButton={TextButton(onClick={val what=deletion;deletion=null;stopPlay.set(true);act {if(what=="audio")app.vault.deleteAudio(s.id)else app.vault.delete(s.id)}}){Text(t("delete"))}},dismissButton={TextButton(onClick={deletion=null}){Text(t("cancel"))}})
    if(export)AlertDialog(onDismissRequest={export=false},text={Text(t("external_notice"))},confirmButton={TextButton(onClick={export=false;app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,(if(s.approved)""else t("draft")+"\n")+s.summaryText()),t("external_export")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}){Text(t("external_export"))}},dismissButton={TextButton(onClick={export=false}){Text(t("cancel"))}})
    if(enroll!=null) {var consent by remember {mutableStateOf(false)};var name by remember {mutableStateOf("")}
        AlertDialog(onDismissRequest={enroll=null},title={Text(t("enroll"))},text={Column {Text(t("profile_notice"));OutlinedTextField(value=name,onValueChange={name=it},label={Text(t("name"))});Toggle(t("profile_consent"),consent){consent=it}}},
            confirmButton={TextButton(enabled=consent && name.isNotBlank(),onClick={val speaker=enroll!!;enroll=null;act {require(s.transcript.filter {it.speaker==speaker}.sumOf {it.endMs-it.startMs}>=10000);val p=VoiceProfile(name=name,embedding=s.speakerVectors.getValue(speaker),modelHash=app.models.entry("titanet-small").sha256);app.vault.putProfiles(app.vault.profiles()+p)}}){Text(t("save"))}},dismissButton={TextButton(onClick={enroll=null}){Text(t("cancel"))}})
    }
}
private fun play(app: ProApp,s: Session,cancel: AtomicBoolean,from:Long=0,until:Long?=null) {
    val track=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
        .setBufferSizeInBytes(64000).setTransferMode(AudioTrack.MODE_STREAM).build()
    try {AudioReader(app.vault,s.id).use {audio ->track.play();var start=from;val end=until?:maxOf(s.durationMs,audio.index.maxOfOrNull {it.startMs+it.samples/16}?:0)
        while(start<end && !cancel.get()){val samples=audio.window(start,minOf(1000,end-start));track.write(samples,0,samples.size,AudioTrack.WRITE_BLOCKING);samples.fill(0f);start+=1000}}}
    finally {track.stop();track.release()}
}

@Composable fun ModelView(app: ProApp,t: CopyText,act: (suspend ()->Unit)->Unit) {
    var selection by remember {mutableStateOf<String?>(null)};var busy by remember {mutableStateOf<String?>(null)};var progress by remember {mutableStateOf(0f)}
    val cancel=remember {AtomicBoolean(false)}
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->val id=selection;if(uri!=null && id!=null){busy=id;cancel.set(false);act {try {app.models.install(id,uri,cancel){a,b->progress=a.toFloat()/b}}finally {busy=null}}}}
    LazyColumn(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {Text(t("setup_notice"))}
        items(app.models.manifest.models,key={it.id}) {m ->Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp)) {
            Text(m.id,style=MaterialTheme.typography.titleMedium);Text("${m.bytes/1024/1024} MiB · ${m.license}");Text(t(if(app.models.installed(m.id))"installed"else "missing"))
            if(busy==m.id) {LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth());TextButton(onClick={cancel.set(true)}){Text(t("cancel"))}}
            else Row {TextButton(enabled=busy==null,onClick={selection=m.id;import.launch(arrayOf("*/*"))}){Text(t("install"))};if(!m.archive)TextButton(enabled=busy==null,onClick={busy=m.id;cancel.set(false);act {try {app.models.download(m.id,cancel){a,b->progress=a.toFloat()/b}}finally {busy=null}}}){Text(t("download"))};if(app.models.installed(m.id))TextButton(enabled=app.processing.value==null,onClick={act {app.models.remove(m.id)}}){Text(t("delete"))}}
            if(m.task=="asr")TextButton(onClick={act {app.vault.putPrivate("preferences",json.encodeToString(app.vault.preferences().copy(asrModel=m.id)))}}){Text(t("choose"))}
        }}}
    }
}
@Composable fun ProfilesView(app: ProApp,t: CopyText,act: (suspend ()->Unit)->Unit) {
    val profiles=app.vault.profiles();var deleting by remember {mutableStateOf<VoiceProfile?>(null)}
    LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {item {Text(t("profile_notice"));if(profiles.isEmpty())Text(t("no_profiles"))}
        items(profiles,key={it.id}) {p ->var name by remember(p.name){mutableStateOf(p.name)};Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)) {
            OutlinedTextField(value=name,onValueChange={name=it},label={Text(t("name"))});TextButton(onClick={act {app.vault.putProfiles(app.vault.profiles().map {if(it.id==p.id)it.copy(name=name)else it})}}){Text(t("save"))}
            Toggle(t("matching"),p.enabled){v->act {app.vault.putProfiles(app.vault.profiles().map {if(it.id==p.id)it.copy(enabled=v)else it})}}
            TextButton(onClick={deleting=p}){Text(t("delete_profile"))}
        }}}
    }
    if(deleting!=null)AlertDialog(onDismissRequest={deleting=null},text={Text(t("confirm_delete"))},confirmButton={TextButton(onClick={val p=deleting!!;deleting=null;act {app.vault.putProfiles(app.vault.profiles().filterNot {it.id==p.id});app.vault.list().forEach {s->app.vault.update(s.id){it.copy(suggestedProfiles=it.suggestedProfiles.filterValues {v->v!=p.id})}}}}){Text(t("delete"))}},dismissButton={TextButton(onClick={deleting=null}){Text(t("cancel"))}})
}
