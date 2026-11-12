package io.github.tanay7.photocleaner.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/** Small jobs off the main thread (media-index checks), answered on it. Nothing thrown escapes. */
object Bg {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "bg").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /**
     * Starts a thread of its own for a longer job. If the phone can't even start a thread, [failed]
     * runs instead of the app ending.
     */
    fun start(name: String, failed: () -> Unit, work: () -> Unit) {
        try {
            Thread({
                try {
                    work()
                } catch (t: Throwable) {
                    Log.w("PhotoCleaner", "$name failed", t)
                    main.post(failed)
                }
            }, name).apply { isDaemon = true }.start()
        } catch (t: Throwable) {
            Log.w("PhotoCleaner", "Couldn't start $name", t)
            failed()
        }
    }

    /** Runs [work] in the background, then [done] on the main thread with its result, or with null if it failed. */
    fun <T> run(work: () -> T, done: (T?) -> Unit) {
        exec.execute {
            val r = try {
                work()
            } catch (t: Throwable) {
                Log.w("PhotoCleaner", "Background job failed", t)
                null
            }
            main.post { done(r) }
        }
    }
}
