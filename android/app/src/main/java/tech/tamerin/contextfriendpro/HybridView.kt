package tech.tamerin.contextfriendpro

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/** "eu" is the Vertex AI multi-region endpoint: data residency and ML processing within EU member states. */
fun regionLabel(t: CopyText) = if (Hybrid.location == "eu") t("region_eu") else Hybrid.location

@Composable fun KeepScreenOn(on: Boolean) {
    // Gemini Nano runs only while the app is the top foreground app (ML Kit Prompt API).
    val view = LocalView.current
    DisposableEffect(on) { view.keepScreenOn = on; onDispose { view.keepScreenOn = false } }
}
