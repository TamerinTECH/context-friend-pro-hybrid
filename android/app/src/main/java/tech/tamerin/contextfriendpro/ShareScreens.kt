@file:OptIn(ExperimentalLayoutApi::class)

package tech.tamerin.contextfriendpro

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SHARE_REASONS = setOf("firebase_not_configured", "not_signed_in", "share_not_found", "share_wrong_key",
    "share_link_invalid", "share_too_large", "nothing_to_share")

private fun reasonOf(e: Throwable) = e.message?.takeIf { it in SHARE_REASONS } ?: "cloud_failed"

// ---- Share summary: one entry point, what first, then how ---------------------------------------------------------

@Composable fun ShareScreen(ui: Ui, s: Session, onBack: () -> Unit, onNearby: () -> Unit) {
    val t = ui.t
    val app = ui.app
    var linkOpen by remember { mutableStateOf(false) }
    val outgoing = (if (s.approved) "" else t("draft") + "\n") + s.summaryText()
    Column(Modifier.fillMaxSize()) {
        PageTop(t("share_summary"), ui, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PagePadding).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(t("share_what"))
            Panel {
                if (s.approved) StatusChip(t("chip_reviewed"), Brand.SuccessContainer, Brand.Success)
                else StatusChip(t("chip_draft"), Brand.WarningContainer, Brand.Warning)
                Text(ui.title(s), style = MaterialTheme.typography.titleSmall)
                Text(s.summaryText(), style = contentStyle(MaterialTheme.typography.bodyMedium), maxLines = 8, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(t("share_summary_only"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
            }
            if (!s.approved) Note(t("share_draft_note"), Icons.Default.Warning, Brand.WarningContainer, Brand.Warning)
            SectionTitle(t("share_how"))
            if (Hybrid.configured && !s.received) {
                MethodCard(Glyphs.Link, t("method_link"), t("method_link_text").replace("{days}", CloudShare.TTL_DAYS.toString())) { linkOpen = !linkOpen }
                if (linkOpen) LinkFlow(ui, s)
            }
            if (!s.received) MethodCard(Glyphs.Phone, t("method_nearby"), t("method_nearby_text"), onNearby)
            MethodCard(Icons.Default.Share, t("method_app"), t("method_app_text")) {
                app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, outgoing), t("method_app"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable private fun MethodCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, onClick: () -> Unit) {
    Panel(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Brand.Primary)
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
    }
}

/** Encrypted link: created only on a clear button press; success shown only after the server confirmed the write. */
@Composable private fun LinkFlow(ui: Ui, s: Session) {
    val t = ui.t
    val app = ui.app
    var busy by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Panel(color = Brand.PrimaryContainer, border = Brand.PrimaryContainer) {
        val current = link
        if (current == null) {
            Text(t("link_explain"), style = MaterialTheme.typography.bodyMedium, color = Brand.OnPrimaryContainer)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            failure?.let { Text(t("reason_$it"), color = Brand.Error, style = MaterialTheme.typography.bodyMedium) }
            PrimaryButton(t(if (s.approved) "cloud_share_create" else "link_create_draft"), enabled = !busy, onClick = {
                busy = true; failure = null
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { CloudShare.publish(app, s).encode() } }
                        .onSuccess { link = it; app.changed() }
                        .onFailure { failure = reasonOf(it) }
                    busy = false
                }
            })
        } else {
            Text(t("cloud_share_ready").replace("{days}", CloudShare.TTL_DAYS.toString()), style = MaterialTheme.typography.bodyMedium, color = Brand.OnPrimaryContainer)
            val bitmap = remember(current) { qrBitmap(current) }
            Image(bitmap.asImageBitmap(), t("method_link"), Modifier.fillMaxWidth().aspectRatio(1f))
            PrimaryButton(t("cloud_share_send_link"), {
                app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, current), t("method_link"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, icon = Icons.Default.Share)
            SecondaryButton(t(if (copied) "link_copied" else "link_copy"), {
                app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(t("method_link"), current)); copied = true
            })
            Text(t("link_expiry_note"), style = MaterialTheme.typography.bodyMedium, color = Brand.OnPrimaryContainer)
        }
    }
}

// ---- Review what you send (cloud) ---------------------------------------------------------------------------------

@Composable fun CloudReviewScreen(ui: Ui, s: Session, engine: Processing, onDone: () -> Unit) {
    val t = ui.t
    val app = ui.app
    val config by app.hybridConfig.collectAsState()
    var extra by remember { mutableStateOf(listOf<String>()) }
    var sending by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val draft = remember(extra, s.revision) {
        runCatching {
            CloudDraft.build(s, CloudDraft.knownNames(s, app.vault.profiles().map { it.name }, app.vault.preferences().people + config.extraProtectedTerms),
                extra, Policy.chunkChars(NotesEngine.CLOUD_REDACTED, config))
        }
    }
    androidx.activity.compose.BackHandler { if (!sending) onDone() }
    Column(Modifier.fillMaxSize()) {
        PageTop(t("cloud_review_title"), ui, { if (!sending) onDone() })
        val d = draft.getOrNull()
        when {
            !config.cloudEnabled -> Column(Modifier.padding(PagePadding)) { Note(t("reason_cloud_disabled"), InfoIcon) }
            d == null -> Column(Modifier.padding(PagePadding)) { Note(t("reason_" + (draft.exceptionOrNull()?.message ?: "cloud_failed")), Icons.Default.Warning, Brand.ErrorContainer, Brand.Error) }
            else -> {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = PagePadding, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Text(t("cloud_review_intro"), style = MaterialTheme.typography.bodyLarge) }
                    item { SectionTitle(t("cloud_review_text")) }
                    items(d.rows) { row -> Panel { Text(highlight(listOfNotNull(row.speaker?.let { "$it:" }, row.text).joinToString(" ")), style = contentStyle(), modifier = Modifier.fillMaxWidth()) } }
                    item {
                        Text(t("cloud_replaced") + ": " + if (d.counts.isEmpty()) "0" else d.counts.entries.joinToString { "${it.value} " + t("entity_${it.key.name}") },
                            style = MaterialTheme.typography.titleSmall)
                    }
                    if (d.risk.possibleNames.isNotEmpty()) item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(t("cloud_possible_names"), style = MaterialTheme.typography.bodyMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                d.risk.possibleNames.take(30).forEach { word -> AssistChip(onClick = { extra = extra + word }, label = { Text(word) }) }
                            }
                        }
                    }
                    if (s.language != "en") item { Note(t("cloud_review_language"), Icons.Default.Warning, Brand.WarningContainer, Brand.Warning) }
                    item {
                        Panel {
                            Text(t("cloud_where_title"), style = MaterialTheme.typography.titleSmall)
                            Text(t("cloud_where").replace("{region}", regionLabel(t)), style = MaterialTheme.typography.bodyMedium)
                            Text(t("cloud_what"), style = MaterialTheme.typography.bodyMedium)
                            Text(t("cloud_detection_note"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
                        }
                    }
                    item {
                        TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(t("cloud_exact_request"))
                            Icon(if (details) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                        }
                        if (details) Surface(color = Brand.SurfaceContainer, shape = MaterialTheme.shapes.small) {
                            Column(Modifier.padding(10.dp)) {
                                SelectionContainer { Text(d.prompt, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                                Text("${d.prompt.length} " + t("characters"), style = MaterialTheme.typography.labelSmall, color = Brand.Muted)
                            }
                        }
                    }
                }
                Column(Modifier.navigationBarsPadding().padding(PagePadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sending) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(t("cloud_sending"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted) }
                    PrimaryButton(t("cloud_send_once"), enabled = !sending, icon = Glyphs.Cloud, onClick = {
                        sending = true
                        // Pressing this button is the consent for exactly this reviewed text, once.
                        scope.launch { val reason = engine.cloud(d, consent = true); sending = false; result = reason ?: "sent" }
                    })
                }
            }
        }
    }
    result?.let { r ->
        AlertDialog(onDismissRequest = { result = null; if (r == "sent") onDone() },
            text = { Text(if (r == "sent") t("cloud_sent") else t("reason_$r")) },
            confirmButton = { TextButton(onClick = { result = null; if (r == "sent") onDone() }) { Text(t("close")) } })
    }
}

/** Placeholders such as [PERSON_1] stand out, so the reader sees what was replaced. */
private fun highlight(text: String) = buildAnnotatedString {
    var last = 0
    Regex("""\[[A-Z]+_\d+]""").findAll(text).forEach { m ->
        append(text.substring(last, m.range.first))
        withStyle(SpanStyle(background = Brand.Accent.copy(alpha = 0.45f), fontWeight = FontWeight.SemiBold)) { append(m.value) }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

// ---- Receive ------------------------------------------------------------------------------------------------------

@Composable fun ReceiveScreen(ui: Ui, onOpened: (String) -> Unit, onNearby: () -> Unit) {
    val t = ui.t
    val app = ui.app
    var text by remember { mutableStateOf("") }
    var scan by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun open(value: String) {
        val trimmed = value.trim()
        if (!trimmed.startsWith("CFS1:")) { failure = if (trimmed.startsWith("CF")) "code_is_nearby" else "share_link_invalid"; return }
        busy = true; failure = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { CloudShare.open(app, ShareLink.parse(trimmed)) } }
                .onSuccess { app.changed(); onOpened(it) }
                .onFailure { failure = reasonOf(it) }
            busy = false
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> scan = granted; if (!granted) failure = "camera_denied" }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(PagePadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Heading(t("receive_heading"))
        Text(t("receive_intro"), style = MaterialTheme.typography.bodyLarge, color = Brand.Muted)
        if (Hybrid.configured) {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Glyphs.Qr, null, tint = Brand.Primary)
                    Text(t("receive_scan_title"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp))
                }
                Text(t("receive_scan_text"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
                if (scan) {
                    QrCamera(Modifier.fillMaxWidth().aspectRatio(1f)) { value -> if (!busy && value.startsWith("CF")) { scan = false; text = value; open(value) } }
                    SecondaryButton(t("cancel"), { scan = false })
                } else PrimaryButton(t("receive_scan_button"), { failure = null; camera.launch(Manifest.permission.CAMERA) }, icon = Glyphs.Qr)
            }
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Glyphs.Link, null, tint = Brand.Primary)
                    Text(t("receive_paste_title"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp))
                }
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(t("receive_paste_label")) }, modifier = Modifier.fillMaxWidth())
                SecondaryButton(t("receive_open"), { open(text) }, enabled = text.isNotBlank() && !busy)
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        failure?.let { Note(t("reason_$it"), Icons.Default.Warning, Brand.ErrorContainer, Brand.Error) }
        Panel(onClick = onNearby) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyphs.Phone, null, tint = Brand.Primary)
                Text(t("receive_nearby_title"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp))
            }
            Text(t("receive_nearby_text"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
        }
        Note(t("receive_no_models"), Icons.Default.Info)
    }
}

// ---- Nearby phone ---------------------------------------------------------------------------------------------------

@Composable fun NearbyScreen(ui: Ui, mode: TransferMode, session: Session?, onBack: () -> Unit) {
    val title = when (mode) { TransferMode.SEND -> "method_nearby"; TransferMode.RECEIVE -> "receive_nearby_title"; TransferMode.PROFILES -> "voice_send" }
    Column(Modifier.fillMaxSize()) {
        PageTop(ui.t(title), ui, onBack)
        Box(Modifier.weight(1f).padding(horizontal = PagePadding).navigationBarsPadding()) { TransferView(ui.app, mode, session, ui.t, ui.act) }
    }
}
