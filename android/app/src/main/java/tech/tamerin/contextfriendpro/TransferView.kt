@file:OptIn(ExperimentalLayoutApi::class)

package tech.tamerin.contextfriendpro

import android.Manifest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

/** Phone-to-phone transfer without the internet: send a meeting, receive one, or send voice profiles. */
enum class TransferMode { SEND, RECEIVE, PROFILES }

@Composable fun TransferView(app: ProApp,mode: TransferMode,fixed: Session?,t: CopyText,act: (suspend ()->Unit)->Unit) {
    val transfers=remember {Transfers(app)}
    val receiveMode=mode==TransferMode.RECEIVE;val profileMode=mode==TransferMode.PROFILES
    var sessionId by remember {mutableStateOf(fixed?.id)}
    var profileIds by remember {mutableStateOf(setOf<String>())}
    var profileConsent by remember {mutableStateOf(false)};var includeText by remember {mutableStateOf(false)};var includeAudio by remember {mutableStateOf(false)};var allowDraft by remember {mutableStateOf(fixed?.approved==false)}
    var input by remember {mutableStateOf("")};var invite by remember {mutableStateOf<Invitation?>(null)};var verified by remember {mutableStateOf(false)}
    var payload by remember {mutableStateOf<BundlePayload?>(null)};var sealed by remember {mutableStateOf<ByteArray?>(null)};var frames by remember {mutableStateOf<List<String>>(emptyList())};var frame by remember {mutableStateOf(0)}
    var scan by remember {mutableStateOf(false)};var collector by remember {mutableStateOf(QrFrames.Collector())}
    var open by remember {mutableStateOf<OpenBundle?>(null)};var acceptance by remember {mutableStateOf<CompletableDeferred<String>?>(null)}
    var receipt by remember {mutableStateOf<String?>(null)};var confirmed by remember {mutableStateOf(false)};var progress by remember {mutableStateOf(0f)}
    var lan by remember {mutableStateOf<LanReceiver?>(null)};var ble by remember {mutableStateOf<BleReceiver?>(null)}
    var devices by remember {mutableStateOf<List<android.bluetooth.BluetoothDevice>>(emptyList())};var busy by remember {mutableStateOf(false)}
    var host by remember {mutableStateOf("")};var port by remember {mutableStateOf("")}
    var bluetoothAction by remember {mutableStateOf<(() -> Unit)?>(null)}
    val bluetoothPermissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {granted ->if(granted.values.all {it})bluetoothAction?.invoke();bluetoothAction=null}
    fun withBluetooth(action: ()->Unit) {bluetoothAction=action;bluetoothPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN))}
    val cameraPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {scan=it}
    val onReceive: suspend (ByteArray)->String={bytes ->
        val item=transfers.open(bytes);val completion=CompletableDeferred<String>()
        withContext(Dispatchers.Main){open=item;profileConsent=false;acceptance=completion}
        withTimeout(600000){completion.await()}
    }
    val owner=LocalLifecycleOwner.current
    val sendScope=rememberCoroutineScope();var sendJob by remember {mutableStateOf<Job?>(null)}
    fun send(block:suspend ()->Unit) {busy=true;sendJob=sendScope.launch {try {withContext(Dispatchers.IO){block()}}catch(e:Exception){if(e !is CancellationException)act {throw e}}finally {busy=false}}}
    DisposableEffect(owner) {val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP){lan?.close();ble?.close();sendJob?.cancel();acceptance?.complete("rejected")}};owner.lifecycle.addObserver(observer);onDispose {owner.lifecycle.removeObserver(observer);sendJob?.cancel()}}
    DisposableEffect(Unit) {onDispose {lan?.close();ble?.close();acceptance?.complete("rejected");invite?.let {if(receiveMode)runCatching {transfers.revoke(it.id)}}}}
    fun receiveQr(text: String) {
        act {
            when {
                text.startsWith("CFI1:") -> {invite=Invitation.decode(text);host=invite!!.host.orEmpty();port=invite!!.port?.toString().orEmpty();verified=false;scan=false}
                text.startsWith("CFQ1:") -> {val bytes=collector.add(text);if(bytes!=null){open=transfers.open(bytes);profileConsent=false;scan=false}}
                text.startsWith("CFR1:") -> {confirmed=transfers.verifyReceipt(text,invite!!,sealed!!);scan=false}
                else -> error("unknown_qr")
            }
        }
    }
    @Composable fun scanner(label: String) {
        if(scan) {
            QrCamera(Modifier.fillMaxWidth().height(300.dp),::receiveQr)
            if(collector.progress().second>0)Text("${collector.progress().first}/${collector.progress().second}")
            OutlinedButton(onClick={scan=false}){Text(t("close"))}
        } else Button(onClick={cameraPermission.launch(Manifest.permission.CAMERA)}){Text(label)}
        OutlinedTextField(value=input,onValueChange={input=it},modifier=Modifier.fillMaxWidth(),label={Text(t("paste"))})
        TextButton(enabled=input.isNotBlank(),onClick={receiveQr(input.trim())}){Text(t("use_pasted"))}
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(receiveMode) {
            Text(t("nearby_receive_steps"),style=MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={act {lan?.close();ble?.close();invite=transfers.invite();collector=QrFrames.Collector();receipt=null}}){Text(t("qr"))}
                OutlinedButton(onClick={withBluetooth {act {lan?.close();ble?.close();invite=transfers.invite();ble=BleReceiver(app,onReceive)}}}){Text(t("ble"))}
                OutlinedButton(onClick={act {ble?.close();lan?.close();val address=Lan.addresses().firstOrNull()?:error("no_lan");val server=LanReceiver(address,onReceive);lan=server;invite=transfers.invite(address.hostAddress,server.port)}}){Text(t("lan"))}
            }
            invite?.let {inv ->
                SectionTitle(t("invitation"));QrImage(inv.encode(),t("invitation"))
                Text(t("fingerprint")+": "+inv.fingerprint(),style=MaterialTheme.typography.bodyMedium);if(inv.host!=null)Text("${inv.host}:${inv.port}",style=MaterialTheme.typography.bodyMedium)
                scanner(t("scan_sender_codes"))
            }
            if(receipt!=null){SectionTitle(t("confirmed"));QrImage(receipt!!,t("confirmed"));Text(t("profile_copied"),style=MaterialTheme.typography.bodyMedium)}
        } else {
            Text(t("nearby_send_steps"),style=MaterialTheme.typography.bodyLarge)
            scanner(t("scan_receiver_code"))
            invite?.let {inv ->
                SectionTitle(t("verify_recipient"));Text(t("fingerprint")+": "+inv.fingerprint(),style=MaterialTheme.typography.titleSmall)
                CheckRow(t("confirm_recipient"),verified){verified=it}
                if(verified)TextButton(onClick={act {transfers.trust(inv,inv.fingerprint())}}){Text(t("trust_recipient"))}
            }
            SectionTitle(t("share_what"))
            if(profileMode) {
                Text(t("profile_share_notice"),style=MaterialTheme.typography.bodyMedium)
                app.vault.profiles().forEach {p->CheckRow(p.name,p.id in profileIds){v->profileIds=if(v)profileIds+p.id else profileIds-p.id;payload=null;sealed=null}}
                CheckRow(t("profile_share_confirm"),profileConsent){profileConsent=it}
            } else {
                if(fixed==null) FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {app.vault.list().forEach {s ->FilterChip(selected=sessionId==s.id,onClick={sessionId=s.id;payload=null;sealed=null},label={Text(s.title)})}}
                else Text(fixed.title,style=MaterialTheme.typography.titleSmall)
                Toggle(t("include_transcript"),includeText){includeText=it;payload=null;sealed=null}
                if(fixed?.audioRetained!=false)Toggle(t("include_audio"),includeAudio){includeAudio=it;payload=null;sealed=null}
                Toggle(t("allow_draft"),allowDraft){allowDraft=it;payload=null;sealed=null}
            }
            OutlinedButton(enabled=if(profileMode)profileConsent && profileIds.isNotEmpty()else sessionId!=null,onClick={act {payload=if(profileMode)transfers.profiles(app.vault.profiles().filter {it.id in profileIds})else transfers.meeting(app.vault.load(sessionId!!),includeText,includeAudio,allowDraft);sealed=null}}){Text(t("preview"))}
            payload?.let {p ->Panel {Text(t("preview"),style=MaterialTheme.typography.titleSmall)
                p.meeting?.let {m ->Text(t(if(m.approved)"chip_reviewed"else "draft"));Text(m.title);Text(m.summary);m.transcript?.forEach {Text(clock(it.startMs)+" · "+it.text)};if(m.audio!=null)Text(t("include_audio")+": ${m.audio.sumOf {it.samples}/16000} s")}
                p.profiles?.forEach {Text(it.name+" · "+it.modelHash.take(12))}
            }}
            Button(enabled=verified && payload!=null && invite!=null,onClick={act {sealed=transfers.seal(payload!!,invite!!);confirmed=false;frames=emptyList()}}){Text(t("prepare"))}
            if(sealed!=null) {
                Text(if(confirmed)t("confirmed")else t("unconfirmed"),style=MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={act {frames=QrFrames.encode(sealed!!);frame=0}}){Text(t("qr"))}
                    OutlinedButton(onClick={withBluetooth {act {devices=Ble.discover(app)}}}){Text(t("ble"))}
                }
                OutlinedTextField(value=host,onValueChange={host=it},label={Text("IP")},modifier=Modifier.fillMaxWidth());OutlinedTextField(value=port,onValueChange={port=it},label={Text("Port")},modifier=Modifier.fillMaxWidth())
                OutlinedButton(enabled=!busy,onClick={send {val inv=invite!!.copy(host=host,port=port.toInt());val r=Lan.send(inv,sealed!!){progress=it};confirmed=transfers.verifyReceipt(r,inv,sealed!!)}}){Text(t("lan"))}
                devices.forEachIndexed {i,device ->OutlinedButton(enabled=!busy,onClick={send {val r=Ble.send(app,device,sealed!!){progress=it};confirmed=transfers.verifyReceipt(r,invite!!,sealed!!)}}){Text("Bluetooth ${i+1}")}}
                if(busy){LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth());TextButton(onClick={sendJob?.cancel()}){Text(t("cancel"))}}
            }
            if(frames.isNotEmpty()){QrImage(frames[frame],t("qr"));Text("${frame+1}/${frames.size}");Row {TextButton(enabled=frame>0,onClick={frame--}){Text(t("previous"))};TextButton(enabled=frame<frames.lastIndex,onClick={frame++}){Text(t("next"))}}}
        }
        open?.let {item ->
            HorizontalDivider();Text(t(if(item.trusted)"trusted"else "unverified"),style=MaterialTheme.typography.titleMedium)
            val p=item.payload;p.meeting?.let {m->Text(t(if(m.approved)"chip_reviewed"else "draft"));Text(m.title,style=MaterialTheme.typography.titleSmall);Text(m.summary);Text(t("transcript")+": ${m.transcript?.size?:0}");Text(t("include_audio")+": ${m.audio?.sumOf {it.samples}?.div(16000)?:0} s")}
            p.profiles?.let {list ->Text(t("profile_share_notice"));list.forEach {Text(it.name)};CheckRow(t("profile_share_confirm"),profileConsent){profileConsent=it}}
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {Button(enabled=p.purpose!="profiles" || profileConsent,onClick={act {val r=transfers.accept(item,profileConsent);acceptance?.complete(r);receipt=r;open=null}}){Text(t("accept"))};TextButton(onClick={acceptance?.complete("rejected");open=null}){Text(t("cancel"))}}
        }
        Spacer(Modifier.height(24.dp))
    }
}
@Composable private fun QrImage(text: String,label: String) {val bitmap=remember(text){qrBitmap(text)};Image(bitmap.asImageBitmap(),label,Modifier.fillMaxWidth().heightIn(max=330.dp).aspectRatio(1f))}
