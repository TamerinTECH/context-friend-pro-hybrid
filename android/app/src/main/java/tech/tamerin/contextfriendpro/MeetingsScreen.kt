package tech.tamerin.contextfriendpro

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---- Meetings ----------------------------------------------------------------------------------------------------

@Composable fun MeetingsScreen(ui: Ui, sessions: List<Session>, recording: RecordingStatus, processing: String?, pref: Preferences,
                               onNew: () -> Unit, onOpen: (String) -> Unit, onRecording: () -> Unit, onPrepare: () -> Unit) {
    val nano by ui.app.nano.collectAsState()
    val ready = speechReady(ui.app, pref) && localNotesReady(pref.language, pref.engine, nano, ui.app.models.installed(pref.summaryModel))
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(PagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(30.dp)
                Spacer(Modifier.width(10.dp))
                Text(ui.t("app_name"), style = MaterialTheme.typography.titleMedium)
            }
        }
        item { Heading(ui.t("home_heading"), Modifier.padding(top = 4.dp)) }
        if (recording.sessionId != null) item {
            Panel(color = Brand.Navy, border = Brand.Navy, onClick = onRecording) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(Brand.RecordRed, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(ui.t(if (recording.state == "paused") "paused" else "recording") + " · " + clock(recording.elapsedMs),
                        color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(ui.t("back_to_recording"), color = Brand.Accent, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (sessions.isEmpty()) item { Hero(ui) }
        if (!ready) item {
            Panel(color = Brand.WarningContainer, border = Brand.WarningContainer, onClick = onPrepare) {
                Text(ui.t("prepare_banner_title"), style = MaterialTheme.typography.titleSmall, color = Brand.Warning)
                Text(ui.t("prepare_banner_text"), style = MaterialTheme.typography.bodyMedium, color = Brand.Warning)
            }
        }
        item {
            PrimaryButton(ui.t("new_recording"), onNew, enabled = recording.sessionId == null && processing == null, icon = Glyphs.Mic)
        }
        if (sessions.isNotEmpty()) {
            item { SectionTitle(ui.t("recent")) }
            items(sessions, key = { it.id }) { s -> MeetingRow(ui, s, s.phase(processing, recording.sessionId)) { onOpen(s.id) } }
        } else item {
            Text(ui.t("home_empty"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
        }
    }
}

@Composable private fun Hero(ui: Ui) {
    Surface(color = Brand.Navy, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ui.t("home_hero"), color = Color.White, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Icon(Glyphs.Waves, null, tint = Brand.Accent, modifier = Modifier.size(56.dp))
        }
    }
}

@Composable fun MeetingRow(ui: Ui, s: Session, phase: Phase, onClick: () -> Unit) {
    Panel(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (s.received) Glyphs.Download else Glyphs.Mic, null, tint = Brand.Primary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(ui.title(s), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(ui.meta(s), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
            }
            PhaseChip(ui, phase)
        }
    }
}

@Composable fun PhaseChip(ui: Ui, phase: Phase) {
    val (key, colors) = when (phase) {
        Phase.RECORDING -> "chip_recording" to (Brand.ErrorContainer to Brand.Error)
        Phase.PROCESSING -> "chip_processing" to (Brand.PrimaryContainer to Brand.OnPrimaryContainer)
        Phase.NOT_PROCESSED -> "chip_not_processed" to (Brand.SurfaceContainer to Brand.Navy)
        Phase.NO_SPEECH -> "chip_no_speech" to (Brand.SurfaceContainer to Brand.Navy)
        Phase.FAILED, Phase.INTERRUPTED -> "chip_attention" to (Brand.ErrorContainer to Brand.Error)
        Phase.DRAFT -> "chip_draft" to (Brand.WarningContainer to Brand.Warning)
        Phase.REVIEWED -> "chip_reviewed" to (Brand.SuccessContainer to Brand.Success)
        Phase.RECEIVED -> "chip_received" to (Brand.PrimaryContainer to Brand.OnPrimaryContainer)
    }
    StatusChip(ui.t(key), colors.first, colors.second)
}

// ---- Before you begin ---------------------------------------------------------------------------------------------

@Composable fun SetupScreen(ui: Ui, pref: Preferences, onBack: () -> Unit, onStarted: () -> Unit) {
    val app = ui.app
    var title by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("general") }
    var spoken by remember { mutableStateOf(pref.language) }
    var informed by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    fun start() {
        starting = true
        ui.act {
            val custom = title.isNotBlank()
            val s = Session(title = if (custom) title.trim().take(200) else ui.dateTime(System.currentTimeMillis()), customTitle = custom,
                language = spoken, state = "recording", template = template)
            val key = app.vault.create(s)
            app.preparedRecording = s to key
            withContext(Dispatchers.Main) {
                ContextCompat.startForegroundService(app, Intent(app, RecordingService::class.java).setAction("start"))
                onStarted()
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) { denied = false; start() } else denied = true
    }
    Column(Modifier.fillMaxSize()) {
        PageTop("", ui, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PagePadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Heading(ui.t("setup_heading"))
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(ui.t("setup_title_label")) }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
            ChoiceRow(ui.t("setup_format"), ui.t("template_$template"),
                options = (MeetingTemplate.SCHOOL + MeetingTemplate.MEDICAL + MeetingTemplate.LEGAL).map { it.id to ui.t("template_${it.id}") }) { template = it }
            ChoiceRow(ui.t("setup_spoken"), languageName(spoken), options = listOf("en", "de", "he").map { it to languageName(it) }) { spoken = it }
            Note(ui.t(if (pref.deleteAudioAfterApproval) "setup_policy_delete" else "setup_policy_keep"), Glyphs.Shield,
                title = ui.t("setup_policy_title"))
            CheckRow(ui.t("setup_informed"), informed) { informed = it }
            Text(ui.t("record_notice"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
            if (denied) {
                Note(ui.t("mic_denied"), Icons.Default.Warning, Brand.ErrorContainer, Brand.Error)
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(ui.t("open_app_settings")) }
            }
        }
        Column(Modifier.navigationBarsPadding().padding(PagePadding)) {
            PrimaryButton(ui.t("start"), {
                permission.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
            }, enabled = informed && !starting && app.recording.value.sessionId == null, icon = Glyphs.Mic)
        }
    }
}

fun languageName(code: String) = when (code) { "de" -> "Deutsch"; "he" -> "עברית"; else -> "English" }

/** A labelled value that opens a menu of options: compact, and it never overflows sideways. */
@Composable fun ChoiceRow(label: String, value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Panel(onClick = { open = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.titleSmall, color = Brand.Primary)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(id) }) }
        }
    }
}

// ---- Recording ----------------------------------------------------------------------------------------------------

@Composable fun RecordingScreen(ui: Ui, recording: RecordingStatus, sessions: List<Session>, onLeave: () -> Unit) {
    val app = ui.app
    val history = remember { mutableStateListOf<Float>() }
    LaunchedEffect(recording.elapsedMs, recording.level) {
        if (recording.state == "recording") { history.add(recording.level); while (history.size > 60) history.removeAt(0) }
    }
    val session = sessions.firstOrNull { it.id == recording.sessionId }
    val paused = recording.state == "paused"
    val hearing = history.takeLast(15).any { it > 0.02f }
    val message = when {
        recording.sessionId == null -> ui.t("rec_saving")
        paused -> ui.t("rec_paused_note")
        hearing -> ui.t("rec_hearing")
        recording.elapsedMs > 5000 -> ui.t("rec_silent")
        else -> ui.t("rec_listening")
    }
    Box(Modifier.fillMaxSize().background(Brand.Navy)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp)) {
            IconButton(onClick = onLeave) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, ui.t("back_to_meetings"), tint = Color.White)
            }
            Text(session?.let { ui.title(it) } ?: ui.t("new_recording"), color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Box(Modifier.size(10.dp).background(if (paused) Brand.RecordingSecondary else Brand.RecordRed, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(ui.t(if (paused) "paused" else "recording"), color = Brand.RecordingSecondary, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.weight(1f))
            Text(clock(recording.elapsedMs), color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = ui.tf("rec_elapsed", "time" to clock(recording.elapsedMs)) })
            LevelBars(history, Modifier.fillMaxWidth().height(96.dp).padding(vertical = 12.dp))
            Text(message, color = Brand.RecordingSecondary, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
            Spacer(Modifier.height(16.dp))
            Surface(color = Color.White.copy(alpha = 0.10f), contentColor = Color.White, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Glyphs.Shield, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(ui.t("rec_on_device"), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                OutlinedButton(onClick = { app.startService(Intent(app, RecordingService::class.java).setAction(if (paused) "resume" else "pause")) },
                    enabled = recording.sessionId != null, modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    shape = MaterialTheme.shapes.medium) {
                    Icon(if (paused) Glyphs.Mic else Glyphs.Pause, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text(ui.t(if (paused) "resume" else "pause"), style = MaterialTheme.typography.labelLarge)
                }
                Button(onClick = { app.startService(Intent(app, RecordingService::class.java).setAction("stop")) },
                    enabled = recording.sessionId != null, modifier = Modifier.weight(1.3f).heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Accent, contentColor = Brand.Navy), shape = MaterialTheme.shapes.medium) {
                    Icon(Glyphs.Stop, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text(ui.t("finish_recording"), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

// ---- Info icon helper --------------------------------------------------------------------------------------------
val InfoIcon get() = Icons.Default.Info
