package tech.tamerin.contextfriendpro

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Creates notes in the background: speech to text, speakers and, with the ContextFriend engine, the notes themselves
 * keep going when the screen is off or another app is open. Gemini Nano answers only while the app is on screen, so
 * a meeting that reaches its notes in the background waits ("notes_waiting") until the app is opened again.
 * Everything stays on the phone; this service only keeps the work alive.
 */
class ProcessingService: Service() {
    private val app get()=application as ProApp
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private var job: Job?=null
    private var wake: PowerManager.WakeLock?=null
    /** Meetings asked for while a run is going (a recording that just ended, "Continue now"): they go next. */
    private val requests=java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val tried=java.util.Collections.synchronizedSet(mutableSetOf<String>())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        notify(app.progress.value?.second?.let {(it*100).toInt()})
        intent?.getStringExtra("id")?.let {tried-=it;requests+=it}
        if(job?.isActive!=true) job=scope.launch {
            tried.clear()
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ContextFriendPro:processing").apply {acquire(6*60*60*1000L)}
            val updates=launch {app.progress.map {it?.second?.let {p -> (p*100).toInt()}}.distinctUntilChanged().collectLatest {notify(it)}}
            try {
                // Requested meetings first, then everything else that is unfinished, oldest first. A meeting that stops
                // again (failed, paused, waiting for the screen) is not retried in this run.
                while(isActive) {
                    val next=generateSequence {requests.poll()}.firstOrNull {it !in tried}
                        ?: runCatching {app.pendingMeetings()}.getOrDefault(emptyList()).firstOrNull {it !in tried} ?: break
                    tried+=next
                    app.engine.run(next)
                }
            } finally {
                // Stop the progress updates first: a late update would post the notification again after it was removed.
                withContext(NonCancellable) {updates.cancelAndJoin()}
                wake?.let {if(it.isHeld)it.release()};wake=null
                stopForeground(STOP_FOREGROUND_REMOVE);getSystemService(NotificationManager::class.java).cancel(8);stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notify(percent: Int?) {
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("processing",getString(R.string.processing_channel),NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val builder=Notification.Builder(this,"processing").setSmallIcon(R.drawable.ic_stat_contextfriend)
            .setContentTitle(getString(R.string.processing_title)).setContentIntent(open).setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET).setOnlyAlertOnce(true)
        if(percent!=null) builder.setContentText(getString(R.string.processing_percent,percent)).setProgress(100,percent,false)
        else builder.setProgress(100,0,true)
        val type=if(Build.VERSION.SDK_INT>=35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(8,builder.build(),type)
    }

    override fun onTimeout(startId: Int,fgsType: Int) {app.engine.cancel();stopSelf()}

    override fun onDestroy() {scope.cancel();super.onDestroy()}
}
