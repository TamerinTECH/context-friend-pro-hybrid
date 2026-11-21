package tech.tamerin.contextfriendpro
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.rule.GrantPermissionRule
import org.junit.*
import org.junit.Assert.*
import java.io.File
import android.content.Intent

class FlowTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule val permission:GrantPermissionRule=GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO,android.Manifest.permission.POST_NOTIFICATIONS)
    /** Processing changes state twice in a row. That cancels the list still loading, which is not a failure. */
    @Test fun rapidStateChangesDoNotShowTheErrorDialog() {
        val app=ui.activity.application as ProApp
        val ids=List(3) {val s=Session(title="Flow rapid $it",state="recorded");app.vault.create(s).fill(0);s.id}
        try {
            repeat(60) {app.changed();ui.waitForIdle()}
            Thread.sleep(500);ui.waitForIdle()
            ui.onAllNodesWithText("The operation could not finish. Your existing saved data is preserved.").assertCountEquals(0)
        } finally {ids.forEach {app.vault.delete(it)}}
    }
    @Test fun readerNavigationAndNativeRecording() {
        val app=ui.activity.application as ProApp
        ui.onNodeWithText("Models",useUnmergedTree=false).performClick();ui.waitForIdle()
        ui.onNodeWithText("Meetings").performClick();ui.waitForIdle()
        ui.onNodeWithText("Start recording").performClick()
        ui.onNode(isToggleable()).performClick()
        ui.onAllNodes(hasText("Start recording") and hasClickAction()).onLast().performClick()
        ui.waitUntil(timeoutMillis=15000){app.recording.value.sessionId!=null}
        val id=app.recording.value.sessionId!!
        // Real microphone levels keep publishing state. Drive Compose frames
        // explicitly during capture; waiting for global quiescence would wait
        // forever even when the controls and recorder are responsive.
        ui.mainClock.autoAdvance=false
        try {
            Thread.sleep(3200);ui.mainClock.advanceTimeBy(64);ui.onNodeWithText("Pause").performClick();ui.waitUntil(timeoutMillis=5000){app.recording.value.state=="paused"}
            Thread.sleep(300);ui.mainClock.advanceTimeBy(64);ui.onNodeWithText("Resume").performClick();Thread.sleep(1800)
            ui.mainClock.advanceTimeBy(64)
            ui.onNodeWithText("Stop").performClick();ui.waitUntil(timeoutMillis=15000){app.recording.value.sessionId==null}
            ui.mainClock.autoAdvance=true
            val session=app.vault.load(id);assertEquals(app.recording.value.error,"recorded",session.state);assertTrue(session.durationMs>=4000);assertTrue(app.vault.chunks(id).size>=4)
            ui.waitForIdle();val image=ui.onRoot().captureToImage().asAndroidBitmap()
            File(app.getExternalFilesDir(null),"flow-reader.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        } finally {
            if(app.recording.value.sessionId==id){
                app.startService(Intent(app,RecordingService::class.java).setAction("stop"))
                ui.waitUntil(timeoutMillis=15000){app.recording.value.sessionId==null}
            }
            ui.mainClock.autoAdvance=true
            if(app.recording.value.sessionId==null)app.vault.delete(id)
        }
    }
}
