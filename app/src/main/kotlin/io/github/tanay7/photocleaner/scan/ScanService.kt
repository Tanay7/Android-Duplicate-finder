package io.github.tanay7.photocleaner.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import io.github.tanay7.photocleaner.MainActivity
import io.github.tanay7.photocleaner.R
import io.github.tanay7.photocleaner.core.Fmt
import java.util.concurrent.CancellationException
import kotlin.concurrent.thread

/**
 * Runs a scan as a foreground service, so it carries on when the app is left. Shows its progress in
 * a notification with a Cancel button, and says when it's done if the app isn't on screen.
 */
class ScanService : Service() {
    private var worker: Thread? = null
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            ScanState.cancel.set(true)
            return START_NOT_STICKY
        }
        val req = ScanState.request
        if (req == null || worker != null) {
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTIFY_PROGRESS, progressNotification(getString(R.string.app_name), "Getting ready…", 0, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        worker = thread(name = "scan") {
            val status = try {
                ScanState.Done(Scanner(this, req, ScanState.cancel) { stage, done, total -> onProgress(stage, done, total) }.run())
            } catch (_: CancellationException) {
                ScanState.Idle
            } catch (e: Throwable) {
                Log.e(Scanner.TAG, "Scan failed", e)
                ScanState.Failed(e.message ?: e.javaClass.simpleName)
            }
            ScanState.post(status)
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (status is ScanState.Done && !ScanState.uiVisible) notifyDone(status.result)
            worker = null
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Android ends a media-processing service after 6 hours a day: stop cleanly instead. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        ScanState.cancel.set(true)
        stopSelf()
    }

    private fun onProgress(stage: Stage, done: Int, total: Int) {
        ScanState.post(ScanState.Running(stage, done, total))
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotified < 1000 && done != total) return
        lastNotified = now
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFY_PROGRESS, progressNotification(getString(R.string.app_name), stageText(stage, done, total), done, total))
    }

    private fun progressNotification(title: String, text: String, done: Int, total: Int): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ScanService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notifyDone(r: ScanResult) {
        val dupes = r.groups.sumOf { g -> g.members.count { it.suggestTrash } }
        val bytes = r.groups.sumOf { g -> g.members.filter { it.suggestTrash }.sumOf { r.items[it.item].size } }
        val text = if (dupes == 0) "No duplicates found" else "${Fmt.plural(dupes, "duplicate", "duplicates")} · ${Fmt.bytes(bytes)} can be freed"
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Scan finished")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFY_DONE, n)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Scans", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Progress of a scan, and when it's done"
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    companion object {
        private const val CHANNEL = "scan"
        private const val NOTIFY_PROGRESS = 1
        private const val NOTIFY_DONE = 2
        private const val ACTION_CANCEL = "io.github.tanay7.photocleaner.CANCEL"

        fun start(ctx: Context, req: ScanRequest) {
            ScanState.request = req
            ScanState.cancel.set(false)
            ScanState.post(ScanState.Running(Stage.LISTING, 0, 0))
            ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFY_DONE)
            try {
                ctx.startForegroundService(Intent(ctx, ScanService::class.java))
            } catch (e: Exception) {
                Log.e(Scanner.TAG, "Can't start the scan", e)
                ScanState.post(ScanState.Failed("Android didn't let the scan start: ${e.message}"))
            }
        }

        fun cancel() = ScanState.cancel.set(true)

        fun stageText(stage: Stage, done: Int, total: Int): String = when (stage) {
            Stage.LISTING -> "Finding photos and videos…"
            Stage.READING -> "Reading photos: ${Fmt.count(done)} of ${Fmt.count(total)}"
            Stage.VIDEOS -> "Checking videos: ${Fmt.count(done)} of ${Fmt.count(total)}"
            Stage.COMPARING -> "Comparing…"
        }
    }
}
