@file:OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)

package tech.tamerin.contextfriendpro

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.firebase.ai.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.text.DateFormat
import java.util.Date

private val LocalGreen = Color(0xff1f7a4d)
private val CloudBlue = Color(0xff245ca8)

/** Where the current notes ran: driven by the recorded inference source, never by assumption. */
@Composable fun NotesBadge(notes: NotesProvenance, t: CopyText) {
    val cloud = notes.engine == NotesEngine.CLOUD_REDACTED.id
    val color = if (cloud) CloudBlue else LocalGreen
    val calls = notes.onDeviceCalls + notes.cloudCalls
    val text = (if (cloud) "☁ " else "🔒 ") + t("badge_${notes.engine}") + " · $calls " + t("calls") +
        (if (cloud && notes.redactedEntities > 0) " · ${notes.redactedEntities} " + t("replaced_count") else "")
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.10f), border = BorderStroke(1.dp, color),
        modifier = Modifier.padding(vertical = 6.dp).semantics { contentDescription = text }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(text, color = color, style = MaterialTheme.typography.labelLarge)
            Text(notes.model, color = color, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** The template is metadata for the next processing run; changing it does not create a revision. */
@Composable fun TemplatePicker(app: ProApp, s: Session, enabled: Boolean, t: CopyText, act: (suspend () -> Unit) -> Unit) {
    Text(t("template"), style = MaterialTheme.typography.labelMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (MeetingTemplate.SCHOOL + MeetingTemplate.MEDICAL + MeetingTemplate.LEGAL).forEach { template ->
            FilterChip(selected = s.template == template.id, enabled = enabled,
                onClick = { act { app.vault.update(s.id) { it.copy(template = template.id) } } },
                label = { Text(t("template_${template.id}")) })
        }
    }
}

/** "eu" is the Vertex AI multi-region endpoint: data residency and ML processing within EU member states. */
fun regionLabel(t: CopyText) = if (Hybrid.location == "eu") t("region_eu") else Hybrid.location

@Composable fun KeepScreenOn(on: Boolean) {
    // Gemini Nano runs only while the app is the top foreground app (ML Kit Prompt API).
    val view = LocalView.current
    DisposableEffect(on) { view.keepScreenOn = on; onDispose { view.keepScreenOn = false } }
}

/**
 * The privacy panel: the exact outgoing text, what was replaced, words that might still be names,
 * and an explicit consent. Nothing is sent unless the user ticks the box and presses Send.
 */
@Composable fun CloudPanel(app: ProApp, s: Session, engine: Processing, t: CopyText, onClose: (String?) -> Unit) {
    var extra by remember { mutableStateOf(listOf<String>()) }
    var consent by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val config by app.hybridConfig.collectAsState()
    val draft = remember(extra, s.revision) {
        runCatching {
            CloudDraft.build(s, CloudDraft.knownNames(s, app.vault.profiles().map { it.name }, app.vault.preferences().people + config.extraProtectedTerms),
                extra, Policy.chunkChars(NotesEngine.CLOUD_REDACTED, config))
        }
    }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!sending) onClose(null) },
        title = { Text(t("cloud_panel_title")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val d = draft.getOrNull()
                if (d == null) { Text(t("reason_" + (draft.exceptionOrNull()?.message ?: "cloud_failed"))); return@Column }
                Text(t("cloud_note").replace("{region}", regionLabel(t)), style = MaterialTheme.typography.bodySmall)
                Text(t("cloud_replaced") + ": " + if (d.counts.isEmpty()) "0" else d.counts.entries.joinToString { "${it.value} " + t("entity_${it.key.name}") })
                if (d.risk.possibleNames.isNotEmpty()) {
                    Text(t("cloud_possible_names"), style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        d.risk.possibleNames.take(30).forEach { word -> AssistChip(onClick = { extra = extra + word; consent = false }, label = { Text(word) }) }
                    }
                }
                if (s.language != "en") Text(t("cloud_review_language"), style = MaterialTheme.typography.labelMedium)
                Surface(color = Color(0xfff1f3f6), shape = RoundedCornerShape(8.dp)) {
                    SelectionContainer {
                        Text(d.prompt, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState()))
                    }
                }
                Text("${d.prompt.length} " + t("characters"), style = MaterialTheme.typography.labelSmall)
                Toggle(t("cloud_consent"), consent) { consent = it }
                if (sending) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = consent && !sending && draft.isSuccess, onClick = {
                sending = true
                scope.launch { val reason = engine.cloud(draft.getOrThrow(), consent); sending = false; onClose(reason ?: "sent") }
            }) { Text(t("cloud_send")) }
        },
        dismissButton = { TextButton(enabled = !sending, onClick = { onClose(null) }) { Text(t("cancel")) } })
}

@Composable fun AiPrivacyView(app: ProApp, t: CopyText, act: (suspend () -> Unit) -> Unit) {
    val nano by app.nano.collectAsState()
    val config by app.hybridConfig.collectAsState()
    val signedIn by app.signedIn.collectAsState()
    val generation by app.generation.collectAsState()
    var pref by remember(generation) { mutableStateOf(app.vault.preferences()) }
    var audit by remember(generation) { mutableStateOf(Audit.list(app.vault).asReversed()) }
    var download by remember { mutableStateOf<String?>(null) }
    var person by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun save(p: Preferences) { pref = p; act { app.vault.putPrivate("preferences", json.encodeToString(p)) } }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(t("ai_intro"), style = MaterialTheme.typography.bodyMedium) }
        item {
            EngineCard("🔒", t("nano_title"), t("nano_state_${nano.name}"), LocalGreen) {
                if (nano == NanoState.DOWNLOADABLE || nano == NanoState.DOWNLOADING) {
                    TextButton(enabled = download == null, onClick = {
                        download = t("nano_state_DOWNLOADING")
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                Hybrid.downloadNano().collect { status ->
                                    download = when (status) {
                                        is DownloadStatus.DownloadInProgress -> t("nano_state_DOWNLOADING") + " " + (status.totalBytesDownloaded / 1_000_000) + " MB"
                                        is DownloadStatus.DownloadFailed -> t("nano_download_failed")
                                        else -> download
                                    }
                                }
                            }.onFailure { download = t("nano_download_failed") }
                            app.refreshHybrid(); download = null
                        }
                    }) { Text(t("nano_download")) }
                    download?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
                if (nano == NanoState.UNAVAILABLE) Text(t("nano_help"), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { app.refreshHybrid() }) { Text(t("refresh")) }
            }
        }
        item {
            val installed = app.models.installed(pref.summaryModel)
            EngineCard("🔒", t("gemma_title"), t(if (installed) "installed" else "missing"), LocalGreen) {
                Text(t("gemma_note"), style = MaterialTheme.typography.labelSmall)
            }
        }
        item {
            val state = when {
                !Hybrid.configured -> "cloud_state_unconfigured"
                !config.cloudEnabled -> "cloud_state_off"
                !signedIn -> "cloud_state_offline"
                else -> "cloud_state_on"
            }
            EngineCard("☁", t("cloud_title"), t(state), CloudBlue) {
                Text(t("cloud_note").replace("{region}", regionLabel(t)), style = MaterialTheme.typography.labelSmall)
                Text("${config.cloudModel} · ${Hybrid.location} (Vertex AI / Agent Platform)", style = MaterialTheme.typography.labelSmall)
            }
        }
        item {
            Text(t("engine"), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EnginePreference.entries.forEach { e -> FilterChip(selected = pref.engine == e.name, onClick = { save(pref.copy(engine = e.name)) }, label = { Text(t("engine_${e.name}")) }) }
            }
        }
        item {
            Text(t("people"), style = MaterialTheme.typography.titleSmall)
            Text(t("people_note"), style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(value = person, onValueChange = { person = it }, label = { Text(t("name")) }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(enabled = person.isNotBlank(), onClick = { save(pref.copy(people = (pref.people + person.trim()).distinct())); person = "" }) { Text(t("add")) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pref.people.forEach { name -> InputChip(selected = false, onClick = { save(pref.copy(people = pref.people - name)) }, label = { Text("$name ✕") }) }
            }
        }
        item {
            Text(t("audit"), style = MaterialTheme.typography.titleSmall)
            Text(t("audit_note"), style = MaterialTheme.typography.labelSmall)
            Row {
                TextButton(enabled = audit.isNotEmpty(), onClick = {
                    app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, Audit.export(app.vault)), t("audit_export"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(t("audit_export")) }
                TextButton(enabled = audit.isNotEmpty(), onClick = { act { Audit.clear(app.vault) }; audit = emptyList() }) { Text(t("audit_clear")) }
            }
            if (audit.isEmpty()) Text(t("audit_empty"), style = MaterialTheme.typography.bodySmall)
        }
        items(audit.take(100)) { e -> AuditRow(e, t) }
    }
}

@Composable private fun EngineCard(icon: String, title: String, state: String, color: Color, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, color.copy(alpha = 0.4f))) {
        Column(Modifier.padding(14.dp)) {
            Text("$icon $title", style = MaterialTheme.typography.titleSmall, color = color)
            Text(state, style = MaterialTheme.typography.bodyMedium)
            content()
        }
    }
}

@Composable private fun AuditRow(e: AuditEntry, t: CopyText) {
    val color = when (e.source) { "IN_CLOUD" -> CloudBlue; null -> Color(0xff8a5a00); else -> LocalGreen }
    Row(Modifier.fillMaxWidth().background(color.copy(alpha = 0.06f), RoundedCornerShape(6.dp)).padding(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(t("audit_${e.action}") + (e.source?.let { " · $it" } ?: ""), color = color, style = MaterialTheme.typography.labelLarge)
            Text(listOfNotNull(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.at)), e.model,
                if (e.calls > 0) "${e.calls} " + t("calls") else null,
                if (e.redacted.isNotEmpty()) e.redacted.values.sum().toString() + " " + t("replaced_count") else null,
                if (e.charsSent > 0) "${e.charsSent} " + t("characters") else null,
                e.reason, e.meeting?.let { "#$it" }).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
        }
    }
}
