package tech.tamerin.contextfriendpro

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * First launch: what the app does, that it stays on the phone, and one tap to get the phone ready. Three short steps,
 * skippable, shown once (Preferences.onboarded). The last step checks the real state of the models and Gemini Nano.
 */
@Composable fun WelcomeScreen(ui: Ui, pref: Preferences, onDone: (getReady: Boolean) -> Unit, onGuide: () -> Unit) {
    val app = ui.app
    var step by rememberSaveable { mutableIntStateOf(0) }
    val nano by app.nano.collectAsState()
    val generation by app.generation.collectAsState()
    val speech = remember(generation) { speechReady(app, pref) }
    val notes = localNotesReady(pref.language, pref.engine, nano, app.models.installed(pref.summaryModel))
    val nanoCovers = localNotesReady(pref.language, "GEMINI_NANO", nano, false) || (nano == NanoState.DOWNLOADABLE && pref.language == "en")
    val ready = speech && notes
    BackHandler(enabled = step > 0) { step-- }

    Column(Modifier.fillMaxSize().background(Brand.Background).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.CenterEnd) {
            if (step < 2) TextButton(onClick = { step = 2 }) { Text(ui.t("welcome_skip")) }
        }
        AnimatedContent(targetState = step, modifier = Modifier.weight(1f), label = "welcome", transitionSpec = {
            val direction = if (targetState > initialState) 1 else -1
            (slideInHorizontally(tween(320)) { it * direction / 4 } + fadeIn(tween(320))) togetherWith
                (slideOutHorizontally(tween(240)) { -it * direction / 4 } + fadeOut(tween(200)))
        }) { page ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                when (page) {
                    0 -> ListeningArt()
                    1 -> PrivacyArt()
                    else -> ReadyArt(ready)
                }
                Spacer(Modifier.height(32.dp))
                Text(ui.t("welcome_${page + 1}_title"), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                    color = Brand.Navy, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(12.dp))
                Text(ui.t("welcome_${page + 1}_text"), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = Brand.Muted)
                if (page == 2) {
                    Spacer(Modifier.height(24.dp))
                    Panel {
                        WelcomeCheck(speech, ui.t("speech_to_text"))
                        WelcomeCheck(speech, ui.t("speaker_separation"))
                        WelcomeCheck(notes, ui.t(if (nanoCovers) "summary_writing_nano" else "summary_writing_gemma"))
                    }
                    TextButton(onClick = onGuide, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Info, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(ui.t("welcome_local_ai_guide"))
                    }
                }
            }
        }
        StepDots(step)
        if (step < 2) PrimaryButton(ui.t("welcome_next"), { step++ })
        else {
            PrimaryButton(ui.t(if (ready) "welcome_start" else "welcome_get_ready"), { onDone(!ready) }, icon = if (ready) null else Glyphs.Download)
            if (!ready) TextButton(onClick = { onDone(false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(ui.t("welcome_later")) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable private fun StepDots(step: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.Center) {
        repeat(3) { i ->
            val width by animateDpAsState(if (i == step) 24.dp else 8.dp, label = "dot")
            Box(Modifier.padding(horizontal = 4.dp).size(width = width, height = 8.dp).background(if (i == step) Brand.Primary else Brand.Divider, CircleShape))
        }
    }
}

@Composable private fun WelcomeCheck(ok: Boolean, text: String) {
    Row(Modifier.heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Default.Check else Glyphs.Download, null, tint = if (ok) Brand.Success else Brand.Primary, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 10.dp))
    }
}

/** The logo listening: level bars move under it like the recording screen's. */
@Composable private fun ListeningArt() {
    val time by rememberInfiniteTransition(label = "listen").animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "t")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(176.dp).background(Brand.PrimaryContainer, CircleShape), contentAlignment = Alignment.Center) { BrandMark(104.dp) }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(9) { i ->
                val wave = 0.5f + 0.5f * sin(2 * PI * (time + i / 9.0) + i).toFloat()
                Box(Modifier.width(6.dp).fillMaxHeight(0.25f + 0.75f * wave).background(if (i % 3 == 1) Brand.Accent else Brand.WaveBlue, CircleShape))
            }
        }
    }
}

/** A shield with a slow pulse: the work stays on the phone. */
@Composable private fun PrivacyArt() {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing)), label = "p")
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(176.dp).scale(1f + 0.25f * pulse).alpha(0.5f * (1f - pulse)).background(Brand.SuccessContainer, CircleShape))
        Box(Modifier.size(176.dp).background(Brand.SuccessContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Glyphs.Shield, null, tint = Brand.Success, modifier = Modifier.size(84.dp))
        }
    }
}

/** The chip, with a check when the phone is ready. */
@Composable private fun ReadyArt(ready: Boolean) {
    val badge by animateFloatAsState(if (ready) 1f else 0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "badge")
    Box(Modifier.size(176.dp).background(Brand.PrimaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Glyphs.Chip, null, tint = Brand.Primary, modifier = Modifier.size(84.dp))
        Box(Modifier.align(Alignment.BottomEnd).padding(14.dp).size(44.dp).scale(badge).background(Brand.Success, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(26.dp))
        }
    }
}
