package io.github.tanay7.photocleaner.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/** Small jobs off the main thread (media-index checks), answered on it. Nothing thrown escapes. */
object Bg {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "bg").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

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
