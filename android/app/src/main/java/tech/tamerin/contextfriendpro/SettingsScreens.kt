@file:OptIn(ExperimentalLayoutApi::class, com.google.firebase.ai.type.PublicPreviewAPI::class)

package tech.tamerin.contextfriendpro

import android.app.KeyguardManager
import android.content.Intent
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.ai.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.util.concurrent.atomic.AtomicBoolean

private fun savePrefs(ui: Ui, p: Preferences, onPref: (Preferences) -> Unit) {
    onPref(p); ui.act { ui.app.vault.putPrivate("preferences", json.encodeToString(p)) }
}

// ---- Settings root ------------------------------------------------------------------------------------------------

@Composable fun SettingsScreen(ui: Ui, pref: Preferences, onPage: (String) -> Unit, onPref: (Preferences) -> Unit) {
    val t = ui.t
    val app = ui.app
    val nano by app.nano.collectAsState()
    val config by app.hybridConfig.collectAsState()
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    var lockError by remember { mutableStateOf(false) }
    val ready = speechReady(app, pref) && localNotesReady(pref.language, pref.engine, nano, app.models.installed(pref.summaryModel))
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(PagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Heading(t("nav_settings"))
        SectionTitle(t("group_phone"))
        Panel(modifier = Modifier) {
            NavRow(Glyphs.Chip, t("page_ai"), t(if (ready) "ai_ready" else "ai_needs_setup")) { onPage("ai") }
            HorizontalDivider(color = Brand.Divider)
            NavRow(Glyphs.Storage, t("page_storage"), t(if (pref.deleteAudioAfterApproval) "storage_delete_after" else "storage_keep")) { onPage("storage") }
            HorizontalDivider(color = Brand.Divider)
            NavRow(Glyphs.Translate, t("language"), languageName(pref.language)) { onPage("language") }
        }
        SectionTitle(t("group_privacy"))
        Panel {
            NavRow(Glyphs.History, t("page_activity"), t("audit_note")) { onPage("activity") }
            HorizontalDivider(color = Brand.Divider)
            NavRow(Glyphs.Cloud, t("page_cloud"), t(cloudState(config))) { onPage("cloud") }
            HorizontalDivider(color = Brand.Divider)
            Toggle(t("lock"), pref.appLock) { value ->
                if (!value || app.getSystemService(KeyguardManager::class.java).isDeviceSecure) savePrefs(ui, pref.copy(appLock = value), onPref) else lockError = true
            }
            if (lockError) Text(t("lock_needs_screen_lock"), color = Brand.Error, style = MaterialTheme.typography.bodyMedium)
            Toggle(t("screen_capture"), pref.allowScreenCapture) {
                val p = pref.copy(allowScreenCapture = it); activity?.let { a -> applyScreenCapture(a, it) }; savePrefs(ui, p, onPref)
            }
            Text(t("screen_capture_note"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
        }
        SectionTitle(t("group_advanced"))
        Panel {
            NavRow(Glyphs.Tune, t("page_models"), t("page_models_sub")) { onPage("models") }
            HorizontalDivider(color = Brand.Divider)
            NavRow(Icons.Default.Person, t("page_voice"), t(if (pref.matching) "voice_on" else "voice_off")) { onPage("voice") }
            if (BuildConfig.DEBUG) {
                HorizontalDivider(color = Brand.Divider)
                TextButton(onClick = { ui.act { ExampleMeeting.create(app) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text(t("debug_example")) }
            }
        }
        Text(t("loss_notice"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
        Text(t("disclaimer"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
    }
}

private fun cloudState(config: HybridConfig) = when {
    !Hybrid.configured -> "cloud_state_unconfigured"
    !config.cloudEnabled -> "cloud_state_off"
    else -> "cloud_state_on"
}

@Composable fun SettingsPage(ui: Ui, name: String, pref: Preferences, onBack: () -> Unit, onPage: (String) -> Unit, onPref: (Preferences) -> Unit,
                             onNearbyProfiles: () -> Unit) {
    val title = when (name) {
        "ai" -> "page_ai"; "activity" -> "page_activity"; "storage" -> "page_storage"; "cloud" -> "page_cloud"; "language" -> "language"
        "models" -> "page_models"; "voice" -> "page_voice"; else -> "prepare_title_short"
    }
    Column(Modifier.fillMaxSize()) {
        PageTop(ui.t(title), ui, onBack)
        Box(Modifier.weight(1f).navigationBarsPadding()) {
            when (name) {
                "ai" -> OnDeviceAiPage(ui, pref, onPage, onPref)
                "activity" -> ActivityPage(ui)
                "storage" -> StoragePage(ui, pref, onPref)
                "cloud" -> CloudPage(ui, pref, onPref)
                "language" -> LanguagePage(ui, pref, onPref)
                "models" -> ModelsPage(ui)
                "voice" -> VoicePage(ui, pref, onPref, onNearbyProfiles)
                else -> PreparePage(ui, pref, onPage)
            }
        }
    }
}

@Composable private fun PageColumn(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = PagePadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)

// ---- On-device AI -------------------------------------------------------------------------------------------------

@Composable private fun OnDeviceAiPage(ui: Ui, pref: Preferences, onPage: (String) -> Unit, onPref: (Preferences) -> Unit) {
    val t = ui.t
    val app = ui.app
    val nano by app.nano.collectAsState()
    var download by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val gemma = app.models.installed(pref.summaryModel)
    PageColumn {
        Text(t("ai_intro"), style = MaterialTheme.typography.bodyLarge)
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Glyphs.Chip, null, tint = Brand.Success); Text(t("nano_title"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 10.dp)) }
            ReadyLine(ui, nano == NanoState.AVAILABLE, t("nano_state_${nano.name}"))
            if (nano == NanoState.DOWNLOADABLE || nano == NanoState.DOWNLOADING) {
                SecondaryButton(t("nano_download"), enabled = download == null, onClick = {
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
                })
                download?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            if (nano == NanoState.UNAVAILABLE) Text(t("nano_help"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
            TextButton(onClick = { app.refreshHybrid() }) { Text(t("refresh")) }
        }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Glyphs.Chip, null, tint = Brand.Success); Text(t("gemma_title"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 10.dp)) }
            ReadyLine(ui, gemma, t(if (gemma) "ready" else "missing"))
            Text(t("gemma_note"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
        }
        Panel {
            Text(t("speech_title"), style = MaterialTheme.typography.titleSmall)
            ReadyLine(ui, app.models.installed(pref.asrModel), t("speech_to_text"))
            ReadyLine(ui, app.models.installed("pyannote-segmentation3") && app.models.installed("titanet-small"), t("speaker_separation"))
        }
        SectionTitle(t("engine"))
        Panel {
            EnginePreference.entries.forEach { e ->
                RadioRow(t("engine_${e.name}"), pref.engine == e.name) { savePrefs(ui, pref.copy(engine = e.name), onPref) }
            }
            Text(t("engine_note"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
        }
        PrimaryButton(t("prepare_title_short"), { onPage("prepare") })
        SecondaryButton(t("page_models"), { onPage("models") })
    }
}

@Composable private fun ReadyLine(ui: Ui, ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Default.Check else Icons.Default.Info, null, tint = if (ok) Brand.Success else Brand.Warning, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

// ---- Get ready for offline notes ----------------------------------------------------------------------------------

@Composable private fun PreparePage(ui: Ui, pref: Preferences, onPage: (String) -> Unit) {
    val t = ui.t
    val app = ui.app
    val nano by app.nano.collectAsState()
    val generation by app.generation.collectAsState()
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var failed by remember { mutableStateOf(false) }
    val cancel = remember { AtomicBoolean(false) }
    val scope = rememberCoroutineScope()
    // Notes: Gemini Nano when this phone has it and the language is English; otherwise the ContextFriend engine.
    val nanoCovers = localNotesReady(pref.language, "GEMINI_NANO", nano, false) || (nano == NanoState.DOWNLOADABLE && pref.language == "en")
    val needed = remember(generation, nanoCovers, pref) {
        listOf(pref.asrModel, "pyannote-segmentation3", "titanet-small") + if (nanoCovers) emptyList() else listOf(pref.summaryModel)
    }
    val missing = needed.filterNot { app.models.installed(it) }
    val bytes = missing.sumOf { app.models.entry(it).bytes }
    val free = remember(generation) { runCatching { StatFs(app.models.root.path).availableBytes }.getOrDefault(0L) }
    PageColumn {
        Heading(t("prepare_heading"))
        Text(t("prepare_intro"), style = MaterialTheme.typography.bodyLarge, color = Brand.Muted)
        Panel(color = Brand.PrimaryContainer, border = Brand.PrimaryContainer) {
            Text(t("prepare_recommended"), style = MaterialTheme.typography.titleSmall, color = Brand.OnPrimaryContainer)
            ReadyLine(ui, app.models.installed(pref.asrModel), t("speech_to_text"))
            ReadyLine(ui, app.models.installed("pyannote-segmentation3") && app.models.installed("titanet-small"), t("speaker_separation"))
            ReadyLine(ui, localNotesReady(pref.language, pref.engine, nano, app.models.installed(pref.summaryModel)),
                t(if (nanoCovers) "summary_writing_nano" else "summary_writing_gemma"))
        }
        if (missing.isEmpty() && localNotesReady(pref.language, pref.engine, nano, app.models.installed(pref.summaryModel))) {
            Note(t("prepare_done"), Icons.Default.Check, Brand.SuccessContainer, Brand.Success)
        } else {
            if (missing.isNotEmpty()) {
                Text(ui.tf("prepare_size", "size" to mb(bytes), "free" to mb(free)), style = MaterialTheme.typography.bodyLarge)
                if (free in 1 until bytes + 200_000_000L) Note(t("prepare_low_storage"), Icons.Default.Warning, Brand.WarningContainer, Brand.Warning)
                busy?.let { id ->
                    Text(ui.tf("prepare_downloading", "name" to ui.ltr(id)), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { cancel.set(true) }) { Text(t("cancel")) }
                }
                if (failed) Note(t("prepare_failed"), Icons.Default.Warning, Brand.ErrorContainer, Brand.Error)
                PrimaryButton(t("prepare_download"), enabled = busy == null, icon = Glyphs.Download, onClick = {
                    failed = false; cancel.set(false)
                    scope.launch(Dispatchers.IO) {
                        try {
                            for (id in missing) {
                                if (app.models.entry(id).archive) continue
                                busy = id; progress = 0f
                                app.models.download(id, cancel) { a, b -> progress = a.toFloat() / b }
                            }
                        } catch (_: Exception) { failed = true } finally { busy = null; app.changed() }
                    }
                })
                Text(t("prepare_wifi"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
            }
            if (nanoCovers && nano == NanoState.DOWNLOADABLE) Note(t("prepare_nano_hint"), Icons.Default.Info)
        }
        TextButton(onClick = { onPage("models") }, modifier = Modifier.heightIn(min = 48.dp)) { Text(t("technical_options")) }
        Note(t("receive_no_models"), Icons.Default.Info)
    }
}

private fun mb(bytes: Long) = "%,d MB".format(bytes / 1_000_000)

// ---- Privacy activity ---------------------------------------------------------------------------------------------

@Composable private fun ActivityPage(ui: Ui) {
    val t = ui.t
    val app = ui.app
    val generation by app.generation.collectAsState()
    var audit by remember(generation) { mutableStateOf(Audit.list(app.vault).asReversed()) }
    var confirmClear by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = PagePadding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(t("activity_intro"), style = MaterialTheme.typography.bodyLarge) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = audit.isNotEmpty(), onClick = {
                    app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, Audit.export(app.vault)), t("audit_export"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(t("audit_export")) }
                OutlinedButton(enabled = audit.isNotEmpty(), onClick = { confirmClear = true }) { Text(t("audit_clear")) }
            }
        }
        if (audit.isEmpty()) item { Text(t("audit_empty"), style = MaterialTheme.typography.bodyLarge, color = Brand.Muted) }
        items(audit.take(200)) { e -> ActivityRow(ui, e) }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, text = { Text(t("audit_clear_confirm")) },
        confirmButton = { TextButton(onClick = { confirmClear = false; ui.act { Audit.clear(app.vault) }; audit = emptyList() }) { Text(t("audit_clear")) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(t("cancel")) } })
}

@Composable private fun ActivityRow(ui: Ui, e: AuditEntry) {
    val t = ui.t
    var open by remember { mutableStateOf(false) }
    val (where, container, content) = when (e.source) {
        "IN_CLOUD" -> Triple(t("where_cloud"), Brand.PrimaryContainer, Brand.OnPrimaryContainer)
        "ENCRYPTED_STORE" -> Triple(t("where_encrypted_store"), Brand.PrimaryContainer, Brand.OnPrimaryContainer)
        // Only a stop before the request may say "nothing sent". A failed request may have reached the server.
        null -> when (e.action) {
            "cloud_blocked" -> Triple(t("where_nowhere"), Brand.WarningContainer, Brand.Warning)
            "policy_violation" -> Triple(t("where_discarded"), Brand.WarningContainer, Brand.Warning)
            else -> Triple(t("where_failed"), Brand.ErrorContainer, Brand.Error)
        }
        else -> Triple(t("where_device"), Brand.SuccessContainer, Brand.Success)
    }
    Panel(onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t("audit_${e.action}"), style = MaterialTheme.typography.titleSmall)
                Text(ui.dateTime(e.at), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
            }
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, t("details"), tint = Brand.Muted)
        }
        StatusChip(where, container, content)
        if (open) {
            val rows = listOfNotNull(
                e.model?.let { t("detail_model") to it },
                e.calls.takeIf { it > 0 }?.let { t("detail_requests") to it.toString() },
                e.redacted.takeIf { it.isNotEmpty() }?.let { t("detail_replaced") to it.values.sum().toString() },
                e.charsSent.takeIf { it > 0 }?.let { t("detail_characters") to it.toString() },
                e.reason?.let { t("detail_reason") to it },
                e.meeting?.let { t("detail_meeting") to "#$it" },
            )
            rows.forEach { (k, v) ->
                Row { Text(k, style = MaterialTheme.typography.bodyMedium, color = Brand.Muted, modifier = Modifier.width(120.dp)); Text(ui.ltr(v), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

// ---- Storage, cloud, language ---------------------------------------------------------------------------------------

@Composable private fun StoragePage(ui: Ui, pref: Preferences, onPref: (Preferences) -> Unit) {
    val t = ui.t
    PageColumn {
        Text(t("storage_intro"), style = MaterialTheme.typography.bodyLarge)
        Panel {
            Toggle(t("retention"), pref.deleteAudioAfterApproval) { savePrefs(ui, pref.copy(deleteAudioAfterApproval = it), onPref) }
            Text(t("retention_note"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
        }
        Note(t("loss_notice"), Icons.Default.Info)
    }
}

@Composable private fun CloudPage(ui: Ui, pref: Preferences, onPref: (Preferences) -> Unit) {
    val t = ui.t
    val config by ui.app.hybridConfig.collectAsState()
    var person by remember { mutableStateOf("") }
    PageColumn {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Glyphs.Cloud, null, tint = Brand.Primary); Text(t("cloud_title"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 10.dp)) }
            Text(t(cloudState(config)), style = MaterialTheme.typography.bodyLarge)
            Text(t("cloud_note").replace("{region}", regionLabel(t)), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
            Text(ui.ltr("${config.cloudModel} · ${Hybrid.location} · Vertex AI"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
        }
        SectionTitle(t("people"))
        Text(t("people_note"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = person, onValueChange = { person = it }, label = { Text(t("name")) }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(enabled = person.isNotBlank(), onClick = { savePrefs(ui, pref.copy(people = (pref.people + person.trim()).distinct()), onPref); person = "" }) { Text(t("add")) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pref.people.forEach { name -> InputChip(selected = false, onClick = { savePrefs(ui, pref.copy(people = pref.people - name), onPref) }, label = { Text("$name ✕") }) }
        }
    }
}

@Composable private fun LanguagePage(ui: Ui, pref: Preferences, onPref: (Preferences) -> Unit) {
    PageColumn {
        Panel {
            listOf("en", "de", "he").forEach { l ->
                RadioRow(languageName(l), pref.language == l) { savePrefs(ui, pref.copy(language = l), onPref) }
            }
        }
    }
}

// ---- Models (technical) and voice recognition -----------------------------------------------------------------------

@Composable private fun ModelsPage(ui: Ui) {
    val t = ui.t
    val app = ui.app
    val generation by app.generation.collectAsState()
    var selection by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    val cancel = remember { AtomicBoolean(false) }
    val pref = remember(generation) { app.vault.preferences() }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = selection
        if (uri != null && id != null) { busy = id; cancel.set(false); ui.act { try { app.models.install(id, uri, cancel) { a, b -> progress = a.toFloat() / b } } finally { busy = null } } }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = PagePadding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(t("setup_notice"), style = MaterialTheme.typography.bodyLarge) }
        items(app.models.manifest.models, key = { it.id }) { m ->
            val installed = remember(generation, busy) { app.models.installed(m.id) }
            Panel {
                Text(ui.ltr(m.id), style = MaterialTheme.typography.titleSmall)
                Text(ui.ltr("${m.bytes / 1_000_000} MB · ${m.license}"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
                ReadyLine(ui, installed, t(if (installed) "installed" else "missing"))
                if (m.task == "asr" && pref.asrModel == m.id) StatusChip(t("in_use"), Brand.PrimaryContainer, Brand.OnPrimaryContainer)
                if (busy == m.id) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { cancel.set(true) }) { Text(t("cancel")) }
                } else FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = busy == null, onClick = { selection = m.id; import.launch(arrayOf("*/*")) }) { Text(t("install")) }
                    if (!m.archive && !installed) TextButton(enabled = busy == null, onClick = {
                        busy = m.id; cancel.set(false); ui.act { try { app.models.download(m.id, cancel) { a, b -> progress = a.toFloat() / b } } finally { busy = null } }
                    }) { Text(t("download")) }
                    if (installed) TextButton(enabled = app.processing.value == null, onClick = { ui.act { app.models.remove(m.id) } }) { Text(t("delete")) }
                    if (m.task == "asr" && pref.asrModel != m.id) TextButton(onClick = {
                        ui.act { app.vault.putPrivate("preferences", json.encodeToString(app.vault.preferences().copy(asrModel = m.id))) }
                    }) { Text(t("choose")) }
                }
            }
        }
    }
}

@Composable private fun VoicePage(ui: Ui, pref: Preferences, onPref: (Preferences) -> Unit, onNearbyProfiles: () -> Unit) {
    val t = ui.t
    val app = ui.app
    val generation by app.generation.collectAsState()
    val profiles = remember(generation) { app.vault.profiles() }
    var deleting by remember { mutableStateOf<VoiceProfile?>(null) }
    PageColumn {
        Text(t("voice_intro"), style = MaterialTheme.typography.bodyLarge)
        Panel {
            Toggle(t("matching"), pref.matching) { savePrefs(ui, pref.copy(matching = it), onPref) }
            Text(t("profile_notice"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
        }
        SectionTitle(t("voice_profiles_saved"))
        if (profiles.isEmpty()) Text(t("no_profiles"), style = MaterialTheme.typography.bodyLarge, color = Brand.Muted)
        profiles.forEach { p ->
            var name by remember(p.id, p.name) { mutableStateOf(p.name) }
            Panel {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(t("name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Toggle(t("profile_enabled"), p.enabled) { v -> ui.act { app.vault.putProfiles(app.vault.profiles().map { if (it.id == p.id) it.copy(enabled = v) else it }) } }
                FlowRow {
                    TextButton(enabled = name.isNotBlank() && name != p.name, onClick = { ui.act { app.vault.putProfiles(app.vault.profiles().map { if (it.id == p.id) it.copy(name = name.trim()) else it }) } }) { Text(t("save")) }
                    TextButton(onClick = { deleting = p }) { Text(t("delete_profile"), color = Brand.Error) }
                }
            }
        }
        if (profiles.isNotEmpty()) SecondaryButton(t("voice_send"), onNearbyProfiles)
        Text(t("voice_experimental"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
    }
    deleting?.let { p ->
        AlertDialog(onDismissRequest = { deleting = null }, text = { Text(t("confirm_delete_profile")) },
            confirmButton = { TextButton(onClick = {
                deleting = null
                ui.act {
                    app.vault.putProfiles(app.vault.profiles().filterNot { it.id == p.id })
                    app.vault.list().forEach { s -> app.vault.update(s.id) { it.copy(suggestedProfiles = it.suggestedProfiles.filterValues { v -> v != p.id }) } }
                }
            }) { Text(t("delete"), color = Brand.Error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(t("cancel")) } })
    }
}

@Suppress("unused") private val LockIcon = Icons.Default.Lock
