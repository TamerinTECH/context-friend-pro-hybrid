package tech.tamerin.contextfriendpro

import android.media.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalLayoutApi::class)
@Composable fun MeetingScreen(ui: Ui, s: Session, pref: Preferences, processing: String?, recordingId: String?, engine: Processing,
                              onBack: () -> Unit, onEdit: () -> Unit, onShare: () -> Unit, onCloud: () -> Unit, onPrepare: () -> Unit,
                              onNewRecording: () -> Unit, onDeleted: () -> Unit) {
    val app = ui.app
    val t = ui.t
    val phase = s.phase(processing, recordingId)
    val busy = phase == Phase.PROCESSING
    val locked = busy || recordingId != null
    val nano by app.nano.collectAsState()
    val config by app.hybridConfig.collectAsState()
    val ready = speechReady(app, pref) && localNotesReady(s.language, pref.engine, nano, app.models.installed(pref.summaryModel))
    val canProcess = ready && s.audioRetained && !s.received && recordingId == null && processing == null
    val cloudEligible = !s.received && Hybrid.configured && config.cloudEnabled && s.hasSpeech()
    var tab by remember(s.id) { mutableStateOf("summary") }
    var focus by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    val stopPlay = remember { AtomicBoolean(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    DisposableEffect(Unit) { onDispose { stopPlay.set(true) } }
    KeepScreenOn(busy)

    // Through the background service, so the notes keep coming with the screen off. In the rare case Android
    // refuses the service, the work runs here while the app is open.
    fun process() { if (!app.startProcessing(s.id)) scope.launch { engine.run(s.id) } }
    fun regenerate() {
        scope.launch {
            withContext(Dispatchers.IO) { app.vault.update(s.id) { it.copy(stages = it.stages - "summary") } }
            app.changed(); if (!app.startProcessing(s.id)) engine.run(s.id)
        }
    }
    fun play(from: Long = 0, until: Long? = null) {
        if (playing) { stopPlay.set(true); return }
        playing = true; stopPlay.set(false)
        ui.act { try { playAudio(app, s, stopPlay, from, until) } finally { playing = false } }
    }

    val speakers = (s.speakerVectors.keys + s.speakers.keys + s.transcript.mapNotNull { it.speaker }).distinct()
    val content = phase == Phase.DRAFT || phase == Phase.REVIEWED || phase == Phase.RECEIVED
    val showTranscriptTab = content && s.transcript.isNotEmpty()
    LaunchedEffect(tab, focus) {
        val target = focus ?: return@LaunchedEffect
        if (tab != "transcript") return@LaunchedEffect
        val index = s.transcript.indexOfFirst { it.id == target }
        if (index >= 0) listState.animateScrollToItem(2 + (if (speakers.isNotEmpty()) 1 else 0) + index)
    }

    Column(Modifier.fillMaxSize()) {
        PageTop(ui.title(s), ui, onBack) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, t("more_options")) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    fun item(key: String, enabled: Boolean = true, color: Color = Color.Unspecified, action: () -> Unit) =
                        @Composable { DropdownMenuItem(text = { Text(t(key), color = if (enabled) color else Color.Unspecified) }, enabled = enabled,
                            onClick = { menu = false; action() }) }
                    item("rename", !busy) { dialog = "rename" }()
                    if (!s.received) DropdownMenuItem(text = { Text(t("menu_format") + ": " + t("template_${s.template}")) }, enabled = !locked,
                        onClick = { menu = false; dialog = "format" })
                    if (!s.received && s.audioRetained && s.hasSpeech() && content) item("menu_regenerate", canProcess) { regenerate() }()
                    if (cloudEligible && content) item("menu_cloud", !locked) { onCloud() }()
                    s.previousNotes()?.let { previous ->
                        DropdownMenuItem(text = { Text(t("menu_previous") + " · " + originLabel(ui, previous.notes)) }, enabled = !locked,
                            onClick = { menu = false; ui.act { app.vault.update(s.id) { it.restorePrevious() } } })
                    }
                    if (s.audioRetained && s.durationMs > 0 && !s.received) item(if (playing) "stop_playback" else "play_recording", recordingId == null) { play() }()
                    HorizontalDivider()
                    if (s.audioRetained && !s.received) item("delete_audio", !locked) { dialog = "delete_audio" }()
                    item("delete_meeting", !locked, Brand.Error) { dialog = "delete" }()
                }
            }
        }
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(start = PagePadding, end = PagePadding, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "meta") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ui.meta(s), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OriginChip(ui, s)
                        if (content) PhaseChip(ui, phase)
                    }
                }
            }
            item(key = "tabs") {
                if (showTranscriptTab) {
                    TabRow(selectedTabIndex = if (tab == "summary") 0 else 1, containerColor = Brand.Background, contentColor = Brand.Primary) {
                        Tab(selected = tab == "summary", onClick = { tab = "summary" }, text = { Text(t("summary"), style = MaterialTheme.typography.titleSmall) })
                        Tab(selected = tab == "transcript", onClick = { tab = "transcript" }, text = { Text(t("transcript"), style = MaterialTheme.typography.titleSmall) })
                    }
                }
            }
            when {
                phase == Phase.PROCESSING -> item { ProcessingPanel(ui, s, onBack, onCancel = { engine.cancel() }) }
                phase == Phase.WAITING -> item { ProcessingPanel(ui, s, onBack, waiting = true, canContinue = ready && recordingId == null, onContinue = ::process) }
                phase == Phase.RECORDING -> item { Note(t("recording_in_progress"), Glyphs.Mic) }
                phase == Phase.NO_SPEECH -> item { NoSpeechPanel(ui, s, onNewRecording, onBack) }
                phase == Phase.NOT_PROCESSED || phase == Phase.FAILED || phase == Phase.INTERRUPTED -> item {
                    NotesMissingPanel(ui, s, phase, ready, canProcess, onProcess = ::process, onPrepare = onPrepare)
                }
                tab == "summary" || !showTranscriptTab -> {
                    item { SummaryContent(ui, s) { source -> focus = source; tab = "transcript" } }
                    if (s.gaps.isNotEmpty()) item {
                        Note(t("gaps") + ": " + s.gaps.joinToString { clock(it.startMs) + "–" + (it.endMs?.let(::clock) ?: "?") }, Icons.Default.Warning,
                            Brand.WarningContainer, Brand.Warning)
                    }
                    item { Note(t("check_before_sharing"), Icons.Default.Info) }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            when (phase) {
                                Phase.DRAFT -> {
                                    PrimaryButton(t("mark_reviewed"), enabled = !locked, onClick = {
                                        ui.act {
                                            app.vault.update(s.id) { require(it.revision == s.revision); it.copy(approvedRevision = s.revision) }
                                            if (app.vault.preferences().deleteAudioAfterApproval) app.vault.deleteAudio(s.id)
                                        }
                                    }, icon = Icons.Default.Check)
                                    SecondaryButton(t("edit_summary"), onEdit, enabled = !locked)
                                    TextButton(onClick = onShare, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(t("share_as_draft")) }
                                }
                                else -> {
                                    PrimaryButton(t("share_summary"), onShare, icon = Glyphs.Link)
                                    SecondaryButton(t("edit_summary"), onEdit, enabled = !locked)
                                }
                            }
                        }
                    }
                    if (cloudEligible && s.origin() != Origin.CLOUD) item {
                        Panel(color = Brand.PrimaryContainer, border = Brand.PrimaryContainer, onClick = if (locked) null else onCloud) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Glyphs.Cloud, null, tint = Brand.Primary)
                                Spacer(Modifier.width(10.dp))
                                Text(t("cloud_card_title"), style = MaterialTheme.typography.titleSmall, color = Brand.OnPrimaryContainer)
                            }
                            Text(t("cloud_card_text"), style = MaterialTheme.typography.bodyMedium, color = Brand.OnPrimaryContainer)
                        }
                    }
                }
                else -> {
                    if (speakers.isNotEmpty()) item {
                        Panel {
                            SectionTitle(t("speakers_title"))
                            speakers.forEach { id ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Person, null, tint = Brand.Muted)
                                    Text(s.speakers[id] ?: id, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                                    TextButton(enabled = !locked, onClick = { dialog = "speaker:$id" }) { Text(t("rename_short")) }
                                }
                                s.suggestedProfiles[id]?.let { pid -> app.vault.profiles().find { it.id == pid } }?.let { profile ->
                                    TextButton(enabled = !locked, onClick = {
                                        ui.act { app.vault.update(s.id) { it.edited().copy(speakers = it.speakers + (id to profile.name), suggestedProfiles = it.suggestedProfiles - id) } }
                                    }) { Text(ui.tf("confirm_suggestion", "name" to profile.name)) }
                                }
                                if (pref.matching && s.speakerVectors.containsKey(id))
                                    TextButton(enabled = !locked, onClick = { dialog = "enroll:$id" }) { Text(t("enroll")) }
                            }
                        }
                    }
                    itemsIndexed(s.transcript, key = { _, line -> line.id }) { _, line ->
                        SegmentCard(ui, s, line, focused = line.id == focus, locked = locked, playing = playing,
                            onPlay = { play(line.startMs, line.endMs) })
                    }
                }
            }
        }
    }
    MeetingDialogs(ui, s, dialog, onClose = { dialog = null }, onDeleted = { stopPlay.set(true); onDeleted() }, onRegenerate = ::regenerate, canProcess = canProcess)
}

@Composable private fun OriginChip(ui: Ui, s: Session) {
    when (s.origin()) {
        Origin.ON_DEVICE -> StatusChip(originLabel(ui, s.notes), Brand.SuccessContainer, Brand.Success, Glyphs.Phone)
        Origin.CLOUD -> StatusChip(originLabel(ui, s.notes), Brand.PrimaryContainer, Brand.OnPrimaryContainer, Glyphs.Cloud)
        Origin.RECEIVED -> StatusChip(ui.t(if (s.sourceBundle?.startsWith("cloud:") == true) "origin_received_link" else "origin_received_phone"),
            Brand.SurfaceContainer, Brand.Navy, Glyphs.Download)
        Origin.NONE -> {}
    }
}

fun originLabel(ui: Ui, notes: NotesProvenance?): String = when (notes?.engine) {
    null -> ui.t("origin_unknown")
    NotesEngine.CLOUD_REDACTED.id -> ui.t("origin_cloud")
    else -> ui.t("origin_device") + " · " + ui.t("engine_name_${notes.engine}")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun SummaryContent(ui: Ui, s: Session, onSource: (String) -> Unit) {
    val t = ui.t
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val edited = s.editedSummary
        val summary = s.summary
        if (edited != null || summary == null) {
            Text(s.summaryText().ifBlank { t("empty_summary") }, style = contentStyle(), modifier = Modifier.fillMaxWidth())
            // Received notes arrive as plain text: they were not edited here.
            if (edited != null && !s.received) Text(t("edited_note"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
            return
        }
        val ids = s.transcript.map { it.id }.toSet()
        @Composable fun statement(text: String, sources: List<String>) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text, style = contentStyle(), modifier = Modifier.fillMaxWidth())
                val linked = sources.filter { it in ids }
                if (linked.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    linked.forEach { source ->
                        val at = s.transcript.first { it.id == source }.startMs
                        AssistChip(onClick = { onSource(source) }, label = { Text(ui.tf("source_at", "time" to clock(at))) },
                            leadingIcon = { Icon(Glyphs.Link, null, Modifier.size(16.dp)) }, border = BorderStroke(1.dp, Brand.Divider))
                    }
                }
            }
        }
        if (summary.paragraphs.isNotEmpty()) { SectionTitle(t("section_overview")); summary.paragraphs.forEach { statement(it.text, it.sources) } }
        if (summary.decisions.isNotEmpty()) { SectionTitle(t("section_decisions")); summary.decisions.forEach { statement(it.text, it.sources) } }
        if (summary.actions.isNotEmpty()) {
            SectionTitle(t("section_next_steps"))
            summary.actions.forEach { a -> statement(listOfNotNull(a.text, a.owner, a.due).joinToString(" · "), a.sources) }
        }
        if (summary.uncertainties.isNotEmpty()) { SectionTitle(t("section_open")); summary.uncertainties.forEach { statement(it.text, it.sources) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun SegmentCard(ui: Ui, s: Session, line: Segment, focused: Boolean, locked: Boolean, playing: Boolean, onPlay: () -> Unit) {
    val t = ui.t
    var editing by remember { mutableStateOf(false) }
    var value by remember(line.id, line.correction) { mutableStateOf(line.text) }
    var assigned by remember(line.id, line.speaker) { mutableStateOf(line.speaker) }
    Panel(border = if (focused) Brand.Primary else Brand.Divider) {
        Text(clock(line.startMs) + " – " + clock(line.endMs) + " · " + (s.speakers[line.speaker] ?: line.speaker ?: t("unknown_speaker")),
            style = MaterialTheme.typography.labelMedium, color = Brand.Muted)
        if (line.uncertain) StatusChip(t("unclear"), Brand.WarningContainer, Brand.Warning)
        if (editing) {
            OutlinedTextField(value = value, onValueChange = { value = it }, modifier = Modifier.fillMaxWidth(), textStyle = contentStyle())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = assigned == null, onClick = { assigned = null }, label = { Text(t("unknown_speaker")) })
                (s.speakerVectors.keys + s.speakers.keys).distinct().forEach { speaker ->
                    FilterChip(selected = assigned == speaker, onClick = { assigned = speaker }, label = { Text(s.speakers[speaker] ?: speaker) })
                }
            }
            Row {
                TextButton(onClick = {
                    ui.act { ui.app.vault.update(s.id) { old -> old.edited().copy(transcript = old.transcript.map { if (it.id == line.id) it.copy(correction = value, speaker = assigned) else it }) } }
                    editing = false
                }) { Text(t("save")) }
                TextButton(onClick = { editing = false; value = line.text }) { Text(t("cancel")) }
            }
        } else {
            Text(line.text, style = contentStyle(), modifier = Modifier.fillMaxWidth())
            FlowRow {
                TextButton(enabled = !locked, onClick = { editing = true }) { Text(t("edit")) }
                if (s.audioRetained) TextButton(enabled = !locked || playing, onClick = onPlay) { Text(t(if (playing) "stop_playback" else "play_moment")) }
            }
        }
    }
}

/** Live progress while the notes are made; the same panel, paused, for a meeting that continues by itself later. */
@Composable private fun ProcessingPanel(ui: Ui, s: Session, onBack: () -> Unit, onCancel: () -> Unit = {},
                                        waiting: Boolean = false, canContinue: Boolean = false, onContinue: () -> Unit = {}) {
    val t = ui.t
    val progress by ui.app.progress.collectAsState()
    val share = progress?.takeIf { it.first == s.id }?.second ?: if (waiting) s.donePercent() / 100f else null
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Heading(t(if (waiting) "waiting_heading" else "processing_heading"))
        Text(t("processing_where"), style = MaterialTheme.typography.bodyLarge, color = Brand.Muted)
        if (share != null) {
            Text(ui.tf("processing_percent", "n" to (share * 100).toInt()), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
            LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth().height(8.dp), color = Brand.Primary,
                trackColor = Brand.PrimaryContainer, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
        s.steps().forEach { (step, state) ->
            Panel(color = if (state == StepState.CURRENT) Brand.PrimaryContainer else Brand.Surface,
                border = if (state == StepState.CURRENT) Brand.PrimaryContainer else Brand.Divider) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepDot(state)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(t("step_${step.name.lowercase()}"), style = MaterialTheme.typography.titleSmall)
                        if (step == Step.SPEECH && state == StepState.CURRENT && s.durationMs > 0)
                            Text(ui.tf("step_speech_progress", "done" to clock(s.transcribedMs()), "total" to clock(s.durationMs)),
                                style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
                        if (step == Step.SPEAKERS && state == StepState.CURRENT && s.durationMs > 0)
                            Text(ui.tf("step_speech_progress", "done" to clock(s.speakersMs()), "total" to clock(s.durationMs)),
                                style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
                    }
                }
            }
        }
        if (waiting) {
            Text(t(if (s.state == "notes_waiting") "waiting_screen" else "waiting_note"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
            PrimaryButton(t("continue_now"), onContinue, enabled = canContinue)
            SecondaryButton(t("back_to_meetings"), onBack)
        } else {
            Text(t("processing_keep_open"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
            PrimaryButton(t("back_to_meetings"), onBack)
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(t("cancel_processing")) }
        }
    }
}

@Composable private fun NoSpeechPanel(ui: Ui, s: Session, onNewRecording: () -> Unit, onBack: () -> Unit) {
    val t = ui.t
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Box(Modifier.size(72.dp).background(Brand.SurfaceContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Glyphs.MicOff, null, tint = Brand.Navy, modifier = Modifier.size(36.dp))
        }
        Text(t("no_speech_heading"), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(t("no_speech_text"), style = MaterialTheme.typography.bodyLarge, color = Brand.Muted, textAlign = TextAlign.Center)
        Note(t("no_speech_check_text"), InfoIcon, title = t("no_speech_check_title"))
        PrimaryButton(t("try_new_recording"), onNewRecording, icon = Glyphs.Mic)
        SecondaryButton(t("back_to_meetings"), onBack)
        if (s.audioRetained && s.durationMs > 0) Text(t("no_speech_audio_hint"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted, textAlign = TextAlign.Center)
    }
}

@Composable private fun NotesMissingPanel(ui: Ui, s: Session, phase: Phase, ready: Boolean, canProcess: Boolean, onProcess: () -> Unit, onPrepare: () -> Unit) {
    val t = ui.t
    val code = s.failure()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
        when (phase) {
            Phase.FAILED -> Note(code?.let { t("reason_$it") } ?: t("reason_stage_failed_retry_or_check_models"), Icons.Default.Warning,
                Brand.ErrorContainer, Brand.Error, title = t("failed_title"))
            Phase.INTERRUPTED -> {
                Note(t("interrupted_text"), Icons.Default.Warning, Brand.WarningContainer, Brand.Warning, title = t("interrupted_title"))
                s.gaps.lastOrNull { it.reason != "user_pause" }?.let { gap ->
                    Text(ui.tf("interrupted_reason", "time" to clock(gap.startMs), "reason" to t("gap_${gap.reason}")),
                        style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
                }
            }
            else -> Panel {
                Text(t("not_processed_title"), style = MaterialTheme.typography.titleMedium)
                Text(t("not_processed_text"), style = MaterialTheme.typography.bodyLarge, color = Brand.Muted)
            }
        }
        if (!s.audioRetained) Text(t("audio_gone_no_retry"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
        else PrimaryButton(t(if (phase == Phase.FAILED) "retry_on_phone" else "create_notes"), onProcess, enabled = canProcess)
        if (!ready || code in SETUP_FAILURES) SecondaryButton(t("prepare_title_short"), onPrepare)
    }
}

@Composable private fun MeetingDialogs(ui: Ui, s: Session, dialog: String?, onClose: () -> Unit, onDeleted: () -> Unit, onRegenerate: () -> Unit, canProcess: Boolean) {
    val app = ui.app
    val t = ui.t
    when {
        dialog == "rename" -> {
            var value by remember { mutableStateOf(s.topicTitle() ?: "") }
            AlertDialog(onDismissRequest = onClose, title = { Text(t("rename")) },
                text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, label = { Text(t("setup_title_label")) }) },
                confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = {
                    ui.act { app.vault.update(s.id) { it.copy(title = value.trim().take(200), customTitle = true) } }; onClose()
                }) { Text(t("save")) } },
                dismissButton = { TextButton(onClick = onClose) { Text(t("cancel")) } })
        }
        dialog == "format" -> AlertDialog(onDismissRequest = onClose, title = { Text(t("menu_format")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    (MeetingTemplate.SCHOOL + MeetingTemplate.MEDICAL + MeetingTemplate.LEGAL).forEach { template ->
                        RadioRow(t("template_${template.id}"), s.template == template.id) { ui.act { app.vault.update(s.id) { it.copy(template = template.id) } } }
                    }
                    if (s.summary != null) Text(t("format_regenerate_note"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
                }
            },
            confirmButton = {
                if (s.summary != null && s.audioRetained) TextButton(enabled = canProcess, onClick = { onClose(); onRegenerate() }) { Text(t("menu_regenerate")) }
                else TextButton(onClick = onClose) { Text(t("done")) }
            },
            dismissButton = { if (s.summary != null && s.audioRetained) TextButton(onClick = onClose) { Text(t("done")) } })
        dialog == "delete_audio" -> AlertDialog(onDismissRequest = onClose, title = { Text(t("delete_audio_title")) }, text = { Text(t("confirm_delete_audio")) },
            confirmButton = { TextButton(onClick = { ui.act { app.vault.deleteAudio(s.id) }; onClose() }) { Text(t("delete_audio"), color = Brand.Error) } },
            dismissButton = { TextButton(onClick = onClose) { Text(t("cancel")) } })
        dialog == "delete" -> AlertDialog(onDismissRequest = onClose, title = { Text(t("delete_title")) }, text = { Text(t("confirm_delete")) },
            confirmButton = { Button(onClick = { onClose(); onDeleted(); ui.act { app.vault.delete(s.id) } },
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Error)) { Text(t("delete_meeting")) } },
            dismissButton = { TextButton(onClick = onClose) { Text(t("cancel")) } })
        dialog?.startsWith("speaker:") == true -> {
            val id = dialog.removePrefix("speaker:")
            var value by remember(id) { mutableStateOf(s.speakers[id] ?: "") }
            AlertDialog(onDismissRequest = onClose, title = { Text(t("rename_speaker")) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, label = { Text(t("name")) })
                    Text(t("rename_speaker_note"), style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
                } },
                confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = {
                    ui.act { app.vault.update(s.id) { it.edited().copy(speakers = it.speakers + (id to value.trim())) } }; onClose()
                }) { Text(t("save")) } },
                dismissButton = { TextButton(onClick = onClose) { Text(t("cancel")) } })
        }
        dialog?.startsWith("enroll:") == true -> {
            val speaker = dialog.removePrefix("enroll:")
            var consent by remember(speaker) { mutableStateOf(false) }
            var name by remember(speaker) { mutableStateOf(s.speakers[speaker] ?: "") }
            AlertDialog(onDismissRequest = onClose, title = { Text(t("enroll")) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t("profile_notice"), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(t("name")) }, singleLine = true)
                    CheckRow(t("profile_consent"), consent) { consent = it }
                } },
                confirmButton = { TextButton(enabled = consent && name.isNotBlank(), onClick = {
                    onClose()
                    ui.act {
                        require(s.transcript.filter { it.speaker == speaker }.sumOf { it.endMs - it.startMs } >= 10000)
                        val p = VoiceProfile(name = name.trim(), embedding = s.speakerVectors.getValue(speaker), modelHash = app.models.entry("titanet-small").sha256)
                        app.vault.putProfiles(app.vault.profiles() + p)
                    }
                }) { Text(t("save")) } },
                dismissButton = { TextButton(onClick = onClose) { Text(t("cancel")) } })
        }
    }
}

// ---- Edit summary -------------------------------------------------------------------------------------------------

@Composable fun EditScreen(ui: Ui, s: Session, onDone: () -> Unit) {
    val t = ui.t
    var text by remember(s.revision) { mutableStateOf(s.summaryText()) }
    var confirmLeave by remember { mutableStateOf(false) }
    val changed = text != s.summaryText()
    // Reads the text state when called. A function reference that captured `changed` was cached by the Compose
    // compiler with its first value, so system back left the editor without asking (found on the emulator).
    val leave: () -> Unit = { if (text != s.summaryText()) confirmLeave = true else onDone() }
    androidx.activity.compose.BackHandler(onBack = leave)
    Column(Modifier.fillMaxSize().imePadding()) {
        PageTop(t("edit_summary"), ui, leave)
        Column(Modifier.weight(1f).padding(horizontal = PagePadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s.approved) Note(t("edit_resets_review"), InfoIcon)
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().weight(1f), label = { Text(t("summary")) }, textStyle = contentStyle())
        }
        Row(Modifier.navigationBarsPadding().padding(PagePadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(t("cancel"), leave, Modifier.weight(1f))
            PrimaryButton(t("save_changes"), {
                ui.act { ui.app.vault.update(s.id) { it.edited().copy(editedSummary = text) } }; onDone()
            }, Modifier.weight(1f), enabled = changed && text.isNotBlank())
        }
    }
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, text = { Text(t("discard_changes")) },
        confirmButton = { TextButton(onClick = { confirmLeave = false; onDone() }) { Text(t("discard")) } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(t("keep_editing")) } })
}

internal fun playAudio(app: ProApp, s: Session, cancel: AtomicBoolean, from: Long = 0, until: Long? = null) {
    val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
        .setBufferSizeInBytes(64000).setTransferMode(AudioTrack.MODE_STREAM).build()
    try {
        AudioReader(app.vault, s.id).use { audio ->
            track.play()
            var start = from
            val end = until ?: maxOf(s.durationMs, audio.index.maxOfOrNull { it.startMs + it.samples / 16 } ?: 0)
            while (start < end && !cancel.get()) {
                val samples = audio.window(start, minOf(1000, end - start))
                track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING); samples.fill(0f); start += 1000
            }
        }
    } finally { track.stop(); track.release() }
}
