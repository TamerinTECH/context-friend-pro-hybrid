package tech.tamerin.contextfriendpro

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.*

/** Every place the app can show. Root screens have the bottom bar; the others have a back button. */
sealed interface Screen {
    data object Meetings : Screen
    data object Receive : Screen
    data object Settings : Screen
    data object Setup : Screen
    data object Recording : Screen
    data class Meeting(val id: String) : Screen
    data class Edit(val id: String) : Screen
    data class Share(val id: String) : Screen
    data class Cloud(val id: String) : Screen
    /** Phone-to-phone transfer: send a meeting, receive, or send voice profiles. */
    data class Nearby(val mode: TransferMode, val id: String? = null) : Screen
    /** Settings pages: ai, activity, storage, cloud, language, models, voice, prepare. */
    data class Page(val name: String) : Screen
}

private val ROOTS = listOf(Screen.Meetings, Screen.Receive, Screen.Settings)

@Composable fun ProInterface(app: ProApp) {
    val generation by app.generation.collectAsState()
    val recording by app.recording.collectAsState()
    val processing by app.processing.collectAsState()
    var pref by remember { mutableStateOf(app.vault.preferences()) }
    val dictionaries = remember { json.decodeFromString<Map<String, Map<String, String>>>(app.assets.open("translations.json").bufferedReader().readText()) }
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val engine = app.engine
    val act: (suspend () -> Unit) -> Unit = { block -> scope.launch { try { withContext(Dispatchers.IO) { block() }; app.changed() } catch (e: CancellationException) { throw e } catch (_: Exception) { error = true } } }
    val ui = remember(pref.language, dictionaries) { Ui(app, pref.language, dictionaries, act) }

    // A new change cancels the list still loading. That is not a failure, and runCatching would report it as one.
    LaunchedEffect(generation) {
        try { sessions = withContext(Dispatchers.IO) { app.vault.list() } } catch (e: CancellationException) { throw e } catch (_: Exception) { error = true }
        pref = app.vault.preferences()
    }

    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Meetings)) }
    val screen = stack.last()
    fun go(s: Screen) { stack = if (s in ROOTS) listOf(s) else stack + s }
    fun back() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun replace(s: Screen) { stack = stack.dropLast(1) + s }
    // Screens with their own back logic (unsaved edits, a cloud request in flight) handle back themselves:
    // nested BackHandlers do not reliably take priority after the activity restarts.
    BackHandler(enabled = stack.size > 1 && screen !is Screen.Edit && screen !is Screen.Cloud) { back() }

    // Recording ends -> open the meeting. The recording service has already started its notes in the background
    // service (ProcessingService), also when the app was not on screen. Nothing ever goes to the cloud here.
    var recordingId by remember { mutableStateOf<String?>(null) }
    // A tap on the recording notification: back to the recording, wherever the app was.
    val openRecording by app.openRecording.collectAsState()
    LaunchedEffect(openRecording) { if (openRecording > 0 && recording.sessionId != null && screen != Screen.Recording) go(Screen.Recording) }
    LaunchedEffect(recording.sessionId) {
        val id = recording.sessionId
        if (id != null) {
            recordingId = id
            if (screen != Screen.Recording) go(Screen.Recording)
        } else recordingId?.let { done ->
            recordingId = null
            stack = listOf(Screen.Meetings, Screen.Meeting(done))
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides if (ui.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        SystemBarIcons(dark = screen != Screen.Recording)
        val root = screen in ROOTS
        Scaffold(containerColor = Brand.Background, contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (root) NavigationBar(containerColor = Brand.Surface, tonalElevation = NavigationBarDefaults.Elevation) {
                    val items = listOf(Triple(Screen.Meetings, Icons.Default.Home, "nav_meetings"), Triple(Screen.Receive, Glyphs.Download, "nav_receive"),
                        Triple(Screen.Settings, Icons.Default.Settings, "nav_settings"))
                    items.forEach { (target, icon, label) ->
                        NavigationBarItem(selected = screen == target, onClick = { go(target) }, icon = { Icon(icon, null) }, label = { Text(ui.t(label)) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = Brand.PrimaryContainer, selectedIconColor = Brand.Primary, selectedTextColor = Brand.Primary))
                    }
                }
            }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                val current = { id: String -> sessions.firstOrNull { it.id == id } }
                when (val s = screen) {
                    Screen.Meetings -> MeetingsScreen(ui, sessions, recording, processing, pref,
                        onNew = { go(Screen.Setup) }, onOpen = { go(Screen.Meeting(it)) }, onRecording = { go(Screen.Recording) },
                        onPrepare = { go(Screen.Page("prepare")) })
                    Screen.Receive -> ReceiveScreen(ui, onOpened = { go(Screen.Meeting(it)) }, onNearby = { go(Screen.Nearby(TransferMode.RECEIVE)) })
                    Screen.Settings -> SettingsScreen(ui, pref, onPage = { go(Screen.Page(it)) }, onPref = { pref = it })
                    Screen.Setup -> SetupScreen(ui, pref, onBack = ::back, onStarted = { replace(Screen.Recording) })
                    Screen.Recording -> RecordingScreen(ui, recording, sessions, onLeave = { stack = listOf(Screen.Meetings) })
                    is Screen.Meeting -> current(s.id)?.let { m ->
                        MeetingScreen(ui, m, pref, processing, recording.sessionId, engine,
                            onBack = ::back, onEdit = { go(Screen.Edit(m.id)) }, onShare = { go(Screen.Share(m.id)) },
                            onCloud = { go(Screen.Cloud(m.id)) }, onPrepare = { go(Screen.Page("prepare")) },
                            onNewRecording = { stack = listOf(Screen.Meetings, Screen.Setup) }, onDeleted = { stack = listOf(Screen.Meetings) })
                    } ?: LaunchedEffect(s.id, sessions) {
                        // A meeting that was just created (received, opened) can be missing until the list reloads.
                        // Go back only when it really is gone from the vault (for example after a delete).
                        if (withContext(Dispatchers.IO) { runCatching { app.vault.load(s.id) }.isFailure }) back()
                    }
                    is Screen.Edit -> current(s.id)?.let { EditScreen(ui, it, onDone = ::back) }
                    is Screen.Share -> current(s.id)?.let { ShareScreen(ui, it, onBack = ::back, onNearby = { go(Screen.Nearby(TransferMode.SEND, s.id)) }) }
                    is Screen.Cloud -> current(s.id)?.let { CloudReviewScreen(ui, it, engine, onDone = ::back) }
                    is Screen.Nearby -> NearbyScreen(ui, s.mode, s.id?.let(current), onBack = ::back)
                    is Screen.Page -> SettingsPage(ui, s.name, pref, onBack = ::back, onPage = { go(Screen.Page(it)) }, onPref = { pref = it },
                        onNearbyProfiles = { go(Screen.Nearby(TransferMode.PROFILES)) })
                }
            }
        }
        if (error) AlertDialog(onDismissRequest = { error = false }, text = { Text(ui.t("error")) },
            confirmButton = { TextButton(onClick = { error = false }) { Text(ui.t("close")) } })
    }
}

/** Speech to text and speaker separation are installed (the notes engine is checked separately). */
fun speechReady(app: ProApp, pref: Preferences) =
    app.models.installed(pref.asrModel) && app.models.installed("pyannote-segmentation3") && app.models.installed("titanet-small")
