package tech.tamerin.contextfriendpro

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SHARE_REASONS = setOf("firebase_not_configured", "not_signed_in", "share_not_found", "share_wrong_key",
    "share_link_invalid", "share_too_large", "nothing_to_share")

private fun reasonOf(e: Throwable) = e.message?.takeIf { it in SHARE_REASONS } ?: "cloud_failed"

/** Share the notes through the cloud, end-to-end encrypted. Shows exactly what is shared before anything leaves. */
@Composable fun CloudShareDialog(app: ProApp, s: Session, t: CopyText, onClose: () -> Unit) {
    var consent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val days = CloudShare.TTL_DAYS.toString()
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(t("cloud_share")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val current = link
                if (current == null) {
                    Text(t("cloud_share_note").replace("{days}", days), style = MaterialTheme.typography.bodySmall)
                    Surface(color = Color(0xfff1f3f6), shape = RoundedCornerShape(8.dp)) {
                        Text((if (s.approved) "" else t("draft") + "\n") + s.summaryText(), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
                    }
                    Toggle(t("cloud_share_consent"), consent) { consent = it }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    failure?.let { Text(t("reason_$it"), color = MaterialTheme.colorScheme.error) }
                } else {
                    Text(t("cloud_share_ready").replace("{days}", days), style = MaterialTheme.typography.bodySmall)
                    val bitmap = remember(current) { qrBitmap(current) }
                    Image(bitmap.asImageBitmap(), t("cloud_share"), Modifier.fillMaxWidth().aspectRatio(1f))
                    TextButton(onClick = {
                        app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, current), t("cloud_share"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text(t("cloud_share_send_link")) }
                }
            }
        },
        confirmButton = {
            if (link == null) TextButton(enabled = consent && !busy, onClick = {
                busy = true; failure = null
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { CloudShare.publish(app, s).encode() } }
                        .onSuccess { link = it; app.changed() }
                        .onFailure { failure = reasonOf(it) }
                    busy = false
                }
            }) { Text(t("cloud_share_create")) }
            else TextButton(onClick = onClose) { Text(t("close")) }
        },
        dismissButton = { if (link == null) TextButton(enabled = !busy, onClick = onClose) { Text(t("cancel")) } })
}

/** Open a received cloud link: paste the text or scan its QR. */
@Composable fun OpenCloudLinkDialog(app: ProApp, t: CopyText, onOpened: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var scan by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun open(value: String) {
        busy = true; failure = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { CloudShare.open(app, ShareLink.parse(value)) } }
                .onSuccess { app.changed(); onOpened(it) }
                .onFailure { failure = reasonOf(it) }
            busy = false
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(t("cloud_open")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("cloud_open_note"), style = MaterialTheme.typography.bodySmall)
                if (scan) QrCamera(Modifier.fillMaxWidth().aspectRatio(1f)) { value ->
                    if (value.startsWith("CFS1:") && !busy) { scan = false; text = value; open(value) }
                } else TextButton(onClick = { scan = true }) { Text(t("scan")) }
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("CFS1:…") }, modifier = Modifier.fillMaxWidth())
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                failure?.let { Text(t("reason_$it"), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank() && !busy, onClick = { open(text) }) { Text(t("cloud_open")) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(t("cancel")) } })
}
