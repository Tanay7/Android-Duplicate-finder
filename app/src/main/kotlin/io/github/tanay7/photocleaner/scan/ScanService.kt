package io.github.tanay7.photocleaner.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import io.github.tanay7.photocleaner.MainActivity
import io.github.tanay7.photocleaner.Prefs
import io.github.tanay7.photocleaner.R
import io.github.tanay7.photocleaner.core.Fmt
import java.util.concurrent.CancellationException
import kotlin.concurrent.thread

/**
 * Runs a scan as a foreground service, so it carries on when the app is left or the screen goes off.
 * Shows its progress in a notification with a Cancel button, and says when it's done if the app isn't
 * on screen.
 *
 * Everything but the scan itself happens on the main thread, one step after the other: a scan is
 * started, its thread hands the outcome back, and only then can the next scan start.
 */
class ScanService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var current: ScanRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotified = 0L
    private var lastAwake = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            ScanState.cancel()
            if (current == null) stopSelf()
            return START_NOT_STICKY
        }
        val req = ScanState.request
        if (req == null || current != null || !ScanState.busy) {
            if (current == null) stopSelf()
            return START_NOT_STICKY
        }
        current = req
        goForeground()
        stayAwake()
        try {
            thread(name = "scan") {
                val status = scan(req)
                main.post { finish(req, status) }
            }
        } catch (t: Throwable) {
            Log.e(Scanner.TAG, "Couldn't start the scan", t)
            finish(req, ScanState.Failed("The scan couldn't start: ${t.message ?: t.javaClass.simpleName}"))
        }
        return START_NOT_STICKY
    }

    /** The scan, on its own thread; whatever happens comes back as a status. */
    private fun scan(req: ScanRequest): ScanState.Status = try {
        ScanState.Done(Scanner(this, req) { stage, done, total, threads -> onProgress(req, stage, done, total, threads) }.run())
    } catch (_: CancellationException) {
        if (req.timedOut) ScanState.Failed(TIME_LIMIT) else ScanState.Idle
    } catch (e: Throwable) {
        Log.e(Scanner.TAG, "Scan failed", e)
        ScanState.Failed(if (e is OutOfMemoryError) "The phone ran out of memory. Scan again: it continues where it stopped." else e.message ?: e.javaClass.simpleName)
    }

    /** The scan is over (main thread): let go of everything, then say so. */
    private fun finish(req: ScanRequest, status: ScanState.Status) {
        current = null
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        ScanState.end(req, status)
        if (status is ScanState.Done && !ScanState.uiVisible) notifyDone(status.result)
        stopSelf()
    }

    /**
     * A scan that can't run in the foreground still runs: it just may be stopped once the app has
     * been left for a while (what it has read by then is kept).
     */
    private fun goForeground() {
        try {
            createChannel()
            startForeground(NOTIFY_PROGRESS, progressNotification("Getting ready…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } catch (e: Exception) {
            Log.w(Scanner.TAG, "Scanning without a foreground service", e)
        }
    }

    /**
     * Keeps the processor awake while scanning, or a scan would stall whenever the screen goes off.
     * Held for ten minutes at a time and renewed while the scan makes progress, so it can never
     * outlive a scan by more than that.
     */
    private fun stayAwake() {
        try {
            val lock = wakeLock ?: getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhotoCleaner:scan")
                .apply { setReferenceCounted(false) }.also { wakeLock = it }
            lock.acquire(10 * 60_000L)
            lastAwake = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            Log.w(Scanner.TAG, "No wake lock", e)
        }
    }

    /**
     * Android allows work of this kind in the background for 6 hours a day: when that runs out the
     * service must stop within seconds, or Android ends the app. It stops at once; the scan thread
     * stops at its next check, keeping what has been read, and hands its outcome back as usual.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        current?.let {
            it.timedOut = true
            it.cancel.set(true)
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    /** Called on the scan's threads. */
    private fun onProgress(req: ScanRequest, stage: Stage, done: Int, total: Int, threads: Int) {
        ScanState.progress(req, ScanState.Running(stage, done, total, threads))
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotified < 1000 && done != total) return
        lastNotified = now
        main.post {
            if (current !== req) return@post
            if (SystemClock.elapsedRealtime() - lastAwake > 60_000) stayAwake()
            try {
                getSystemService(NotificationManager::class.java).notify(NOTIFY_PROGRESS, progressNotification(stageText(stage, done, total), done, total))
            } catch (_: Exception) {
            }
        }
    }

    private fun progressNotification(text: String, done: Int, total: Int): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ScanService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(getString(R.string.app_name))
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
        try {
            val dupes = r.groups.sumOf { g -> g.members.count { it.item in r.selected } }
            val bytes = r.selected.sumOf { r.items[it].size }
            val text = if (dupes == 0) "No duplicates found" else "${Fmt.plural(dupes, "duplicate", "duplicates")} · ${Fmt.bytes(bytes)} can be freed"
            val n = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Scan finished")
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build()
            getSystemService(NotificationManager::class.java).notify(NOTIFY_DONE, n)
        } catch (_: Exception) {
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
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
        private const val TIME_LIMIT = "Android stops work in the background after 6 hours a day. Scan again to finish: it continues where it stopped."

        /**
         * Starts a scan (main thread, with the app on screen). Not started while another one is
         * still winding up: [ScanState.busy] says so.
         */
        fun start(ctx: Context, req: ScanRequest): Boolean {
            if (ScanState.busy) return false
            ScanState.begin(req)
            Prefs.saveLastScan(ctx, req.folders, req.options)
            if (!req.restoring) Prefs.clearTicks(ctx)
            try {
                ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFY_DONE)
                // A plain start: the app is on screen, and the service says it is a foreground one
                // itself. (Android ends an app whose "foreground start" isn't followed up in seconds;
                // this way there is no such promise to break.)
                ctx.startService(Intent(ctx, ScanService::class.java))
            } catch (e: Exception) {
                Log.e(Scanner.TAG, "Can't start the scan", e)
                ScanState.end(req, ScanState.Failed("Android didn't let the scan start: ${e.message}"))
            }
            return true
        }

        fun cancel() = ScanState.cancel()

        fun stageText(stage: Stage, done: Int, total: Int): String = when (stage) {
            Stage.LISTING -> "Finding photos and videos…"
            Stage.READING -> "Reading photos: ${Fmt.count(done)} of ${Fmt.count(total)}"
            Stage.VIDEOS -> "Checking videos: ${Fmt.count(done)} of ${Fmt.count(total)}"
            Stage.COMPARING -> if (total > 0) "Comparing: ${done * 100 / total}%" else "Comparing…"
        }

        /**
         * "Using all 8 cores", or how many. (Fewer can be for little memory, heat, fewer files left
         * than cores, or the files read again one at a time: no reason is guessed.)
         */
        fun coresText(threads: Int): String {
            val cores = Runtime.getRuntime().availableProcessors()
            return when {
                threads >= cores -> "Using all ${Fmt.count(cores)} cores"
                else -> "Using ${Fmt.count(threads)} of ${Fmt.count(cores)} cores"
            }
        }
    }
}
