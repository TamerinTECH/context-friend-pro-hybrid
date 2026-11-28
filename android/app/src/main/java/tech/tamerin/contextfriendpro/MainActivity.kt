package tech.tamerin.contextfriendpro

import android.app.KeyguardManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

typealias CopyText=(String)->String

class MainActivity: ComponentActivity() {
    private val app get()=application as ProApp
    private val locked=mutableStateOf(false)
    private var unlockInProgress=false
    private val unlock=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {result ->unlockInProgress=false;locked.value=result.resultCode!=RESULT_OK}
    override fun onCreate(savedInstanceState: Bundle?) {
        // Light app: dark status and navigation icons on transparent bars. The recording screen switches them to light.
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.light(AndroidColor.TRANSPARENT,AndroidColor.TRANSPARENT),
            navigationBarStyle=SystemBarStyle.light(AndroidColor.TRANSPARENT,AndroidColor.TRANSPARENT))
        super.onCreate(savedInstanceState);applyScreenCapture(this,runCatching {app.vault.preferences().allowScreenCapture}.getOrDefault(false))
        // Splash (themes.xml, drawable/splash_icon_animated.xml): let the icon finish its animation, then the badge grows
        // and fades into the app. Only on a cold start, so it never slows down returning to the app.
        splashScreen.setOnExitAnimationListener {view ->
            val ends=(view.iconAnimationStart?.toEpochMilli() ?: 0L)+(view.iconAnimationDuration?.toMillis() ?: 0L)
            view.postDelayed({
                view.iconView?.animate()?.scaleX(1.35f)?.scaleY(1.35f)?.alpha(0f)?.setDuration(300)
                    ?.setInterpolator(android.view.animation.AccelerateInterpolator())?.start()
                view.animate().alpha(0f).setStartDelay(100).setDuration(260).withEndAction {view.remove()}.start()
            },(ends-System.currentTimeMillis()).coerceIn(0L,700L))
        }
        locked.value=app.vault.preferences().appLock
        setContent {ContextFriendTheme {if(locked.value) LockedScreen {unlockApp()} else ProInterface(app)}}
        handle(intent)
    }
    override fun onNewIntent(intent: android.content.Intent) {super.onNewIntent(intent);handle(intent)}
    private fun handle(intent: android.content.Intent?) {if(intent?.action==OPEN_RECORDING)app.openRecording.value++}
    /** On screen again: pick up meetings whose notes were interrupted or waited for the app (Gemini Nano needs it on screen). */
    override fun onStart() {super.onStart();app.resumePending()}
    override fun onStop() {if(runCatching {app.vault.preferences().appLock}.getOrDefault(true) && !unlockInProgress)locked.value=true;super.onStop()}
    private fun unlockApp() {val manager=getSystemService(KeyguardManager::class.java);val intent=manager.createConfirmDeviceCredentialIntent("ContextFriend Pro",null);if(intent!=null){unlockInProgress=true;unlock.launch(intent)}}
}

/** Shown while the app lock is on. The three languages, because the preferences may not be readable yet. */
@Composable private fun LockedScreen(onUnlock: ()->Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
        BrandMark(56.dp)
        Spacer(Modifier.height(20.dp))
        Text("ContextFriend Pro",style=MaterialTheme.typography.headlineSmall,textAlign=TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Unlock · Entsperren · פתיחה",onUnlock)
    }
}

/** FLAG_SECURE unless the user allowed screen capture in Settings. */
fun applyScreenCapture(activity: android.app.Activity,allowed: Boolean) {if(allowed)activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)else activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)}

/** A saved preference. The whole row is the switch: tapping the label toggles it, and screen readers announce one switch with its label. */
@Composable fun Toggle(label: String,value: Boolean,onChange: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value=value,role=Role.Switch,onValueChange=onChange).heightIn(min=52.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f).padding(end=12.dp),style=MaterialTheme.typography.bodyLarge)
        Switch(checked=value,onCheckedChange=null)
    }
}
