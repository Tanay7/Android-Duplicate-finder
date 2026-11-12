package io.github.tanay7.photocleaner.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Loads thumbnails off the main thread into image views that may be recycled meanwhile: a result is
 * dropped when its view has been asked to show another picture since.
 */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val exec = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    /** The picture each view should show now; read from the loader threads. */
    private val wanted: MutableMap<ImageView, String> = Collections.synchronizedMap(WeakHashMap())

    fun load(cr: ContentResolver, view: ImageView, uri: Uri, px: Int, onFail: ((ImageView) -> Unit)? = null) {
        val key = "$uri@$px"
        wanted[view] = key
        val hit = cache.get(key)
        if (hit != null) {
            view.setImageBitmap(hit)
            return
        }
        view.setImageDrawable(null)
        exec.execute {
            // Scrolled past already: skip the work.
            if (wanted[view] != key) return@execute
            val bmp = try {
                cr.loadThumbnail(uri, Size(px, px), null)
            } catch (_: Throwable) {
                null       // unreadable, or no memory for it right now: show the placeholder
            }
            if (bmp != null) cache.put(key, bmp)
            main.post {
                if (wanted[view] != key) return@post
                if (bmp != null) view.setImageBitmap(bmp) else onFail?.invoke(view)
            }
        }
    }

    fun cached(uri: Uri, px: Int): Bitmap? = cache.get("$uri@$px")

    /** A photo decoded to fit within [maxSide], turned the way it is shown; null if it can't be read. */
    fun full(cr: ContentResolver, uri: Uri, maxSide: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { decoder, info, _ ->
            val long = max(info.size.width, info.size.height)
            if (long > maxSide) {
                val s = maxSide.toDouble() / long
                decoder.setTargetSize(max(1, (info.size.width * s).roundToInt()), max(1, (info.size.height * s).roundToInt()))
            }
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    fun forget(uri: Uri) {
        val prefix = "$uri@"
        for (k in cache.snapshot().keys) if (k.startsWith(prefix)) cache.remove(k)
    }

    /** Runs [task] on the loader threads; nothing it throws can take the app down. */
    fun run(task: Runnable) = exec.execute {
        try {
            task.run()
        } catch (t: Throwable) {
            android.util.Log.w("PhotoCleaner", "Background task failed", t)
        }
    }
}
