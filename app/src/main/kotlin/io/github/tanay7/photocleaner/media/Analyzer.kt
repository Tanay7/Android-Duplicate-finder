// The framework's ExifInterface is as good as AndroidX's on the Android versions this app needs (16+).
@file:Suppress("ExifInterface")

package io.github.tanay7.photocleaner.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ExifInterface
import io.github.tanay7.photocleaner.core.ImageAnalysis
import io.github.tanay7.photocleaner.core.ImageFeatures
import io.github.tanay7.photocleaner.core.Scratch
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/** Reads files: their SHA-256 and, for photos, what their pixels look like. */
object Analyzer {
    /**
     * What one reading thread keeps from file to file: the analysis buffers, a read buffer and the
     * hash state. Reusing them means a photo allocates almost nothing on the Java heap, so every
     * core can read at once without the garbage collector pausing them all. One per thread.
     */
    class Worker {
        val scratch = Scratch()
        val buf = ByteArray(1 shl 20)
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    }

    /**
     * Reads a photo: its SHA-256 (streamed, never held in memory) and, if [wantFeatures], its EXIF
     * capture time and camera and its pixels, decoded reduced to at most 1024 px (turned the way its
     * EXIF data says) and measured. Failures come back in the row's error, except running out of
     * memory, which the scanner answers by trying the file again on its own.
     */
    fun photo(cr: ContentResolver, item: MediaItem, wantFeatures: Boolean, w: Worker): FeatureRow {
        val sha = try {
            sha256(cr, item, w)
        } catch (e: Exception) {
            return failed(item, "Can't read: ${e.message ?: e.javaClass.simpleName}")
        }
        if (!wantFeatures) return FeatureRow(item.key, item.size, item.modified, sha, item.shownWidth, item.shownHeight, null, null, null, null)
        var taken: Double? = null
        var camera: String? = null
        try {
            cr.openFileDescriptor(item.uri, "r")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                val t = exif.dateTimeOriginal
                if (t > 0) taken = t / 1000.0
                camera = listOfNotNull(exif.getAttribute(ExifInterface.TAG_MAKE), exif.getAttribute(ExifInterface.TAG_MODEL))
                    .joinToString(" ") { it.trim() }.trim().ifEmpty { null }
            }
        } catch (_: Exception) {
            // no EXIF data (PNG, GIF) or unreadable: no capture time, so it can't be a burst frame
        }
        return try {
            val d = decode(cr, item, w)
            FeatureRow(item.key, item.size, item.modified, sha, d.width, d.height, taken, camera, d.features, null)
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (e: Exception) {
            FeatureRow(item.key, item.size, item.modified, sha, item.shownWidth, item.shownHeight, taken, camera, null,
                "$CANT_DECODE${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** A video: only its SHA-256, for exact copies. */
    fun video(cr: ContentResolver, item: MediaItem, w: Worker): FeatureRow = try {
        FeatureRow(item.key, item.size, item.modified, sha256(cr, item, w), item.shownWidth, item.shownHeight, null, null, null, null)
    } catch (e: Exception) {
        failed(item, "Can't read: ${e.message ?: e.javaClass.simpleName}")
    }

    fun failed(item: MediaItem, why: String) =
        FeatureRow(item.key, item.size, item.modified, null, item.shownWidth, item.shownHeight, null, null, null, why)

    const val CANT_DECODE = "Can't decode: "

    class Decoded(val width: Int, val height: Int, val features: ImageFeatures)

    private fun decode(cr: ContentResolver, item: MediaItem, w: Worker): Decoded {
        var fullW = 0
        var fullH = 0
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, item.uri)) { decoder, info, _ ->
            fullW = info.size.width
            fullH = info.size.height
            val long = max(fullW, fullH)
            if (long > ImageAnalysis.REDUCED_SIZE) {
                val s = ImageAnalysis.REDUCED_SIZE.toDouble() / long
                decoder.setTargetSize(max(1, (fullW * s).roundToInt()), max(1, (fullH * s).roundToInt()))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val held = ArrayList<Bitmap>(3)
        held.add(bmp)
        try {
            // The decoder turns the photo the way its EXIF data says; report the size it is shown at.
            if (bmp.width != bmp.height && (bmp.width > bmp.height) != (fullW > fullH)) {
                val t = fullW
                fullW = fullH
                fullH = t
            }
            var b = bmp
            val long = max(b.width, b.height)
            if (long > ImageAnalysis.REDUCED_SIZE) {
                // A decoder that couldn't scale gave the whole picture: reduce it, so the buffers stay small.
                val s = ImageAnalysis.REDUCED_SIZE.toDouble() / long
                b = Bitmap.createScaledBitmap(b, max(1, (b.width * s).roundToInt()), max(1, (b.height * s).roundToInt()), true)
                held.add(b)
            }
            if (b.config != Bitmap.Config.ARGB_8888) {
                b = b.copy(Bitmap.Config.ARGB_8888, false) ?: throw IOException("can't convert ${b.config}")
                held.add(b)
            }
            val src = b
            val f = ImageAnalysis.analyze(src.width, src.height, w.scratch) { y0, count, out ->
                src.getPixels(out, 0, src.width, 0, y0, src.width, count)
            }
            return Decoded(fullW, fullH, f)
        } finally {
            for (x in held) x.recycle()
        }
    }

    /**
     * SHA-256 of a whole file, read through the worker's buffer. An empty file has no fingerprint:
     * empty files aren't copies of each other in any useful sense.
     */
    fun sha256(cr: ContentResolver, item: MediaItem, w: Worker): String {
        val digest = w.digest
        digest.reset()
        var total = 0L
        val stream = cr.openInputStream(item.uri) ?: throw IOException("no stream")
        stream.use {
            while (true) {
                val n = it.read(w.buf)
                if (n < 0) break
                digest.update(w.buf, 0, n)
                total += n
            }
        }
        if (total == 0L) throw IOException("the file is empty")
        return hex(digest.digest())
    }

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            sb.append(HEX[(x.toInt() shr 4) and 0xF])
            sb.append(HEX[x.toInt() and 0xF])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
