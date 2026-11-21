package tech.tamerin.contextfriendpro
import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.rule.GrantPermissionRule
import org.junit.*
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Walks every screen with invented demo data and saves each as an image, for design review only.
 * FLAG_SECURE blocks system screenshots, so the images come from Compose itself. Every image is
 * also written to logcat as base64 JPEG (tag CFSHOT) because that always reaches the host.
 */
class ScreenshotTourTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule val permission:GrantPermissionRule=GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO,android.Manifest.permission.POST_NOTIFICATIONS)
    private val app get()=ui.activity.application as ProApp
    private var n=0
    private fun shot(name: String,dialog: Boolean=false) {
        try {
            ui.waitForIdle()
            val node=if(dialog)ui.onNode(isDialog()) else ui.onRoot()
            val bmp=node.captureToImage().asAndroidBitmap();val label="%02d-%s".format(++n,name)
            File(app.getExternalFilesDir(null),"$label.png").outputStream().use {bmp.compress(Bitmap.CompressFormat.PNG,100,it)}
            val out=ByteArrayOutputStream();bmp.compress(Bitmap.CompressFormat.JPEG,82,out)
            val b64=Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);val parts=b64.chunked(3000)
            Log.i("CFSHOT","BEGIN $label ${parts.size}")
            parts.forEachIndexed {i,p->Log.i("CFSHOT","DATA $label $i $p")}
            Log.i("CFSHOT","END $label")
        } catch(e: Throwable) {Log.e("CFSHOT","FAILED $name: $e")}
    }
    private fun step(name: String,block: ()->Unit) {try {block();ui.waitForIdle()} catch(e: Throwable) {Log.e("CFSHOT","STEP FAILED $name: $e")}}
    private fun click(text: String) {ui.onAllNodes(hasText(text) and hasClickAction()).onFirst().performClick();ui.waitForIdle()}
    private fun tab(text: String) {ui.onAllNodesWithText(text).onFirst().performClick();ui.waitForIdle()}

    private fun seg(i: Int,start: Int,speaker: String,text: String)=Segment(id="00000000-0000-4000-8000-0000000000%02d".format(i),startMs=start*1000L,endMs=start*1000L+9000,original=text,speaker=speaker,language="en")
    private fun demo(): Session {
        val t=listOf(
            seg(1,0,"S1","Let's start with the roadmap for the spring release."),
            seg(2,9,"S2","The mobile app is on track, but the sharing feature needs another week of testing."),
            seg(3,19,"S1","Then we keep the date and cut the second export format."),
            seg(4,29,"S3","I can write the release notes by Thursday."),
            seg(5,40,"S2","One open point: we still don't know whether the school pilot starts in March or April."),
            seg(6,52,"S1","Daniel will ask the school and report back on Monday."))
        val ids=t.map {it.id}
        val summary=Summary("Spring release planning",
            paragraphs=listOf(Statement("The team reviewed the spring release. The mobile app is on track; sharing needs one more week of testing.",listOf(ids[1])),
                Statement("The release date stays. The second export format is cut to protect the date.",listOf(ids[2]))),
            decisions=listOf(Statement("Keep the release date and drop the second export format.",listOf(ids[2]))),
            actions=listOf(ActionItem("Write the release notes","Maria","Thursday",listOf(ids[3])),ActionItem("Ask the school about the pilot start","Daniel","Monday",listOf(ids[5]))),
            uncertainties=listOf(Statement("Unclear whether the school pilot starts in March or April.",listOf(ids[4]))))
        return Session(title="Spring release planning",state="needs_review",language="en",durationMs=3_120_000,transcript=t,summary=summary,
            speakers=mapOf("S1" to "Ron","S2" to "Daniel","S3" to "Maria"),speakerVectors=mapOf("S1" to List(192){0.1f},"S2" to List(192){0.2f},"S3" to List(192){0.3f}),
            stages=mapOf("asr" to Stage("done"),"speakers" to Stage("done"),"summary" to Stage("done")))
    }

    @Test fun tour() {
        val created=mutableListOf<String>()
        try {
            step("empty") {shot("start-empty-no-meetings")}
            val a=demo();app.vault.create(a).fill(0);created+=a.id
            val b=Session(title="Customer call",state="needs_review",language="en",durationMs=1_500_000,
                transcript=listOf(seg(11,0,"S1","Thanks for joining.")),summary=Summary("Customer call",listOf(Statement("Short intro call.",listOf("00000000-0000-4000-8000-000000000011")))));app.vault.create(b).fill(0);created+=b.id
            val c=Session(title="Team sync (processing failed)",state="failed",durationMs=900_000);app.vault.create(c).fill(0);created+=c.id
            val d=Session(title="Board prep",state="recorded",durationMs=2_400_000);app.vault.create(d).fill(0);created+=d.id
            app.vault.putProfiles(listOf(VoiceProfile(name="Ron",embedding=List(192){0.1f},modelHash="a".repeat(64)),VoiceProfile(name="Daniel",embedding=List(192){0.2f},modelHash="a".repeat(64))))
            app.changed();Thread.sleep(800);ui.waitForIdle()
            step("list") {shot("start-previous-meetings")}

            step("start dialog") {click("Start recording");shot("start-recording-consent",dialog=true)}
            step("consent") {ui.onNode(isToggleable()).performClick();shot("start-recording-consent-ticked",dialog=true)}
            step("recording") {
                ui.onAllNodes(hasText("Start recording") and hasClickAction()).onLast().performClick()
                ui.waitUntil(timeoutMillis=15000){app.recording.value.sessionId!=null}
                created+=app.recording.value.sessionId!!
                ui.mainClock.autoAdvance=false;Thread.sleep(3500);ui.mainClock.advanceTimeBy(64)
                shot("recording-live")
                ui.onNodeWithText("Pause").performClick();ui.waitUntil(timeoutMillis=5000){app.recording.value.state=="paused"};ui.mainClock.advanceTimeBy(64)
                shot("recording-paused")
                ui.onNodeWithText("Stop").performClick();ui.waitUntil(timeoutMillis=15000){app.recording.value.sessionId==null}
                ui.mainClock.autoAdvance=true
            }
            ui.mainClock.autoAdvance=true
            Thread.sleep(800);ui.waitForIdle()

            step("detail") {click("Spring release planning");shot("meeting-summary")}
            step("scroll") {ui.onAllNodes(hasScrollAction()).onLast().performTouchInput {swipeUp()};shot("meeting-summary-decisions-actions")}
            step("transcript") {tab("Transcript");shot("meeting-transcript")}
            step("speakers") {tab("Voice profiles");shot("meeting-speakers")}
            step("summary edit") {tab("Summary");click("Edit");shot("meeting-edit-summary");click("Cancel")}
            step("export") {click("Export selected summary");shot("meeting-external-export-warning",dialog=true);click("Cancel")}
            step("delete") {click("Delete");shot("meeting-delete-confirm",dialog=true);click("Cancel")}
            step("send") {click("Send");shot("share-send-options")}
            step("send2") {try {ui.onAllNodes(hasText("QR") and hasClickAction()).onFirst().performClick();ui.waitForIdle()} catch(_: Throwable) {};shot("share-send-detail")}
            step("receive") {tab("Send / receive");try {click("Receive")} catch(_: Throwable) {};shot("share-receive")}
            step("failed") {tab("Meetings");click("Team sync (processing failed)");shot("meeting-failed-retry")}
            step("recorded") {tab("Meetings");click("Board prep");shot("meeting-recorded-not-processed")}
            step("models") {tab("Models");shot("models")}
            step("profiles") {tab("Voice profiles");shot("voice-profiles")}
            step("settings") {tab("Settings");shot("settings")}
            step("de") {click("Deutsch");Thread.sleep(500);ui.waitForIdle();tab("Besprechungen");shot("german-meetings")}
            step("he") {click("עברית");Thread.sleep(500);ui.waitForIdle();shot("hebrew-rtl-settings")}
        } finally {
            try {app.vault.putPrivate("preferences",json.encodeToString(app.vault.preferences().copy(language="en")))} catch(_: Throwable) {}
            created.forEach {try {app.vault.delete(it)} catch(_: Throwable) {}}
            try {app.vault.putProfiles(emptyList())} catch(_: Throwable) {}
            if(app.recording.value.sessionId!=null) app.startService(Intent(app,RecordingService::class.java).setAction("stop"))
        }
    }
}
